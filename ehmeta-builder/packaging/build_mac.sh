#!/bin/bash
# Mac 打包（PyInstaller）——产出 dist/ehmeta-builder.app 与 dist/ehmeta-builder-cli
#
# 前置：需要一个【带 Tk 的 Python】作为构建解释器（Tkinter 随 Python 安装提供）：
#   - macOS 系统自带 /usr/bin/python3（Python 3.9 + Tk 8.5）——最省事；
#   - 或 Homebrew：brew install python@3.12 python-tk@3.12
# 注意：Homebrew 的 python 默认【不含 Tk】，直接用它打包会导致 GUI 启动崩溃（No module named '_tkinter'）。
set -e
cd "$(dirname "$0")"
PY="${PYTHON:-/usr/bin/python3}"
echo "构建解释器: $PY ($($PY --version 2>&1))"
$PY -c "import tkinter; print('Tk', tkinter.TkVersion, 'OK')"
rm -rf build_venv dist
$PY -m venv build_venv
./build_venv/bin/python -m pip install -q --upgrade pip
# 中国大陆网络可用：PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple bash build_mac.sh
./build_venv/bin/python -m pip install -q ${PIP_INDEX_URL:+-i $PIP_INDEX_URL} pyinstaller
./build_venv/bin/pyinstaller --noconfirm --clean --windowed --name ehmeta-builder --paths .. entry_gui.py
./build_venv/bin/pyinstaller --noconfirm --clean --console --name ehmeta-builder-cli --paths .. entry_cli.py
echo "产物：packaging/dist/ehmeta-builder.app  packaging/dist/ehmeta-builder-cli"
echo "提示：未签名 .app 首次打开需右键→打开"
