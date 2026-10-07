package dev.straza.approver.shared.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
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
