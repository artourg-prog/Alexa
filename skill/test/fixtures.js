'use strict';
// Shape copied from the real "Active Orders" tab (header + rows); names, phones and workers are made up.
const header = [
  'txn_id', 'receipt_number', 'customer_or_shop', 'phone', 'phone_normalized', 'received_date', 'promised_at',
  'reported_workers', 'sms_state', 'sms_accepted_at_known', 'sms_accepted_at', 'sms_source', 'readiness',
  'source_snapshot_at', 'payment_type', 'payment_status',
];
const row = (receipt, customer, phone, norm, received, promised, sms, type, status) => [
  `r-${receipt}`, receipt, customer, phone, norm, received, promised, 'SECRET WORKER', sms, 'false', '', '', 'unknown',
  '2026-10-27T14:53:56-07:00', type, status,
];
const orders = [
  header,
  row('33515', 'Pat Lee', '702 555 4417', '7025554417', '10-09-26', '10-09-26 1:30 PM', 'Not texted', 'Cash', 'PAID'),
  row('33520', 'Sam Roe', '702 555 4417', '7025554417', '10-12-26', '10-14-26 5 PM', 'Accepted / sent via service', 'Zelle + Due', 'PART PAID'),
  row('33498', 'Kim Day', '702 555 2222', '7025552222', '10-08-26', '10-11-26 5 PM', 'Not sent', 'Due', 'NOT PAID'),
  row('26148', 'Palazzo Store', '725 555 9528', '7255559528', '10-10-26', '5 PM', 'Not texted', 'Account', 'ON ACCOUNT'),
  row('33600', 'Old Sync', '702 555 3333', '7025553333', '10-15-26', '', 'Not texted', 'Card + Due', ''), // payment_status not live yet
  row('33601', 'No Phone', '', '', '10-15-26', '', 'Not texted', 'Card', 'PAID'),
  row('33602', 'Intl', '+1 702 555 0030', '17025550030', '10-16-26', '', 'Not texted', 'Card', 'PAID'),
  row('', 'Blank Receipt', '702 555 4417', '7025554417', '10-20-26', '', '', '', ''),
];
module.exports = { header, row, orders };
