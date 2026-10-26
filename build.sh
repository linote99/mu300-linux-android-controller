#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash ./tests/run-synthetic.sh
SDK=${ANDROID_HOME:-/workspace/.android-sdk}
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-35"
JAR="$PLATFORM/android.jar"
OUT=build
APP=app/src/main
VERSION_NAME=0.4.0
VERSION_CODE=13
PACKAGE=org.linote.mu300controller
for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$BT/core-lambda-stubs.jar" "$JAR"; do
    [[ -e "$f" ]] || { echo "缺少 Android 构建工具：$f" >&2; exit 1; }
done
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" dist
rm -f "dist/MU300-Linux-OTG-Controller-$VERSION_NAME.apk" "dist/MU300-Linux-OTG-Controller-$VERSION_NAME.apk.sha256" "dist/MU300-Linux-OTG-Controller-$VERSION_NAME.apk.idsig"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
    --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"
mkdir -p "$OUT/gen/org/linote/mu300controller"
cat > "$OUT/gen/org/linote/mu300controller/BuildInfo.java" <<JAVA
package org.linote.mu300controller;
final class BuildInfo {
    static final String VERSION_NAME = "$VERSION_NAME";
    static final int VERSION_CODE = $VERSION_CODE;
    private BuildInfo() { }
}
JAVA
find "$APP/java" "$OUT/gen" -name '*.java' -print > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
    -classpath "$JAR" -Xlint:-options -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' -print > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KEYSTORE=signing/mu300-otg-controller.p12
mkdir -p signing
if [[ ! -f "$KEYSTORE" ]]; then
    PASS=$(python3 -c 'import secrets; print(secrets.token_hex(16))')
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias mu300-otg-controller \
        -keyalg RSA -keysize 3072 -validity 10000 -storepass "$PASS" -keypass "$PASS" \
        -dname "CN=MU300 Linux OTG Controller, O=Linote, C=US" >/dev/null
    printf '%s\n' "$PASS" > signing/.storepass
    chmod 600 "$KEYSTORE" signing/.storepass
    echo "为此独立控制端生成了本地签名密钥（保存在 signing/，请勿公开）。"
fi
PASS=$(cat signing/.storepass)
APK="dist/MU300-Linux-OTG-Controller-$VERSION_NAME.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias mu300-otg-controller \
    --ks-pass "pass:$PASS" --key-pass "pass:$PASS" --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$APK" > "$OUT/verify.txt"
"$BT/aapt2" dump badging "$APK" > "$OUT/badging.txt"
head -n 1 "$OUT/badging.txt"
grep -q "package: name='$PACKAGE' versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" "$OUT/badging.txt"
(cd dist && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256")
SOURCE_ZIP="dist/MU300-Linux-OTG-Controller-$VERSION_NAME-source.zip"
rm -f "$SOURCE_ZIP" "$SOURCE_ZIP.sha256"
zip -qr "$SOURCE_ZIP" .gitignore README.md RESEARCH.md UPSTREAM-NOTICES.md build.sh tests app/src/main/AndroidManifest.xml app/src/main/java app/src/main/res app/src/main/assets
(cd dist && sha256sum "$(basename "$SOURCE_ZIP")" > "$(basename "$SOURCE_ZIP").sha256")
printf '构建成功：%s\n' "$APK"
cat "dist/$(basename "$APK").sha256"
printf '源码包：%s\n' "$SOURCE_ZIP"
cat "dist/$(basename "$SOURCE_ZIP").sha256"
