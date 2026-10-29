plugins {
    id("com.android.application")
}

android {
    namespace = "com.tailorshop.counter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tailorshop.counter"
        minSdk = 30
        targetSdk = 36
        versionCode = 32 // bumped by publish-phone-update.sh
        versionName = "0.32"
    }

    buildTypes {
        // Side-loaded debug build: `run-as` lets the Mac drop the key into private storage.
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for the queue-file tests (Android's is a stub on the JVM)
}
