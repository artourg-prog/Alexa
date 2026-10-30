# Sofia — counter assistant rules, rewrite 0.42

Owner-approved replacement of the phone voice/UI implementation. This document supersedes the previous Android SpeechRecognizer session-loop rules. Existing package, signing identity, private configuration files and durable delivery queues are retained. The application is the counter assistant, not the separate business-line telephone agent.

## Wake, sleep and visible listening

- Default is **tap to talk**, as requested after the owner reported near-total battery drain during a working day. Wake the phone with its existing Samsung gesture/unlock behavior, or tap Listen while Sofia is visible. A visible daytime resume starts a conversation. System Always On Display and double-tap settings remain phone settings; the app does not simulate AOD or claim those settings were configured remotely.
- The wake chime for a tap/resume waits for the local microphone/decoder to report readiness. The listening background and microphone status distinguish preparing, listening, speaking, processing and off.
- Microphone capture stops between conversations and on activity pause. The screen is held awake only for an active conversation, speech, visible result cards or pickup confirmation. Afterwards Android's existing screen timeout is allowed; no new device-admin/accessibility permissions are required and no lock credentials are bypassed.
- An optional Settings mode retains continuous foreground listening during shop hours. In that mode the wake name is detected locally. No microphone listening takes place while the screen/activity is asleep.
- The recognized partial/final words remain visible while the shop screen is awake. They are not discarded between ASR segments. No ambient audio recordings are saved.
- New speech extends a 30-second silence window; there is no 8-second or 60-second hard conversation cutoff while new words continue. Decoder segment boundaries do not end the dialogue. Short digit fragments are joined while a number is explicitly awaited. Incomplete-command speech prompts wait for continued speech rather than immediately interrupting the speaker.
- Speech output is excluded from recognition. After speech, decoders are recreated over resident acoustic models rather than reusing reset native lattices. Microphone errors recover in foreground; night and tap-idle states do not start a recovery loop.
- Shop hours remain 10:45–19:30 America/Los_Angeles. A manual Listen tap gives temporary testing access outside hours; the existing USB-debug test exception remains. Tap mode cancels automatic screen-opening alarms and does not reopen the screen after reboot/update. Existing private Wi-Fi update jobs remain.

## Two business flows

### Customer lookup and explicit pickup

- Natural EN/RU variants of find/check/look up/search/show/посмотри/проверь/найди are read-only. A bare number after waking is a safe lookup, never an implicit work recording. Last-four labels are excluded from the number itself.
- Search accepts four phone-ending digits, one five/six-digit receipt or a full phone. Uncertain or incomplete numbers cause clarification. A six-digit receipt is never silently truncated into phone-ending digits.
- Cards show receipt, customer, amount, readiness and payment. Unpaid digits are red; paid digits are green. Both use a black background. The receipt number is the dominant auto-sized element; supporting text is compact during dialogue; clock, date and microphone diagnostic text are hidden. Say “Not paid / Не оплачено”, never the old debt wording. Never speak customer names or phone numbers aloud.
- Not-ready cards expire after 10 seconds; ready cards after 60 seconds. Multiple cards can be swiped in stable order. Expiry of another card does not change the selected receipt. Card expiry never confirms pickup.
- Pickup requires its explicit button and retains the existing five-second Undo. The request captures that exact receipt even if another card is browsed. Durable outbox, tablet acknowledgement and visible unresolved-warning safeguards remain.
- Only “not ready” is assigned the male voice. Payment and other responses use the female selection. Android voice gender is not portable metadata: actual installed voices must be checked on the owner's phone, with Settings voice selection available.
- Read-only follow-ups last 15 seconds. Ambient completion statements after a lookup cannot record work without a new wake/Listen session.

### Finished work and optional SMS

