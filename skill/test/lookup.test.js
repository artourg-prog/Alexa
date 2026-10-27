'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { normalizeLast4, findOrders, payment, smsLabel, prettyPhone, columnMap, speech, MAX_CARDS } = require('../lambda/lookup');
const { header, row, orders } = require('./fixtures');

const byNum = (last4) => findOrders(last4, orders);

test('last 4 normalisation', () => {
  assert.strictEqual(normalizeLast4('4417'), '4417');
  assert.strictEqual(normalizeLast4('30'), '0030');
  assert.strictEqual(normalizeLast4(''), null);
  assert.strictEqual(normalizeLast4(undefined), null);
  assert.strictEqual(normalizeLast4('12345'), null);
});

test('several matches: newest first, blank receipt skipped, fields shown', () => {
  const r = byNum('4417');
  assert.strictEqual(r.count, 2);
  assert.deepStrictEqual(r.cards, [
    { receipt: '33520', customer: 'Sam Roe', phone: '(702) 555-4417', promised: '10-14-26 5 PM', sms: 'TEXT SENT', paid: 'PART PAID' },
    { receipt: '33515', customer: 'Pat Lee', phone: '(702) 555-4417', promised: '10-09-26 1:30 PM', sms: 'NOT TEXTED', paid: 'PAID' },
  ]);
});

test('reported_workers never leaves the lookup, and no "ready" is claimed', () => {
  const all = ['4417', '2222', '9528', '3333', '0030'].flatMap((n) => byNum(n).cards);
  const text = JSON.stringify(all);
  assert.doesNotMatch(text, /SECRET WORKER/);
  assert.doesNotMatch(text, /READY/i);
  for (const c of all) assert.ok(!('workers' in c) && !('ready' in c));
});

test('payment: payment_status used as is; falls back to payment_type while blank', () => {
  assert.strictEqual(byNum('2222').cards[0].paid, 'NOT PAID');
  assert.strictEqual(byNum('9528').cards[0].paid, 'ON ACCOUNT');
  assert.strictEqual(byNum('3333').cards[0].paid, 'PART PAID'); // status blank, type Card + Due
  assert.strictEqual(payment('', 'Due'), 'NOT PAID');
  assert.strictEqual(payment('', 'Cash + Card'), 'PAID');
  assert.strictEqual(payment('', 'Account'), 'ON ACCOUNT');
  assert.strictEqual(payment('', ''), '');
  assert.strictEqual(payment('paid', 'Due'), 'PAID'); // status wins
  assert.strictEqual(payment('weird', 'Card'), 'PAID'); // unknown status -> fallback
});

test('sms labels', () => {
  assert.strictEqual(smsLabel('Not texted'), 'NOT TEXTED');
  assert.strictEqual(smsLabel('Accepted / sent via service'), 'TEXT SENT');
  assert.strictEqual(smsLabel('Not sent'), 'TEXT NOT SENT');
  assert.strictEqual(smsLabel('Queued'), 'QUEUED');
  assert.strictEqual(smsLabel(''), '');
});

test('phones: +1, blank', () => {
  assert.strictEqual(byNum('0030').cards[0].phone, '(702) 555-0030');
  assert.strictEqual(prettyPhone('', ''), '');
  assert.strictEqual(prettyPhone('12345', ''), '12345');
});

test('columns found by header name even if moved; missing key columns -> BAD_HEADER', () => {
  const moved = orders.map((r) => [...r].reverse());
  assert.deepStrictEqual(findOrders('4417', moved).cards.map((c) => c.receipt), ['33520', '33515']);
  assert.throws(() => columnMap(['foo', 'bar']), /BAD_HEADER/);
  assert.deepStrictEqual(findOrders('4417', []), { cards: [], more: 0, count: 0 });
});

test('caps at MAX_CARDS with "+N more"', () => {
  const many = [header, ...Array.from({ length: 13 }, (_, i) => row(String(30000 + i), 'S', '', '7255559528', '10-10-26', '', '', 'Account', 'ON ACCOUNT'))];
  const r = findOrders('9528', many);
  assert.strictEqual(r.cards.length, MAX_CARDS);
  assert.strictEqual(r.more, 3);
  assert.strictEqual(r.cards[0].receipt, '30012');
});

test('speech: count only, no list read out', () => {
  assert.strictEqual(speech('4417', byNum('4417')), '2 orders for 4 4 1 7.');
  assert.strictEqual(speech('2222', byNum('2222')), '1 order for 2 2 2 2.');
  assert.strictEqual(speech('9999', byNum('9999')), 'No orders for 9 9 9 9.');
  assert.doesNotMatch(speech('4417', byNum('4417')), /33520|Sam/);
});
