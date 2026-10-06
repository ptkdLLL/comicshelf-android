# -*- coding: utf-8 -*-

from __future__ import annotations
"""PyInstaller GUI 入口（打包用）。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ehmeta_builder import gui  # noqa: E402

if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--workdir", default=os.path.expanduser("~/ComicShelfBuilder"))
    ap.add_argument("--proxy", default=None)
    a = ap.parse_args()
    gui.run(workdir=a.workdir, proxy=a.proxy)
