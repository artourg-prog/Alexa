# Alexa
Echo Show pickup lookup: last 4 phone digits → receipt number, payment, text status

- `skill/ART-STEPS.md` — console steps for Art
- `skill/interactionModels/custom/en-US.json` — interaction model (slot `lastFour`)
- `skill/lambda/` — Alexa-hosted code (`index.js`, `lookup.js`, `sheets.js`, `screen.js`, `apl.json`, `package.json`)
- `skill/test/` — `cd skill/lambda && npm install && npm test`

Source: sheet "Order Ready For Pickup", tab "Active Orders" (synced from the Pickups tablet) — not the master.
Read-only: `spreadsheets.readonly` scope + Viewer share; only `values.batchGet` is called.
Match on `phone_normalized` last 4. Shows receipt, customer, phone, promised, SMS state, `payment_status`
(falls back to `payment_type` while blank). `reported_workers` is never shown; no "ready" is claimed.
Voice says only the count.
