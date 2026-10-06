@echo off
REM Windows 打包（PyInstaller）——需在 Windows 机器上执行（未在开发机验证）
cd /d %~dp0
pip install --upgrade pyinstaller
pyinstaller --noconfirm --clean --windowed --name ehmeta-builder --paths .. entry_gui.py
pyinstaller --noconfirm --clean --console --name ehmeta-builder-cli --paths .. entry_cli.py
echo 产物: dist\ehmeta-builder\  dist\ehmeta-builder-cli\
