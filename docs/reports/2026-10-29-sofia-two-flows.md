# Sofia 0.32: two visible command examples and split speech context

Owner requested completion without Claude (unavailable), with all substantive work preserved in GitHub. Alexa Mac Builder review request was posted but no automatic start/acceptance is claimed. Coordinator implemented locally from978d87c/installed0.31.

- Idle clock/date now has two small English examples for Pickup (last-four and receipt lookup) and two for Daily Report (worker intro/save and save/send SMS). The examples are accepted by existing finish/lookup parsing.
- Added a bounded addressed conversation context so wake, approved-worker introduction and finish command can arrive as separate recognizer results.30s idle,60s hard cap; unrelated room text cannot extend it. Clear on action/pause/reply window. A new addressed worker intro can start during a previous finish follow-up window.
- Read-only question detection runs before finish intent: who did/finished, check/find and saved-status questions cannot become a scan. MainActivity also guards against a mutating recognition alternative when the primary phrase is a question.
- Numbered tailor questions answer the requested topic instead of repeating ready/payment status. Tailor field and Daily Report saved ownership remain distinct sources.
- Durable save-before-SMS question, yes/no/timeout behavior, quiet hours, retry/duplicate contracts, report API and existing settings remain.

Validation: local offline testDebugUnitTest assembleDebug passed,95 tests,0failures/errors;6new conversation/intent cases. APK0.32/code32,3,350,961bytes; SHA2560eacfc651050bc7a8d45f80fc31290f3081b63930b4ca8dace54861adfe1be6a. Original signer verified. No real scan, SMS or customer/payroll mutation was used for tests. No paid Actions.

No phone connected to adb during this session; no claim of installation, real speech acceptance or physical layout inspection. Existing private Wi-Fi feed publication and checksum/read-back outcomes are logged in coordination comments after completion. Previous network feed was0.26; prior0.27–0.31 were direct USB installations. Owner will check on the phone.

Not implemented by this change: male not-ready voice, separate10s not-ready card timeout, full new pickup-card redesign, or AI semantic model. Ambiguous mixed commands are not a general conversational dialogue engine; questions conservatively remain read-only.
