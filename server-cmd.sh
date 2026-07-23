#!/usr/bin/env bash
# yousa.ccwu.cc 远程命令执行脚本
# 用法: bash server-cmd.sh "你的命令"
#
# 认证方式：专用 ADMIN_EXEC_API_KEY 或 .admin_exec_key。
#
# 示例:
#   bash server-cmd.sh "cat /opt/yousa/static/version.json"
#   bash server-cmd.sh "systemctl status yousa --no-pager -l"
#   bash server-cmd.sh "cd /opt/yousa && git pull --ff-only && systemctl restart yousa"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_URL="https://yousa.ccwu.cc"

CMD="$*"
if [ -z "$CMD" ]; then
    echo "用法: $0 <命令>"
    exit 1
fi

API_KEY="${ADMIN_EXEC_API_KEY:-}"
if [ -z "$API_KEY" ] && [ -f "$SCRIPT_DIR/.admin_exec_key" ]; then
    API_KEY=$(cat "$SCRIPT_DIR/.admin_exec_key")
fi
if [ "${#API_KEY}" -lt 32 ]; then
    echo "错误: ADMIN_EXEC_API_KEY/.admin_exec_key 缺失或长度不足 32 字符"
    exit 1
fi

curl -s -X POST "$BASE_URL/admin/exec" \
    -H "X-Admin-Exec-Key: $API_KEY" \
    --data-urlencode "cmd=$CMD" 2>&1
