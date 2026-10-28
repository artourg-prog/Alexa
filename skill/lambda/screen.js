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

// The receipt number is the main thing: as large as fits for up to 3 orders,
// everything else small underneath. Longer lists (rare) shrink and scroll.
function sizes(n) {
  if (n <= 1) return { title: '32dp', receipt: '160dp', name: '32dp', detail: '24dp', badge: '36dp', pad: '2vh' };
  if (n === 2) return { title: '30dp', receipt: '120dp', name: '28dp', detail: '22dp', badge: '32dp', pad: '1.5vh' };
  if (n === 3) return { title: '28dp', receipt: '88dp', name: '26dp', detail: '20dp', badge: '28dp', pad: '1vh' };
  if (n <= 6) return { title: '28dp', receipt: '48dp', name: '24dp', detail: '20dp', badge: '26dp', pad: '0.5vh' };
  return { title: '26dp', receipt: '36dp', name: '22dp', detail: '18dp', badge: '22dp', pad: '0.5vh' };
}

// nums: the last-4 numbers asked for, in spoken order.
function datasource(nums, result, message = '') {
  const { cards = [], count = 0 } = result || {};
  const several = nums.length > 1;
  const phones = nums.map((n) => `…${n}`).join(', ');
  const title = `Phone ${phones}` + (count > 1 ? ` · ${count} orders` : '') + (count > 6 ? ' · swipe up for more' : '');
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
        // which number found nothing, when several were asked
        missing: several && result ? result.groups.filter((g) => !g.count).map((g) => `No orders for …${g.last4}`).join('   ') : '',
      },
    },
  };
}

module.exports = { datasource, sizes, COLORS };
