package dev.straza.approver.shared.push

/**
 * Structural check for a bare RFC 4122 UUID string: 36 characters, dashes at
 * 8-13-18-23, hex everywhere else, case-insensitive, nothing around it.
 *
 * Gates navigation from a notification tap. The tap's `ref` is untrusted, and
 * the server only mints bare UUIDs as request ids. A ref that fails this check
 * may still trigger a fetch but does not steer the UI. Version and variant
 * bits are not checked. Whether the ref names anything real is decided only by
 * matching it against ids the server returns.
 */
object BareUuid {

    private const val LENGTH = 36
    private val DASHES = intArrayOf(8, 13, 18, 23)

    fun isValid(value: String): Boolean {
        if (value.length != LENGTH) return false
        for (i in 0 until LENGTH) {
            val c = value[i]
            val expectDash = i in DASHES
            val ok = if (expectDash) c == '-' else
                (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!ok) return false
        }
        return true
    }
}
