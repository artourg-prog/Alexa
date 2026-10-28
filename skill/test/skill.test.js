'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { buildSkill } = require('../lambda/index');
const { orders } = require('./fixtures');

function envelope(request, withScreen = true) {
  return {
    version: '1.0',
    session: { new: true, sessionId: 's', application: { applicationId: 'amzn1.ask.skill.6b5473b4-54e7-4395-8439-76ff1e9b13e4' }, user: { userId: 'u' } },
    context: {
      System: {
        application: { applicationId: 'amzn1.ask.skill.6b5473b4-54e7-4395-8439-76ff1e9b13e4' },
        user: { userId: 'u' },
        device: { deviceId: 'd', supportedInterfaces: withScreen ? { 'Alexa.Presentation.APL': { runtime: { maxVersion: '2024.1' } } } : {} },
      },
    },
    request: { requestId: 'r', timestamp: new Date().toISOString(), locale: 'en-US', ...request },
  };
}
const lookup = (value, b, c) => ({
  type: 'IntentRequest',
  intent: {
    name: 'LookupIntent',
    confirmationStatus: 'NONE',
    slots: {
      lastFour: { name: 'lastFour', value },
      lastFourB: { name: 'lastFourB', value: b },
      lastFourC: { name: 'lastFourC', value: c },
    },
  },
});
const run = (req, read = async () => orders, screen = true) => buildSkill(read).create().invoke(envelope(req, screen));

test('"ask Tailor Shop 4417" → screen with both orders, voice says only the count', async () => {
  const res = await run(lookup('4417'));
  assert.match(res.response.outputSpeech.ssml, /2 orders for 4 4 1 7/);
  assert.doesNotMatch(res.response.outputSpeech.ssml, /33520|Sam/);
  assert.strictEqual(res.response.shouldEndSession, true);
  const d = res.response.directives[0];
  assert.strictEqual(d.type, 'Alexa.Presentation.APL.RenderDocument');
  const props = d.datasources.data.properties;
  assert.deepStrictEqual(props.cards, [
    { receipt: '33520', customer: 'Sam Roe', phone: '(702) 555-4417' },
    { receipt: '33515', customer: 'Pat Lee', phone: '(702) 555-4417' },
  ]);
  assert.strictEqual(props.message, '');
  assert.doesNotMatch(JSON.stringify(res), /SECRET WORKER|READY|PAID|Promised|TEXT/);
});

test('no screen → count only, no APL directive', async () => {
  const res = await run(lookup('2222'), undefined, false);
  assert.match(res.response.outputSpeech.ssml, /1 order for 2 2 2 2\. Look on the Echo Show/);
  assert.ok(!res.response.directives);
});

test('no match → "No orders" on screen', async () => {
  const res = await run(lookup('9999'));
  assert.strictEqual(res.response.directives[0].datasources.data.properties.message, 'No orders');
});

test('51 matches → all on a scrollable screen, compact rows, voice count only', async () => {
  const { header, row } = require('./fixtures');
  const many = [header, ...Array.from({ length: 51 }, (_, i) => row(String(30000 + i), 'Store', '', '7255559528', '10-10-26', '', '', 'Account', 'ON ACCOUNT'))];
  const res = await run(lookup('9528'), async () => many);
  const props = res.response.directives[0].datasources.data.properties;
  assert.strictEqual(props.cards.length, 51);
  assert.match(props.title, /51 orders · swipe up/);
  assert.strictEqual(props.size.receipt, '40dp');
  assert.ok(!('footer' in props));
  assert.match(res.response.outputSpeech.ssml, /51 orders for 9 5 2 8\./);
  const apl = res.response.directives[0].document;
  assert.strictEqual(apl.mainTemplate.item.items.find((i) => i.type === 'Sequence').scrollDirection || 'vertical', 'vertical');
});

test('"4417 and 2222 and 9999" → all on one screen, receipt huge for ≤3, missing number flagged', async () => {
  const res = await run(lookup('4417', '2222', '9999'));
  const props = res.response.directives[0].datasources.data.properties;
  assert.deepStrictEqual(props.cards.map((c) => c.receipt), ['33520', '33515', '33498']);
  assert.strictEqual(props.title, 'Phone …4417, …2222, …9999 · 3 orders');
  assert.strictEqual(props.missing, 'No orders for …9999');
  assert.strictEqual(props.size.receipt, '96dp');
  assert.match(res.response.outputSpeech.ssml, /2 orders for 4 4 1 7, 1 order for 2 2 2 2, no orders for 9 9 9 9\./);
});

test('one order → receipt number at its biggest', async () => {
  const res = await run(lookup('2222'));
  assert.strictEqual(res.response.directives[0].datasources.data.properties.size.receipt, '180dp');
});

test('sheet header changed → says so', async () => {
  const res = await run(lookup('4417'), async () => [['foo'], ['1']]);
  assert.strictEqual(res.response.directives[0].datasources.data.properties.message, 'Sheet layout changed');
});

test('missing digits → asks again', async () => {
  const res = await run(lookup(undefined));
  assert.match(res.response.outputSpeech.ssml, /last 4 digits/);
  assert.ok(res.response.reprompt);
});

test('sheet down → says so, shows it', async () => {
  const res = await run(lookup('4417'), async () => { throw new Error('HTTP_403'); });
  assert.match(res.response.outputSpeech.ssml, /Can't reach the sheet/);
  assert.strictEqual(res.response.directives[0].datasources.data.properties.message, "Can't reach the sheet");
});

test('key not pasted yet → clear message', async () => {
  const res = await run(lookup('4417'), async () => { throw new Error('KEY_MISSING'); });
  assert.match(res.response.outputSpeech.ssml, /Sheet key not set up yet/);
});

test('launch and help ask for the digits', async () => {
  for (const req of [{ type: 'LaunchRequest' }, { type: 'IntentRequest', intent: { name: 'AMAZON.HelpIntent', slots: {} } }]) {
    const res = await run(req);
    assert.ok(res.response.reprompt);
  }
});

test('interaction model and APL are valid JSON with the right names', () => {
  const m = require('../interactionModels/custom/en-US.json').interactionModel.languageModel;
  assert.strictEqual(m.invocationName, 'tailor shop');
  const intent = m.intents.find((i) => i.name === 'LookupIntent');
  for (const slot of intent.slots) {
    assert.strictEqual(slot.type, 'AMAZON.FOUR_DIGIT_NUMBER');
    assert.doesNotMatch(slot.name, /\d/, 'Amazon rejects digits in slot names');
  }
  for (const s of intent.samples) {
    for (const [, name] of s.matchAll(/{(\w+)}/g)) assert.ok(intent.slots.some((x) => x.name === name), s);
  }
  assert.deepStrictEqual(intent.slots.map((x) => x.name), ['lastFour', 'lastFourB', 'lastFourC']);
  assert.ok(intent.samples.every((s) => s.includes('{lastFour}')));
  const apl = require('../lambda/apl.json');
  assert.strictEqual(apl.type, 'APL');
});
