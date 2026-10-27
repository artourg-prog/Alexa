'use strict';
// Shapes copied from real "All Receipts" / "Pickup Texts" rows (A:J, A:C); names and phones are made up.
// A Date, B Receipt #, C Total, D Payment, E Account, F Desc, G Customer, H "D", I Phone, J "P"
const receipts = [
  ['10/09/26', '26126', '$30.00', 'Zelle', 'Walking In Customers', 'shirt shorten', 'Pat Lee', '✓', '(702) 555-4417', ''],
  ['10/10/26', '26135', '$190.00', 'Zelle + Due', 'Walking In Customers', 'jacket', 'Sam Roe', '✓', '(702) 555-4417', '✓'],
  ['10/12/26', '26192', '$75.00', 'Due', 'Walking In Customers', 'pants', 'Kim Day', '✓', '(702) 555-1111', '✓'],
  ['10/13/26', '26238', '$20.00', 'Due', 'Walking In Customers', 'hem', 'Lou Fox', '', '(702) 555-2222', ''],
  ['10/14/26', '33501', '$150.00', 'Card + Due', 'Walking In Customers', 'dress', 'Ana Ruiz', '', '+1 (702) 555-3333', ''],
  ['10/10/26', '26148', '$90.00', 'Account', '7 For All Mankind @ Palazzo', 'jeans', 'Store Guy', '', '(725) 555-9528', ''],
  ['10/15/26', '26266', '$60.00', 'Cash', 'Walking In Customers', 'jeans', 'Jo Park', '', '17025554444', ''],
  ['10/15/26', '26267', '$40.00', '', 'Walking In Customers', 'jeans', 'No Pay', '', '(702) 555-0030', ''],
  ['10/16/26', '', '', '', '', '', '', '', '(702) 555-4417', ''],
];
// Real tab has repeats per receipt (e.g. SENT then REQUESTED) and non-SENT states.
const pickups = [
  ['26266', 'n:26266:walk-in', 'SENT'],
  ['26126', 'n:26126:walk-in', 'NOT_YET'],
  ['26238', 'n:26238:walk-in', 'UNSURE'],
  ['33501', 'n:33501:walk-in', 'FAILED'],
  ['26266', 'n:26266:walk-in', 'REQUESTED'],
];
module.exports = { receipts, pickups };
