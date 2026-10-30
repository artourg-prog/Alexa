#!/bin/zsh
# Publishes a new Tailor Counter build for the Fold's Wi-Fi updates. Run ONLY after Art says "Publish".
#   ./publish-phone-update.sh
# Bumps the version, runs the tests, builds (signed with the same Mac key as every earlier build),
# splits it into ~4 MB pieces with SHA-256s, and uploads into Art's private Drive folder
# "Tailor Counter Builds": parts/ first, latest.json LAST (so a half-uploaded build is never offered).
# Uploads use Art's own Google sign-in on this Mac (gcloud). Prints no secrets.
set -euo pipefail
cd "$(dirname $0)"
FOLDER=${SOFIA_UPDATES_FOLDER:?Set SOFIA_UPDATES_FOLDER from private release configuration}
PARTS=${SOFIA_UPDATES_PARTS:?Set SOFIA_UPDATES_PARTS from private release configuration}
GRADLE=app/build.gradle.kts

TOKEN=$(gcloud auth print-access-token 2>/dev/null) || { echo "Not signed in to Google on this Mac. Run: gcloud auth login --enable-gdrive-access"; exit 1; }
curl -sf -o /dev/null -H "Authorization: Bearer $TOKEN" "https://www.googleapis.com/drive/v3/files/$FOLDER?fields=id" \
  || { echo "This Mac can't reach the Drive folder yet. Run once: gcloud auth login --enable-gdrive-access   (then run this again)"; exit 1; }

OLD=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' $GRADLE)
CODE=$((OLD + 1)); NAME="0.$CODE"
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $CODE/; s/versionName = \"[^\"]*\"/versionName = \"$NAME\"/" $GRADLE
echo "Building v$NAME ($CODE)…"
./gradlew -q testDebugUnitTest assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk

OUT=$(mktemp -d)
split -b 4m -a 2 "$APK" "$OUT/p$CODE.part"
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
NAMES=(); SUMS=()
for f in $(ls "$OUT" | sort); do NAMES+=("\"$f\""); SUMS+=("\"$(sha "$OUT/$f")\""); done
JOIN() { local IFS=,; echo "$*"; }
print -r -- "{\"package\":\"com.tailorshop.counter\",\"versionCode\":$CODE,\"versionName\":\"$NAME\",\"size\":$(wc -c < $APK | tr -d ' '),\"sha256\":\"$(sha $APK)\",\"parts\":[$(JOIN $NAMES)],\"partSha256\":[$(JOIN $SUMS)]}" > "$OUT.latest.json"

upload() { # file, name, parent
  curl -sf -H "Authorization: Bearer $TOKEN" \
    -F "metadata={\"name\":\"$2\",\"parents\":[\"$3\"]};type=application/json" \
    -F "file=@$1;type=application/octet-stream" \
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id" >/dev/null \
    || { echo "Upload of $2 failed. Nothing is offered to the phone (latest.json not uploaded)."; exit 1; }
}
for f in $(ls "$OUT" | sort); do upload "$OUT/$f" "$f" $PARTS; echo "  uploaded piece $f"; done
upload "$OUT.latest.json" latest.json $FOLDER
rm -rf "$OUT" "$OUT.latest.json"
echo "Published v$NAME. The Fold installs it at 8:00 AM on Wi-Fi, or right away with the Update button."
