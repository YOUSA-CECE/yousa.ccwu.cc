#!/bin/bash
# yousa.ccwu.cc — 一键发版（版本号+编译APK+部署）
# 自动处理 Clash/VPN 环境，挂了代理也能编译
# 用法: bash release.sh "更新内容说明"
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_JSON="$APP_DIR/static/version.json"
ANDROID_DIR="/d/DeepSeek/yousa-android"
WIN_DIR="$(echo "$APP_DIR" | sed 's|^/\([a-z]\)/|\U\1:/|')"
WIN_VJSON="${WIN_DIR}/static/version.json"
MSG="${1:-自动发版}"
HOSTS="/c/Windows/System32/drivers/etc/hosts"

# ── 网络兼容层 ──
# 检测本地代理(Clash/V2ray)，自动配 Gradle + hosts 直连 Google
PROXY_HOST=""
PROXY_PORT=""
for port in 7897 7890 7891; do
  if curl -s --connect-timeout 1 -o /dev/null http://127.0.0.1:$port 2>/dev/null; then
    PROXY_HOST="127.0.0.1"; PROXY_PORT=$port
    echo "🌐 检测到本地代理 :$port"
    break
  fi
done

if [ -n "$PROXY_HOST" ]; then
  # DNS 被代理劫持时，用 8.8.8.8 获取 Google 真实 IP 写入 hosts
  REAL_IP=$(nslookup dl.google.com 8.8.8.8 2>/dev/null | grep "Address:" | tail -1 | awk '{print $2}')
  if [[ "$REAL_IP" != 198.18.* ]] && [ -n "$REAL_IP" ]; then
    # 删除旧记录，写入正确 IP
    sed -i '/dl\.google\.com/d' "$HOSTS" 2>/dev/null || true
    echo "$REAL_IP dl.google.com" >> "$HOSTS"
    echo "  → 已添加 hosts: $REAL_IP dl.google.com"
  fi
  # Gradle JVM 走代理
  export GRADLE_OPTS="-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT"
fi

# ── 1. 版本号 +1 ──
python3 << PYEOF
import json
with open('$WIN_VJSON', 'r') as f:
    d = json.load(f)
d['versionCode'] += 1
print(f'📈 versionCode: {d["versionCode"]-1} → {d["versionCode"]}')
parts = d['versionName'].split('.')
parts[-1] = str(int(parts[-1]) + 1)
d['versionName'] = '.'.join(parts)
print(f'📈 versionName: v{d["versionName"]}')
d['changelog'] = '$MSG'
print(f'📝 changelog: {d["changelog"]}')
with open('$WIN_VJSON', 'w') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)
    f.write('\n')
PYEOF

# ── 2. 编译 APK ──
echo ""
echo "🔨 编译 APK..."
cd "$ANDROID_DIR"
export JAVA_HOME=/d/AndroidBuildEnv/jdk/jdk-17.0.19+10
export ANDROID_HOME=/d/AndroidBuildEnv/android-sdk
export PATH=$JAVA_HOME/bin:/d/AndroidBuildEnv/gradle/gradle-8.7/bin:$PATH
gradle assembleDebug --no-daemon 2>&1 | tail -5

# ── 3. 清理 hosts（避免残留） ──
if [ -n "$PROXY_HOST" ]; then
  sed -i '/dl\.google\.com/d' "$HOSTS" 2>/dev/null || true
fi

# ── 4. 复制 APK ──
cp app/build/outputs/apk/debug/app-debug.apk "$APP_DIR/static/"
cp app/build/outputs/apk/debug/app-debug.apk "/d/DeepSeek/yousa-安卓App_v1.0.0.apk"
echo "✅ APK 已更新"

# ── 5. Git 提交 + SSH 部署 ──
echo ""
echo "📦 推送代码..."
NEW_VER=$(python3 -c "import json; print(json.load(open('$WIN_VJSON'))['versionName'])")
cd "$APP_DIR"
git add -A
git commit -m "release: v${NEW_VER} — $MSG"
git push

echo "🚀 部署到服务器..."
ssh -i ~/.ssh/aliyun_ecs root@47.76.83.82 '
  cd /opt/yousa
  git pull
  systemctl restart yousa
  echo "✅ $(date +%T) 部署完成"
' 2>&1 | grep -v WARNING

# ── 6. 完成 ──
echo ""
echo "🎉 发版完成！"
python3 -c "
import json
with open('$WIN_VJSON') as f:
    d = json.load(f)
print(f'  版本: v{d[\"versionName\"]} (code={d[\"versionCode\"]})')
print(f'  更新内容: {d[\"changelog\"]}')
"
echo "  📱 https://yousa.ccwu.cc/app"
echo "  📥 https://yousa.ccwu.cc/download"
