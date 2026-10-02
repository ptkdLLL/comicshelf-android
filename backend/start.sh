#!/bin/bash
# cs-backend 一键启动 (macOS / Linux)。等价于: python3 deploy.py up
# 用法: ./start.sh [setup|start|stop|restart|status|logs|doctor|fetch-models|...]
cd "$(dirname "$0")"
exec python3 deploy.py "${1:-up}" "${@:2}"
