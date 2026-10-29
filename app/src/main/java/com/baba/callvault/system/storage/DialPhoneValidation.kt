package com.baba.callvault.system.storage

/**
 * Click-to-dial phone validation — mirrors
 * `src/lib/dial/validation.ts` in WhatsApp Max.
 *
 * Rules: preserve a valid international `+` prefix, never invent a
 * country code, reject empty / placeholder / malformed values.
 * Pure — unit tested without Android.
 */
object DialPhoneValidation {

    private val PLACEHOLDERS = setOf(
        "n/a", "na", "nil", "null", "undefined", "none",
        "-", "--", "---", "test", "unknown", "tbd"
    )

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var value = raw.trim()
        value = Regex("""\s*(ext\.?|x)\d+\s*$""", RegexOption.IGNORE_CASE)
            .replace(value, "").trim()
        value = value.split(";").firstOrNull()?.trim().orEmpty()
        val hasPlus = value.startsWith("+")
        val digits = value.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (hasPlus) "+$digits" else digits
    }

    fun isPlaceholder(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        if (PLACEHOLDERS.contains(trimmed.lowercase())) return true
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return true
        if (digits.all { it == digits[0] }) return true
        if ((digits.startsWith("12345") || digits.startsWith("01234")) && digits.length <= 10) return true
        return false
    }

    fun isDialable(raw: String?): Boolean {
        if (isPlaceholder(raw)) return false
        val digits = normalize(raw).filter { it.isDigit() }
        if (digits.length < 7 || digits.length > 15) return false
        if (!digits.first().isDigit() || digits.first() == '0') return false
        return true
    }

    /** `tel:` URI for ACTION_DIAL, or null when not dialable. */
    fun toTelUri(raw: String?): String? {
        if (!isDialable(raw)) return null
        return "tel:${normalize(raw)}"
    }
}
