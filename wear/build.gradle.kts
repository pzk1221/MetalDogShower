plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production Galaxy Watch packages stay ARM-only. Screenshot/test builds can opt into the
// official x86_64 Wear emulator with -PwearTargetAbi=x86_64 without bloating delivery APKs.
val wearTargetAbi = providers.gradleProperty("wearTargetAbi").getOrElse("armeabi-v7a")

android {
    namespace = "com.panzhikun.metaldogshower.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.panzhikun.metaldogshower"
        minSdk = 30
        targetSdk = 37
        versionCode = 9
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += wearTargetAbi
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "backend"
    productFlavors {
        create("fake") {
            dimension = "backend"
            applicationIdSuffix = ".fake"
            versionNameSuffix = "-fake"
            buildConfigField("boolean", "USE_FAKE_BACKEND", "true")
        }
        create("real") {
            dimension = "backend"
            buildConfigField("boolean", "USE_FAKE_BACKEND", "false")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("optimized") {
            initWith(getByName("release"))
            // This is the production-optimized upgrade for the personally
            // installed debug application. Keeping the debug package/signing
            // identity lets Android preserve its encrypted provisioning data.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.wearable)

    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.wear.compose:compose-material:1.6.2")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-expression:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material3:1.4.2")

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    // Android's local JVM stubs do not implement JSONObject; use the real
    // implementation for the pure provisioning-schema migration tests.
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
