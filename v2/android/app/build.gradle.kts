plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "fr.atelierdesmiracles.miracleklipper.v2"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.atelierdesmiracles.miracleklipper.v2"
        minSdk = 26
        targetSdk = 35
        versionCode = 20000
        versionName = "2.0.0-test.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
