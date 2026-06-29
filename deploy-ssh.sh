#!/bin/bash
# yousa.ccwu.cc — SSH 一键部署（本机用）
# 用法: bash deploy-ssh.sh ["提交信息"]
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
MSG="${1:-自动部署}"

echo "📦 提交并推送..."
cd "$APP_DIR"
git add -A
git commit -m "$MSG" 2>/dev/null || echo "(无新更改)"
git push

echo "🚀 SSH 部署到服务器..."
ssh -i ~/.ssh/aliyun_ecs root@47.76.83.82 '
  cd /opt/yousa
  git pull
  systemctl restart yousa
  echo "✅ $(date +%T) 部署完成"
' 2>&1 | grep -v WARNING

echo ""
echo "✨ 完成！"