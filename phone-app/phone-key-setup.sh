#!/bin/zsh
# ONE script Art runs once on the Mac to set up the "Picked up" phone key:
#   1. makes a new random key (never printed, never in git or logs);
#   2. hands it on stdin to the Pickups setter, which stores it as PHONE_SECRET in the web app
#      and prints the web app address (Pickups — Mac owns that part);
#   3. saves address + key in ~/AlexaSecrets/pickup-config.json (only Art can read it) and puts it on the Fold;
#   4. checks the web app accepts the key with an empty status question (marks nothing).
set -euo pipefail
SETTER=${PICKUPS_SET_PHONE_SECRET:-$HOME/AlexaSecrets/bin/pickups-set-phone-secret.sh}
OUT=$HOME/AlexaSecrets/pickup-config.json
[[ -x $SETTER ]] || { echo "The Pickups part isn't on this Mac yet ($SETTER). Nothing changed."; exit 1; }

echo "1/4 Making a new phone key…"
KEY=$(openssl rand -hex 32)

echo "2/4 Saving it in the Pickups web app…"
URL=$(print -r -- "$KEY" | "$SETTER" | tail -1 | tr -d '\r')
[[ $URL == https://script.google.com/* ]] || { echo "The Pickups part didn't finish. Nothing was put on the phone."; exit 1; }

echo "3/4 Putting it on the Fold…"
umask 077
URL=$URL KEY=$KEY python3 -c 'import json,os; print(json.dumps({"url": os.environ["URL"], "secret": os.environ["KEY"]}))' > "$OUT"
unset KEY
"$(dirname $0)/install-fold.sh" | grep -E "Fold found|pickup key installed|Done"

echo "4/4 Checking the web app accepts the key…"
RES=$(python3 - "$OUT" <<'PY'
import json, sys, urllib.request
c = json.load(open(sys.argv[1]))
req = urllib.request.Request(c["url"], json.dumps({"secret": c["secret"], "mode": "pickupStatus", "requestIds": []}).encode(),
                             {"Content-Type": "application/json"})
try:
    print("OK" if "error" not in urllib.request.urlopen(req, timeout=20).read().decode().lower() else "REFUSED")
except Exception:
    print("FAILED")
PY
)
case $RES in
  OK) echo "All set. The Picked up button now shows on the Fold.";;
  *)  echo "The web app didn't accept the key ($RES). Tell Claude: phone key check failed.";;
esac
