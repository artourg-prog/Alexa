'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { normalizeLast4, findReceipts, status, readySet, speech, MAX_CARDS } = require('../lambda/lookup');
const { receipts, pickups } = require('./fixtures');

const byNum = (last4) => findReceipts(last4, receipts, pickups);

test('last 4 normalisation', () => {
  assert.strictEqual(normalizeLast4('4417'), '4417');
  assert.strictEqual(normalizeLast4('30'), '0030');
  assert.strictEqual(normalizeLast4(''), null);
  assert.strictEqual(normalizeLast4(undefined), null);
  assert.strictEqual(normalizeLast4('12345'), null);
  assert.strictEqual(normalizeLast4('?'), null);
});

test('several matches: all shown, newest first, names kept, blank receipt # skipped', () => {
  const r = byNum('4417');
  assert.strictEqual(r.count, 2);
  assert.deepStrictEqual(r.cards.map((c) => c.receipt), ['26135', '26126']);
  assert.deepStrictEqual(r.cards.map((c) => c.customer), ['Sam Roe', 'Pat Lee']);
});

test('picked up wins, even with Due (26135 Zelle + Due, 26192 Due)', () => {
  assert.deepStrictEqual(byNum('4417').cards[0], {
    receipt: '26135', customer: 'Sam Roe', date: '10/10/26', total: '$190.00', ready: 'PICKED UP', paid: null,
  });
  assert.strictEqual(byNum('1111').cards[0].ready, 'PICKED UP');
  assert.strictEqual(byNum('1111').cards[0].paid, null);
});

test('column H "D" is NOT ready', () => {
  const c = byNum('4417').cards[1]; // H ✓, pickup text NOT_YET
  assert.strictEqual(c.ready, 'NOT READY');
  assert.strictEqual(c.paid, 'PAID');
});

test('ready only when a pickup text was SENT', () => {
  assert.strictEqual(byNum('4444').cards[0].ready, 'READY'); // SENT (+ a later REQUESTED row)
  assert.strictEqual(byNum('2222').cards[0].ready, 'NOT READY'); // UNSURE
  assert.strictEqual(byNum('3333').cards[0].ready, 'NOT READY'); // FAILED
  assert.deepStrictEqual([...readySet(pickups)], ['26266']);
});

test('paid rules', () => {
  assert.strictEqual(byNum('2222').cards[0].paid, 'NOT PAID'); // Due
  assert.strictEqual(byNum('3333').cards[0].paid, 'PART PAID'); // Card + Due
  assert.strictEqual(byNum('4444').cards[0].paid, 'PAID'); // Cash
  assert.strictEqual(byNum('0030').cards[0].paid, 'NO PAYMENT TYPE'); // blank
  for (const p of ['Cash + Due', 'Zelle + Due', 'due']) {
    const row = ['', '1', '', p, '', '', '', '', '', ''];
    assert.strictEqual(status(row, new Set()).paid, p === 'due' ? 'NOT PAID' : 'PART PAID');
  }
  for (const p of ['Card', 'Cash', 'Zelle', 'Cash + Card']) {
    assert.strictEqual(status(['', '1', '', p], new Set()).paid, 'PAID');
  }
});

test('store (Account) receipts: ON ACCOUNT, no ready status', () => {
  const c = byNum('9528').cards[0];
  assert.strictEqual(c.paid, 'ON ACCOUNT');
  assert.strictEqual(c.ready, null);
});

test('phone formats: +1, raw digits', () => {
  assert.strictEqual(byNum('3333').count, 1);
  assert.strictEqual(byNum('4444').count, 1);
});

test('no match', () => {
  const r = byNum('9999');
  assert.strictEqual(r.count, 0);
  assert.match(speech('9999', r), /No receipts for 9 9 9 9/);
});

test('caps at MAX_CARDS with "+N more"', () => {
  const many = Array.from({ length: 13 }, (_, i) => ['10/10/26', String(30000 + i), '', 'Account', '', '', 'S', '', '(725) 555-9528', '']);
  const r = findReceipts('9528', many, []);
  assert.strictEqual(r.cards.length, MAX_CARDS);
  assert.strictEqual(r.more, 3);
  assert.strictEqual(r.cards[0].receipt, '30012');
});

test('speech', () => {
  assert.strictEqual(speech('4444', byNum('4444')), 'Receipt 26266, ready, paid.');
  assert.match(speech('4417', byNum('4417')), /^2 receipts for 4 4 1 7\. Receipt 26135, Sam Roe, picked up\./);
});
