(function () {
  'use strict';
  const root = document.getElementById('sofia-touch-review');
  const body = root.querySelector('.sf-body'), phone = root.querySelector('.sf-phone');
  const pending = new Map(); let serial = 0, polling = null, returnTimer = null, revision = 0;
  const state = { step: 'home', flow: 'lookup', digits: '', selected: null, worker: '', workers: [], sound: true, spoken: '', matches: [], event: null, history: [], checkStartedAt: 0 };
  window.counterReply = function (id, json) {
    const request = pending.get(id); if (!request) return; pending.delete(id);
    try { const result = JSON.parse(json); result.ok ? request.resolve(result) : request.reject(new Error(result.error || 'Please try again.')); }
    catch (error) { request.reject(error); }
  };
  function call(action, data = {}) {
    return new Promise((resolve, reject) => {
      const id = String(++serial); pending.set(id, { resolve, reject });
      if (!window.Sofia) { pending.delete(id); reject(new Error('Sofia connection unavailable.')); return; }
      window.Sofia.request(id, action, JSON.stringify(data));
    });
  }
  function clone(id) { return root.querySelector('#' + id).content.cloneNode(true); }
  function today(at) { return new Intl.DateTimeFormat('en-US', { timeZone: 'America/Los_Angeles', month: 'short', day: 'numeric', year: 'numeric' }).format(at ? new Date(at) : new Date()); }
  function say(text) {
    state.spoken = text; root.querySelector('.sf-spoken').textContent = text; root.querySelector('.sf-speech').hidden = false;
    if (state.sound) call('speak', { text }).catch(() => {});
  }
  function clearTimers() { clearInterval(polling); polling = null; clearTimeout(returnTimer); returnTimer = null; }
  function home() {
    ++revision; clearTimers(); state.step = 'home'; state.digits = ''; state.selected = null; state.worker = ''; state.event = null; state.history = [];
    root.querySelector('.sf-speech').hidden = true; render();
  }
  function change(step, remember = true) { if (remember) state.history.push(state.step); state.step = step; render(); }
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
      let low = 28, high = wide ? 400 : parseFloat(getComputedStyle(number).fontSize);
      if (fits(high)) return;
      while (high - low > 1) { const mid = Math.floor((low + high) / 2); if (fits(mid)) low = mid; else high = mid; }
      number.style.fontSize = low + 'px';
    });
  }
  function receipt() {
    const fragment = clone('sf-receipt-details'), item = state.selected;
    fragment.querySelector('.sf-receipt-number').textContent = item.number;
    fragment.querySelector('.sf-customer').textContent = item.name || '';
    fragment.querySelector('.sf-total').textContent = item.total || '—';
    fragment.querySelector('.sf-work').textContent = item.work || '';
    fragment.querySelector('.sf-receipt').classList.toggle('sf-unpaid', item.pay === 'DUE');
    const ready = fragment.querySelector('.sf-ready');
    const paid = { PAID: 'Paid', DUE: 'Not paid', ACCOUNT: 'Store account', UNKNOWN: 'Payment unavailable' }[item.pay] || 'Payment unavailable';
    // From the Order Index: its own state line (not ready + promised time / already picked up / unknown), no payment guess.
    ready.textContent = item.pickupNote ? item.pickupNote : item.state ? item.stateText + (item.stale ? ' · index may be out of date' : '')
      : item.unverified ? 'Receipt entered · checked when saved' : paid + ' · ' + (item.ready ? 'Ready for pickup' : 'In progress');
    ready.classList.toggle('not-paid', item.pay === 'DUE'); ready.classList.toggle('in-progress', !item.ready);
    fragment.querySelector('.sf-today').textContent = today(); return fragment;
  }
  async function find() {
    const rev = ++revision; const number = state.digits;
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
    const payment = item.pay === 'PAID' ? 'Оплачено.' : item.pay === 'DUE' ? 'Не оплачено.' : item.pay === 'ACCOUNT' ? 'Счёт магазина.' : '';
    const spokenState = { NOT_READY: 'Ещё не готова.', COLLECTED: 'Уже выдана.', UNKNOWN: 'Статус неизвестен, проверьте на планшете.', READY: 'Готова к выдаче.' }[item.state];
    if (item.state) say('Квитанция ' + item.number.split('').join(' ') + '. ' + spokenState);
    else if (!item.unverified) say('Квитанция ' + item.number.split('').join(' ') + '. ' + payment + ' ' + (item.ready ? 'Готова к выдаче.' : 'Работа ещё не закончена.'));
  }
  function bindKeys() {
    const input = body.querySelector('.sf-digits'), enter = body.querySelector('.sf-find');
    const phoneMode = state.flow === 'lookup'; input.maxLength = phoneMode ? 4 : 9; input.value = state.digits;
    input.placeholder = phoneMode ? '····' : '········'; input.setAttribute('aria-label', phoneMode ? 'Last four phone digits' : 'Receipt number');
    const sync = () => { state.digits = input.value.replace(/[^0-9]/g, '').slice(0, phoneMode ? 4 : 9); input.value = state.digits; enter.disabled = phoneMode ? state.digits.length !== 4 : state.digits.length < 3; };
    input.oninput = sync; input.onkeydown = event => { if (event.key === 'Enter' && !enter.disabled) find(); };
    body.querySelectorAll('[data-key]').forEach(button => button.onclick = () => { const key = button.dataset.key; input.value = key === 'clear' ? '' : key === 'delete' ? input.value.slice(0, -1) : input.value + key; sync(); });
    enter.onclick = find; sync();
  }
  async function save(sms) {
    const rev = ++revision; state.event = null; state.checkStartedAt = Date.now(); change('busy');
    try {
      const result = await call('save', { number: state.selected.number, worker: state.worker, sms });
      if (rev !== revision) return;
      state.event = result; polling = setInterval(updateFinish, 2000); updateFinish();
    } catch (error) { if (rev === revision) { change('sms', false); fail(error); } }
  }
  let checking = false;
  async function updateFinish() {
    if (!state.event || checking) return; checking = true; const rev = revision;
    try {
      const item = await call('status', { id: state.event.id }); if (rev !== revision) return; state.event = item;
      const tabletApplied = item.scan === 'ADDED' || item.scan === 'ALREADY';
      const smsDone = !item.smsRequested || ['SENT', 'FAILED', 'NO_PHONE', 'BAD_PHONE', 'AMBIGUOUS', 'NOT_FOUND', 'UNSURE', 'REJECTED'].includes(item.sms);
      if (item.recorded === true) {
        const firstConfirmation = state.step !== 'report-result';
        if (smsDone) { clearInterval(polling); polling = null; }
        state.step = 'report-result'; render();
        if (firstConfirmation) {
          say('Recorded. Квитанция ' + item.number + '. Записано на имя ' + item.worker + '.' + (item.smsRequested && item.sms === 'SENT' ? ' Сообщение клиенту отправлено.' : !item.smsRequested ? ' Без SMS.' : ''));
          returnTimer = setTimeout(home, 25000);
        }
      } else {
        state.step = 'busy'; render();
        const message = item.scan === 'REJECTED' || item.scan === 'HELD' ? item.scanText : tabletApplied ? 'Saving Daily Report…' : 'Waiting for the tablet…';
        body.querySelector('.sf-busy-title').textContent = message;
        const line = body.querySelector('.sf-busy p');
        line.textContent = [item.reportReason || 'Saved only after confirmation for this worker and day.', item.smsRequested ? item.smsText : ''].filter(Boolean).join(' · ');
        if (item.scan === 'REJECTED' || item.scan === 'HELD') { clearInterval(polling); polling = null; }
        else if (Date.now() - state.checkStartedAt > 90000) {
          clearInterval(polling); polling = null;
          body.querySelector('.sf-busy-title').textContent = 'Daily Report not confirmed';
          line.textContent = 'Check Daily Report on the tablet. This request is retained; do not enter it again. Back lets you continue with another receipt.';
        }
      }
    } catch (error) { if (rev === revision) fail(error); } finally { checking = false; }
  }
  async function pickup() {
    const number = state.selected.number, rev = ++revision; change('busy');
    try {
      const result = await call('pickup', { number, txn: state.selected.txn || '' }); if (rev !== revision) return;
      home(); say(result.status === 'APPLIED' || result.status === 'ALREADY_PICKED_UP' ? 'Recorded. Квитанция ' + number + ' забрана.' : 'Запись выдачи квитанции ' + number + ' сохранена.');
    } catch (error) { if (rev === revision) { change('order', false); fail(error); } }
  }
  function decorate() {
    ['home', 'keypad', 'order', 'workers', 'sms', 'busy', 'report-result'].forEach(step => phone.classList.toggle('is-' + step, state.step === step));
    root.querySelector('.sf-back').hidden = ['home', 'busy', 'report-result'].includes(state.step);
    icons(); fitNumbers();
    root.querySelectorAll('.sf-signature').forEach(logo => {
      let timer; logo.onpointerdown = () => { clearTimeout(timer); timer = setTimeout(() => call('settings').catch(fail), 1000); };
      logo.onpointerup = logo.onpointerleave = () => clearTimeout(timer);
      logo.oncontextmenu = event => event.preventDefault();
    });
  }
  function render() {
    const step = state.step;
    if (step === 'home') {
      body.replaceChildren(clone('sf-home-screen'));
      body.querySelectorAll('[data-go]').forEach(button => button.onclick = () => { ++revision; clearTimers(); state.flow = button.dataset.go; state.digits = ''; change('keypad'); });
    } else if (step === 'keypad') {
      body.replaceChildren(clone('sf-keypad-screen')); body.querySelector('.sf-keypad-heading').textContent = state.flow === 'report' ? 'Please enter receipt number' : 'Please enter last four';
      body.querySelector('.sf-find').classList.toggle('violet', state.flow === 'report'); body.querySelector('.sf-find').classList.toggle('green', state.flow === 'lookup'); bindKeys();
    } else if (step === 'matches') {
      body.replaceChildren(clone('sf-matches-screen')); body.querySelector('.sf-flow-name').textContent = 'CHOOSE RECEIPT'; body.querySelector('.sf-match-count').textContent = state.matches.length + ' receipts';
      state.matches.forEach(item => { const entry = clone('sf-match-item'); entry.querySelector('.sf-match-number').textContent = item.number; entry.querySelector('.sf-match-customer').textContent = item.name || ''; entry.querySelector('.sf-match-work').textContent = item.work || ''; entry.querySelector('.sf-match-total').textContent = item.total || ''; entry.querySelector('button').onclick = () => select(item); body.querySelector('.sf-matches').append(entry); });
    } else if (step === 'order') {
      body.replaceChildren(clone('sf-order-screen')); body.querySelector('.sf-detail-slot').append(receipt()); body.querySelector('.sf-pickup').disabled = !state.selected.ready || !!state.selected.pickupNote;
      body.querySelector('.sf-pickup').onclick = pickup; body.querySelector('.sf-not-picked-up').onclick = () => { home(); say('Not picked up. Заказ остаётся в списке готовых к выдаче.'); };
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
      const next = document.createElement('button'); next.type = 'button'; next.className = 'sf-wide neutral sf-pending-next'; next.textContent = 'Back'; next.onclick = home; body.append(next);
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
  root.querySelector('.sf-back').onclick = () => { ++revision; clearTimers(); state.step = state.history.pop() || 'home'; render(); };
  root.querySelector('.sf-repeat').onclick = () => say(state.spoken);
  root.querySelector('.sf-sound').onclick = () => { state.sound = !state.sound; root.querySelector('.sf-sound').setAttribute('aria-pressed', String(state.sound)); };
  new ResizeObserver(fitNumbers).observe(body);
  render(); call('load').then(result => { state.workers = result.workers; }).catch(fail);
})();
