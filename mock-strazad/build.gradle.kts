plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.straza.approver.mock.MainKt")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // HTTP, TLS and crypto come from the JDK. zxing-core, which the app
    // already uses, only encodes the enrollment QR.
    implementation(libs.zxing.core)
    // JUnit 4, whose artifacts are already in the dependency-verification metadata.
    testImplementation(libs.kotlin.test)
}
