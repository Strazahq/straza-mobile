package dev.straza.approver.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `Retry-After` is untrusted input: only a non-negative delta-seconds value counts. */
class RetryAfterTest {

    @Test
    fun `delta seconds parse`() {
        assertEquals(120, parseRetryAfterSeconds("120"))
        assertEquals(7, parseRetryAfterSeconds(" 7 "))
        assertEquals(0, parseRetryAfterSeconds("0"))
    }

    @Test
    fun `anything else is null so the default backoff applies`() {
        assertNull(parseRetryAfterSeconds(null))
        assertNull(parseRetryAfterSeconds(""))
        assertNull(parseRetryAfterSeconds("-1"))
        assertNull(parseRetryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(parseRetryAfterSeconds("soon"))
    }
}
