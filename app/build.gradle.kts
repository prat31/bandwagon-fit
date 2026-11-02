import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Injects the build ID Crashlytics requires at startup; without it every build crashes on launch.
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "fit.bandwagon"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "fit.bandwagon"
        minSdk = 28
        targetSdk = 36
        // Every Play upload needs a higher versionCode: `make bundle VERSION_CODE=…` or CI passes
        // -PversionCode; local builds keep 1.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Host the dev build dials for the Auth/Firestore/Functions emulators. The Android emulator
        // reaches the host machine as 10.0.2.2; a physical device uses adb reverse (127.0.0.1) or a
        // Tailscale IP. Override with -PemulatorHost=<ip>.
        val emulatorHost = (findProperty("emulatorHost") as String?) ?: "10.0.2.2"
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            // Local emulators by default; `-PfirebaseEmulators=false` talks to the real bandwagon-dev project.
            val useEmulators = (findProperty("firebaseEmulators") as String?)?.toBoolean() ?: true
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", useEmulators.toString())
            manifestPlaceholders["crashlyticsEnabled"] = false
        }
        create("prod") {
            dimension = "env"
            buildConfigField("boolean", "USE_FIREBASE_EMULATORS", "false")
            manifestPlaceholders["crashlyticsEnabled"] = true
        }
    }

    // Play upload key, from a git-ignored keystore.properties (storeFile, storePassword, keyAlias,
    // keyPassword) or the same names as BANDWAGON_* environment variables in CI. Without either,
    // release builds are unsigned; Play App Signing holds the real app key.
    val signingProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signing(name: String): String? = signingProps.getProperty(name) ?: System.getenv("BANDWAGON_${name.uppercase()}")
    val uploadStore = signing("storeFile")
    if (uploadStore != null) {
        signingConfigs.create("upload") {
            storeFile = rootProject.file(uploadStore)
            storePassword = signing("storePassword")
            keyAlias = signing("keyAlias")
            keyPassword = signing("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(17)
}

// Each flavor has its own project's config in src/<flavor>/google-services.json. The plugin fails
// without them, so fresh clones still build against the local emulators until they are added.
if (file("src/dev/google-services.json").exists() && file("src/prod/google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:health"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":core:notifications"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:groups"))
    implementation(project(":feature:challenges"))
    implementation(project(":feature:home"))
    implementation(project(":feature:health"))
    implementation(project(":feature:you"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
