# Tailor Shop skill — paste steps for Art

The sheet share is done. Do these in order.

**1. Words** — Build tab → **Interaction Model** → **JSON Editor** → select all, delete → paste `interactionModels/custom/en-US.json` → **Save** → **Build skill**. Wait for "Build successful".

**2. Code** — **Code** tab → folder `lambda`:
- `index.js` → select all, delete → paste ours
- `package.json` → select all, delete → paste ours
- **New File** → type `lambda/lookup.js` → paste ours
- **New File** → `lambda/sheets.js` → paste ours
- **New File** → `lambda/screen.js` → paste ours
- **New File** → `lambda/apl.json` → paste ours

**3. Key** — **New File** → `lambda/sheet-key.json` → paste the key text from the Mac builder. Nowhere else.

**4. Save + Deploy** — **Save** → **Deploy**. Wait for "Deployment successful".

**5. Try it** — **Test** tab (Development) → type `ask tailor shop 4417` (a real client's last 4). Two or three at once: `ask tailor shop 4417 and 2222`.
Screen: receipt number big, customer name and full phone under it. Alexa only says how many.

If the screen says "Sheet key not set up yet" → redo step 3. "Can't reach the sheet" → tell the builder.

**Don't click Publish** until ChatGPT has reviewed.
