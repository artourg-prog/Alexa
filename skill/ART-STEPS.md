# Tailor Shop skill — steps for Art

Open developer.amazon.com → Alexa → **Tailor Shop**. Each step: copy, paste, click.

**1. Words Alexa understands**
Build tab → Interaction Model → **JSON Editor** → delete everything → paste `interactionModels/custom/en-US.json` → **Save** → **Build skill**.

**2. Turn on the screen**
Build tab → **Interfaces** → switch on **Alexa Presentation Language** → tick **Echo Show** → **Save** → **Build skill** again.

**3. The code**
Code tab. In the `lambda` folder:
- open `index.js` → delete all → paste ours
- open `package.json` → delete all → paste ours
- **New file** for each, then paste: `lookup.js`, `sheets.js`, `screen.js`, `apl.json`
- **New file** `sheet-key.json` → paste the key text the Mac builder gives you (never put it anywhere else)

Click **Save**, then **Deploy**. Wait for "Deployment successful".

**4. Try it**
Test tab → set to **Development** → type `ask tailor shop 4417` → you should see the receipts.
Then on the Echo Show: "Alexa, ask Tailor Shop 4417".

**5. Don't click Publish** until ChatGPT has reviewed.

---
What the screen shows: receipt #, name, **READY / NOT READY / PICKED UP**, **PAID / NOT PAID / PART PAID / ON ACCOUNT**.
"Sheet key not set up yet" on screen = step 3 key file missing.
