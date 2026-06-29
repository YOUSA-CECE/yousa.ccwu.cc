#!/bin/bash
# yousa.ccwu.cc — Git 推送（服务器 cron 会自动拉取部署）
# 用法: bash deploy-ssh.sh ["提交信息"]
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
MSG="${1:-自动部署}"

echo "📦 提交并推送..."
cd "$APP_DIR"
git add -A
git commit -m "$MSG" 2>/dev/null || echo "(无新更改)"
git push
echo "✨ 推送完成，服务器将自动拉取更新"