- “This is [worker] … finished receipt [number]”, “закончил работу”, “запиши в Daily Report” and “просто запиши” are recording intents. Approved worker identity survives separate speech segments; missing/ambiguous identity is requested, never invented. The executor requires one complete five/six-digit receipt; multiple or incomplete numbers are clarified.
- First durably save one scan event locally, using the existing Daily Report Reader contract and stable idempotency identity. No amount is invented or sent: allocation remains on the tablet.
- Only successful local persistence allows “Record queued. Send SMS? / Запись в очереди. SMS отправить?”
- Yes requests SMS for that same event. No or 30 seconds of silence retains the work record without SMS. Explicit “just save / просто запиши / без SMS” skips the question. A question, another receipt number or unrelated shop speech is not SMS approval.
- Reader quiet hours and SMS policy remain unchanged. Queueing is not delivery. Only tablet ADDED acknowledgement is called saved, and only SENT is called sent. The corresponding configurable sounds use those acknowledgements and persistent announcement identities, not optimistic UI success.
- Repeating the same worker/receipt on the same day reuses the durable event rather than creating duplicate work/SMS ids. Storage failure cannot start an undurable SMS request. Corrupt outbox data is preserved for review and never silently treated as an empty healthy queue.

## Questions, clarification and privacy

- “Who did/finished/made this receipt?”, “Под кем записан?”, and saved/recorded/report questions are **read-only saved-report inquiries**. A missing number can arrive in later segments without changing the question into a work record or readiness lookup.
- Answers come from the existing Reader saved-report index, with worker/date. Unavailable is not “not recorded”. Unsaved in-progress tablet entries are outside that endpoint's coverage; readiness alone never proves report bookkeeping.
- Lookup cards and a new work-recording screen are separated. Changing a spoken command does not undo a record already durably accepted by the tablet.
- “Sofia, can you hear me?”, identity and thanks retain short local responses. This is a shop-task assistant, not unrestricted open-domain conversation.
- Streaming EN/RU recognition and backup wake verification run locally. No raw audio or real names/numbers are sent to a new AI service. Optional Cloudflare semantic code exists but is undeployed/unconfigured and is not a release dependency. It can only suggest guarded intents; it cannot execute shop actions.
- Private keys/channel configuration/model blobs are excluded from public Git. Existing API contracts and runtime permissions are preserved. No production SMS, report writes, pickups or customer-record changes are used for automated tests.

## Acceptance boundaries

Emulator tests establish software behavior with synthesized speech and fake delivery transports. Real shop noise, recognition accuracy, target-phone TTS gender, Samsung AOD/double-tap behavior, battery drain and actual Wi-Fi installation still require device observation. No claim of unlimited conversation, perfect hearing or a guaranteed battery-life improvement follows from emulator tests.

## 0.41 interaction follow-up

Wake/resume and Listen immediately trigger a source refresh. A lookup arriving during that refresh waits for its result instead of speaking stale readiness. A cache miss rereads the same cloud mirror once and retries automatically; a failed read retains the last good copy and explicitly labels it cached/offline. A missing result still shows and reads back the exact searched digits, clearing any previous customer's card. This does not manufacture data the tablet has not yet published.

The animated script Sofia name distinguishes listening, processing and speaking; it responds gently to microphone energy only during active listening. Animation stops in idle/night/background and respects the system animation setting. The gear and Sofia name both open Settings only after a continuous three-second hold; tap, movement, release, detach or backgrounding cancels entry. No openly clickable Settings text remains. Pickup remains its existing exact-receipt confirmation API, separate from SMS, and never directly deletes a source-sheet row.

The visible heard line uses digits for EN/RU spoken number runs (including partial results), preserving other original words. Raw recognition text remains the input to action parsing; formatting is not an accuracy claim or a correction to uncertain digits.

## 0.42 clean communication board

Listen is at the upper left and the three-second hold settings gear at the upper right; the Sofia name retains its hold entry. Heard words appear in turquoise without the technical Heard prefix. Clock, date and microphone diagnostics are hidden, while offline order-source warnings remain visible. A full-width green Confirm Pickup button, at least 104dp tall, sits at the bottom and names the selected receipt; the existing durable confirmation and five-second Undo flow are retained. The top controls remain accessible outside shop hours for the temporary manual Listen session. A short tap on the free visible idle board restarts listening without waiting for AOD; action controls, drags and long holds retain their own behavior. Recognition accuracy and wake-name detection were not changed by this UI release.
