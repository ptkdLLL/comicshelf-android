# -*- coding: utf-8 -*-

from __future__ import annotations
"""`python -m ehmeta_builder <cmd>` 入口（转 cli.main）。"""
import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
