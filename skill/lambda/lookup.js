'use strict';

// Pure logic: sheet rows in, order cards out. No network, no writes.
//
// Source: sheet "Order Ready For Pickup", tab "Active Orders" (synced from the
// Pickups tablet). Columns are found by header name, so a moved column can't
// shift data into the wrong field. Only receipt, customer and phone are shown
// (the screen's job is finding the bag on the shelf); `reported_workers` is never read.
const FIELDS = {
  receipt: 'receipt_number',
  customer: 'customer_or_shop',
  phone: 'phone',
  phoneNormalized: 'phone_normalized',
  received: 'received_date', // sort only
};

function str(v) {
  return v == null ? '' : String(v).trim();
}

function digitsOnly(s) {
  return str(s).replace(/\D/g, '');
}

// Alexa's FOUR_DIGIT_NUMBER slot can drop a leading zero ("0030" -> "30").
function normalizeLast4(spoken) {
  const d = digitsOnly(spoken);
  if (!d || d.length > 4) return null;
  return d.padStart(4, '0');
}

// Header row -> { field: columnIndex } for the fields we use only.
function columnMap(header) {
  const idx = {};
  const names = (header || []).map((h) => str(h).toLowerCase());
  for (const [field, name] of Object.entries(FIELDS)) {
    const i = names.indexOf(name);
    if (i >= 0) idx[field] = i;
  }
  if (idx.receipt == null || (idx.phoneNormalized == null && idx.phone == null)) {
    throw new Error('BAD_HEADER');
  }
  return idx;
}

function prettyPhone(raw, normalized) {
  const d = digitsOnly(normalized || raw);
  const ten = d.length === 11 && d[0] === '1' ? d.slice(1) : d;
  if (ten.length === 10) return `(${ten.slice(0, 3)}) ${ten.slice(3, 6)}-${ten.slice(6)}`;
  return str(raw);
}

// "10-09-26" or "10/09/26" (MM DD YY) -> sortable number; unknown sorts last.
function dateKey(s) {
  const m = /^(\d{1,2})[-/](\d{1,2})[-/](\d{2,4})/.exec(str(s));
  if (!m) return 0;
  const y = m[3].length === 2 ? 2000 + Number(m[3]) : Number(m[3]);
  return y * 10000 + Number(m[1]) * 100 + Number(m[2]);
}

// rows[0] is the header row.
function findOrders(last4, rows) {
  const [header, ...data] = rows || [];
  if (!header) return { cards: [], count: 0 };
  const c = columnMap(header);
  const get = (r, f) => (c[f] == null ? '' : str(r[c[f]]));

  const matches = data.filter((r) => {
    if (!get(r, 'receipt')) return false;
    const d = digitsOnly(get(r, 'phoneNormalized') || get(r, 'phone'));
    return d.length >= 4 && d.slice(-4) === last4;
  });
  matches.sort(
    (a, b) =>
      dateKey(get(b, 'received')) - dateKey(get(a, 'received')) ||
      Number(digitsOnly(get(b, 'receipt'))) - Number(digitsOnly(get(a, 'receipt'))),
  );

  // All matches: the screen scrolls (Art wants every order, no cut-off).
  const cards = matches.map((r) => ({
    receipt: get(r, 'receipt'),
    customer: get(r, 'customer'),
    phone: prettyPhone(get(r, 'phone'), get(r, 'phoneNormalized')),
  }));
  return { cards, count: matches.length };
}

// Several last-4 numbers in one request (several clients at once).
// Duplicates are dropped; results keep the spoken order, newest first within each number.
function findMany(nums, rows) {
  const unique = [...new Set(nums.filter(Boolean))];
  const groups = unique.map((last4) => ({ last4, ...findOrders(last4, rows) }));
  return {
    groups: groups.map((g) => ({ last4: g.last4, count: g.count })),
    cards: groups.flatMap((g) => g.cards.map((c) => ({ ...c, last4: g.last4 }))),
    count: groups.reduce((n, g) => n + g.count, 0),
  };
}

function spokenDigits(s) {
  return String(s).split('').join(' ');
}

function orders(n) {
  return n === 1 ? '1 order' : `${n} orders`;
}

// Screen carries the details; voice only says how many, per number.
function speech(result, hasScreen = true) {
  const parts = result.groups.map((g) =>
    g.count ? `${orders(g.count)} for ${spokenDigits(g.last4)}` : `no orders for ${spokenDigits(g.last4)}`,
  );
  const said = parts.join(', ') + '.';
  const text = said[0].toUpperCase() + said.slice(1);
  return hasScreen || !result.count ? text : `${text} Look on the Echo Show for details.`;
}

module.exports = { FIELDS, normalizeLast4, columnMap, prettyPhone, findOrders, findMany, speech };
