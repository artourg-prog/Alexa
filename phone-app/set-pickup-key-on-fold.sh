#!/bin/zsh
# Fold receiver for the "Picked up" phone key, called by Pickups' phone-key.sh:
#   printf '%s\n%s\n' "$URL" "$KEY" | ~/Alexa/phone-app/set-pickup-key-on-fold.sh
# Reads the web app URL (line 1) and phone key (line 2) on stdin, so the key is never on a command
# line, in a log or in git, and writes them into Tailor Counter's private storage on the Fold only
# (serial RFCX10EB2XV, model SM-F9*). Prints SET or "NOT SET: <reason>", never the key.
set -uo pipefail
ADB=$HOME/Library/Android/sdk/platform-tools/adb
S=RFCX10EB2XV
PKG=com.tailorshop.counter
IFS= read -r URL || true
IFS= read -r KEY || true
[[ $URL == https://script.google.com/* ]] || { echo "NOT SET: bad url"; exit 1; }
[[ $KEY =~ ^[0-9a-f]{32,128}$ ]] || { echo "NOT SET: bad key"; exit 1; }
[[ $($ADB -s $S get-state 2>/dev/null) == device ]] || { echo "NOT SET: Fold not connected"; exit 1; }
[[ $($ADB -s $S shell getprop ro.product.model | tr -d '\r') == SM-F9* ]] || { echo "NOT SET: not the Fold"; exit 1; }
URL=$URL KEY=$KEY python3 -c 'import json,os; print(json.dumps({"url": os.environ["URL"], "secret": os.environ["KEY"]}))' |
  $ADB -s $S exec-in run-as $PKG sh -c 'mkdir -p files && cat > files/pickup-config.tmp && chmod 600 files/pickup-config.tmp && mv files/pickup-config.tmp files/pickup-config.json' \
  || { echo "NOT SET: couldn't write on the Fold"; exit 1; }
unset KEY
[[ $($ADB -s $S shell run-as $PKG sh -c 'test -s files/pickup-config.json && echo ok' | tr -d '\r') == ok ]] \
  && echo SET || echo "NOT SET: not saved"
