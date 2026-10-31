package com.securitycam.core.config

import java.util.regex.Pattern

/**
 * Validates international phone numbers (strict E.164 format) and email addresses (RFC 5322).
 * Provides normalizers, collection filters, and batch parsers.
 */
object RecipientValidator {

    // ITU-T E.164 standard: '+' followed by 1-9 country code and 6 to 14 subscriber digits (7 to 15 digits total)
    private val E164_REGEX = Pattern.compile("^\\+[1-9]\\d{6,14}$")

    // RFC 5322 compliant email regex with valid TLD requirement (2-15 alpha characters)
    private val EMAIL_REGEX = Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,15}$"
    )

    /**
     * Validates if [phone] strictly adheres to the international E.164 specification.
     */
    fun isValidE164(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val trimmed = phone.trim()
        return E164_REGEX.matcher(trimmed).matches()
    }

    /**
     * Strips whitespace, hyphens, parentheses, and dots. Converts leading '00' to '+'.
     */
    fun normalizePhoneNumber(phone: String?): String {
        if (phone.isNullOrBlank()) return ""
        var cleaned = phone.trim().replace(Regex("[\\s\\-\\(\\)\\.]"), "")
        if (cleaned.startsWith("00")) {
            cleaned = "+" + cleaned.substring(2)
        }
        return cleaned
    }

    /**
     * Validates if [email] is a valid RFC 5322 format.
     */
    fun isValidEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        val trimmed = email.trim()
        return EMAIL_REGEX.matcher(trimmed).matches()
    }

    /**
     * Filters a list of phone numbers, normalizing and keeping only valid E.164 entries without duplicates.
     */
    fun filterValidPhones(phones: Collection<String>?): List<String> {
        if (phones == null) return emptyList()
        return phones
            .map { normalizePhoneNumber(it) }
            .filter { isValidE164(it) }
            .distinct()
    }

    /**
     * Filters a list of email addresses, trimming and keeping only valid RFC 5322 entries without duplicates.
     */
    fun filterValidEmails(emails: Collection<String>?): List<String> {
        if (emails == null) return emptyList()
        return emails
            .map { it.trim() }
            .filter { isValidEmail(it) }
            .distinct()
    }

    /**
     * Parses a comma- or semicolon-separated string into validated phone numbers.
     */
    fun parseAndFilterPhones(rawInput: String?): List<String> {
        if (rawInput.isNullOrBlank()) return emptyList()
        val rawTokens = rawInput.split(Regex("[,;\\n]"))
        return filterValidPhones(rawTokens)
    }

    /**
     * Parses a comma- or semicolon-separated string into validated email addresses.
     */
    fun parseAndFilterEmails(rawInput: String?): List<String> {
        if (rawInput.isNullOrBlank()) return emptyList()
        val rawTokens = rawInput.split(Regex("[,;\\n]"))
        return filterValidEmails(rawTokens)
    }
}
