package dev.straza.approver.shared.net

import dev.straza.approver.shared.protocol.SpkiPin
import dev.straza.approver.shared.security.CFScope
import dev.straza.approver.shared.security.cfScope
import dev.straza.approver.shared.security.toByteArray
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateRef
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustRef
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly

/**
 * SPKI pinning over SecTrust. Pinned: trust is decided only by whether some
 * certificate in the chain carries the pinned key; the system store is not
 * consulted. The matching certificate is then made the only anchor and trust
 * is re-evaluated, which keeps hostname and validity checks on without
 * bringing back the system roots. Unpinned: default handling.
 *
 * `SecKeyCopyExternalRepresentation` returns a bare key, so [SpkiDer]
 * reattaches the SPKI wrapper before hashing. The key shape is inferred from
 * the bytes (an EC point starts with 0x04, PKCS#1 with 0x30), not from
 * `SecKeyCopyAttributes`, which is a CFNumber-vs-CFString trap.
 */
@OptIn(ExperimentalForeignApi::class)
internal object DarwinSpkiPinning {

    sealed interface Verdict {
        data object Trusted : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /** Same message as Android's PinMismatchException. */
    const val PIN_MISMATCH = "server key does not match the pinned key"

    fun evaluate(trust: SecTrustRef, pin: SpkiPin): Verdict = cfScope {
        val chain = own(SecTrustCopyCertificateChain(trust))
            ?: return@cfScope Verdict.Rejected("empty certificate chain")
        val count = CFArrayGetCount(chain).toInt()
        if (count == 0) return@cfScope Verdict.Rejected("empty certificate chain")

        // The whole chain is checked, not only the leaf, so an operator can pin
        // a private CA and rotate leaf certificates without re-enrolling
        // every phone.
        var matched: SecCertificateRef? = null
        for (i in 0 until count) {
            @Suppress("UNCHECKED_CAST")
            val certificate = CFArrayGetValueAtIndex(chain, i.convert()) as SecCertificateRef? ?: continue
            val spki = spkiOf(certificate) ?: continue
            if (pin.matches(sha256(spki))) {
                matched = certificate
                break
            }
        }
        // A pin failure has no bypass. The message carries no key material or
        // host detail.
        val anchor = matched ?: return@cfScope Verdict.Rejected(PIN_MISMATCH)

        // Re-evaluate with the matched certificate as the only anchor: hostname,
        // validity and the chain to that anchor are checked, and the system
        // roots are not used. A self-signed leaf is a valid anchor; the chain
        // is then of length one.
        memScoped {
            val values = allocArray<COpaquePointerVar>(1)
            values[0] = anchor
            val anchors = own(
                CFArrayCreate(
                    kCFAllocatorDefault,
                    values,
                    1,
                    kCFTypeArrayCallBacks.ptr,
                ),
            )
            SecTrustSetAnchorCertificates(trust, anchors)
            SecTrustSetAnchorCertificatesOnly(trust, true)

            val error = alloc<CFErrorRefVar>()
            if (!SecTrustEvaluateWithError(trust, error.ptr)) {
                error.value?.let { own(it) }
                // The key matched but the platform rejected the chain around it
                // (expired, hostname mismatch). Still a refusal.
                return@cfScope Verdict.Rejected("anchored trust evaluation failed")
            }
        }
        Verdict.Trusted
    }

    private fun CFScope.spkiOf(certificate: SecCertificateRef): ByteArray? = memScoped {
        val key = own(SecCertificateCopyKey(certificate)) ?: return null
        val error = alloc<CFErrorRefVar>()
        val raw = own(SecKeyCopyExternalRepresentation(key, error.ptr))
            ?: run {
                error.value?.let { own(it) }
                return null
            }
        val bytes = raw.toByteArray()
        // An unrecognised key shape yields null and can never match a pin.
        SpkiDer.fromEcPoint(bytes)
            ?: bytes.takeIf { it.firstOrNull() == 0x30.toByte() }?.let(SpkiDer::fromRsaPkcs1)
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
        bytes.usePinned { data ->
            digest.usePinned { out ->
                CC_SHA256(
                    if (bytes.isEmpty()) null else data.addressOf(0),
                    bytes.size.convert(),
                    out.addressOf(0).reinterpret(),
                )
            }
        }
        return digest
    }
}
