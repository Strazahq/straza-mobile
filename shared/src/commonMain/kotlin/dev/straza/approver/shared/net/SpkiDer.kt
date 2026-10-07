package dev.straza.approver.shared.net

/**
 * Rebuilds an X.509 SubjectPublicKeyInfo (SPKI) DER from the raw key forms
 * Apple's Security framework exports. The enrollment pin is `sha256(SPKI
 * DER)`, but `SecKeyCopyExternalRepresentation` returns a bare X9.63 point for
 * EC (`04‖X‖Y`) or PKCS#1 DER for RSA, so the SPKI wrapper has to be added
 * back before hashing.
 *
 * An unknown key shape returns null, which can never match a pin.
 */
object SpkiDer {

    /**
     * SPKI for an EC public key from its X9.63 uncompressed point. The curve
     * is inferred from the point length (P-256, P-384 or P-521). Compressed
     * points (02/03 prefix) are refused: Security framework always exports
     * uncompressed.
     */
    fun fromEcPoint(point: ByteArray): ByteArray? {
        if (point.isEmpty() || point[0] != UNCOMPRESSED_POINT) return null
        val curveOid = when (point.size) {
            65 -> OID_SECP256R1
            97 -> OID_SECP384R1
            133 -> OID_SECP521R1
            else -> return null
        }
        return sequence(sequence(OID_EC_PUBLIC_KEY + curveOid) + bitString(point))
    }

    /** SPKI for an RSA public key from its PKCS#1 `RSAPublicKey` DER. */
    fun fromRsaPkcs1(pkcs1: ByteArray): ByteArray =
        sequence(sequence(OID_RSA_ENCRYPTION + DER_NULL) + bitString(pkcs1))

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = content.size
        val lengthBytes = when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
            // A 4096-bit RSA PKCS#1 key is about 526 bytes, so two length
            // octets are enough.
            else -> byteArrayOf(0x82.toByte(), (length ushr 8).toByte(), length.toByte())
        }
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }

    private fun sequence(content: ByteArray): ByteArray = tlv(0x30, content)

    /** DER BIT STRING with zero unused bits, the form in which SPKI carries the key. */
    private fun bitString(content: ByteArray): ByteArray =
        tlv(0x03, byteArrayOf(0x00) + content)

    private const val UNCOMPRESSED_POINT = 0x04.toByte()

    // OIDs, pre-encoded (tag 0x06 + length + arcs).
    private val OID_EC_PUBLIC_KEY = bytes(0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01) // 1.2.840.10045.2.1
    private val OID_SECP256R1 = bytes(0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07) // 1.2.840.10045.3.1.7
    private val OID_SECP384R1 = bytes(0x06, 0x05, 0x2B, 0x81, 0x04, 0x00, 0x22) // 1.3.132.0.34
    private val OID_SECP521R1 = bytes(0x06, 0x05, 0x2B, 0x81, 0x04, 0x00, 0x23) // 1.3.132.0.35
    private val OID_RSA_ENCRYPTION =
        bytes(0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x01) // 1.2.840.113549.1.1.1
    private val DER_NULL = bytes(0x05, 0x00)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
