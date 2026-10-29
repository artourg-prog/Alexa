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
