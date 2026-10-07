package dev.straza.approver.shared.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Reference vectors from keys generated with openssl (`openssl ec/rsa -pubout
 * -outform DER`). The raw forms are the bytes `SecKeyCopyExternalRepresentation`
 * returns for the same keys: the X9.63 point for EC, the PKCS#1 block for RSA.
 * One wrong header byte would make every iOS pin fail to match.
 */
class SpkiDerTest {

    // openssl ecparam -name prime256v1: full SPKI, and its 65-byte point.
    private val p256Spki = (
        "3059301306072a8648ce3d020106082a8648ce3d030107034200041dff4d1c1bc707e9" +
            "dac2508b378a8a517435b7a224ed076ede359a6ec2dd4f1b2440e8e0927bcb733208c4" +
            "39825f728919455ae2bc583d01a16352f1e7696758"
        ).hex()
    private val p256Point = p256Spki.copyOfRange(p256Spki.size - 65, p256Spki.size)

    // openssl ecparam -name secp384r1: full SPKI, and its 97-byte point.
    private val p384Spki = (
        "3076301006072a8648ce3d020106052b81040022036200044a9eb4b5bde0692ec72f2c" +
            "2d4f517a343dd4b70e75b8d7bbac07d2e02725f7290feb08fb58fa34f09438523d8d47" +
            "32665b9cc988b9648b634c87bdfbe55add5d9393914b4c6fb0503492783bb0fdbd4d35" +
            "c49d0ad38eac842301d596ab7d60be"
        ).hex()
    private val p384Point = p384Spki.copyOfRange(p384Spki.size - 97, p384Spki.size)

    // openssl genrsa 2048: full SPKI; the PKCS#1 block is the BIT STRING
    // payload after the fixed 24-byte SPKI header (30 82 01 22 / 30 0d alg /
    // 03 82 01 0f 00).
    private val rsa2048Spki = (
        "30820122300d06092a864886f70d01010105000382010f003082010a0282010100adcb" +
            "7556497253567af4ef0440d21d45f410ea398392ffde3231724390d5b038f74a0ec057" +
            "aba29c8f3d66097f006c240052678af05d3e8428a249c45d33f600e3a32e67db118eb7" +
            "58d312860ed0a61098dfad0e2ae262ca9e526c4ea209dffb3f995b737003baafad2129" +
            "687c17af11102a133b973760ce13db2e6fd04e7a536d1273d1405cd29838d8a0613637" +
            "daf7f1fc71d07e9099eb7006fcbd9729490bf217c1bac39a717b93f1b94265a6e1e09e" +
            "4c4a5216d79c10ee6b94d6447405ad60c6d304a652684521ca2b22b89eee299187168a" +
            "3b054df326155868b9fe90ba8235650687178fa6469b5d90f0f93d1a017a1f7b48d830" +
            "bde5fc8ba30c1aa41b0203010001"
        ).hex()
    private val rsa2048Pkcs1 = rsa2048Spki.copyOfRange(24, rsa2048Spki.size)

    @Test
    fun p256PointReassemblesToItsSpki() {
        assertContentEquals(p256Spki, SpkiDer.fromEcPoint(p256Point))
    }

    @Test
    fun p384PointReassemblesToItsSpki() {
        assertContentEquals(p384Spki, SpkiDer.fromEcPoint(p384Point))
    }

    @Test
    fun rsa2048Pkcs1ReassemblesToItsSpki() {
        assertContentEquals(rsa2048Spki, SpkiDer.fromRsaPkcs1(rsa2048Pkcs1))
    }

    @Test
    fun unknownShapesFailClosed() {
        // Compressed point prefix: not something a live SecKey exports.
        assertNull(SpkiDer.fromEcPoint(byteArrayOf(0x03) + ByteArray(64)))
        // Lengths off by one on either side of a real curve size.
        assertNull(SpkiDer.fromEcPoint(byteArrayOf(0x04) + ByteArray(63)))
        assertNull(SpkiDer.fromEcPoint(byteArrayOf(0x04) + ByteArray(65)))
        assertNull(SpkiDer.fromEcPoint(ByteArray(0)))
    }

    private fun String.hex(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
