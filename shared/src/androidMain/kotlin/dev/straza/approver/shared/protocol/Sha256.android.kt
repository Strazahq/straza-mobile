package dev.straza.approver.shared.protocol

internal actual fun sha256(bytes: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
