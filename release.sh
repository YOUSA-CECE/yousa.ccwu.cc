#!/bin/bash
# yousa.ccwu.cc — 一键发版（更新版本号 + 编译 APK + 部署）
# 用法: bash release.sh "更新内容说明"
set -e

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSION_JSON="$APP_DIR/static/version.json"
ANDROID_DIR="/d/DeepSeek/yousa-android"
# Windows path for Python
WIN_DIR="$(echo "$APP_DIR" | sed 's|^/\([a-z]\)/|\U\1:/|')"
WIN_VJSON="${WIN_DIR}/static/version.json"
MSG="${1:-自动发版}"

if [ ! -f "$VERSION_JSON" ]; then
  echo "❌ 找不到 $VERSION_JSON"
  exit 1
fi

echo "📋 当前版本信息:"
python3 -c "
import sys,json
with open('$WIN_VJSON') as f:
    d = json.load(f)
print(f'  版本: v{d[\"versionName\"]} (code={d[\"versionCode\"]})')
print(f'  更新日志: {d[\"changelog\"][:60]}')
"

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
gradle assembleDebug --no-daemon 2>&1 | tail -3

# ── 3. 复制 APK ──
cp app/build/outputs/apk/debug/app-debug.apk "$APP_DIR/static/"
cp app/build/outputs/apk/debug/app-debug.apk "/d/DeepSeek/yousa-安卓App_v1.0.0.apk"
echo "✅ APK 已更新"

# ── 4. Git 提交 + SSH 部署 ──
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

# ── 5. 显示结果 ──
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