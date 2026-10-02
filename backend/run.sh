#!/bin/bash
# 兼容旧入口: 转发到跨平台部署器。建议直接用 ./start.sh 或 python3 deploy.py
# 旧用法 ./run.sh start|stop|status|log 全部等价可用。
cd "$(dirname "$0")"
exec python3 deploy.py "${1:-up}" "${@:2}"
