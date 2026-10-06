# -*- coding: utf-8 -*-

from __future__ import annotations
"""PyInstaller CLI 入口（打包用）。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ehmeta_builder.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
