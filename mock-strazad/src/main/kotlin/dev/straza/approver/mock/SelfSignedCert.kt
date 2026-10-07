package dev.straza.approver.mock

import java.io.File
import java.security.KeyStore

/**
 * Self-signed P-256 certificate for the mock, generated with the JDK's
 * `keytool` because the JDK exports no API for building an X.509 certificate.
 * The SAN covers localhost and 127.0.0.1: the app verifies the hostname even
 * when it pins, so the certificate has to match the host it is reached on.
 */
internal object SelfSignedCert {

    const val ALIAS = "mock-strazad"

    fun loadOrCreate(file: File, password: CharArray): KeyStore {
        if (!file.exists()) generate(file, password)
        return KeyStore.getInstance("PKCS12").apply {
            file.inputStream().use { load(it, password) }
        }
    }

    private fun generate(file: File, password: CharArray) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val cmd = listOf(
            keytool, "-genkeypair",
            "-alias", ALIAS,
            "-keyalg", "EC",
            "-groupname", "secp256r1",
            "-sigalg", "SHA256withECDSA",
            "-dname", "CN=localhost,O=Straza Mock,C=SK",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-validity", "30",
            "-keystore", file.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", String(password),
            "-keypass", String(password),
        )

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed to create the mock certificate:\n$output" }
    }
}
