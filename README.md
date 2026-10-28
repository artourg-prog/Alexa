# Alexa
Echo Show pickup lookup: last 4 phone digits (up to 3 at once) → receipt number, customer, phone

- `skill/ART-STEPS.md` — console steps for Art
- `skill/interactionModels/custom/en-US.json` — interaction model (slot `lastFour`)
- `skill/lambda/` — Alexa-hosted code (`index.js`, `lookup.js`, `sheets.js`, `screen.js`, `apl.json`, `package.json`)
- `skill/test/` — `cd skill/lambda && npm install && npm test`

Source: sheet "Order Ready For Pickup", tab "Active Orders" (synced from the Pickups tablet) — not the master.
Read-only: `spreadsheets.readonly` scope + Viewer share; only `values.batchGet` is called.
Match on `phone_normalized` last 4. Shows only receipt # (as large as fits), customer and full phone —
the screen is for finding the bag on the shelf. `reported_workers` is never shown. Voice says only the count.
