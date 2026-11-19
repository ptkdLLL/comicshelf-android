import contextlib
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock
from urllib.error import HTTPError
import zipfile

from ehmeta_builder import builder, cli, download, ehdb2lite
from ehmeta_builder.sources import Source


EHDATA = b'{"7":{"gid":7,"title":"Early title"}}'
EHDB = Source(
    key="ehdb", label="Early aliases", kind="http", url="https://example.invalid/ehdb",
    local_rel="sources/ehdb/gdata.json", extract_rel="sources/ehdb/eh_lite.db",
    size=len(EHDATA), sha256=hashlib.sha256(EHDATA).hexdigest(), optional=True,
)
LITE = EHDB.extract_rel


def response(data):
    stream = io.BytesIO(data)
    stream.status = 200
    stream.headers = {"Content-Length": str(len(data))}
    return stream


class OptionalSourceTest(unittest.TestCase):
    def setUp(self):
        # Both characters are legal in a work directory but special in SQLite URIs/SQL.
        temp = tempfile.TemporaryDirectory(prefix="builder #'")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.opener = mock.Mock()
        opener_patch = mock.patch.object(download, "_opener", return_value=self.opener)
        opener_patch.start()
        self.addCleanup(opener_patch.stop)
        source_patch = mock.patch.object(cli, "SOURCES", [EHDB])
        source_patch.start()
        self.addCleanup(source_patch.stop)

    def write(self, path, data):
        dest = self.root / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return dest

    def required_inputs(self):
        self.write("sources/hwei/gdata.json", b'{"7":{"gid":7,"title":"Current title","tags":["language:chinese"]}}')
        self.write("sources/bogey/merge.jsonl", b'{"gmetadata":[{"gid":7,"title":"API title"}]}')
        self.write("sources/bogey_csv/gallery.csv", b"gid,title,title_jpn\n7,CSV title,\n")
        self.write("sources/ehtag/db.raw.json", b'{"data":[]}')

    def fetch(self, **kwargs):
        return cli.fetch(str(self.root), None, log=lambda _: None, **kwargs)

    def run_cli(self, *args):
        with contextlib.redirect_stdout(io.StringIO()):
            return cli.main([*args, "--workdir", str(self.root)])

    def meta(self, path="out/ehmeta.db.zip"):
        with zipfile.ZipFile(self.root / path) as z:
            return json.loads(z.read("meta.json"))

    def test_cold_cli_fetch_only_ehdb_downloads_and_converts(self):
        self.opener.open.return_value = response(EHDATA)
        self.assertEqual(self.run_cli("fetch", "--only", "ehdb"), 0)
        self.opener.open.assert_called_once()
        self.assertEqual((self.root / EHDB.local_rel).read_bytes(), EHDATA)
        with contextlib.closing(sqlite3.connect(self.root / LITE)) as db:
            self.assertEqual(db.execute("SELECT id,title FROM g").fetchall(), [(7, "Early title")])

    def test_skip_optional_uses_valid_cache_even_with_force(self):
        self.write(EHDB.local_rel, EHDATA)
        self.assertIn("ehdb", self.fetch(skip_optional=True, force=True))
        self.opener.open.assert_not_called()
        self.assertTrue((self.root / LITE).is_file())

    def test_skip_optional_never_downloads_to_repair_a_bad_cache(self):
        cached = self.write(EHDB.local_rel, b"short")
        self.assertNotIn("ehdb", self.fetch(skip_optional=True))
        self.opener.open.assert_not_called()
        self.assertEqual(cached.read_bytes(), b"short")
        self.assertFalse((self.root / LITE).exists())

    def test_force_bypasses_both_cache_checks(self):
        self.write(EHDB.local_rel, EHDATA)
        download.save_manifest(str(self.root), {"ehdb": {"sha256": EHDB.sha256}})
        self.opener.open.return_value = response(EHDATA)
        self.assertEqual(self.run_cli("fetch", "--only", "ehdb", "--force"), 0)
        self.opener.open.assert_called_once()

    def test_force_does_not_replace_valid_cache_on_bad_digest(self):
        cached = self.write(EHDB.local_rel, EHDATA)
        self.opener.open.side_effect = lambda *args, **kwargs: response(b"x" * len(EHDATA))
        with mock.patch.object(download.time, "sleep"):
            self.assertNotIn("ehdb", self.fetch(force=True))
        self.assertEqual(cached.read_bytes(), EHDATA)
        self.assertFalse((self.root / LITE).exists())

    def test_lfs_quota_failure_does_not_stop_required_sources(self):
        aux = Source("aux", "Required", "http", "sources/aux/cache", url="https://example.invalid/aux")
        self.required_inputs()
        for code in (402, 403):
            with self.subTest(code=code):
                self.opener.open.reset_mock()
                self.opener.open.side_effect = lambda req, **kw: (
                    self.fail_quota(code) if req.full_url == EHDB.url else response(b"required")
                )
                with mock.patch.object(cli, "SOURCES", [EHDB, aux]), mock.patch.object(download.time, "sleep"):
                    versions = self.fetch(force=True)
                self.assertEqual(set(versions), {"aux"})
                self.assertEqual(
                    [call.args[0].full_url for call in self.opener.open.call_args_list], [EHDB.url, aux.url],
                )
                self.assertFalse((self.root / LITE).exists())
        builder.build(str(self.root), log=lambda _: None)
        self.assertEqual(self.meta()["sources"]["eh_lite"], {"records": 0, "role": "skipped"})

    @staticmethod
    def fail_quota(code):
        raise HTTPError(EHDB.url, code, "LFS unavailable", {}, None)

    def test_update_skip_ehdb_neither_fetches_nor_includes_cached_aliases(self):
        self.required_inputs()
        cached = self.write(EHDB.local_rel, EHDATA)
        # Keep the hot converted cache present: the switch must also control construction.
        ehdb2lite.convert(str(cached), str(self.root / LITE), log=lambda _: None)
        self.assertEqual(self.run_cli("update", "--skip-ehdb", "--force"), 0)
        self.opener.open.assert_not_called()
        self.assertEqual(self.meta()["sources"]["eh_lite"], {"records": 0, "role": "skipped"})

    def test_pack_uses_alias_only_when_the_lite_database_is_read(self):
        self.required_inputs()
        self.write(EHDB.local_rel, EHDATA)
        builder.build(str(self.root), log=lambda _: None)
        without_lite = self.meta()
        self.assertEqual(without_lite["sources"]["eh_lite"], {"records": 0, "role": "skipped"})
        self.fetch(skip_optional=True)
        builder.build(str(self.root), log=lambda _: None)
        with_lite = self.meta()
        self.assertEqual(with_lite["sources"]["eh_lite"], {"records": 1, "role": "alias-only"})
        for key in ("gallery", "tag", "gt"):
            self.assertEqual(without_lite["counts"][key], with_lite["counts"][key])
        with contextlib.closing(sqlite3.connect(self.root / "out/ehmeta.db")) as db:
            self.assertEqual(db.execute("SELECT gid FROM title WHERE text='Early title'").fetchall(), [(7,)])

    def test_gui_checkbox_controls_download_and_inclusion(self):
        try:
            from ehmeta_builder.gui import App
        except ModuleNotFoundError as e:
            if e.name in ("tkinter", "_tkinter"):
                self.skipTest("Tkinter is not installed")
            raise

        self.required_inputs()
        app = mock.Mock()
        app.dir_var.get.return_value = str(self.root)
        app._proxy.return_value = None
        app._spawn.side_effect = lambda work: work()
        app.clean_extracted.get.return_value = False
        app.with_ehdb.get.return_value = True
        self.opener.open.return_value = response(EHDATA)
        App._build(app)
        self.opener.open.assert_called_once()
        self.assertEqual(self.meta()["sources"]["eh_lite"]["role"], "alias-only")
        app.with_ehdb.get.return_value = False
        self.opener.open.reset_mock()
        App._build(app)
        self.opener.open.assert_not_called()
        self.assertEqual(self.meta()["sources"]["eh_lite"]["role"], "skipped")


if __name__ == "__main__":
    unittest.main()
