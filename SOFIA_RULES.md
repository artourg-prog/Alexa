# Sofia — the counter assistant on the Fold: rule book

The single source of truth for how counter Sofia behaves (Tailor Counter app, `phone-app/`). The code must match
this file; when Art changes a rule, this file is rewritten first. "Counter Sofia" is not the Grok phone agent
on the shop line ("phone Sofia").

## 1. Wake

- **What wakes her:** her name **opening the phrase**: "София, …", "Sofia, …". One call word before the name is
  allowed: hey / hi / ok / так / вот / ну / слушай / а / привет. Spellings: Sofia, Sophia, София, Софья, Софи.
- **What does not wake her:**
  - her name mid-sentence ("на стороне Софии…", "для Софи…");
  - forms used when talking *about* her ("Софии", "Софию");
  - anything without her name.
- **Listening screen:** the moment her name is heard (while the person is still speaking), the screen turns
  **bright blue**: "Sofia" + "Listening… · Слушаю…" + the hint. The wake sound plays, including when recognition supplies only a final result. Complete commands execute immediately; the window is not a mandatory delay.
- **How long:**
  - 30 s from the last new recognition that is *for her*, never more than **60 s** after her name;
  - after her name, a following phrase holds her only if it contains a **number** (or the exact Voice Finish
    answer she is waiting for, section 3);
  - no number → she goes quietly back (no voice).
- **Only her name** ("София?"): she waits 30 s quietly; nothing comes → back to the clock, nothing said.
- **Polite words** (please / пожалуйста / будь добра / could you / can you) are ignored inside a command.
- "Sofia, thank you" / "София, спасибо" → "You're welcome!" / "Пожалуйста!".
- "Sofia, who are you?" → "I'm Sofia, the counter assistant." / "Я София, помощница на приёмке."
- "Sofia, can you hear me?" / "София, ты меня слышишь?" → "Yes, I'm listening." / "Да, слушаю."

## 2. Lookup / pickup flow

- **Command:** "Sofia, find / look for / check / search / where is / show me … <number>",
  "София, найди / поищи / посмотри / проверь / покажи / глянь / где … <number>", or just "Sofia, <number>".
  The number is the **last 4 phone digits**, a **5-digit receipt #**, or a full phone number. Receipts said in
  parts ("26 279", "262-79", "26.314") are joined; several receipts in one phrase stay separate.
- **Answer:** one natural sentence, **in the language of the question**:
  - "Receipt 2 6 3 1 5 is ready for pickup, there is a balance due." /
    "Квитанция 2 6 3 1 5 готова к выдаче, не оплачено."
  - It's paid / balance due / store account / nothing when the payment is unknown.
  - Never "already picked up"; **never the client's phone or name aloud**.
- **Screen:**
  - the receipt number big (**red, pulsing** when there is a balance due);
  - under it: customer · phone, ready / not finished, the Picked up state;
  - "Sofia + hint" at the top;
  - several results rotate every 10 s (swipe to flip);
  - each result stays **1 minute**.
- **Follow-up questions** for 15 s after an answer, with no name needed, about the receipt on screen, each in the
  question's language:
  - who was the tailor / кто мастер;
  - how much / сколько;
  - is it paid / оплачено;
  - when was it dropped off / когда сдали;
  - what was the work / что делали.
  - **No promised date:** the pickup sheet has no such column.
- **A bare number in those 15 s** = another lookup.
- **Picked up** (lookup flow only):
  - one button per receipt, visible as long as that receipt is on screen;
  - tap → **5 s Undo** → the receipt and its button leave;
  - one request to the Pickups web app, and the tablet applies it on an exact single match.
  - **Truthful states:** saved on the phone / sent, waiting for the tablet / confirmed by the tablet /
    NOT confirmed — check on the tablet.
  - Nothing unconfirmed is ever dropped. Problems stay on screen (amber) until the tablet answers or someone taps
    "Checked".

## 3. Finish flow (Voice Finish)

