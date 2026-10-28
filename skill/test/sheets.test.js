'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('crypto');
const path = require('path');

const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
});
const sheets = require('../lambda/sheets');
const keyOf = (k) => () => sheets.checkKey(k);
const goodKey = keyOf({ client_email: 'r@x.iam.gserviceaccount.com', private_key: privateKey });

test('only a read-only token and a GET batchGet are ever requested', async () => {
  sheets._resetTokenCache();
  const calls = [];
  const http = async (method, url, body, headers) => {
    calls.push({ method, url, body, headers });
    if (url.startsWith('https://oauth2.googleapis.com/token')) return { access_token: 'tok', expires_in: 3600 };
    return { valueRanges: [{ values: [['receipt_number'], ['1']] }] };
  };
  const out = await sheets.readSheet(http, goodKey);
  await sheets.readSheet(http, goodKey); // token cached → 1 token call total

  assert.deepStrictEqual(out, [['receipt_number'], ['1']]);
  assert.strictEqual(calls.filter((c) => c.method === 'POST').length, 1);
  const sheetCalls = calls.filter((c) => c.url.includes('sheets.googleapis.com'));
  assert.strictEqual(sheetCalls.length, 2);
  for (const c of sheetCalls) {
    assert.strictEqual(c.method, 'GET');
    assert.match(c.url, /values:batchGet\?/);
    assert.match(c.url, /14NLtaKEBKPLU-lM6SSpXuJx-Wog-qmXltMIjsimh0as/);
    assert.doesNotMatch(c.url, /1-LVtZR-ycBMipCROolHWmIC8hcnphw7co6U11r6ZE68/); // never the master
    assert.match(decodeURIComponent(c.url), /'Active Orders'!A1:Z/);
    assert.strictEqual(c.headers.Authorization, 'Bearer tok');
  }

  // JWT: readonly scope only, signed with the key.
  const assertion = new URLSearchParams(calls[0].body).get('assertion');
  const [h, p, s] = assertion.split('.');
  const claims = JSON.parse(Buffer.from(p, 'base64url'));
  assert.strictEqual(claims.scope, 'https://www.googleapis.com/auth/spreadsheets.readonly');
  assert.strictEqual(claims.iss, 'r@x.iam.gserviceaccount.com');
  assert.ok(crypto.createVerify('RSA-SHA256').update(`${h}.${p}`).verify(publicKey, Buffer.from(s, 'base64url')));
});

test('no write code anywhere in the skill', () => {
  const fs = require('fs');
  const dir = path.join(__dirname, '..', 'lambda');
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.js'))) {
    const src = fs.readFileSync(path.join(dir, f), 'utf8');
    assert.doesNotMatch(src, /batchUpdate|:append|values\.update|'PUT'|"PUT"|'DELETE'|clear\b/, f);
  }
});

test('placeholder key → KEY_INCOMPLETE, no network', async () => {
  sheets._resetTokenCache();
  let called = false;
  const placeholder = require('../lambda/sheet-key.example.json');
  await assert.rejects(sheets.readSheet(async () => { called = true; }, keyOf(placeholder)), /KEY_INCOMPLETE/);
  assert.strictEqual(called, false);
});

test('key file problems each get their own code', () => {
  const fs = require('fs');
  const os = require('os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'key-'));
  const f = (name, text) => { const p = path.join(dir, name); fs.writeFileSync(p, text); return p; };
  const real = { type: 'service_account', client_email: 'r@x.iam.gserviceaccount.com', private_key: privateKey };

  assert.throws(() => sheets.loadKey(path.join(dir, 'nope.json')), /KEY_FILE_MISSING/);
  assert.throws(() => sheets.loadKey(f('cut.json', JSON.stringify(real).slice(0, 200))), /KEY_BAD_JSON/);
  assert.throws(() => sheets.loadKey(f('noemail.json', JSON.stringify({ private_key: privateKey }))), /KEY_INCOMPLETE/);
  assert.throws(
    () => sheets.loadKey(f('garbled.json', JSON.stringify({ ...real, private_key: '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n' }))),
    /KEY_INVALID/,
  );

  // A real Google key file: one long line, \n escapes inside private_key.
  assert.strictEqual(sheets.loadKey(f('ok.json', JSON.stringify(real))).client_email, real.client_email);
  // Same with a BOM, trailing newline, and pretty-printed.
  assert.ok(sheets.loadKey(f('bom.json', '﻿' + JSON.stringify(real, null, 2) + '\n\n')));
  // Line breaks turned into literal backslash-n by the paste (double-escaped).
  const doubled = JSON.stringify(real).replace(/\\n/g, '\\\\n');
  const k = sheets.loadKey(f('doubled.json', doubled));
  assert.ok(k.private_key.includes('\n') && !k.private_key.includes('\\n'));
  fs.rmSync(dir, { recursive: true });
});

test('missing key file next to the code → KEY_FILE_MISSING', async () => {
  sheets._resetTokenCache();
  const fs = require('fs');
  if (fs.existsSync(path.join(__dirname, '..', 'lambda', 'sheet-key.json'))) return; // real key present locally
  await assert.rejects(sheets.readSheet(async () => ({})), /KEY_FILE_MISSING/);
});
