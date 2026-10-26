#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-/workspace/.android-sdk}
BT="$SDK/build-tools/34.0.0"
APK=dist/MU300-Linux-OTG-Controller-0.4.0.apk
[[ -s "$APK" ]] || { echo "FAIL: APK 不存在或为空：$APK" >&2; exit 1; }
"$BT/apksigner" verify --verbose "$APK" >/dev/null
"$BT/zipalign" -c -v 4 "$APK" >/dev/null
BADGING=$("$BT/aapt2" dump badging "$APK")
grep -q "package: name='org.linote.mu300controller' versionCode='13' versionName='0.4.0'" <<<"$BADGING"
grep -q "sdkVersion:'26'" <<<"$BADGING"
grep -q "targetSdkVersion:'35'" <<<"$BADGING"
grep -q "launchable-activity: name='org.linote.mu300controller.MainActivity'" <<<"$BADGING"
PERMS=$("$BT/aapt2" dump permissions "$APK")
grep -q "android.permission.INTERNET" <<<"$PERMS"
if grep -Eqi 'READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|WRITE_SECURE_SETTINGS|REBOOT|MANAGE_EXTERNAL_STORAGE|REQUEST_INSTALL_PACKAGES' <<<"$PERMS"; then
    echo "FAIL: APK 声明了未预期的高风险权限" >&2; exit 1
fi
grep -q 'android.hardware.usb.host' <<<"$BADGING"
ENTRIES=$(unzip -Z1 "$APK")
for name in android-web.sh storage.sh android-boot-image.sh android-mount-mu300root.sh switch.sh SHA256SUMS; do
    grep -qx "assets/upstream/device/$name" <<<"$ENTRIES" || { echo "FAIL: APK 缺少上游资源 $name" >&2; exit 1; }
done
grep -qx 'assets/upstream/LICENSE' <<<"$ENTRIES" || { echo "FAIL: APK 缺少上游 MIT 许可" >&2; exit 1; }
while read -r expected name; do
    [[ -n "$name" ]] || continue
    actual=$(unzip -p "$APK" "assets/upstream/device/$name" | sha256sum | cut -d' ' -f1)
    [[ "$actual" == "$expected" ]] || { echo "FAIL: 内嵌上游脚本哈希不匹配：$name" >&2; exit 1; }
done < <(unzip -p "$APK" assets/upstream/device/SHA256SUMS)
if grep -Eq '(^|/)(vmlinuz|Image|zImage|payload/)' <<<"$ENTRIES"; then
    echo "FAIL: APK 不应打包 GPL 内核或 Magisk payload" >&2; exit 1
fi
(cd dist && sha256sum -c "$(basename "$APK").sha256")
echo "PASS: v0.4.0 APK 签名、对齐、清单、权限、上游资源/许可证及 SHA-256 均通过；未访问任何设备。"
