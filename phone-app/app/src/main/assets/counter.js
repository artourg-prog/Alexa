(function () {
  'use strict';
  const root = document.getElementById('sofia-touch-review');
  const body = root.querySelector('.sf-body'), phone = root.querySelector('.sf-phone');

  // Use touch release for the action buttons, with the same small-movement tolerance as
  // the keypad. Keep native clicks for keyboard, accessibility and mouse input.
  // A finger that stops a moving list, or that rests on a button, is not a tap: neither would have been a click before.
  const HOLD_MS = 700, SETTLE_MS = 150;
  let touchTap = null, suppressTouchClickUntil = 0, scrolledAt = -Infinity;
  root.addEventListener('scroll', () => { scrolledAt = performance.now(); }, true);
  root.addEventListener('pointerdown', event => {
    if (event.pointerType !== 'touch' || !event.isPrimary) return;
    const button = event.target.closest('button');
    if (!button || button.disabled || button.hasAttribute('data-key') || typeof button.onclick !== 'function') return;
    if (performance.now() - scrolledAt < SETTLE_MS) return;
    touchTap = { button, id: event.pointerId, x: event.clientX, y: event.clientY, at: performance.now() };
    button.classList.add('sf-touch-pressed');
  });
  function endTouch(event) {
    if (!touchTap || touchTap.id !== event.pointerId) return;
    const { button, x, y, at } = touchTap;
    touchTap = null;
    button.classList.remove('sf-touch-pressed');
    if (event.type === 'pointercancel') return;
    suppressTouchClickUntil = performance.now() + 500;
    const rect = button.getBoundingClientRect();
    if (performance.now() - at <= HOLD_MS && button.isConnected && !button.disabled && Math.hypot(event.clientX - x, event.clientY - y) <= 30 &&
        event.clientX >= rect.left && event.clientX <= rect.right &&
        event.clientY >= rect.top && event.clientY <= rect.bottom) {
      button.click(); suppressTouchClickUntil = performance.now() + 500; // counted from after the new screen is drawn
    }
  }
  window.addEventListener('pointerup', endTouch);
  window.addEventListener('pointercancel', endTouch);
  root.addEventListener('click', event => {
    if (event.isTrusted && event.detail > 0 && event.pointerType !== 'mouse' &&
        performance.now() < suppressTouchClickUntil) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);
  const pending = new Map(); let serial = 0, polling = null, returnTimer = null, revision = 0;
  // Spoken digit by digit, so a recorded 26-490 never has its dash read out.
  const spell = number => String(number || '').replace(/[^0-9]/g, '').split('').join(' ');
  const state = { step: 'home', flow: 'lookup', digits: '', selected: null, worker: '', workers: [], sound: true, spoken: '', matches: [], event: null, history: [], checkStartedAt: 0 };
  window.counterReply = function (id, json) {
    const request = pending.get(id); if (!request) return; pending.delete(id);
    try { const result = JSON.parse(json); result.ok ? request.resolve(result) : request.reject(new Error(result.error || 'Please try again.')); }
    catch (error) { request.reject(error); }
  };
  // Every request ends: an answer, an error, or a timeout (owner 11-02 freeze: a request that never answered left the
  // page waiting with no way out). A late answer after the timeout is ignored; nothing is re-sent from here.
  const WAIT_MS = { save: 45000, pickup: 45000, find: 40000, report: 40000, reopen: 40000 };
  const LATE = { save: 'Save confirmation has not arrived. The request may still complete — check Pending work in Settings before entering it again.',
    pickup: 'Pickup confirmation has not arrived. The request may still complete — check Pending work in Settings before tapping again.' };
  function call(action, data = {}) {
    return new Promise((resolve, reject) => {
      const id = String(++serial);
      const timer = setTimeout(() => { if (pending.delete(id)) reject(new Error(LATE[action] || 'Sofia did not answer. Please try again.')); }, WAIT_MS[action] || 20000);
      pending.set(id, { resolve: value => { clearTimeout(timer); resolve(value); }, reject: error => { clearTimeout(timer); reject(error); } });
      if (!window.Sofia) { pending.delete(id); clearTimeout(timer); reject(new Error('Sofia connection unavailable.')); return; }
      try { window.Sofia.request(id, action, JSON.stringify(data)); } catch (error) { pending.delete(id); clearTimeout(timer); reject(error); }
    });
  }
  function clone(id) { return root.querySelector('#' + id).content.cloneNode(true); }
  function today(at) { return new Intl.DateTimeFormat('en-US', { timeZone: 'America/Los_Angeles', month: 'short', day: 'numeric', year: 'numeric' }).format(at ? new Date(at) : new Date()); }
  // voice: 'sofia' (the usual voice) or 'male-en' (a DUE order's whole announcement, owner 11-02). A problem the phone
  // reports (no male voice, engine error, media volume off) is shown under the spoken words, never hidden.
  function say(text, voice = 'sofia') {
    state.spoken = text; state.spokenVoice = voice; root.querySelector('.sf-spoken').textContent = text; root.querySelector('.sf-speech').hidden = false;
    root.querySelector('.sf-listen').disabled = !text;
    voiceNote('');
    if (state.sound) call('speak', { text, voice }).then(result => voiceNote(result.problem || '')).catch(error => voiceNote(error.message));
  }
  function voiceNote(text) { const note = root.querySelector('.sf-voice-note'); note.textContent = text; note.hidden = !text; }
  window.counterVoice = voiceNote;
  function currentAnnouncement() {
    if (state.step === 'home') return { voice: 'sofia', text: 'Здравствуйте. Выберите дневной отчёт, чтобы записать работу, или поиск, чтобы найти заказ.' };
    if (state.step === 'keypad') return { voice: 'sofia', text: (state.flow === 'report' ? 'Введите номер квитанции.' : 'Введите последние четыре цифры телефона.')
      + (state.digits ? ' Введено: ' + state.digits.split('').join(' ') + '.' : '') };
    if (state.step === 'matches') return { voice: 'sofia', text: 'Найдено квитанций: ' + state.matches.length + '. Выберите нужную квитанцию.' };
    if (state.step === 'report-result' && state.event) return { voice: 'sofia', text: (state.event.recorded === true ? 'Записано.' : 'Сохранение не подтверждено.')
      + ' Квитанция ' + spell(state.event.number) + '. Работник ' + (state.event.worker || state.worker) + '.' };
    if (state.step === 'busy' && !state.selected) return { voice: 'sofia', text: (state.flow === 'report' ? 'Ищу квитанцию.' : 'Ищу заказ по последним четырём цифрам телефона.')
      + (state.digits ? ' Номер: ' + state.digits.split('').join(' ') + '.' : '') + ' Пожалуйста, подождите.' };
    if (state.flow === 'report' && state.selected && !['home', 'keypad', 'matches', 'report-result'].includes(state.step)) {
      const item = state.selected, digits = spell(item.number), worker = state.worker || (state.event && state.event.worker) || '';
      if (item.pay === 'DUE') return { voice: 'male-en', text: 'Receipt ' + digits + '. '
        + (worker ? 'Worker: ' + worker + '. ' : 'Choose a worker. ')
        + (item.due ? 'Outstanding balance due: ' + item.due + '.' : 'Outstanding balance is due. The amount due is not shown; check the receipt.') };
      return { voice: 'sofia', text: 'Квитанция ' + digits + '. ' + (worker ? 'Работник ' + worker + '.' : 'Выберите работника.')
        + (state.event && state.event.receiptNotFound === true ? ' Квитанция не найдена.' : state.step === 'busy' ? ' Ожидаю подтверждения.' : '') };
    }
    return state.spoken ? { text: state.spoken, voice: state.spokenVoice || 'sofia' }
      : { text: 'Выберите действие на экране.', voice: 'sofia' };
  }
  function listen() {
    const announcement = currentAnnouncement(); if (!announcement) return;
    voiceNote('');
    return call('speak', announcement)
      .then(result => voiceNote(result.problem || '')).catch(error => voiceNote(error.message));
  }
  function clearTimers() { clearInterval(polling); polling = null; clearTimeout(returnTimer); returnTimer = null; }
  function home() {
    ++revision; clearTimers(); state.step = 'home'; state.digits = ''; state.selected = null; state.worker = ''; state.event = null; state.history = [];
    state.spoken = ''; root.querySelector('.sf-listen').disabled = true; voiceNote('');
    root.querySelector('.sf-speech').hidden = true; render();
  }
  // Returning from the waiting screen (remember=false) drops the step it saved, so Back then really goes back (0.56 kept
  // it: after an error the first Back stayed on the same screen and looked dead, owner 11-02).
  function change(step, remember = true) {
    if (remember) { if (state.step !== 'busy') state.history.push(state.step); } // the waiting screen is never a Back target
    else if (state.history[state.history.length - 1] === step) state.history.pop();
    state.step = step; render();
  }
  function fail(error) {
    // The message must be seen: on the Fold the bottom of the page is off screen, so a miss looked like nothing
    // happened (11-01). Prefer the slot under the digits, bring it into view, and Sofia says it.
    let text = body.querySelector('.sf-error') || body.querySelector('.sf-native-error');
    if (!text) { text = document.createElement('p'); text.className = 'sf-native-error'; text.setAttribute('role', 'alert'); body.prepend(text); }
    text.textContent = error.message || 'Please try again.';
    try { text.scrollIntoView({ block: 'center' }); } catch (_) {}
    say(text.textContent);
  }
  function icons() {
    const glyph = { house: '⌂', 'volume-2': '♫', search: '⌕', 'notebook-pen': '▤', 'arrow-left': '←', 'arrow-right': '→', delete: '⌫', 'phone-outgoing': '✉', files: '▤', 'user-round': '♙', 'shopping-bag': '✓', 'circle-check': '✓', check: '✓', send: '➤' };
    root.querySelectorAll('i[data-lucide]').forEach(icon => {
      const name = { 'phone-outgoing': 'phone', files: 'files', 'notebook-pen': 'files', 'circle-check': 'check', check: 'check', 'shopping-bag': 'check', send: 'send', search: 'search' }[icon.dataset.lucide]; if (name) { icon.replaceWith(window.receiptIcon(name)); return; } const span = document.createElement('span'); span.textContent = glyph[icon.dataset.lucide] || '✓'; span.setAttribute('aria-hidden', 'true'); span.className = 'sf-icon'; icon.replaceWith(span);
    });
  }
  // Receipt digits: in landscape they grow to fill the card's width (Art 11-01), in portrait they only shrink to fit;
  // either way they shrink further until the screen needs no scrolling.
  function fitNumbers() {
    const wide = matchMedia('(orientation: landscape)').matches;
    root.querySelectorAll('.sf-receipt-number').forEach(number => {
      number.style.fontSize = ''; const range = document.createRange(); range.selectNodeContents(number);
      const fits = size => { number.style.fontSize = size + 'px'; return range.getBoundingClientRect().width <= number.clientWidth - 4 && body.scrollHeight <= body.clientHeight; };
      // As large as practical in both orientations (owner 11-02): grow to the card's width, then shrink until nothing scrolls.
      let low = 28, high = wide ? 400 : 260;
      if (fits(high)) return;
      while (high - low > 1) { const mid = Math.floor((low + high) / 2); if (fits(mid)) low = mid; else high = mid; }
      number.style.fontSize = low + 'px';
    });
  }
  // A store order says which store, so an order of one location is never taken for another's or for a walk-in's.
  function customerLine(item) { return [item.name, item.store].filter(Boolean).join(' · '); }
  function receipt() {
    const fragment = clone('sf-receipt-details'), item = state.selected;
    fragment.querySelector('.sf-receipt-number').textContent = item.number;
    fragment.querySelector('.sf-customer').textContent = customerLine(item);
    fragment.querySelector('.sf-total').textContent = item.total || '—';
    // The alterations description is not shown on the Find card (owner 11-02); the data is unchanged.
    fragment.querySelector('.sf-work').textContent = state.step === 'order' ? '' : item.work || '';
    fragment.querySelector('.sf-receipt').classList.toggle('sf-unpaid', item.pay === 'DUE');
    const ready = fragment.querySelector('.sf-ready');
    const paid = { PAID: 'Paid', DUE: 'Not paid', ACCOUNT: 'Store account', UNKNOWN: 'Payment unavailable' }[item.pay] || 'Payment unavailable';
    // From the Order Index / Picked Up Orders: its own state line (not marked finished + promised time / picked up + time / unknown), no payment guess.
    ready.textContent = item.pickupNote ? item.pickupNote : item.state ? item.stateText + (item.stale ? ' · index may be out of date' : '')
      : item.unverified ? 'Receipt entered · checked when saved' : paid + ' · ' + (item.ready ? 'Ready for pickup' : 'In progress');
    ready.classList.toggle('not-paid', item.pay === 'DUE'); ready.classList.toggle('in-progress', !item.ready);
    fragment.querySelector('.sf-today').textContent = today(); return fragment;
  }
  async function find() {
    const rev = ++revision; const number = state.digits;
    state.spoken = ''; root.querySelector('.sf-listen').disabled = true; voiceNote('');
    change('busy');
    try {
      const result = await call(state.flow === 'report' ? 'report' : 'find', { number });
      if (rev !== revision) return;
      state.matches = result.receipts;
      if (!state.matches.length) { change('keypad', false); fail(new Error('No matching receipt. Check the number.')); return; }
      if (state.matches.length === 1) select(state.matches[0]); else change('matches');
    } catch (error) { if (rev === revision) { change('keypad', false); fail(error); } }
  }
  function select(item) {
    state.selected = item; change(state.flow === 'report' ? 'workers' : 'order');
    const payment = item.pay === 'PAID' ? 'Оплачено.' : item.pay === 'ACCOUNT' ? 'Счёт магазина.' : item.pay === 'DUE' ? '' : 'Оплата не известна.';
    const spokenState = { NOT_READY: 'Ещё не отмечена как готовая.', COLLECTED: 'Заказ уже выдан.', UNKNOWN: 'Статус неизвестен, проверьте на планшете.', READY: 'Готова к выдаче.' }[item.state];
    const digits = spell(item.number);
    if (item.state) say('Квитанция ' + digits + '. ' + spokenState);
    else if (item.unverified) return;
    // A reopened row that may not be picked up here is never announced as ready; a balance due is still said.
    else if (item.blocked) say((item.pay === 'DUE' ? 'Receipt ' + digits + '. ' + (item.due ? 'Outstanding balance due: ' + item.due + '. ' : 'Outstanding balance is due. ') + 'Check the tablet before pickup.' : 'Квитанция ' + digits + '. Выдача здесь недоступна, проверьте на планшете.'), item.pay === 'DUE' ? 'male-en' : undefined);
    // A DUE order: the whole announcement in English, male voice, and it says the outstanding amount (only when the
    // mirror shows it exactly; a part-paid "Zelle + Due" balance has no amount there, and nothing is guessed).
    else if (item.pay === 'DUE') say('Receipt ' + digits + '. ' + (item.due ? 'Outstanding balance due: ' + item.due + '.' : 'Outstanding balance is due. The amount due is not shown; check the receipt.')
      + ' ' + (item.ready ? 'Ready for pickup.' : 'The work is not finished yet.'), 'male-en');
    else say('Квитанция ' + digits + '. ' + payment + ' ' + (item.ready ? 'Готова к выдаче.' : 'Работа ещё не закончена.'));
  }
  function bindKeys() {
    const input = body.querySelector('.sf-digits'), enter = body.querySelector('.sf-find');
    const phoneMode = state.flow === 'lookup'; input.maxLength = phoneMode ? 4 : 9; input.value = state.digits;
    enter.firstChild.textContent = phoneMode ? 'Find' : 'Enter';
    input.placeholder = phoneMode ? '····' : '········'; input.setAttribute('aria-label', phoneMode ? 'Last four phone digits' : 'Receipt number');
    const sync = () => { state.digits = input.value.replace(/[^0-9]/g, '').slice(0, phoneMode ? 4 : 9); input.value = state.digits; enter.disabled = phoneMode ? state.digits.length !== 4 : state.digits.length < 3; };
    input.oninput = sync; input.onkeydown = event => { if (event.key === 'Enter' && !enter.disabled) find(); };
    const press = key => { input.value = key === 'clear' ? '' : key === 'delete' ? input.value.slice(0, -1) : input.value + key; sync(); };
    // A light tap counts once, on release inside the same key (owner 11-02: keys needed a deliberate press). Android
    // drops the click when a finger moves a few pixels; here a small wobble still counts, a drag off the key or a
    // cancelled touch never types, and the click that follows a counted tap is ignored (no double digit).
    body.querySelectorAll('[data-key]').forEach(button => {
      let down = null;
      button.onpointerdown = event => { down = { id: event.pointerId, x: event.clientX, y: event.clientY }; button.classList.add('is-pressed'); try { button.setPointerCapture(event.pointerId); } catch (_) {} };
      button.onpointerup = event => {
        const start = down; down = null; button.classList.remove('is-pressed');
        if (!start || start.id !== event.pointerId) return;
        const r = button.getBoundingClientRect(), inside = event.clientX >= r.left && event.clientX <= r.right && event.clientY >= r.top && event.clientY <= r.bottom;
        if (inside && Math.hypot(event.clientX - start.x, event.clientY - start.y) <= 30) { button.dataset.tapped = '1'; setTimeout(() => delete button.dataset.tapped, 500); press(button.dataset.key); }
      };
      button.onpointercancel = button.onlostpointercapture = () => { down = null; button.classList.remove('is-pressed'); };
      button.onclick = event => { if (button.dataset.tapped) { delete button.dataset.tapped; event.preventDefault(); return; } press(button.dataset.key); };
    });
    enter.onclick = find; sync(); setTimeout(avoidFold, 0);
  }
  // When the WebView reports the Fold's two screen halves (Viewport Segments API), keep the keypad off the crease:
  // push it fully into one half if it fits there. Without that report nothing is guessed and the layout stays as is.
  function avoidFold() {
    const pad = body.querySelector('.sf-keypad'), segs = window.viewport && window.viewport.segments;
    if (!pad || !segs || segs.length !== 2) return;
    pad.style.marginTop = ''; pad.style.marginLeft = '';
    const [a, b] = segs, r = pad.getBoundingClientRect(), gap = 16;
    if (b.top >= a.bottom - 1) {            // halves above and below each other
      if (r.top < b.top + gap && r.bottom > a.bottom - gap && innerHeight - (b.top + gap) >= r.height) pad.style.marginTop = (b.top + gap - r.top) + 'px';
    } else if (b.left >= a.right - 1) {     // halves side by side
      if (r.left < b.left + gap && r.right > a.right - gap && innerWidth - (b.left + gap) >= r.width) pad.style.marginLeft = (b.left + gap - r.left) + 'px';
    }
  }
  async function save(sms) {
    const rev = ++revision; state.event = null; state.checkStartedAt = Date.now(); change('busy');
    try {
      const result = await call('save', { number: state.selected.number, worker: state.worker, sms });
      if (rev !== revision) return;
      // Saved durably on this phone: back home at once (owner 11-03); it finishes in the background and the home list
      // shows its state. "Recorded" and "SMS sent" are still said only when the tablet / Reader confirm them.
      const worker = state.worker; home(); watched.add(result.id);
      say('Сохранено на телефоне. Квитанция ' + spell(result.number) + ', работник ' + worker + '. Завершится автоматически.'
        + (result.smsRequested ? ' SMS будет отправлено после подтверждения.' : ''));
    } catch (error) { if (rev === revision) { change('sms', false); fail(error); } }
  }
  let checking = -1; // the revision whose status request is in flight: a stale one never blocks a newer entry
  async function updateFinish() {
    if (!state.event || checking === revision) return; const rev = revision; checking = rev;
    try {
      const item = await call('status', { id: state.event.id }); if (rev !== revision) return; state.event = item;
      const tabletApplied = item.scan === 'ADDED' || item.scan === 'ALREADY';
      const smsDone = !item.smsRequested || ['SENT', 'FAILED', 'NO_PHONE', 'BAD_PHONE', 'AMBIGUOUS', 'NOT_FOUND', 'UNSURE', 'REJECTED'].includes(item.sms);
      if (item.recorded === true) {
        const firstConfirmation = state.step !== 'report-result';
        if (smsDone) { clearInterval(polling); polling = null; }
        state.step = 'report-result'; render();
        if (firstConfirmation) {
          say('Записано. Квитанция ' + spell(item.number) + '. Работник ' + item.worker + '.' + (item.smsRequested && item.sms === 'SENT' ? ' Сообщение клиенту отправлено.' : !item.smsRequested ? ' Без сообщения клиенту.' : ''));
          returnTimer = setTimeout(home, 25000);
        }
      } else {
        if (state.step !== 'busy') { state.step = 'busy'; render(); } // update in place: Back is never replaced mid-tap
        const missing = item.receiptNotFound === true;
        const message = missing ? 'NOT FOUND' : item.scan === 'REJECTED' || item.scan === 'HELD' ? item.scanText : tabletApplied ? 'Saving Daily Report…' : 'Waiting for the tablet…';
        const title = body.querySelector('.sf-busy-title');
        title.textContent = message; title.className = 'sf-busy-title' + (missing ? ' sf-not-found' : '');
        const line = body.querySelector('.sf-busy p');
        line.textContent = missing ? 'Receipt not found in the master list. Check the receipt number.'
          : ['#' + item.number + ' for ' + item.worker, item.reportReason || 'Saved only after confirmation for this worker and day.', item.smsRequested ? item.smsText : ''].filter(Boolean).join(' · ');
        // The first entry is followed until the Reader confirms it (owner 11-02, 26448: 0.64 stopped checking after 90 s and
        // said "not confirmed", so it took a second entry to see Recorded). The phone keeps checking the same saved event
        // (same scan id; nothing is sent again from here); a long wait says so, and Recorded still appears by itself.
        if (item.scan === 'REJECTED' || item.scan === 'HELD' || missing) { clearInterval(polling); polling = null; }
        else if (Date.now() - state.checkStartedAt > 90000) {
          title.textContent = 'Still waiting for Daily Report';
          line.textContent = '#' + item.number + ' for ' + item.worker + ' is kept on this phone and still being checked — no need to enter it again. It will say Recorded here when the tablet saves it.';
        }
      }
    } catch (error) { if (rev === revision) fail(error); } finally { if (checking === rev) checking = -1; }
  }
  async function pickup() {
    const number = state.selected.number, plain = number.replace(/[^0-9]/g, ''), rev = ++revision; change('busy');
    try {
      const result = await call('pickup', { number, txn: state.selected.txn || '' }); if (rev !== revision) return;
      home(); say(result.status === 'APPLIED' || result.status === 'ALREADY_PICKED_UP' ? 'Recorded. Квитанция ' + plain + ' забрана.' : 'Запись выдачи квитанции ' + plain + ' сохранена.');
    } catch (error) { if (rev === revision) { change('order', false); fail(error); } }
  }
  function decorate() {
    ['home', 'keypad', 'matches', 'order', 'workers', 'sms', 'busy', 'report-result'].forEach(step => phone.classList.toggle('is-' + step, state.step === step));
    // Back is on every screen but home (owner 11-02): while waiting or after Recorded it returns home; the request is kept.
    root.querySelector('.sf-back').hidden = state.step === 'home';
    root.querySelector('.sf-listen').disabled = !currentAnnouncement();
    icons(); fitNumbers();
    root.querySelectorAll('.sf-signature').forEach(logo => {
      let timer; logo.onpointerdown = () => { clearTimeout(timer); timer = setTimeout(() => call('settings').catch(fail), 1000); };
      logo.onpointerup = logo.onpointerleave = () => clearTimeout(timer);
      logo.oncontextmenu = event => event.preventDefault();
    });
  }
  // Home, two columns that scroll on their own (owner 11-04, 11-05). Left: today's Daily Report entries from this phone, each
  // real state; only the Reader's confirmation says In Daily Report. Right: today's receipt numbers found, nothing else.
  const watched = new Set(), announced = new Set(); // entries saved from this screen; each "Recorded" is said once
  let shown = ''; // what the columns already show: an unchanged 5 s check leaves them, and a finger on them, alone
  function row(number, side, line) {
    const n = document.createElement('strong'); n.textContent = number;
    const t = document.createElement('span'); t.textContent = side;
    const l = document.createElement('small'); l.textContent = line;
    return [n, t, l];
  }
  // Tapping a row opens that receipt's card after a fresh read of the lists: it marks nothing and sends nothing. Picked up / Not
  // picked up stay the card's own two buttons. A search that found no receipt just searches again.
  async function reopen(entry) {
    clearTimers(); state.flow = 'lookup'; state.digits = entry.last4; state.selected = null;
    const rev = ++revision; state.spoken = ''; root.querySelector('.sf-listen').disabled = true; voiceNote(''); change('busy');
    try {
      const result = await call('reopen', { last4: entry.last4, receipt: entry.receipt, txn: entry.txn || '', total: entry.amount || '', shown: entry.number || '' });
      if (rev !== revision) return;
      state.matches = result.receipts;
      if (state.matches.length === 1) select(state.matches[0]); else change('matches');
    } catch (error) { if (rev === revision) { home(); fail(error); } }
  }
  function drawColumns(result) {
    const report = body.querySelector('.sf-overview-report'), finds = body.querySelector('.sf-finds-list');
    report.replaceChildren(...result.report.map(entry => {
      const li = document.createElement('li'); li.className = entry.problem ? 'is-problem' : entry.recorded ? 'is-recorded' : '';
      li.append(...row(entry.number, entry.time, entry.worker + ' · ' + entry.line)); return li;
    }));
    finds.replaceChildren(...result.finds.map(entry => {
      const li = document.createElement('li'), button = document.createElement('button'); button.type = 'button';
      button.onclick = () => reopen(entry); // the list keeps only the number (owner 11-05); status comes from the fresh read on the card
      const number = document.createElement('strong'); number.textContent = entry.number; button.append(number); li.append(button); return li;
    }));
    body.querySelector('.sf-overview:not(.sf-finds) .sf-overview-empty').hidden = result.report.length > 0;
    body.querySelector('.sf-finds .sf-overview-empty').hidden = result.finds.length > 0;
    const note = body.querySelector('.sf-finds-note'); note.textContent = result.findNote; note.hidden = !result.findNote;
  }
  async function loadOverview() {
    const rev = revision;
    try {
      const result = await call('overview'); if (rev !== revision || state.step !== 'home' || !body.querySelector('.sf-columns')) return;
      result.report.forEach(entry => {
        if (entry.recorded && watched.has(entry.id) && !announced.has(entry.id)) {
          announced.add(entry.id); watched.delete(entry.id);
          say('Записано. Квитанция ' + spell(entry.number) + '. Работник ' + entry.worker + '.');
        }
      });
      const next = JSON.stringify(result); if (next === shown) return;
      shown = next; drawColumns(result);
    } catch (_) { shown = ''; body.querySelectorAll('.sf-overview-empty').forEach(empty => { empty.hidden = false; empty.textContent = 'List unavailable right now.'; }); }
  }
  function render() {
    const step = state.step;
    if (step === 'home') {
      body.replaceChildren(clone('sf-home-screen'));
      body.querySelectorAll('[data-go]').forEach(button => button.onclick = () => { ++revision; clearTimers(); state.flow = button.dataset.go; state.digits = ''; change('keypad'); });
      shown = ''; clearInterval(polling); polling = setInterval(loadOverview, 5000); loadOverview();
    } else if (step === 'keypad') {
      body.replaceChildren(clone('sf-keypad-screen')); body.querySelector('.sf-keypad-heading').textContent = state.flow === 'report' ? 'Please enter receipt number' : 'Please enter last four';
      body.querySelector('.sf-find').classList.toggle('violet', state.flow === 'report'); body.querySelector('.sf-find').classList.toggle('green', state.flow === 'lookup'); bindKeys();
    } else if (step === 'matches') {
      body.replaceChildren(clone('sf-matches-screen')); body.querySelector('.sf-flow-name').textContent = 'CHOOSE RECEIPT'; body.querySelector('.sf-match-count').textContent = state.matches.length + ' receipts' + (state.flow === 'lookup' && state.matches.every(m => m.state === 'COLLECTED' || /^Picked up ·/.test(m.pickupLine || '')) ? ' · all already picked up' : '');
      state.matches.forEach(item => { const entry = clone('sf-match-item'); entry.querySelector('.sf-match-number').textContent = item.number; entry.querySelector('.sf-match-customer').textContent = customerLine(item); entry.querySelector('.sf-match-work').remove(); const pick = entry.querySelector('.sf-match-pickup'); if (item.pickupLine) { pick.textContent = item.pickupLine; pick.classList.toggle('is-picked', /^Picked up/.test(item.pickupLine)); pick.classList.toggle('is-unsure', /unavailable|conflicting/.test(item.pickupLine)); } else pick.remove(); entry.querySelector('.sf-match-total').textContent = item.total || ''; entry.querySelector('.sf-match').classList.toggle('sf-unpaid', item.pay === 'DUE'); entry.querySelector('button').onclick = () => select(item); body.querySelector('.sf-matches').append(entry); });
    } else if (step === 'order') {
      body.replaceChildren(clone('sf-order-screen')); body.querySelector('.sf-detail-slot').append(receipt());
      // An order already picked up, or with conflicting records, is shown but never offered for another pickup (owner 11-02).
      const line = state.selected.pickupLine || '', collected = state.selected.state === 'COLLECTED' || /^Picked up ·/.test(line), conflict = /conflicting/.test(line);
      body.querySelector('.sf-pickup').disabled = !state.selected.ready || !!state.selected.pickupNote || collected || conflict;
      if (collected || conflict) body.querySelector('.sf-readonly').textContent = collected ? 'Already picked up — pickup is not offered again.' : 'Pickup records disagree — check the tablet. Pickup is not offered here.';
      body.querySelector('.sf-pickup').onclick = pickup; body.querySelector('.sf-not-picked-up').onclick = () => { const ready = state.selected.ready && !state.selected.pickupNote; home(); say(ready ? 'Not picked up. Заказ остаётся в списке готовых к выдаче.' : 'Not picked up.'); };
    } else if (step === 'workers') {
      body.replaceChildren(clone('sf-workers-screen')); body.querySelector('.sf-detail-slot').append(receipt()); body.querySelector('.sf-work-date').textContent = today();
      state.workers.forEach(name => { const button = document.createElement('button'); button.type = 'button'; button.textContent = name; button.onclick = () => { state.worker = name; change('sms'); }; body.querySelector('.sf-workers').append(button); });
      if (!state.workers.length) fail(new Error('Approved workers unavailable. Check Sofia settings.'));
    } else if (step === 'sms') {
      body.replaceChildren(clone('sf-sms-screen')); body.querySelector('.sf-detail-slot').append(receipt()); body.querySelector('.sf-worker-chip span').textContent = state.worker;
      body.querySelectorAll('[data-finish]').forEach(button => button.onclick = () => save(button.dataset.finish === 'sms'));
    } else if (step === 'busy') {
      body.replaceChildren(clone('sf-busy-screen')); if (state.selected) body.querySelector('.sf-detail-slot').append(receipt());
      body.querySelector('.sf-busy-title').textContent = state.event ? 'Saving…' : state.selected ? 'Processing…' : 'Finding…';
    } else if (step === 'report-result') {
      body.replaceChildren(clone('sf-report-recorded-screen')); body.querySelector('.sf-receipt-number').textContent = state.event.number;
      body.querySelector('.sf-confirmation-worker').textContent = state.event.worker; body.querySelector('.sf-confirmation-date').textContent = today(state.event.workday + 'T12:00:00Z');
      body.querySelector('.sf-delivery-status').textContent = state.event.smsRequested ? state.event.sms === 'SENT' ? 'Customer SMS sent' : state.event.smsText || 'Customer SMS not confirmed' : 'Saved · no SMS';
    }
    // The Sofia name on every page, at the home page's size (Art 11-01); holding it opens Settings as on home.
    if (!body.querySelector('.sf-signature')) { const name = document.createElement('h1'); name.className = 'sf-signature'; name.textContent = 'Sofia'; body.prepend(name); }
    decorate();
  }
  // No bottom notice strip (Art 11-01): old receipt notices stayed there and couldn't be cleared. Pending work stays in Settings.
  root.querySelector('.sf-home').onclick = home;
  root.querySelector('.sf-back').onclick = () => {
    if (['busy', 'report-result'].includes(state.step)) { home(); return; }
    ++revision; clearTimers(); state.step = state.history.pop() || 'home'; render();
  };
  window.counterBack = () => { if (state.step === 'home') return false; root.querySelector('.sf-back').click(); return true; };
  root.querySelector('.sf-repeat').onclick = listen;
  root.querySelector('.sf-listen').onclick = listen;
  root.querySelector('.sf-sound').onclick = () => { state.sound = !state.sound; root.querySelector('.sf-sound').setAttribute('aria-pressed', String(state.sound)); };
  new ResizeObserver(fitNumbers).observe(body);
  render(); call('load').then(result => { state.workers = result.workers; }).catch(fail);
})();
