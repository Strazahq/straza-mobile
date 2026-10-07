package dev.straza.approver.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReasonValidationTest {

    @Test
    fun `null empty and whitespace drafts mean no reason`() {
        assertNull(ReasonValidation.forWire(null))
        assertNull(ReasonValidation.forWire(""))
        assertNull(ReasonValidation.forWire("   "))
        assertNull(ReasonValidation.forWire(" \n \t "))
    }

    @Test
    fun `forWire trims the draft`() {
        assertEquals("ok", ReasonValidation.forWire("  ok  "))
        assertEquals("two\nlines", ReasonValidation.forWire("two\nlines\n"))
    }

    @Test
    fun `newline and tab are legitimate prose`() {
        ReasonValidation.requireValid("line one\nline two\ttabbed")
    }

    @Test
    fun `other control characters are refused`() {
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("a\u0000b") }
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("a\rb") }
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("a\u001Bb") } // ESC
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("a\u007Fb") } // DEL
    }

    /** The set comes from the server's rules for a decision reason. */
    @Test
    fun `the 14 invisible formatting code points are refused`() {
        val rejected = charArrayOf(
            '\u202A',
            '\u202B',
            '\u202C',
            '\u202D',
            '\u202E',
            '\u2066',
            '\u2067',
            '\u2068',
            '\u2069',
            '\u200E',
            '\u200F',
            '\u061C',
            '\u200B',
            '\uFEFF',
        )
        for (c in rejected) {
            assertFailsWith<IllegalArgumentException>("U+${c.code.toString(16).uppercase()} must be refused") {
                ReasonValidation.requireValid("a${c}b")
            }
        }
    }

    /** The set is not all of category Cf: emoji sequences and Persian and Indic
     *  orthography need the joiners, and U+2060 is outside it. */
    @Test
    fun `zwj zwnj and word joiner stay legal`() {
        ReasonValidation.requireValid("family: 👨‍👩‍👧")
        ReasonValidation.requireValid("می‌خواهم") // ZWNJ, Persian orthography
        ReasonValidation.requireValid("a\u2060b") // WORD JOINER, outside the set
    }

    @Test
    fun `empty and untrimmed reasons are not signable forms`() {
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("") }
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid(" x") }
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("x\n") }
    }

    @Test
    fun `byte cap counts utf8 bytes not characters`() {
        ReasonValidation.requireValid("x".repeat(500))
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("x".repeat(501)) }
        // "š" is two bytes in UTF-8, so 250 of them are at the cap.
        ReasonValidation.requireValid("š".repeat(250))
        assertFailsWith<IllegalArgumentException> { ReasonValidation.requireValid("š".repeat(251)) }
    }

    @Test
    fun `counter measures bytes per script`() {
        assertEquals(5, ReasonValidation.utf8ByteLength("abcde"))
        assertEquals(2, ReasonValidation.utf8ByteLength("š"))
        assertEquals(4, ReasonValidation.utf8ByteLength("😀")) // one emoji, four bytes
        assertEquals(0, ReasonValidation.utf8ByteLength(""))
    }
}
