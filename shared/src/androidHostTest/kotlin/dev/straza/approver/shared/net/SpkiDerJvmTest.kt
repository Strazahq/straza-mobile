package dev.straza.approver.shared.net

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Generates fresh keys on every run and checks against `java.security`'s own
 * SPKI encoding. [SpkiDerTest] holds the fixed vectors.
 */
class SpkiDerJvmTest {

    @Test
    fun ecP256MatchesJavaEncoding() {
        val spki = ecSpki("secp256r1")
        assertContentEquals(spki, SpkiDer.fromEcPoint(bitStringPayload(spki)))
    }

    @Test
    fun ecP384MatchesJavaEncoding() {
        val spki = ecSpki("secp384r1")
        assertContentEquals(spki, SpkiDer.fromEcPoint(bitStringPayload(spki)))
    }

    @Test
    fun rsaMatchesJavaEncodingAcrossSizes() {
        for (bits in intArrayOf(2048, 4096)) {
            val spki = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(bits) }.generateKeyPair().public.encoded
            assertContentEquals(spki, SpkiDer.fromRsaPkcs1(bitStringPayload(spki)))
        }
    }

    private fun ecSpki(curve: String): ByteArray =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair().public.encoded

    /**
     * Extracts the BIT STRING payload of an SPKI, the raw form
     * `SecKeyCopyExternalRepresentation` returns (X9.63 point for EC, PKCS#1
     * for RSA). A minimal reader for these tests, not a DER library.
     */
    private fun bitStringPayload(spki: ByteArray): ByteArray {
        var i = 0
        fun header(): Pair<Int, Int> {
            val tag = spki[i].toInt() and 0xFF
            i++
            var length = spki[i].toInt() and 0xFF
            i++
            if (length >= 0x80) {
                val octets = length and 0x7F
                length = 0
                repeat(octets) {
                    length = (length shl 8) or (spki[i].toInt() and 0xFF)
                    i++
                }
            }
            return tag to length
        }

        val (outer, _) = header()
        check(outer == 0x30) { "not a SEQUENCE" }
        val (alg, algLen) = header()
        check(alg == 0x30) { "no AlgorithmIdentifier" }
        i += algLen
        val (bitString, bsLen) = header()
        check(bitString == 0x03) { "no BIT STRING" }
        check(spki[i].toInt() == 0) { "unused bits present" }
        return spki.copyOfRange(i + 1, i + bsLen)
    }
}
