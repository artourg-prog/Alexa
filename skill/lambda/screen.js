'use strict';

// Builds the APL datasource for the Echo Show. Purpose: find the bag on the shelf.
// Shows only the receipt number (as large as fits), customer name and full phone.

// Receipt number as large as fits for up to 3 orders; longer lists (rare) shrink and scroll.
function sizes(n) {
  if (n <= 1) return { title: '32dp', receipt: '180dp', name: '40dp', detail: '32dp', pad: '2vh' };
  if (n === 2) return { title: '30dp', receipt: '130dp', name: '32dp', detail: '28dp', pad: '1.5vh' };
  if (n === 3) return { title: '28dp', receipt: '96dp', name: '28dp', detail: '24dp', pad: '1vh' };
  if (n <= 6) return { title: '28dp', receipt: '52dp', name: '24dp', detail: '20dp', pad: '0.5vh' };
  return { title: '26dp', receipt: '40dp', name: '22dp', detail: '18dp', pad: '0.5vh' };
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
        cards: cards.map((c) => ({ receipt: c.receipt, customer: c.customer, phone: c.phone })),
        // which number found nothing, when several were asked
        missing: several && result ? result.groups.filter((g) => !g.count).map((g) => `No orders for …${g.last4}`).join('   ') : '',
      },
    },
  };
}

module.exports = { datasource, sizes };
