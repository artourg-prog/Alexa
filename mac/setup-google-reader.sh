#!/bin/zsh
# One-time, on Art's Mac: create a Google service account that can only READ sheets.
# Key is saved OUTSIDE the repo at ~/AlexaSecrets/tailor-shop-reader.json.
set -euo pipefail
export PATH=/opt/homebrew/share/google-cloud-sdk/bin:$PATH

PROJECT_ID="${PROJECT_ID:-tailor-shop-alexa-$(date +%y%m%d)}"
SA_NAME="alexa-sheet-reader"
SA_EMAIL="$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com"
KEY_DIR="$HOME/AlexaSecrets"
KEY_FILE="$KEY_DIR/tailor-shop-reader.json"

gcloud auth list --filter=status:ACTIVE --format='value(account)' | grep -q . || gcloud auth login

gcloud projects describe "$PROJECT_ID" >/dev/null 2>&1 || gcloud projects create "$PROJECT_ID" --name="Tailor Shop Alexa"
gcloud config set project "$PROJECT_ID"
gcloud services enable sheets.googleapis.com

# No IAM roles granted on the project: the account can do nothing except
# read sheets explicitly shared with it (Art shares as Viewer).
gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1 || \
  gcloud iam service-accounts create "$SA_NAME" --display-name="Alexa Tailor Shop sheet reader (read-only)"

mkdir -p "$KEY_DIR" && chmod 700 "$KEY_DIR"
if [[ ! -f "$KEY_FILE" ]]; then
  gcloud iam service-accounts keys create "$KEY_FILE" --iam-account="$SA_EMAIL"
  chmod 600 "$KEY_FILE"
fi

echo
echo "Share the sheet '2026 RECEIPT - MASTER' with this address as VIEWER:"
echo "  $SA_EMAIL"
