'use strict';

// Pure logic: sheet rows in, receipt cards out. No network, no writes.
//
// "All Receipts" is read as A:J, so the row index is:
//   0 A Date, 1 B Receipt #, 2 C Total, 3 D Payment Type, 4 E Account,
//   5 F Description, 6 G Customer, 7 H "D" (claim check — NOT ready),
//   8 I Phone, 9 J "P" (picked up)
const COL = { date: 0, receipt: 1, total: 2, payment: 3, customer: 6, phone: 8, pickedUp: 9 };

// "Pickup Texts" is read as A:C: 0 Receipt #, 1 Receipt ID, 2 State.
const PT = { receipt: 0, state: 2 };

const MAX_CARDS = 10;

function cell(row, i) {
  return row && row[i] != null ? String(row[i]).trim() : '';
}

function digitsOnly(s) {
  return String(s || '').replace(/\D/g, '');
}

// Alexa's FOUR_DIGIT_NUMBER slot can drop a leading zero ("0030" -> "30").
function normalizeLast4(spoken) {
  const d = digitsOnly(spoken);
  if (!d || d.length > 4) return null;
  return d.padStart(4, '0');
}

// Receipt numbers whose pickup text actually went out.
function readySet(pickupRows) {
  const set = new Set();
  for (const r of pickupRows || []) {
    if (cell(r, PT.state).toUpperCase() === 'SENT') set.add(cell(r, PT.receipt));
  }
  return set;
}

// Approved rules (issue #1):
//   J ✓                    -> PICKED UP (covers "Due" + picked up too)
//   Payment Type "Account" -> ON ACCOUNT, no ready status
//   otherwise ready  = Pickup Texts State SENT, else NOT READY
//             paid   = Due alone -> NOT PAID, Due + other -> PART PAID,
//                      blank -> NO PAYMENT TYPE, anything else -> PAID
function status(row, ready) {
  const payment = cell(row, COL.payment);
  const p = payment.toLowerCase();

  if (cell(row, COL.pickedUp)) return { ready: 'PICKED UP', paid: null };
  if (p === 'account') return { ready: null, paid: 'ON ACCOUNT' };

  let paid;
  if (!p) paid = 'NO PAYMENT TYPE';
  else if (p.includes('due')) paid = p.replace(/\bdue\b/, '').replace(/[+\s]/g, '') ? 'PART PAID' : 'NOT PAID';
  else paid = 'PAID';

  return { ready: ready.has(cell(row, COL.receipt)) ? 'READY' : 'NOT READY', paid };
}

// MM/DD/YY -> sortable number; unknown dates sort last.
function dateKey(s) {
  const m = /^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/.exec(cell([s], 0));
  if (!m) return 0;
  const y = m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3]);
  return y * 10000 + Number(m[1]) * 100 + Number(m[2]);
}

function findReceipts(last4, receiptRows, pickupRows) {
  const ready = readySet(pickupRows);
  const matches = (receiptRows || []).filter(
    (r) => cell(r, COL.receipt) && digitsOnly(cell(r, COL.phone)).slice(-4) === last4,
  );
  matches.sort(
    (a, b) =>
      dateKey(cell(b, COL.date)) - dateKey(cell(a, COL.date)) ||
      Number(digitsOnly(cell(b, COL.receipt))) - Number(digitsOnly(cell(a, COL.receipt))),
  );
  const cards = matches.slice(0, MAX_CARDS).map((r) => ({
    receipt: cell(r, COL.receipt),
    customer: cell(r, COL.customer),
    date: cell(r, COL.date),
    total: cell(r, COL.total),
    ...status(r, ready),
  }));
  return { cards, more: Math.max(0, matches.length - MAX_CARDS), count: matches.length };
}

function spokenDigits(s) {
  return String(s).split('').join(' ');
}

function speech(last4, result) {
  const { cards, count } = result;
  if (!count) return `No receipts for ${spokenDigits(last4)}.`;
  const say = (c) =>
    `Receipt ${c.receipt}${c.customer && count > 1 ? `, ${c.customer}` : ''}, ` +
    [c.ready, c.paid].filter(Boolean).join(', ').toLowerCase() + '.';
  if (count === 1) return say(cards[0]);
  return `${count} receipts for ${spokenDigits(last4)}. ` + cards.slice(0, 3).map(say).join(' ') +
    (count > 3 ? ' The rest are on the screen.' : '');
}

module.exports = { COL, PT, MAX_CARDS, normalizeLast4, readySet, status, findReceipts, speech };
