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
        versionCode = 1
        versionName = "0.1"
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
