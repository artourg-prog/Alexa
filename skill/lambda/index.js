'use strict';

// Tailor Shop — "Alexa, ask Tailor Shop 4417".
// Reads "Order Ready For Pickup" (read-only) and shows the matching orders on the Echo Show.

const Alexa = require('ask-sdk-core');
const { readSheet } = require('./sheets');
const { normalizeLast4, findOrders, speech } = require('./lookup');
const { datasource } = require('./screen');
const apl = require('./apl.json');

const ASK = 'Say the last 4 digits of the phone number.';

function hasScreen(handlerInput) {
  return Boolean(Alexa.getSupportedInterfaces(handlerInput.requestEnvelope)['Alexa.Presentation.APL']);
}

function show(handlerInput, last4, result, message) {
  if (!hasScreen(handlerInput)) return;
  handlerInput.responseBuilder.addDirective({
    type: 'Alexa.Presentation.APL.RenderDocument',
    token: 'tailorShopLookup',
    document: apl,
    datasources: datasource(last4, result, message),
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
      const last4 = normalizeLast4(Alexa.getSlotValue(handlerInput.requestEnvelope, 'lastFour'));
      const rb = handlerInput.responseBuilder;
      if (!last4) return rb.speak(ASK).reprompt(ASK).getResponse();

      let rows;
      try {
        rows = await read();
      } catch (e) {
        console.log('Sheet read failed:', e.message);
        const msg = e.message === 'KEY_MISSING' ? 'Sheet key not set up yet' : "Can't reach the sheet";
        show(handlerInput, last4, null, msg);
        return rb.speak(`${msg}. Try again in a minute.`).getResponse();
      }

      let result;
      try {
        result = findOrders(last4, rows);
      } catch (e) {
        console.log('Sheet layout changed:', e.message);
        show(handlerInput, last4, null, 'Sheet layout changed');
        return rb.speak('The sheet layout changed. Ask the builder to check it.').getResponse();
      }
      show(handlerInput, last4, result);
      return rb.speak(speech(last4, result, hasScreen(handlerInput))).withShouldEndSession(true).getResponse();
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
