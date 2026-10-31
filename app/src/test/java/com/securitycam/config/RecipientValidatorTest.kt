package com.securitycam.config

import com.securitycam.core.config.RecipientValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientValidatorTest {

    @Test
    fun testValidE164PhoneNumbers() {
        val validNumbers = listOf(
            "+12025550123",    // US
            "+447911123456",   // UK
            "+919876543210",   // India
            "+61491570156",    // Australia
            "+4915123456789",  // Germany
            "+819012345678"    // Japan
        )
        for (num in validNumbers) {
            assertTrue("Expected $num to be valid E.164", RecipientValidator.isValidE164(num))
        }
    }

    @Test
    fun testInvalidPhoneNumbers() {
        val invalidNumbers = listOf(
            "12025550123",         // Missing +
            "+0123456789",         // Starting with 0 after +
            "+1-202-555-0123",     // Punctuation (strict E.164)
            "+1234567890123456",   // 16 digits (exceeds max 15 digits)
            "+",                   // Just plus
            "",                    // Empty
            "   ",                 // Whitespace
            "not-a-number",        // Letters
            "+1"                   // Country code only, no subscriber digits
        )
        for (num in invalidNumbers) {
            assertFalse("Expected $num to be invalid E.164", RecipientValidator.isValidE164(num))
        }
    }

    @Test
    fun testNormalizePhoneNumber() {
        assertEquals("+12025550123", RecipientValidator.normalizePhoneNumber("+1 (202) 555-0123"))
        assertEquals("+447911123456", RecipientValidator.normalizePhoneNumber(" +44 7911 123456 "))
        assertEquals("+447911123456", RecipientValidator.normalizePhoneNumber("00447911123456"))
        assertEquals("+919876543210", RecipientValidator.normalizePhoneNumber("+91-98765.43210"))
    }

    @Test
    fun testValidEmailAddresses() {
        val validEmails = listOf(
            "owner@example.com",
            "security.guard@home-domain.org",
            "user+alert@gmail.com",
            "first.last@sub.domain.co.uk",
            "cam123@my-camera.net"
        )
        for (email in validEmails) {
            assertTrue("Expected $email to be valid RFC 5322", RecipientValidator.isValidEmail(email))
        }
    }

    @Test
    fun testInvalidEmailAddresses() {
        val invalidEmails = listOf(
            "plainaddress",
            "@missingusername.com",
            "username@.com",
            "username@domain..com",
            "username@domain",
            "username@domain.c",   // Single char TLD
            "username@",
            "",
            "   ",
            "user name@example.com"
        )
        for (email in invalidEmails) {
            assertFalse("Expected $email to be invalid RFC 5322", RecipientValidator.isValidEmail(email))
        }
    }

    @Test
    fun testFilterValidPhonesAndDeduplication() {
        val raw = listOf(
            "+1 (202) 555-0123",
            "invalid",
            "+12025550123",   // Duplicate normalized
            "+447911123456",
            ""
        )
        val filtered = RecipientValidator.filterValidPhones(raw)
        assertEquals(2, filtered.size)
        assertEquals("+12025550123", filtered[0])
        assertEquals("+447911123456", filtered[1])
    }

    @Test
    fun testFilterValidEmailsAndDeduplication() {
        val raw = listOf(
            "test@example.com",
            "invalid-email",
            "test@example.com ",
            "admin@domain.org"
        )
        val filtered = RecipientValidator.filterValidEmails(raw)
        assertEquals(2, filtered.size)
        assertEquals("test@example.com", filtered[0])
        assertEquals("admin@domain.org", filtered[1])
    }

    @Test
    fun testParseCommaAndSemicolonSeparatedInputs() {
        val rawPhones = "+12025550123, +447911123456; +919876543210\ninvalid"
        val parsedPhones = RecipientValidator.parseAndFilterPhones(rawPhones)
        assertEquals(3, parsedPhones.size)

        val rawEmails = "a@b.com, c@d.org; e@f.net\nbad"
        val parsedEmails = RecipientValidator.parseAndFilterEmails(rawEmails)
        assertEquals(3, parsedEmails.size)
    }
}
