# Sofia voice rewrite (unreleased)

The application id and signing identity stay unchanged. No runtime API keys, phone keys, queues or customer data belong in Git.

Build prerequisites:

1. Install Android SDK 36 and JDK 17+ (the existing Android Studio runtime is supported).
2. Install Android NDK 28.2.13676358 and CMake 3.22.1. The current native build targets the owner's ARM64 Fold (ARMv8.2 with FP16/dot-product). Run `python3 scripts/fetch-whisper.py` with Python 3.9+ for pinned MIT-licensed whisper.cpp v1.9.5 and the 57 MiB multilingual Base Q5_1 model. It is a bounded local backup wake check, not an external audio service.
3. Run `python3 scripts/fetch-voice-models.py` from `phone-app`. It downloads the unchanged official EN/RU Apache-2.0 archives and checks the pinned SHA-256 values. About 84 MiB of model data is packaged in the APK.
4. Supply the owner's private `app/src/main/assets/private-channel.json`, containing a `folder` field for the already existing private update channel. This file is ignored by Git. An emulator build without owner credentials can instead use `{"folder":"test-channel"}`. Never publish a test channel configuration.
5. `./gradlew testDebugUnitTest assembleDebug` builds the app using the Mac's original signing setup. Before publication independently compare the signing certificate with the installed app and the previous release.

Native fixture tests on macOS:

- `sh scripts/make-voice-fixtures.sh` creates synthetic EN/RU audio using locally installed Samantha/Milena voices, plus deterministic SNR 15 dB noise and a 30-second silent lead-in. Audio fixtures are ignored by Git and contain no shop recordings.
- `./gradlew assembleDebugAndroidTest` builds the test APK. Install the app and test APK only on the isolated emulator, then run `com.tailorshop.counter.test/androidx.test.runner.AndroidJUnitRunner`.
- Native ASR fixtures verify name/number recognition, negative speech, noise and silence. The UI scenario uses fake Reader responses and never sends a real SMS or changes shop data.

The optional semantic service is in `intent-worker`. Run `node intent-worker/worker.test.mjs`. Only a finite operational vocabulary and temporary symbolic N/W references leave the phone; entity maps remain private on-device. Server and phone enforce action guards independently. The classifier does not have the shop's write API credentials and cannot execute business actions. Do not trust a model's claim that an action happened.

The optional semantic service is not a release prerequisite. Its deployment is currently blocked by missing Workers script-write permission in the connected Cloudflare integration and a browser session requiring sign-in. The worker has not been deployed. No new provider token is embedded in the APK. After deployment, private `intent-config.json` provisioning through the existing authenticated update channel is required. The original telephone Workers are outside this task.

The public source intentionally excludes model blobs, synthesized audio and private channel/service configuration. Existing on-device runtime config files and atomic queues are preserved across an in-place update. Physical shop speech and installed voice gender remain device acceptance tests, not claims established by emulator tests.

The default listening mode is tap-to-talk. The app releases its screen-on request and microphone between conversations; it uses the phone's existing screen timeout, AOD and wake/unlock settings. Continuous hands-free operation remains a Settings choice. Real phone battery measurements are pending; do not claim emulator performance as measured battery savings.
