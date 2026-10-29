#!/bin/zsh
# Puts Sofia's Voice Finish key on the Fold (same pattern as set-pickup-key-on-fold.sh):
#   printf '%s\n%s\n%s\n' "$READER_URL" "$SOFIA_SECRET" "Andrei,Artour,Carolina,Emma,Tsegi,Tumur" | ~/Alexa/phone-app/set-finish-key-on-fold.sh
# stdin: line 1 = Daily Report Reader /exec URL, line 2 = Sofia's key, line 3 = the exact Approved Workers names
# (comma-separated). Never on a command line, in a log or in git. Fold only. Prints SET or "NOT SET: <reason>".
set -uo pipefail
ADB=$HOME/Library/Android/sdk/platform-tools/adb
S=RFCX10EB2XV
PKG=com.tailorshop.counter
IFS= read -r URL || true
IFS= read -r KEY || true
IFS= read -r NAMES || true
[[ $URL == https://script.google.com/* ]] || { echo "NOT SET: bad url"; exit 1; }
[[ ${#KEY} -ge 16 ]] || { echo "NOT SET: bad key"; exit 1; }
[[ -n $NAMES ]] || { echo "NOT SET: no worker names"; exit 1; }
[[ $($ADB -s $S get-state 2>/dev/null) == device ]] || { echo "NOT SET: Fold not connected"; exit 1; }
[[ $($ADB -s $S shell getprop ro.product.model | tr -d '\r') == SM-F9* ]] || { echo "NOT SET: not the Fold"; exit 1; }
URL=$URL KEY=$KEY NAMES=$NAMES python3 -c 'import json,os; print(json.dumps({"url": os.environ["URL"], "secret": os.environ["KEY"], "workers": [n.strip() for n in os.environ["NAMES"].split(",") if n.strip()]}))' |
  $ADB -s $S exec-in run-as $PKG sh -c 'cat > files/finish-config.tmp' || { echo "NOT SET: couldn't write on the Fold"; exit 1; }
unset KEY
$ADB -s $S shell "run-as $PKG sh -c 'chmod 600 files/finish-config.tmp && mv files/finish-config.tmp files/finish-config.json'"
[[ $($ADB -s $S shell "run-as $PKG sh -c 'test -s files/finish-config.json && echo ok'" | tr -d '\r') == ok ]] \
  && echo SET || echo "NOT SET: not saved"
