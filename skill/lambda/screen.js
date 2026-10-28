'use strict';

// Builds the APL datasource for the Echo Show. Big text, colour per status.
// There is no "ready" field in the source, so the screen never claims ready.

const GREEN = '#2ECC71';
const RED = '#FF5C5C';
const AMBER = '#FFB84D';
const GREY = '#BBBBBB';

const COLORS = {
  PAID: GREEN,
  'NOT PAID': RED,
  'PART PAID': AMBER,
  'ON ACCOUNT': AMBER,
  'TEXT SENT': GREEN,
  'NOT TEXTED': GREY,
  'TEXT NOT SENT': RED,
};

// Fewer orders -> bigger letters; many -> compact rows, swipe to scroll.
function sizes(n) {
  if (n <= 1) return { title: '40dp', receipt: '96dp', name: '48dp', detail: '36dp', badge: '64dp', pad: '2vh' };
  if (n <= 3) return { title: '36dp', receipt: '64dp', name: '34dp', detail: '28dp', badge: '44dp', pad: '1.5vh' };
  if (n <= 6) return { title: '32dp', receipt: '44dp', name: '28dp', detail: '22dp', badge: '32dp', pad: '1vh' };
  return { title: '28dp', receipt: '34dp', name: '24dp', detail: '20dp', badge: '26dp', pad: '0.5vh' };
}

function datasource(last4, result, message = '') {
  const { cards = [], count = 0 } = result || {};
  const title = count > 1 ? `Phone …${last4} · ${count} orders${count > 6 ? ' · swipe up for more' : ''}` : `Phone …${last4}`;
  return {
    data: {
      type: 'object',
      properties: {
        title,
        message: message || (count ? '' : 'No orders'),
        size: sizes(cards.length),
        cards: cards.map((c) => ({
          receipt: c.receipt,
          customer: c.customer,
          detail: [c.phone, c.promised && `Promised ${c.promised}`].filter(Boolean).join('  ·  '),
          paid: c.paid || '',
          sms: c.sms || '',
          paidColor: COLORS[c.paid] || '#FFFFFF',
          smsColor: COLORS[c.sms] || GREY,
        })),
      },
    },
  };
}

module.exports = { datasource, sizes, COLORS };
