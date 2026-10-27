# Alexa
Echo Show pickup lookup: last 4 phone digits → receipt number, ready, paid

- `skill/ART-STEPS.md` — console steps for Art
- `skill/interactionModels/custom/en-US.json` — interaction model
- `skill/lambda/` — Alexa-hosted code (`index.js`, `lookup.js`, `sheets.js`, `screen.js`, `apl.json`, `package.json`)
- `skill/test/` — `cd skill/lambda && npm install && npm test`

Read-only: `spreadsheets.readonly` scope + Viewer share; only `values.batchGet` is called.
Rules (issue #1): J ✓ → PICKED UP · Account → ON ACCOUNT (no ready) · Pickup Texts SENT → READY · Due → NOT PAID, Due + other → PART PAID.
