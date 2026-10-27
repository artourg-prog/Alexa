// Read-only check: proves the service-account key can read the receipts sheet.
// Prints counts only — no customer names or phones.
// Usage: node mac/check-sheet-access.mjs [path/to/key.json]
import { readFileSync } from 'node:fs';
import { createSign } from 'node:crypto';
import { homedir } from 'node:os';

const SHEET_ID = '1-LVtZR-ycBMipCROolHWmIC8hcnphw7co6U11r6ZE68';
const SCOPE = 'https://www.googleapis.com/auth/spreadsheets.readonly';
const keyPath = process.argv[2] ?? `${homedir()}/AlexaSecrets/tailor-shop-reader.json`;
const key = JSON.parse(readFileSync(keyPath, 'utf8'));

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
  iss: key.client_email, scope: SCOPE, aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 600,
})}`;
const jwt = `${unsigned}.${createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url')}`;

const tok = await fetch('https://oauth2.googleapis.com/token', {
  method: 'POST',
  headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
}).then((r) => r.json());
if (!tok.access_token) throw new Error(`token failed: ${JSON.stringify(tok)}`);

async function get(range) {
  const url = `https://sheets.googleapis.com/v4/spreadsheets/${SHEET_ID}/values/${encodeURIComponent(range)}`;
  const r = await fetch(url, { headers: { authorization: `Bearer ${tok.access_token}` } });
  const j = await r.json();
  if (!r.ok) throw new Error(`${range}: ${r.status} ${j.error?.message}`);
  return j.values ?? [];
}

const rows = await get('All Receipts!A2:M');
const header = await get('All Receipts!A1:M1');
const texts = await get('Pickup Texts!A1:Z');
console.log(`OK as ${key.client_email} (scope: readonly)`);
console.log(`All Receipts: ${rows.length} data rows; headers: ${JSON.stringify(header[0])}`);
console.log(`Pickup Texts: ${Math.max(texts.length - 1, 0)} rows; headers: ${JSON.stringify(texts[0])}`);
