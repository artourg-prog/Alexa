plugins { id("com.android.application") }
android {
 namespace="com.tailorshop.sofiaclock"
 compileSdk=36
 defaultConfig {
  applicationId="com.tailorshop.sofiaclock"
  minSdk=30
  targetSdk=36
  versionCode=1
  versionName="0.1"
  testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
 }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("junit:junit:4.13.2")
}
