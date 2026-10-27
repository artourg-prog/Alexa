'use strict';

// Builds the APL datasource for the Echo Show. Big text, colour per status.

const GREEN = '#2ECC71';
const RED = '#FF5C5C';
const AMBER = '#FFB84D';

const COLORS = {
  READY: GREEN,
  'PICKED UP': GREEN,
  PAID: GREEN,
  'NOT READY': RED,
  'NOT PAID': RED,
  'PART PAID': AMBER,
  'ON ACCOUNT': AMBER,
  'NO PAYMENT TYPE': AMBER,
};

// Fewer receipts -> bigger letters.
function sizes(n) {
  if (n <= 1) return { title: '40dp', receipt: '110dp', name: '48dp', badge: '72dp' };
  if (n <= 3) return { title: '36dp', receipt: '72dp', name: '36dp', badge: '52dp' };
  return { title: '32dp', receipt: '52dp', name: '30dp', badge: '40dp' };
}

function datasource(last4, result, message = '') {
  const { cards = [], more = 0, count = 0 } = result || {};
  const title = count > 1 ? `Phone …${last4} · ${count} receipts` : `Phone …${last4}`;
  return {
    data: {
      type: 'object',
      properties: {
        title,
        message: message || (count ? '' : 'No receipts'),
        footer: more ? `+${more} more` : '',
        size: sizes(cards.length),
        cards: cards.map((c) => ({
          receipt: c.receipt,
          customer: c.customer,
          ready: c.ready || '',
          paid: c.paid || '',
          readyColor: COLORS[c.ready] || '#FFFFFF',
          paidColor: COLORS[c.paid] || '#FFFFFF',
        })),
      },
    },
  };
}

module.exports = { datasource, sizes, COLORS };
