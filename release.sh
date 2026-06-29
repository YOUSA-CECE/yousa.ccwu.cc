#!/usr/bin/env bash
# Build, publish metadata, push to GitHub, and deploy yousa Android releases.
# Usage: bash release.sh "更新日志"
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$APP_DIR/android"
VERSION_JSON="$APP_DIR/static/version.json"
BUILD_GRADLE="$ANDROID_DIR/app/build.gradle"
GRADLE_BIN="${GRADLE_BIN:-/d/AndroidBuildEnv/gradle/gradle-8.7/bin/gradle}"
export JAVA_HOME="${JAVA_HOME:-/d/AndroidBuildEnv/jdk/jdk-17.0.19+10}"
export ANDROID_HOME="${ANDROID_HOME:-/d/AndroidBuildEnv/android-sdk}"
CHANGELOG="${1:-性能与稳定性改进}"

python - "$VERSION_JSON" "$BUILD_GRADLE" "$CHANGELOG" <<'PY'
import json
import pathlib
import re
import sys

version_file = pathlib.Path(sys.argv[1])
gradle_file = pathlib.Path(sys.argv[2])
changelog = sys.argv[3]

data = json.loads(version_file.read_text(encoding="utf-8"))
data["versionCode"] = int(data["versionCode"]) + 1
parts = data["versionName"].split(".")
parts[-1] = str(int(parts[-1]) + 1)
data["versionName"] = ".".join(parts)
data["changelog"] = changelog

gradle = gradle_file.read_text(encoding="utf-8")
gradle = re.sub(r"versionCode\s+\d+", f'versionCode {data["versionCode"]}', gradle)
gradle = re.sub(
    r"versionName\s+'[^']+'",
    f'versionName \'{data["versionName"]}\'',
    gradle,
)
gradle_file.write_text(gradle, encoding="utf-8")
version_file.write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(f'准备发布 v{data["versionName"]} (code={data["versionCode"]})')
PY

cd "$ANDROID_DIR"
"$GRADLE_BIN" clean assembleDebug lintDebug --no-daemon

VERSION_NAME="$(python -c "import json; print(json.load(open('$VERSION_JSON', encoding='utf-8'))['versionName'])")"
APK_NAME="yousa-android-v${VERSION_NAME}.apk"
APK_PATH="$APP_DIR/static/$APK_NAME"
cp "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" "$APK_PATH"

python - "$VERSION_JSON" "$APK_PATH" "$APK_NAME" <<'PY'
import json
import pathlib
import sys

version_file = pathlib.Path(sys.argv[1])
apk_file = pathlib.Path(sys.argv[2])
apk_name = sys.argv[3]
data = json.loads(version_file.read_text(encoding="utf-8"))
data["apkUrl"] = "https://yousa.ccwu.cc/static/" + apk_name
data["apkSizeBytes"] = apk_file.stat().st_size
version_file.write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(f"APK: {apk_name} ({data['apkSizeBytes']} bytes)")
print(f"更新日志: {data['changelog']}")
PY

cd "$APP_DIR"
git add -A
git commit -m "release: v${VERSION_NAME} - ${CHANGELOG}"
git push origin master

ssh -i ~/.ssh/aliyun_ecs root@47.76.83.82 '
  set -e
  cd /opt/yousa
  git pull --ff-only
  systemctl restart yousa
  systemctl is-active --quiet yousa
'

echo "发布完成：https://yousa.ccwu.cc/app"
