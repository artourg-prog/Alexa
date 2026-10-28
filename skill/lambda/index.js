'use strict';

// Tailor Shop — "Alexa, ask Tailor Shop 4417".
// Reads "Order Ready For Pickup" (read-only) and shows the matching orders on the Echo Show.

const Alexa = require('ask-sdk-core');
const { readSheet } = require('./sheets');
const { normalizeLast4, findMany, speech } = require('./lookup');
const { datasource } = require('./screen');
const apl = require('./apl.json');

const ASK = 'Say the last 4 digits of the phone number.';

// Sheet/key failures -> short screen text (Art reads this, so plain words).
const READ_ERRORS = {
  KEY_FILE_MISSING: 'Key file missing — add sheet-key.json, Save, Deploy',
  KEY_BAD_JSON: 'Key file is damaged — paste it again, Save, Deploy',
  KEY_INCOMPLETE: 'Key file is incomplete — paste the whole key again',
  KEY_INVALID: 'Key file is damaged — paste it again, Save, Deploy',
  HTTP_400: 'Google rejected the key — ask the builder',
  HTTP_401: 'Google rejected the key — ask the builder',
  HTTP_403: 'No access to the sheet — check the share',
  HTTP_404: 'Sheet not found — ask the builder',
};
const SLOTS = ['lastFour', 'lastFourB', 'lastFourC']; // up to 3 clients in one request

function hasScreen(handlerInput) {
  return Boolean(Alexa.getSupportedInterfaces(handlerInput.requestEnvelope)['Alexa.Presentation.APL']);
}

function show(handlerInput, nums, result, message) {
  if (!hasScreen(handlerInput)) return;
  handlerInput.responseBuilder.addDirective({
    type: 'Alexa.Presentation.APL.RenderDocument',
    token: 'tailorShopLookup',
    document: apl,
    datasources: datasource(nums, result, message),
  });
}

function makeLookupHandler(read = readSheet) {
  return {
    canHandle(handlerInput) {
      return (
        Alexa.getRequestType(handlerInput.requestEnvelope) === 'IntentRequest' &&
        Alexa.getIntentName(handlerInput.requestEnvelope) === 'LookupIntent'
      );
    },
    async handle(handlerInput) {
      const nums = [
        ...new Set(SLOTS.map((s) => normalizeLast4(Alexa.getSlotValue(handlerInput.requestEnvelope, s))).filter(Boolean)),
      ];
      const rb = handlerInput.responseBuilder;
      if (!nums.length) return rb.speak(ASK).reprompt(ASK).getResponse();

      let rows;
      try {
        rows = await read();
      } catch (e) {
        console.log('Sheet read failed:', e.message);
        const msg = READ_ERRORS[e.message] || "Can't reach the sheet";
        show(handlerInput, nums, null, msg);
        return rb.speak(READ_ERRORS[e.message] ? 'There is a setup problem. Look at the screen.' : "Can't reach the sheet. Try again in a minute.").getResponse();
      }

      let result;
      try {
        result = findMany(nums, rows);
      } catch (e) {
        console.log('Sheet layout changed:', e.message);
        show(handlerInput, nums, null, 'Sheet layout changed');
        return rb.speak('The sheet layout changed. Ask the builder to check it.').getResponse();
      }
      show(handlerInput, nums, result);
      return rb.speak(speech(result, hasScreen(handlerInput))).withShouldEndSession(true).getResponse();
    },
  };
}

const LaunchHandler = {
  canHandle(handlerInput) {
    return Alexa.getRequestType(handlerInput.requestEnvelope) === 'LaunchRequest';
  },
  handle(handlerInput) {
    return handlerInput.responseBuilder.speak(ASK).reprompt(ASK).getResponse();
  },
};

const HelpHandler = {
  canHandle(handlerInput) {
    return (
      Alexa.getRequestType(handlerInput.requestEnvelope) === 'IntentRequest' &&
      Alexa.getIntentName(handlerInput.requestEnvelope) === 'AMAZON.HelpIntent'
    );
  },
  handle(handlerInput) {
    const say = 'Say a phone number\'s last 4 digits, like 4 4 1 7.';
    return handlerInput.responseBuilder.speak(say).reprompt(say).getResponse();
  },
};

const StopHandler = {
  canHandle(handlerInput) {
    return (
      Alexa.getRequestType(handlerInput.requestEnvelope) === 'IntentRequest' &&
      ['AMAZON.CancelIntent', 'AMAZON.StopIntent', 'AMAZON.NavigateHomeIntent'].includes(
        Alexa.getIntentName(handlerInput.requestEnvelope),
      )
    );
  },
  handle(handlerInput) {
    return handlerInput.responseBuilder.speak('OK.').withShouldEndSession(true).getResponse();
  },
};

const FallbackHandler = {
  canHandle(handlerInput) {
    return (
      Alexa.getRequestType(handlerInput.requestEnvelope) === 'IntentRequest' &&
      Alexa.getIntentName(handlerInput.requestEnvelope) === 'AMAZON.FallbackIntent'
    );
  },
  handle(handlerInput) {
    return handlerInput.responseBuilder.speak(ASK).reprompt(ASK).getResponse();
  },
};

const SessionEndedHandler = {
  canHandle(handlerInput) {
    return Alexa.getRequestType(handlerInput.requestEnvelope) === 'SessionEndedRequest';
  },
  handle(handlerInput) {
    return handlerInput.responseBuilder.getResponse();
  },
};

const ErrorHandler = {
  canHandle() {
    return true;
  },
  handle(handlerInput, error) {
    console.log('Error:', error && error.stack);
    return handlerInput.responseBuilder.speak('Something went wrong. Try again.').getResponse();
  },
};

function buildSkill(read = readSheet) {
  return Alexa.SkillBuilders.custom()
    .addRequestHandlers(
      LaunchHandler,
      makeLookupHandler(read),
      HelpHandler,
      StopHandler,
      FallbackHandler,
      SessionEndedHandler,
    )
    .addErrorHandlers(ErrorHandler)
    .withCustomUserAgent('tailor-shop/v1');
}

exports.handler = buildSkill().lambda();
exports.buildSkill = buildSkill;
