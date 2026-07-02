#!/usr/bin/env bash
# yousa.ccwu.cc 远程命令执行脚本
# 用法: bash server-cmd.sh "你的命令"
#
# 认证方式（自动检测）:
#   1. 优先用 cookie（已登录时）
#   2. 回退用 .apikey 中的 API Key
#
# 示例:
#   bash server-cmd.sh "cat /opt/yousa/static/version.json"
#   bash server-cmd.sh "systemctl status yousa --no-pager -l"
#   bash server-cmd.sh "cd /opt/yousa && git pull --ff-only && systemctl restart yousa"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COOKIE_FILE="${TMPDIR:-/tmp}/yousa-cookie.txt"
BASE_URL="https://yousa.ccwu.cc"

CMD="$*"
if [ -z "$CMD" ]; then
    echo "用法: $0 <命令>"
    exit 1
fi

# 尝试 cookie 认证
COOKIE_OK=false
if [ -f "$COOKIE_FILE" ]; then
    CHECK=$(curl -s -o /dev/null -w "%{http_code}" -b "$COOKIE_FILE" "$BASE_URL/admin" 2>/dev/null || true)
    if [ "$CHECK" = "200" ]; then
        COOKIE_OK=true
    fi
fi

# cookie 失效则重新登录
if [ "$COOKIE_OK" != "true" ]; then
    API_KEY=$(cat "$SCRIPT_DIR/.apikey" 2>/dev/null || true)
    if [ -z "$API_KEY" ]; then
        echo "错误: 找不到 .apikey"
        exit 1
    fi
    # 用 API Key 方式执行（无需登录）
    curl -s -X POST "$BASE_URL/admin/exec" \
        -H "X-API-Key: $API_KEY" \
        --data-urlencode "cmd=$CMD" 2>&1
else
    # 用 cookie 方式执行
    curl -s -b "$COOKIE_FILE" \
        -X POST "$BASE_URL/admin/exec" \
        --data-urlencode "cmd=$CMD" 2>&1
fi
