// Fake bridge for the Touch page in a desktop browser (no phone, no Reader, no SMS/pickup/report writes).
// Use: serve a folder with counter.html (load this file before receipt-icons.js; CSP allows same-origin scripts),
// receipt-icons.js, counter.js and this file; then in the console:
//   await run('due' | 'partial' | 'paid' | 'unknown' | 'multi')      → card colours, sizes, spoken text + voice
//   flow.status = 'recorded' | 'rejected' | 'pending'; silent.add('save' | 'status'); await report('Worker A'); await back()
// Freeze regressions (owner 11-02): already recorded receipt again (same / other worker), a status or save that never
// answers, late answers after Back, an error then Back, system Back via window.counterBack().
window.spoken = [];
window.mode = 'due';
const SETS = {
  due: [{ number: '26601', name: 'Test Customer', total: '$650.00', due: '$650.00', work: 'Jacket sleeves', ready: true, pay: 'DUE', unverified: false, txn: '' }],
  partial: [{ number: '26602', name: 'Test Customer', total: '$90.00', due: '', work: 'Hem', ready: true, pay: 'DUE', unverified: false, txn: '' }],
  paid: [{ number: '26603', name: 'Test Customer', total: '$40.00', due: '', work: 'Pants hem', ready: true, pay: 'PAID', unverified: false, txn: '' }],
  unknown: [{ number: '26604', name: '', total: '$40.00', due: '', work: 'Zip', ready: false, pay: 'UNKNOWN', unverified: false, txn: '' }],
  multi: [{ number: '26601', name: 'Test Customer', total: '$650.00', due: '$650.00', work: 'Jacket', ready: true, pay: 'DUE', txn: '' },
          { number: '26603', name: 'Another Customer With A Long Name', total: '$40.00', due: '', work: 'Hem', ready: true, pay: 'PAID', txn: '' }],
};
window.voiceProblem = ''; window.flow = { save: 'ok', status: 'recorded', statusDelay: 20 }; window.silent = new Set();
window.Sofia = { request(id, action, json) {
  const b = JSON.parse(json);
  if (action === 'speak') window.spoken.push(b);
  if (window.silent.has(action)) return;                                   // a bridge call that never answers
  let r, delay = 20;
  if (action === 'find') r = { ok: true, receipts: SETS[window.mode] };
  else if (action === 'report') r = { ok: true, receipts: [Object.assign({}, SETS.paid[0], { number: '26431' })] };
  else if (action === 'load') r = { ok: true, workers: ['Worker A', 'Worker B'], finishConfigured: true, pickupConfigured: true };
  else if (action === 'speak') r = { ok: true, problem: window.voiceProblem };
  else if (action === 'save') r = window.flow.save === 'error' ? { ok: false, error: 'Not saved on this phone. Please retry.' } : { ok: true, id: 's-' + b.worker, number: b.number, worker: b.worker, scan: 'ADDED', smsRequested: !!b.sms, sms: '' };
  else if (action === 'status') { delay = window.flow.statusDelay; const st = window.flow.status;
    r = st === 'recorded' ? { ok: true, id: b.id, number: '26431', worker: 'Worker A', scan: 'ALREADY', recorded: true, workday: '2026-11-02', smsRequested: false }
      : st === 'rejected' ? { ok: true, id: b.id, number: '26431', worker: 'Worker B', scan: 'REJECTED', scanText: 'Not saved: already claimed', recorded: false }
      : { ok: true, id: b.id, number: '26431', worker: 'Worker B', scan: 'ACCEPTED', recorded: false, reportReason: '' }; }
  else r = { ok: true };
  setTimeout(() => window.counterReply(id, JSON.stringify(r)), delay); } };
window.w = ms => new Promise(r => setTimeout(r, ms));
window.state12 = () => { const q = s => document.querySelector(s); return { step: q('.sf-phone').className.replace('sf-phone ', ''), back: !q('.sf-back').hidden, title: q('.sf-busy-title')?.textContent || q('.sf-report-recorded h2')?.textContent || '', err: (q('.sf-error') || q('.sf-native-error'))?.textContent || '' }; };
window.report = async (worker, sms) => { document.querySelector('.sf-home').click(); await w(30); document.querySelector('[data-go=report]').click(); await w(30);
  for (const k of '26431') document.querySelector(`[data-key="${k}"]`).click(); document.querySelector('.sf-find').click(); await w(120);
  [...document.querySelectorAll('.sf-workers button')].find(x => x.textContent === worker).click(); await w(30);
  document.querySelector(sms ? '[data-finish=sms]' : '[data-finish=save]').click(); await w(150); };
window.back = async () => { document.querySelector('.sf-back').click(); await w(60); return state12().step; };
window.run = async (mode, vw) => { const w=ms=>new Promise(r=>setTimeout(r,ms)); window.mode = mode; window.spoken = [];
  document.querySelector('.sf-home').click(); await w(50); document.querySelector('[data-go=lookup]').click(); await w(50);
  for (const k of '0101') document.querySelector(`[data-key="${k}"]`).click(); document.querySelector('.sf-find').click(); await w(200);
  if (mode === 'multi') { document.querySelector('.sf-match').click(); await w(150); }
  const q=s=>document.querySelector(s), cs=s=>q(s)?getComputedStyle(q(s)):null, b=q('.sf-body');
  const r=e=>e?Math.round(e.getBoundingClientRect().width)+'x'+Math.round(e.getBoundingClientRect().height):null;
  return JSON.stringify({ step: q('.sf-phone').className, spoken: window.spoken, note: q('.sf-voice-note').hidden ? '' : q('.sf-voice-note').textContent,
    border: cs('.sf-receipt')?.borderTopWidth + ' ' + cs('.sf-receipt')?.borderTopColor, work: q('.sf-work')?.textContent, workShown: cs('.sf-work')?.display,
    customer: cs('.sf-customer')?.fontSize, total: cs('.sf-total')?.fontSize, back: r(q('.sf-back')), arrow: r(q('.sf-not-picked-up .sf-arrow')),
    overflow: b.scrollHeight - b.clientHeight, doc: document.documentElement.scrollHeight - innerHeight }); };
