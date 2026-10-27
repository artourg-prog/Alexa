'use strict';

// Read-only Google Sheets access. Two locks keep it read-only:
//   1. the token is requested with the spreadsheets.readonly scope only;
//   2. the service account is shared on the sheet as Viewer.
// Only values.batchGet (a GET) is ever called. There is no write code here.

const crypto = require('crypto');
const https = require('https');

const SPREADSHEET_ID = '1-LVtZR-ycBMipCROolHWmIC8hcnphw7co6U11r6ZE68';
const RANGES = ["'All Receipts'!A2:J", "'Pickup Texts'!A2:C"];
const SCOPE = 'https://www.googleapis.com/auth/spreadsheets.readonly';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const TIMEOUT_MS = 5000; // Alexa gives the skill 8 s in total

function loadKey() {
  let key;
  try {
    key = require('./sheet-key.json');
  } catch (e) {
    throw new Error('KEY_MISSING');
  }
  return checkKey(key);
}

function checkKey(key) {
  if (!key.client_email || !key.private_key || !key.private_key.includes('PRIVATE KEY')) {
    throw new Error('KEY_MISSING');
  }
  return key;
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

// Returns { receipts: [[...A:J]], pickups: [[...A:C]] }.
async function readSheet(http = request, getKey = loadKey) {
  const token = await accessToken(http, getKey);
  const qs = RANGES.map((r) => `ranges=${encodeURIComponent(r)}`).join('&');
  const url = `https://sheets.googleapis.com/v4/spreadsheets/${SPREADSHEET_ID}/values:batchGet?${qs}`;
  const res = await http('GET', url, null, { Authorization: `Bearer ${token}` });
  const [receipts, pickups] = res.valueRanges || [];
  return { receipts: (receipts && receipts.values) || [], pickups: (pickups && pickups.values) || [] };
}

function _resetTokenCache() {
  cachedToken = null;
}

module.exports = { SPREADSHEET_ID, RANGES, SCOPE, signedJwt, checkKey, readSheet, _resetTokenCache };
