package dev.straza.approver.shared.security

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.posix.memcpy

/**
 * Manual CoreFoundation reference counting for the iOS security layer.
 *
 * Every Create/Copy result that should be freed is handed to [CFScope.own],
 * and [cfScope] releases them all on exit, on success or exception. CF
 * objects a SecItem or SecKey call needs (dictionary keys and values, bytes
 * for `SecItemAdd`) are created in the same scope and stay alive for the
 * call. A `+1` reference that must outlive the scope, such as one the caller
 * returns, is not owned here; its caller releases it.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CFScope {
    private val owned = ArrayList<CFTypeRef?>()

    /** Tracks a CF object created with a +1 reference so [cfScope] releases it. */
    fun <T : CPointer<*>?> own(ref: T): T {
        if (ref != null) owned.add(ref)
        return ref
    }

    fun releaseAll() {
        for (ref in owned) CFRelease(ref)
        owned.clear()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> cfScope(block: CFScope.() -> R): R {
    val scope = CFScope()
    try {
        return scope.block()
    } finally {
        scope.releaseAll()
    }
}

/** Copies Kotlin bytes into an owned `CFData`. An empty array is allowed. */
@OptIn(ExperimentalForeignApi::class)
internal fun CFScope.cfData(bytes: ByteArray): CFDataRef? = own(
    if (bytes.isEmpty()) {
        CFDataCreate(kCFAllocatorDefault, null, 0)
    } else {
        bytes.usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.convert())
        }
    },
)

/** Copies a `CFData` into Kotlin bytes. Does not consume a reference to the receiver. */
@OptIn(ExperimentalForeignApi::class)
internal fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val start = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return ByteArray(length).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), start, length.convert()) }
    }
}

/** Creates an owned `CFString` from a Kotlin string. */
@OptIn(ExperimentalForeignApi::class)
internal fun CFScope.cfString(value: String): CFStringRef? = own(
    CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8),
)

/** Creates an owned `CFNumber` from a Kotlin int. */
@OptIn(ExperimentalForeignApi::class)
internal fun CFScope.cfNumber(value: Int): CFNumberRef? = own(
    memScoped {
        val box = alloc<IntVar>()
        box.value = value
        CFNumberCreate(kCFAllocatorDefault, kCFNumberSInt32Type, box.ptr)
    },
)

/**
 * Creates an owned `CFDictionary` from constant `kSec*` keys and CF values.
 * The standard CF type callbacks make the dictionary retain its entries and
 * release them when it is freed at the end of the scope. An entry with a null
 * value is left out.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFScope.cfDictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val dictionary = CFDictionaryCreateMutable(
        kCFAllocatorDefault,
        entries.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    for ((key, value) in entries) {
        if (value != null) CFDictionaryAddValue(dictionary, key, value)
    }
    own(dictionary)
    // CFMutableDictionaryRef to CFDictionaryRef: the same pointer, reinterpreted
    // as the read-only type the SecItem and SecKey calls take.
    return dictionary?.reinterpret()
}

/**
 * Releases an out-parameter `CFError` and returns its code as a suffix for an
 * exception message, or "" when no error was set. Nulls the var so a reused
 * error slot is not released twice.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFErrorRefVar.describe(): String {
    val error = this.value ?: return ""
    val code = CFErrorGetCode(error)
    CFRelease(error)
    this.value = null
    return " (CFError code $code)"
}
