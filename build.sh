#!/usr/bin/env bash
# 无 Gradle 手工打包：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

export JAVA_HOME="$ROOT/tools/jdk-17.0.21+9"
SDK="$ROOT/tools/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

B="$ROOT/build"
OUT="$ROOT/dist"
KS="$ROOT/tools/debug.keystore"

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$OUT"

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir app/src/main/res -o "$B/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2.exe" link \
  -o "$B/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  -R "$B/res.zip" \
  --java "$B/gen" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "[3/6] javac"
SRC_FILES=$(find app/src/main/java -name "*.java")
GEN_FILES=$(find "$B/gen" -name "*.java")
# shellcheck disable=SC2086
# 注意：不能用 -bootclasspath android.jar —— 它缺 java.lang.invoke.LambdaMetafactory，
# 会让 lambda 编译失败。改用普通 classpath，lambda 由 d8 负责脱糖。
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR" \
  -d "$B/classes" $SRC_FILES $GEN_FILES

echo "[4/6] d8 -> classes.dex"
CLS_FILES=$(find "$B/classes" -name "*.class")
# shellcheck disable=SC2086
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api 21 --lib "$ANDROID_JAR" --output "$B/dex" $CLS_FILES

cp "$B/base.apk" "$B/unaligned.apk"
python tools/add_dex.py "$B/unaligned.apk" "$B/dex/classes.dex"

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 "$B/unaligned.apk" "$B/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT/tvweb-shell.apk" "$B/aligned.apk"

"$JAVA" -jar "$BT/lib/apksigner.jar" verify -v "$OUT/tvweb-shell.apk" | head -6

ls -lh "$OUT/tvweb-shell.apk"
echo "构建完成：$OUT/tvweb-shell.apk"
