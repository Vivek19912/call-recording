package com.baba.callvault.system.storage

import org.junit.Assert.*
import org.junit.Test

class DialPhoneValidationTest {

    @Test
    fun `normalizes separators but preserves international plus`() {
        assertEquals("+918885638563", DialPhoneValidation.normalize("+91 88856 38563"))
        assertEquals("4155552671", DialPhoneValidation.normalize("(415) 555-2671"))
        // No country code is invented for ambiguous numbers.
        assertEquals("8885638563", DialPhoneValidation.normalize("8885638563"))
    }

    @Test
    fun `builds tel URIs for same-device dialing`() {
        assertEquals("tel:+918885638563", DialPhoneValidation.toTelUri("+918885638563"))
        assertEquals("tel:+14155552671", DialPhoneValidation.toTelUri("  +1 (415) 555-2671 "))
    }

    @Test
    fun `rejects empty placeholder and malformed numbers`() {
        listOf("", "   ", "n/a", "NA", "-", "test", "unknown", "0000000", "1111111111", "123", "+")
            .forEach {
                assertFalse("should reject: '$it'", DialPhoneValidation.isDialable(it))
                assertNull(DialPhoneValidation.toTelUri(it))
            }
        assertNull(DialPhoneValidation.toTelUri(null))
    }

    @Test
    fun `accepts valid international numbers`() {
        assertTrue(DialPhoneValidation.isDialable("+918885638563"))
        assertTrue(DialPhoneValidation.isDialable("+14155552671"))
        assertTrue(DialPhoneValidation.isDialable("9876543210"))
    }

    @Test
    fun `never produces ACTION_CALL URIs`() {
        // The dialer is always opened via ACTION_DIAL with a tel: URI;
        // this helper only mints tel: (prefill) values, never a call trigger.
        val uri = DialPhoneValidation.toTelUri("+14155552671")
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("tel:"))
    }
}
