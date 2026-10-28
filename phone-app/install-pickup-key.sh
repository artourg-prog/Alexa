#!/bin/zsh
# Art runs this once: saves the Pickups web app address and the phone-only key on this Mac
# (~/AlexaSecrets, never git) and puts them on the Fold. The key is typed hidden and not shown.
set -euo pipefail
OUT=$HOME/AlexaSecrets/pickup-config.json
read "URL?Pickups web app address: "
read -s "SECRET?Phone key (hidden): "; echo
URL=$URL SECRET=$SECRET python3 -c 'import json,os; print(json.dumps({"url": os.environ["URL"].strip(), "secret": os.environ["SECRET"].strip()}))' > "$OUT"
chmod 600 "$OUT"
echo "Saved. Installing on the Fold…"
exec "$(dirname $0)/install-fold.sh"
