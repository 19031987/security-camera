package com.securitycam.ui

import com.securitycam.core.config.RecipientValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite verifying ITU-T E.164 phone and RFC 5322 email recipient
 * validation logic utilized by SettingsActivity recipient chip editors.
 */
class RecipientChipValidationTest {

    @Test
    fun testValidE164PhoneNumbersAccepted() {
        val validPhones = listOf(
            "+12025550123",      // North America
            "+447911123456",     // United Kingdom
            "+4915123456789",    // Germany
            "+919876543210",     // India
            "+61412345678",      // Australia
            "+819012345678",     // Japan
            "+33612345678"       // France
        )

        for (phone in validPhones) {
            assertTrue("Expected valid E.164 phone: $phone", RecipientValidator.isValidE164(phone))
        }
    }

    @Test
    fun testInvalidPhoneNumbersRejected() {
        val invalidPhones = listOf(
            "",
            "   ",
            "1234567890",        // Missing leading '+'
            "+0123456789",       // Country codes cannot start with 0
            "+1-202-555-0123",   // Hyphens not allowed in strict E.164
            "+1 (202) 555-0123", // Parentheses/spaces not allowed
            "+12345",            // Too short (< 7 digits)
            "+1234567890123456", // Too long (> 15 digits)
            "phone_number",
            "+123abc456"
        )

        for (phone in invalidPhones) {
            assertFalse("Expected rejected phone: $phone", RecipientValidator.isValidE164(phone))
        }
    }

    @Test
    fun testValidRfc5322EmailsAccepted() {
        val validEmails = listOf(
            "security@example.com",
            "first.last@domain.org",
            "user+alerts@camera-home.net",
            "admin@subdomain.example.co.uk",
            "surveillance_123@sub.host-provider.io",
            "a@b.cd"
        )

        for (email in validEmails) {
            assertTrue("Expected valid email: $email", RecipientValidator.isValidEmail(email))
        }
    }

    @Test
    fun testInvalidEmailsRejected() {
        val invalidEmails = listOf(
            "",
            "   ",
            "plainaddress",
            "@missinguser.com",
            "missingdomain@",
            "user@domain",       // No TLD
            "user@.com",
            "user space@domain.com",
            "user@domain..com",
            "user@@domain.com"
        )

        for (email in invalidEmails) {
            assertFalse("Expected rejected email: $email", RecipientValidator.isValidEmail(email))
        }
    }

    @Test
    fun testChipBatchFilterAndDeduplication() {
        val rawPhones = listOf(
            "+12025550123",
            "invalid-phone",
            "+12025550123", // Duplicate
            "+447911123456",
            ""
        )
        val validUniquePhones = RecipientValidator.filterValidPhones(rawPhones).distinct()
        assertEquals(2, validUniquePhones.size)
        assertTrue(validUniquePhones.contains("+12025550123"))
        assertTrue(validUniquePhones.contains("+447911123456"))

        val rawEmails = listOf(
            "owner@example.com",
            "bad email",
            "owner@example.com", // Duplicate
            "security@sub.domain.org"
        )
        val validUniqueEmails = RecipientValidator.filterValidEmails(rawEmails).distinct()
        assertEquals(2, validUniqueEmails.size)
        assertTrue(validUniqueEmails.contains("owner@example.com"))
        assertTrue(validUniqueEmails.contains("security@sub.domain.org"))
    }
}
