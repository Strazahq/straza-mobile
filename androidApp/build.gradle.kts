import java.util.Properties

// AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android
// alongside it is an error (https://kotl.in/gradle/agp-built-in-kotlin).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    // On the classpath only. Applied at the bottom when a google-services.json
    // exists, so the foss flavor and a clean checkout need no Google config.
    alias(libs.plugins.google.services) apply false
}

// Release signing comes from keystore.properties or the STRAZA_RELEASE_*
// environment variables, never from the repository. Without it the release
// build is left unsigned, so a clean checkout still assembles.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSecret(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: providers.environmentVariable(envKey).orNull

val releaseStoreFile = signingSecret("storeFile", "STRAZA_RELEASE_STORE_FILE")
val releaseStorePassword = signingSecret("storePassword", "STRAZA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingSecret("keyAlias", "STRAZA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingSecret("keyPassword", "STRAZA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "dev.straza.approver"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Frozen at the first store upload. The namespace, the package
        // directories (dev.straza.approver) and the Keystore alias scheme do
        // not match it: none of them is store-frozen or user-visible.
        applicationId = "ai.straza.approver"

        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 28
        versionName = "0.1.27"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // rootProject.file, because keystore.properties is read from the
                // repository root and a relative storeFile must resolve there too.
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 28 does not need the malleable v1 (JAR) scheme, whose
                // META-INF signature files also hurt reproducibility.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds install beside a release build and are visibly distinct.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            // The build would otherwise record the commit id of the checkout
            // inside the APK, which an F-Droid build from the public tree cannot
            // reproduce.
            vcsInfo {
                include = false
            }
        }
    }

    // Two flavors. `foss` is UnifiedPush plus polling with no Google
    // dependencies, for F-Droid and GrapheneOS. `play` adds FCM for the Play
    // Store. Only the push transport differs (src/foss, src/play).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            // `.foss` lets the F-Droid build install beside the store build.
            applicationIdSuffix = ".foss"
        }
        create("play") {
            dimension = "distribution"
            // No suffix: the store build carries the canonical applicationId.
        }
    }

    buildFeatures {
        compose = true
        // For the in-app version line. AGP 9 defaults this off.
        buildConfig = true
    }

    // AGP embeds a Google-signed dependency-metadata blob in the APK. It is
    // signed at build time, which defeats reproducible builds, and F-Droid
    // requires it off.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        sarifReport = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    // QR enrollment.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(compose.runtime)
    implementation(compose.ui)

    // UnifiedPush (Apache-2.0, no Google) is in both flavors, so a Play build
    // pointed at a self-hosted strazad can receive push without Google. FCM is
    // play-only: the foss APK must stay free of Google dependencies.
    implementation(libs.unifiedpush.connector)
    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.messaging)
    // Embedded FCM distributor: a fallback, and play-only so the foss flavor
    // stays free of Google dependencies. Its c2dm receiver shares the APK with
    // firebase-messaging (see the play manifest). EmbeddedDistributorReceiver
    // is not subclassed, so the library's default gateway = null applies and
    // no third-party relay sits in the approval path.
    "playImplementation"(libs.unifiedpush.embedded.fcm)
}

// Applied only when the config is present. Without google-services.json (a
// clean checkout, the foss flavor, CI without the secret) the play flavor
// still compiles and FCM stays inert. The file is git-ignored.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    // The plugin registers a process<Variant>GoogleServices task for every
    // variant. The foss ones are disabled: the foss application ids are not in
    // the Firebase project, so the task would fail, and the foss APK must
    // carry no Google config resources.
    tasks.configureEach {
        if (name.startsWith("processFoss") && name.endsWith("GoogleServices")) {
            enabled = false
        }
    }
}
