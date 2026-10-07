package dev.straza.approver.shared.protocol

/**
 * SHA-256 for the protocol layer's message formats, currently the reason line
 * of the decide message. `java.security.MessageDigest` on Android,
 * CommonCrypto on iOS.
 */
internal expect fun sha256(bytes: ByteArray): ByteArray
