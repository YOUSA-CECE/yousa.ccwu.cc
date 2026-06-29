#!/bin/bash
# yousa.ccwu.cc — 一键部署脚本（在服务器上运行）
# 用法: bash deploy.sh

set -e

echo "========================================="
echo "  yousa.ccwu.cc 一键部署"
echo "========================================="

# 1. 安装依赖
echo "[1/5] 安装系统依赖..."
sudo apt update -qq
sudo apt install -y -qq python3 python3-pip python3-venv nginx 2>/dev/null

# 2. 创建项目目录
echo "[2/5] 创建项目目录..."
sudo mkdir -p /opt/yousa
sudo cp -r . /opt/yousa/
sudo chown -R $USER:$USER /opt/yousa

# 3. 安装 Python 依赖
echo "[3/5] 安装 Python 依赖..."
cd /opt/yousa
pip3 install -r requirements.txt 2>/dev/null || pip3 install flask flask-login markdown werkzeug

# 4. 配置 systemd 服务
echo "[4/5] 配置开机自启..."
sudo tee /etc/systemd/system/yousa.service > /dev/null << 'SVC'
[Unit]
Description=yousa.ccwu.cc Flask App
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/yousa
ExecStart=/usr/bin/python3 /opt/yousa/app.py
Restart=always
RestartSec=3
Environment=PORT=5000

[Install]
WantedBy=multi-user.target
SVC

sudo systemctl daemon-reload
sudo systemctl enable yousa
sudo systemctl restart yousa

echo "[5/5] 启动中..."
sleep 2
sudo systemctl status yousa --no-pager

echo ""
echo "========================================="
echo "  部署完成！"
echo "  本地访问: http://localhost:5000"
echo "  检查状态: sudo systemctl status yousa"
echo "  查看日志: sudo journalctl -u yousa -f"
echo "========================================="
