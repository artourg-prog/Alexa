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
const lookup = (value) => ({
  type: 'IntentRequest',
  intent: { name: 'LookupIntent', confirmationStatus: 'NONE', slots: { lastFour: { name: 'lastFour', value } } },
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
  assert.deepStrictEqual(props.cards.map((c) => [c.receipt, c.customer, c.detail, c.paid, c.sms]), [
    ['33520', 'Sam Roe', '(702) 555-4417  ·  Promised 10-14-26 5 PM', 'PART PAID', 'TEXT SENT'],
    ['33515', 'Pat Lee', '(702) 555-4417  ·  Promised 10-09-26 1:30 PM', 'PAID', 'NOT TEXTED'],
  ]);
  assert.strictEqual(props.message, '');
  assert.doesNotMatch(JSON.stringify(res), /SECRET WORKER|READY/);
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
  assert.strictEqual(intent.slots[0].type, 'AMAZON.FOUR_DIGIT_NUMBER');
  assert.doesNotMatch(intent.slots[0].name, /\d/, 'Amazon rejects digits in slot names');
  assert.ok(intent.samples.every((s) => s.includes('{lastFour}')));
  const apl = require('../lambda/apl.json');
  assert.strictEqual(apl.type, 'APL');
});
