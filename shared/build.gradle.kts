plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Compose Multiplatform 1.11.1 with AGP 9.3.0 registers
// `copyAndroidDeviceTestComposeResourcesToAndroidAssets` without configuring
// its `outputDirectory`, which fails the instrumented-test APK build. This
// module ships no `composeResources`, so the task is disabled for that variant.
// Re-check on the next Compose Multiplatform upgrade.
tasks.matching { it.name.contains("AndroidDeviceTestComposeResources") }
    .configureEach { enabled = false }

// Forward -Pstraza.live.* to the host-test JVM for StrazaLiveServerTest, which
// skips when they are absent.
tasks.withType<Test>().configureEach {
    for (key in listOf("straza.live.baseUrl", "straza.live.pin", "straza.live.enrollToken")) {
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        // expect/actual classes are still Beta (KT-61573); this flag silences
        // the warning.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidLibrary {
        namespace = "dev.straza.approver.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        withHostTestBuilder {}

        // Instrumented tests: the Keystore cannot be faked on the JVM, so
        // anything asserting hardware backing runs on a device.
        //
        // Do not set `sourceSetTreeName = "test"` here. It would put commonTest
        // on the device, where dexing rejects it: DEX below version 040
        // (API 30+) forbids spaces in method names, and the host tests use
        // backtick names with spaces.
        withDeviceTestBuilder {}
            .configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    // iOS targets: device and Apple-silicon simulator only, because Compose
    // Multiplatform 1.11 publishes no iosX64 artifacts. Kotlin/Native compiles
    // these on a Linux or Windows host without Xcode; linking the framework and
    // running `:shared:iosSimulatorArm64Test` need a Mac.
    // The static framework is consumed by the `embedAndSignAppleFrameworkForXcode`
    // build phase in iosApp/. `baseName` is the Swift import name.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StrazaShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // Dev-only fake server, for test source sets only. See settings.gradle.kts.
            implementation(project(":mock-strazad"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}
