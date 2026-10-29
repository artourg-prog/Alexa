# Sofia 0.27: listening window and wake sound

Owner requested an immediate update of the connected phone after unreliable wake feedback and premature command cutoff.

Changes:
- Addressed listening and waiting-for-number windows are now 30 seconds instead of 8 seconds.
- Partial recognition extends the addressed window only for changed, relevant text, with a 60-second cap.
- Wake sound is also triggered by an addressed final recognition result when no active wake window exists. Partial and final events share the same helper to avoid double signalling within that window.
- Complete commands retain the existing immediate execution path. No added mandatory 30-second wait.
- Existing sound selection, microphone hours, recognizer busy backoff, finish/SMS confirmation, amounts, data and synchronization rules are unchanged.

Validation:
- Local offline testDebugUnitTest and assembleDebug succeeded: 76 tests, zero failures/errors.
- APK signer SHA-256 matches the established phone release certificate.
- Serial-targeted in-place installation returned Success. Device package inspection confirms version 0.27 / code 27. Main activity launched.

Limitations:
- Real speech with pauses and wake sound must still be checked by the owner.
- No claim that post-idle recognition reliability is fully resolved: OS recognizer busy delays and recognition-engine endpointing remain unchanged.
- This patch extends application context windows; it does not guarantee 30 seconds of continuous audio capture by the Android recognizer.
- No new AI service, no real SMS, no receipt edits. Private Wi-Fi feed has not been changed by this direct USB installation.

## Follow-up: Sofia 0.28

Owner confirmed the longer listening window held during a real speech test, then requested lookup results leave the screen after one minute instead of two. Changed only lookup `keepMs` to 60 seconds and matching rules; finish-result/SMS timers remain unchanged. All 76 local tests and debug build passed again. Device installation is recorded in the coordination follow-up after verification.

## Follow-up: Sofia 0.29

Owner requested immediate installation of two further changes:

- Recover recognizer sessions with missing callbacks. Monotonic deadlines: 15 seconds for initial readiness/final result after end-of-speech, 60 seconds for a lost active callback stream. Healthy silence with callbacks remains active. Recovery is gated by foreground, hands-free, listening hours, no TTS, and an existing active session; intentional busy backoff remains unchanged. Six unit scenarios cover startup stalls, one-hour healthy idle callback traffic, lost callbacks, post-speech deadline despite late RMS, stop/late callback disarming, and fresh session deadlines.
- Voice finish now durably queues a scan without SMS first, then asks by default: receipt digits + "SMS отправить? / Send SMS?". Explicit save-only commands skip the question. Yes requests SMS for the same event; no/timeout leave the scan intact. A failed local save cannot produce an SMS prompt. Scan announcements no longer interrupt the question. Negative button labelled No SMS. Actual tablet application still requires Reader/tablet acknowledgement. SMS uses the existing Reader, recipient logic, template and quiet-hours policy; no live SMS was sent for tests.

Initial local compilation failed due to an incorrectly placed watchdog hook; corrected before any installation. Final offline tests/build succeeded: 84 tests, zero failures/errors. Original signing certificate verified. Serial-targeted in-place installation returned Success; device package confirmed 0.29/code29; main activity launched. Real question/decline test and long-idle speech reliability are not yet confirmed by the owner. No Wi-Fi feed changes.
