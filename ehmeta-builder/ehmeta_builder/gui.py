# -*- coding: utf-8 -*-

from __future__ import annotations
"""Tkinter 图形界面（纯 stdlib，无第三方依赖）。

线程模型：所有网络/构建工作跑在 worker 线程；日志经 queue 回主线程刷新（after 轮询）。
"""
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from . import download as dl
from .sources import SOURCES

PLACEHOLDER = "http://127.0.0.1:7897（Clash Verge 混合端口；留空=直连）"


class App:
    def __init__(self, root: tk.Tk, workdir: str, proxy: str | None):
        self.root = root
        self.q: queue.Queue = queue.Queue()
        self.busy = False
        self.last_zip = ""

        root.title("ehmeta-builder · E-Hentai 元数据构建器")
        root.geometry("860x620")

        pad = {"padx": 8, "pady": 4}
        top = ttk.Frame(root)
        top.pack(fill="x", **pad)

        ttk.Label(top, text="工作目录").grid(row=0, column=0, sticky="w")
        self.dir_var = tk.StringVar(value=workdir)
        ttk.Entry(top, textvariable=self.dir_var, width=58).grid(row=0, column=1, sticky="we")
        ttk.Button(top, text="选择…", command=self._pick_dir).grid(row=0, column=2, padx=4)

        ttk.Label(top, text="代理").grid(row=1, column=0, sticky="w")
        self.proxy_var = tk.StringVar(value=proxy or "")
        e = ttk.Entry(top, textvariable=self.proxy_var, width=58)
        e.grid(row=1, column=1, sticky="we")
        self.proxy_hint = ttk.Label(top, text=PLACEHOLDER, foreground="#888")
        self.proxy_hint.grid(row=2, column=1, sticky="w")
        ttk.Button(top, text="测试连接", command=lambda: self._spawn(self._test)).grid(row=1, column=2, padx=4)
        top.columnconfigure(1, weight=1)

        opt = ttk.Frame(root)
        opt.pack(fill="x", **pad)
        self.with_ehdb = tk.BooleanVar(value=True)
        self.clean_extracted = tk.BooleanVar(value=True)
        ttk.Checkbutton(opt, text="包含 ehdb 早期快照（历史标题别名；GitHub LFS，失败会自动跳过）",
                        variable=self.with_ehdb).pack(anchor="w")
        ttk.Checkbutton(opt, text="构建后清理解压的大文件（省 ~2GB；zip 保留可再解压）",
                        variable=self.clean_extracted).pack(anchor="w")

        run = ttk.Frame(root)
        run.pack(fill="x", **pad)
        self.btn_run = ttk.Button(run, text="一键构建最新数据库", command=self._build)
        self.btn_run.pack(side="left")
        self.btn_check = ttk.Button(run, text="仅检查更新", command=lambda: self._spawn(self._check))
        self.btn_check.pack(side="left", padx=6)
        self.btn_open = ttk.Button(run, text="打开产物文件夹", command=self._open_out, state="disabled")
        self.btn_open.pack(side="left")

        self.bar = ttk.Progressbar(root, mode="determinate", maximum=100)
        self.bar.pack(fill="x", **pad)
        self.status = ttk.Label(root, text="就绪", foreground="#333")
        self.status.pack(fill="x", padx=8)

        self.log_txt = tk.Text(root, height=18, wrap="none", font=("Menlo", 11) if sys.platform == "darwin" else ("Consolas", 10))
        ys = ttk.Scrollbar(root, command=self.log_txt.yview)
        self.log_txt.configure(yscrollcommand=ys.set)
        self.log_txt.pack(side="left", fill="both", expand=True, padx=(8, 0), pady=8)
        ys.pack(side="right", fill="y", pady=8)

        self.root.after(100, self._poll)

    # ---------------------------------------------------------------- 工具

    def _log(self, *a):
        self.q.put(("log", " ".join(str(x) for x in a)))

    def _status(self, s, pct=None):
        self.q.put(("status", s, pct))

    def _pick_dir(self):
        d = filedialog.askdirectory(initialdir=self.dir_var.get())
        if d:
            self.dir_var.set(d)

    def _proxy(self) -> str | None:
        p = self.proxy_var.get().strip() or None
        if p and not p.startswith("http"):
            p = "http://" + p
        return p

    def _open_out(self):
        out = os.path.join(self.dir_var.get(), "out")
        if sys.platform == "darwin":
            subprocess.Popen(["open", out])
        elif os.name == "nt":
            os.startfile(out)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", out])

    def _set_busy(self, b: bool):
        self.busy = b
        st = "disabled" if b else "normal"
        self.btn_run.configure(state=st)
        self.btn_check.configure(state=st)

    def _spawn(self, fn):
        if self.busy:
            return
        self._set_busy(True)
        threading.Thread(target=lambda: self._guard(fn), daemon=True).start()

    def _guard(self, fn):
        try:
            fn()
        except Exception as e:
            self._log(f"✗ 失败：{type(e).__name__}: {e}")
        finally:
            self._set_busy(False)
            self._status("就绪")

    # ---------------------------------------------------------------- 动作

    def _test(self):
        self._status("测试连接…")
        self._log("—— 测试连接 ——", f"代理: {self._proxy() or '直连'}")
        self._log(dl.test_connection(self._proxy()))

    def _check(self):
        wd = self.dir_var.get()
        proxy = self._proxy()
        manifest = dl.load_manifest(wd)
        self._log("—— 检查更新 ——")
        for s in SOURCES:
            try:
                st = dl.check_source(s, wd, proxy, manifest, log=self._log)
                flag = "有更新" if st["changed"] else "已最新"
                self._log(f"  [{s.key}] {flag}（{st['version']}，{st['size']:,}B）" if st["size"]
                          else f"  [{s.key}] {flag}（{st['version']}）")
            except Exception as e:
                self._log(f"  [{s.key}] 检查失败：{type(e).__name__}: {e}")

    def _build(self):
        wd = self.dir_var.get()
        proxy = self._proxy()
        with_ehdb = self.with_ehdb.get()
        clean = self.clean_extracted.get()

        def work():
            os.makedirs(wd, exist_ok=True)
            self._log("=" * 70)
            self._log(f"一键构建：workdir={wd}  proxy={proxy or '直连'}")
            self._status("取源…", 0)
            from . import cli, builder
            skip_optional = not with_ehdb
            versions = cli.fetch(wd, proxy, log=self._log,
                                 skip_optional=skip_optional,
                                 progress=lambda k, d, t: self._status(f"下载 {k}…", (d / t * 100) if t else None))
            self._status("构建…")
            r = builder.build(wd, log=self._log, use_ehdb=with_ehdb, clean_extracted=clean,
                              versions=versions)
            self.last_zip = r["zip"] or r["db"]
            c = r["counts"]
            self._status(f"完成：{os.path.basename(self.last_zip)} · gallery {c['gallery']:,} · gt {c['gt']:,}")
            self.q.put(("enable_open",))
        self._spawn(work)

    # ---------------------------------------------------------------- 主循环

    def _poll(self):
        try:
            while True:
                m = self.q.get_nowait()
                if m[0] == "log":
                    self.log_txt.insert("end", m[1] + "\n")
                    self.log_txt.see("end")
                elif m[0] == "status":
                    self.status.configure(text=m[1])
                    if len(m) > 2 and m[2] is not None:
                        self.bar.configure(mode="determinate", value=max(0, min(100, m[2])))
                    else:
                        self.bar.configure(mode="indeterminate")
                        self.bar.start(60)
                elif m[0] == "enable_open":
                    self.btn_open.configure(state="normal")
                    self.bar.stop()
                    self.bar.configure(mode="determinate", value=100)
        except queue.Empty:
            pass
        self.root.after(120, self._poll)


def run(workdir: str | None = None, proxy: str | None = None):
    root = tk.Tk()
    App(root, workdir or os.path.expanduser("~/ComicShelfBuilder"), proxy)
    root.mainloop()
