package com.v2ray.ang.util

/**
 * Griffin: the flag of a country as an emoji, from its ISO 3166-1 alpha-2 code: the two regional indicator symbols
 * that fonts draw as the flag together.
 */
object CountryFlag {

    private const val REGIONAL_INDICATOR_A = 0x1F1E6

    /**
     * The flag of [countryCode], two letters in either case with spaces around them allowed; null when it is no such
     * code, as a country's full name some IP services give in its place.
     */
    fun emojiOf(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase() ?: return null
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
        return code.map { String(Character.toChars(REGIONAL_INDICATOR_A + (it - 'A'))) }.joinToString("")
    }

    /**
     * How a connection's exit is shown: its flag and code, when [country] is a code, and the [ipAddress], in that
     * order; what is missing is left out, and null when both are.
     */
    fun exitLabel(country: String?, ipAddress: String?): String? {
        val place = country?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            emojiOf(name)?.let { "$it ${name.uppercase()}" } ?: name
        }
        val ip = ipAddress?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(place, ip).joinToString("  ").ifEmpty { null }
    }
}
