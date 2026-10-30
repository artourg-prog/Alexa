# Sofia Clock

Separate small Android clock app, package `com.tailorshop.sofiaclock`, launcher label **Sofia Clock**. It does not replace or modify the Samsung Always On Display service. It has only the INTERNET permission: no microphone, camera, location, contacts, shop credentials, orders or report API access.

The owner's photo is the layout reference: large dim `София` above a nearly edge-to-edge bold time; compact Las Vegas weather at upper left; date below; small battery percentage. Background is pure black. Window brightness is 8% during display/preview and 2% outside the window. The composition shifts slightly each minute; this reduces persistent edges but does not guarantee prevention of OLED burn-in.

When the app is open on external power, the screen stays awake **10:45–20:00 America/Los_Angeles**. External power remains sufficient when Samsung battery protection stops charging at its limit. Off power or after hours, the normal Android screen timeout can sleep the phone. There are no wake alarms, background timers or automatic morning launch: open the clock at the counter each working day. Initial launcher creation gives a five-minute preview even after hours, then releases screen-on. To restart that preview later, hold the clock and select Preview for five minutes. Leaving the app clears its screen-on request immediately.

Tap opens the existing Sofia counter app. Android Back returns to the clock. Hold opens a small menu: open Sofia, preview for five minutes, exit. It is a normal dim-screen app, not low-power native AOD. Main-activity landscape orientation uses Android 16's documented compatibility opt-out; no global display rotation or device security settings are changed.

Weather comes from [NWS KLAS station observations](https://api.weather.gov/stations/KLAS/observations/latest), refreshed no more than once per 15 foreground minutes. It is a Las Vegas airport observation, not a measurement at the shop. The [NWS API](https://www.weather.gov/documentation/services-web-api) is public and needs no API key or precise phone location. The latest valid observation is cached; observations older than three hours are marked earlier readings. Missing/invalid values are not invented. Date/time use the Las Vegas/Los Angeles timezone and the phone clock.

## Build

Use JDK 17+ and Android SDK 36. This subproject deliberately shares only the repository's existing Gradle wrapper, not the counter app's speech models or private configurations:

```sh
ANDROID_HOME=/path/to/android-sdk JAVA_HOME=/path/to/jdk ../phone-app/gradlew -p . testDebugUnitTest assembleDebug assembleDebugAndroidTest --offline
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Emulator UI runner: `com.tailorshop.sofiaclock.test/androidx.test.runner.AndroidJUnitRunner`.

Version 0.1 is locally signed and side-loaded. Actual screen power draw, heat, distance readability and long-term display effects require target-device observation.
