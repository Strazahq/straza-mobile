package dev.straza.approver.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server is Go, whose RFC3339Nano marshaling emits timestamps with and
 * without fractional seconds. `NSISO8601DateFormatter` treats those as
 * different configurations.
 */
class Rfc3339IosTest {

    @Test
    fun parsesPlainRfc3339() {
        assertEquals(1_785_232_800L, parseRfc3339EpochSeconds("2026-07-28T10:00:00Z"))
    }

    @Test
    fun parsesFractionalSeconds() {
        assertEquals(1_785_232_800L, parseRfc3339EpochSeconds("2026-07-28T10:00:00.123456789Z"))
    }

    @Test
    fun parsesNumericOffsets() {
        assertEquals(1_785_232_800L, parseRfc3339EpochSeconds("2026-07-28T12:00:00+02:00"))
    }

    @Test
    fun refusesGarbageAndBlank() {
        assertNull(parseRfc3339EpochSeconds(""))
        assertNull(parseRfc3339EpochSeconds("yesterday-ish"))
        assertNull(parseRfc3339EpochSeconds("2026-07-28"))
    }
}
