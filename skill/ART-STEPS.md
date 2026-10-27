# Tailor Shop skill — steps for Art

Already done: words (interaction model), screen on, skill opens in the Test tab.

**Do these only after the builder says the sheet share is confirmed.**

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
Test tab → type `ask tailor shop 4417` (use a real customer's last 4).
The screen shows: receipt #, name, phone, promised time, PAID / NOT PAID / PART PAID / ON ACCOUNT, and the text status.
Alexa only says how many orders.

If the screen says:
- "Sheet key not set up yet" → step 2 missing or pasted wrong.
- "Can't reach the sheet" → the share isn't done yet.
- "Sheet layout changed" → tell the builder.

**5. Don't click Publish** until ChatGPT has reviewed.
