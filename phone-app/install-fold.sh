#!/bin/zsh
# Install Tailor Counter on the Galaxy Fold over USB and drop the read-only sheet key
# into the app's private storage. Only ever talks to a Samsung Fold (model SM-F...),
# never to the shop tablet or any other device.
set -euo pipefail
ADB=$HOME/Library/Android/sdk/platform-tools/adb
PKG=com.tailorshop.counter
APK=${APK:-$(dirname $0)/app/build/outputs/apk/debug/app-debug.apk}
KEY=$HOME/AlexaSecrets/tailor-shop-reader.json

S=""
for d in $($ADB devices | awk 'NR>1 && $2=="device" {print $1}'); do
  m=$($ADB -s $d shell getprop ro.product.model | tr -d '\r')
  if [[ $m == SM-F9* ]]; then S=$d; echo "Fold found: $m ($d)"; fi
done
[[ -n $S ]] || { echo "No Galaxy Fold connected (or USB debugging not allowed yet)."; exit 1; }

$ADB -s $S install -r "$APK"
$ADB -s $S exec-in run-as $PKG sh -c 'mkdir -p files && cat > files/sheet-key.json' < "$KEY"
$ADB -s $S shell run-as $PKG sh -c 'test -s files/sheet-key.json && echo key installed'
# "Picked up" phone key, only once Art has saved it with install-pickup-key.sh.
PICK=$HOME/AlexaSecrets/pickup-config.json
if [[ -s $PICK ]]; then
  $ADB -s $S exec-in run-as $PKG sh -c 'cat > files/pickup-config.json && chmod 600 files/pickup-config.json' < "$PICK"
  echo "pickup key installed"
fi
$ADB -s $S shell pm grant $PKG android.permission.RECORD_AUDIO
$ADB -s $S shell am start -n $PKG/.MainActivity
echo "Done."
