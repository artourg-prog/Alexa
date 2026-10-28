'use strict';

// Read-only Google Sheets access. Two locks keep it read-only:
//   1. the token is requested with the spreadsheets.readonly scope only;
//   2. the service account is shared on the sheet as Viewer.
// Only values.batchGet (a GET) is ever called. There is no write code here.

const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const path = require('path');

// "Order Ready For Pickup" (synced from the Pickups tablet) — not the master receipts sheet.
const SPREADSHEET_ID = '14NLtaKEBKPLU-lM6SSpXuJx-Wog-qmXltMIjsimh0as';
// Header row included: lookup.js finds columns by name.
const RANGES = ["'Active Orders'!A1:Z"];
const SCOPE = 'https://www.googleapis.com/auth/spreadsheets.readonly';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const TIMEOUT_MS = 5000; // Alexa gives the skill 8 s in total

const KEY_FILE = path.join(__dirname, 'sheet-key.json');

// Each failure gets its own code so the screen says exactly what's wrong.
// Never logs the key itself.
function loadKey(file = KEY_FILE) {
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (e) {
    console.log('Key file not found:', file, e.code);
    throw new Error('KEY_FILE_MISSING');
  }
  let key;
  try {
    key = JSON.parse(text.replace(/^\uFEFF/, '').trim()); // tolerate BOM / stray whitespace
  } catch (e) {
    console.log('Key file is not valid JSON:', e.message, 'length', text.length);
    throw new Error('KEY_BAD_JSON');
  }
  return checkKey(key);
}

function checkKey(key) {
  // Pasting can turn the key's \n line breaks into literal backslash-n; undo that.
  const privateKey = String((key && key.private_key) || '').replace(/\\n/g, '\n');
  if (!key || !key.client_email || !privateKey.includes('PRIVATE KEY')) {
    console.log('Key file incomplete:', { client_email: Boolean(key && key.client_email), private_key: Boolean(privateKey) });
    throw new Error('KEY_INCOMPLETE');
  }
  try {
    crypto.createPrivateKey(privateKey);
  } catch (e) {
    console.log('Private key unreadable:', e.message);
    throw new Error('KEY_INVALID');
  }
  return { ...key, private_key: privateKey };
}

function b64url(buf) {
  return Buffer.from(buf).toString('base64').replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function signedJwt(key, now = Math.floor(Date.now() / 1000)) {
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(
    JSON.stringify({ iss: key.client_email, scope: SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 }),
  );
  const sig = crypto.createSign('RSA-SHA256').update(`${header}.${claims}`).sign(key.private_key);
  return `${header}.${claims}.${b64url(sig)}`;
}

function request(method, url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method, headers, timeout: TIMEOUT_MS }, (res) => {
      let data = '';
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try {
            resolve(JSON.parse(data));
          } catch (e) {
            reject(new Error('BAD_JSON'));
          }
        } else {
          reject(new Error(`HTTP_${res.statusCode}`));
        }
      });
    });
    req.on('timeout', () => req.destroy(new Error('TIMEOUT')));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

let cachedToken = null; // survives between warm Lambda calls

async function accessToken(http, getKey) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.value;
  const key = getKey();
  const body = new URLSearchParams({
    grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
    assertion: signedJwt(key, now),
  }).toString();
  const res = await http('POST', TOKEN_URL, body, { 'Content-Type': 'application/x-www-form-urlencoded' });
  cachedToken = { value: res.access_token, exp: now + (res.expires_in || 3600) };
  return cachedToken.value;
}

// Returns the Active Orders rows, header first.
async function readSheet(http = request, getKey = loadKey) {
  const token = await accessToken(http, getKey);
  const qs = RANGES.map((r) => `ranges=${encodeURIComponent(r)}`).join('&');
  const url = `https://sheets.googleapis.com/v4/spreadsheets/${SPREADSHEET_ID}/values:batchGet?${qs}`;
  const res = await http('GET', url, null, { Authorization: `Bearer ${token}` });
  const [orders] = res.valueRanges || [];
  return (orders && orders.values) || [];
}

function _resetTokenCache() {
  cachedToken = null;
}

module.exports = { SPREADSHEET_ID, RANGES, SCOPE, signedJwt, loadKey, checkKey, readSheet, _resetTokenCache };
