# Tailor Shop skill — steps for Art

Already done: words (interaction model), screen on, skill opens in the Test tab.

**0. Update the words (can do now)**
Build tab → Interaction Model → JSON Editor → delete all → paste our `interactionModels/custom/en-US.json` → **Save** → **Build skill**.
(Adds 2 or 3 numbers at once: "ask tailor shop 4417 and 2222".)

**Do steps 1–5 only after the builder says the sheet share is confirmed.**

**1. Paste the code**
Code tab → left side, folder `lambda`.
- Click `index.js` → select all → delete → paste our `index.js`.
- Click `package.json` → select all → delete → paste our `package.json`.
- For each of these, click **New File** (top left), type the name exactly, then paste ours:
  `lambda/lookup.js`, `lambda/sheets.js`, `lambda/screen.js`, `lambda/apl.json`

**2. Paste the key**
**New File** → `lambda/sheet-key.json` → paste the key text the Mac builder gives you. Nowhere else.

**3. Save and deploy**
Click **Save**, then **Deploy** (top right). Wait for "Deployment successful" (about 1 minute).

**4. Try it**
Test tab → type `ask tailor shop 4417` (use a real customer's last 4). Several at once: `ask tailor shop 4417 and 2222 and 9999`.
The screen shows the receipt number big; under it: name, phone, promised time, PAID / NOT PAID / PART PAID / ON ACCOUNT, and the text status.
Alexa only says how many orders.

If the screen says:
- "Sheet key not set up yet" → step 2 missing or pasted wrong.
- "Can't reach the sheet" → the share isn't done yet.
- "Sheet layout changed" → tell the builder.

**5. Don't click Publish** until ChatGPT has reviewed.
