plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.headunit"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.headunit"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "edition"
    productFlavors {
        create("lite") { dimension = "edition"; buildConfigField("boolean", "FULL", "false") }
        create("full") { dimension = "edition"; buildConfigField("boolean", "FULL", "true") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