- **Command:** "София, это <name>, запиши / готово / закончил / сохрани <receipt(s)> [отправь сообщение]",
  "Sofia, this is <name>, done / finished / save <receipt(s)> [send the message]".
- **Name:**
  - required, and matched to the **exact Approved Workers name**: Andrei, Artour, Carolina, Emma, Tsegi, Tumur
    (Андрей / Andrey → Andrei, Артур / Arthur → Artour, one typo allowed);
  - missing or unclear → she asks only **"Кто это? / Who is this?"** and never guesses;
  - a name that isn't on the list → "Не знаю такого имени. Кто это?" once, then she lets it go.
- **First words to a tailor that day:** "Доброе утро, Andrei." (before noon) / "Здравствуй, Andrei.".
- **Receipt:**
  - always sent to the Daily Report Reader, which checks the **master**;
  - not there → **UNVERIFIED** "Waiting: receipt not in the sheet yet" (kept, not lost);
  - the phone's pickup list only gives the Total on screen ("total unknown" if it's missing);
  - several receipts in one phrase = separate lines.
- **Recording** = exactly a QR scan: Daily Report Reader `addScan` (Sofia's own key), **no amount**. The tablet
  allocates as for a scan, and Art fixes splits on the tablet.
- **Voice Finish:** first persist the scan event locally without SMS, then ask for each receipt: **"2 6 3 1 6. SMS отправить? / 2 6 3 1 6. Send SMS?"**. Only a durable event can receive a question. Tablet acknowledgement still determines when the record is actually applied.
- **Explicit save-only** ("без SMS", "just save", "просто запиши") skips the SMS question.
- **The answer window is 30 s**:
  - yes / да / tap Send → request SMS for the same saved event;
  - no / нет / No SMS or silence → preserve the record, no SMS;
  - a different number starts a separate corrected event; it cannot undo an event already sent to the tablet.
- Scan status announcements do not interrupt an open SMS question.
- **"отправь сообщение" on its own** within 30 s of a save → a read-back for that saved receipt, then the SMS for
  the same event (no second save).
- **A bare number** within 30 s of a finish → the **same tailor** ("Next number → saved for Andrei").
- **The same tailor + receipt again** → "already saved", with no second scan.
- **Statuses**, only from the Reader's answers:
  - "Queued — waiting for the tablet";
  - "Waiting: receipt not in the sheet yet";
  - "Held for owner review";
  - **"Saved for Andrei"**, only once the **Daily Report tablet app is open** and has applied it. Until then it
    stays "Queued — waiting for the tablet";
  - "Already saved";
  - "Not saved: <reason>".
- **SMS** via the existing `pickup {choice:"now"}` only:
  - waiting / held (quiet hours) / not confirmed / not sent;
  - **"SMS sent" + its sound only when the state is SENT and the finish is still on screen.** A text held
    overnight that goes out at 8 AM is recorded silently, with no sound at an empty counter.
- **On screen 30 s** after its last change (always while a question is open), then back to the floating screen.
  Later changes (e.g. the SMS sent in the morning) are tracked silently.
- **The customer's name is not shown or spoken** in this flow.

## 4. The two flows never mix

- A finish phrase (запиши / готово / done …) only ever runs the finish flow: no lookup card, no Picked up
  button, no "picked up?".
- A find phrase only ever runs the lookup flow, and only that flow shows Picked up.
- A new lookup clears the finish screen; a new finish clears lookup results.

## 5. Sounds

- They are the Fold's own Samsung notification sounds, played on the **media volume** (same as her voice), quiet.
- Each event has its own picker in Settings; tap = preview.
  - Wake (her name heard): **Crystal**. Art asked for "Sequence", which is not on this Fold.
  - SMS sent: **Crescent**.
  - Picked up tap: **Voyager**.
  - The choices: Crescent, Chaos, Crystal, Voyager, Red Dwarf, Milky Way, Off.
- **Mutes:** while the counter app is open, the **system** and **notification** volumes are muted, so the speech
  service's own start tone and keyboard / touch sounds are silent. They are restored when the app is left.

## 6. Screen

- **Idle:**
  - only **"Sofia"** (pen-style script) + the clock + the date (MM/DD/YYYY);
  - the block **bounces slowly** around the screen (burn-in protection), at normal brightness.
- **Night mode** (shop closed 19:30–10:45, Los Angeles):
  - black screen, mic off, the phone sleeps;
  - it wakes itself at 10:45 (full-screen alarm);
  - a tap wakes it for 5 min;
  - **test only:** while plugged into the Mac with USB debugging, night mode is off ("TEST: always on (USB)").
- **Always listening:** no on/off button. A small **⚙ gear** (top corner, **hold 3 s**) opens Settings: the
  version, the sound pickers and Update now.
- **Updates** install themselves over Wi-Fi at 8:00 AM (or Update now). Keys and queues are kept.
- **Never on screen:**
  - room talk (only her own phrase, or a number / expected answer while she waits);
  - the client's name in the finish flow.

## 7. Languages and greeting

- She understands English and Russian, and **answers in the language she was asked in** (Russian voice for
  Russian), using the phone's default voice (Google).
- Greeting: in the finish flow, the first time a tailor speaks to her that day (lookups don't identify a tailor).

## 8. Decided (were open)

- Lookup result time: **1 minute** (owner requested October 29, after testing the listening update).
- A receipt not on the phone's pickup list: the finish is still sent, and the Reader decides against the master
  (UNVERIFIED if it's not there). This replaces the earlier phone-side refusal.

## 9. Open conflicts — Art, please choose

1. **Client name and phone on the lookup screen:** the card shows "customer · phone" under the number (the
   original design). Later rules say never *say* them, and the finish flow doesn't *show* the name. Should the
   lookup screen keep showing them?
2. **Voices:** an earlier rule said a female voice for paid and a male voice for balance due. Today there is one
   voice per language (Google), and a free natural voice (Piper) is still to be tested. Keep that rule?
3. **Wake sound "Sequence":** it isn't on this Fold. Crystal is used. Is there another name to use?
4. **Follow-up windows:** lookups give 15 s for a bare number / question; finishes give 30 s. Make them the same?
5. **"да" from someone else:** during the 30 s "верно?" window, anyone's "да" confirms the SMS (the buttons are
   the safe path). Accept as is, or require the tap?

## Recognition recovery (October 29)

A listening session with no callbacks recovers automatically: 15 seconds for initial readiness or a final result after speech end, 60 seconds for lost ongoing callbacks. Silence with continuing callbacks is healthy. Recovery only runs in foreground hands-free mode, during allowed listening hours and outside spoken replies. Existing busy backoff is preserved. This does not guarantee every wake word is recognized.

## Saved-report inquiry (October 29)

An addressed question mentioning Daily Report and checking/saved/recorded routes to a read-only saved-report query before finish or pickup lookup. Require one full receipt number. Return saved worker(s) and dates; unavailable is never not-recorded. Unsaved tablet entries are not covered. A separate Saved in Daily Report sound is configurable and plays on an ADDED transition, never ACCEPTED/queued or an ALREADY result.

## Two command hints and safe intent separation (October 29 follow-up)

Idle screen has two small English examples per function beneath the date: Pickup (check last four / find receipt) and Daily Report (worker intro + save receipt / save and send SMS). Examples must be accepted by the parser.

Questions (including who did/finished, check, saved status) cannot enter finish/record or SMS confirmation, including when another recognition alternative contains a finish word. A numbered tailor question uses that receipt's tailor field and stays read-only. This is distinct from saved-report ownership.

Preserve wake and approved-worker introduction across separate recognition results for30seconds, hard cap60seconds. Room talk does not renew context; cleared after action, activity pause or entering an answer window. No identity guess without an approved-worker intro. Existing SMS yes/no confirmation and durable save-first behavior remain.
