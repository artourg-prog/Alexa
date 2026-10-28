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
        versionCode = 2 // bumped by publish-phone-update.sh
        versionName = "0.2"
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
}
