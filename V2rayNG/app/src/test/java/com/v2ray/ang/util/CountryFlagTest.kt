package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CountryFlagTest {

    @Test
    fun aTwoLetterCodeGivesItsFlagInEitherCase() {
        assertEquals("🇩🇪", CountryFlag.emojiOf("DE"))
        assertEquals("🇩🇪", CountryFlag.emojiOf(" de "))
        assertEquals("🇺🇸", CountryFlag.emojiOf("us"))
    }

    @Test
    fun anythingElseGivesNoFlag() {
        assertNull(CountryFlag.emojiOf(null))
        assertNull(CountryFlag.emojiOf(""))
        assertNull(CountryFlag.emojiOf("D"))
        assertNull(CountryFlag.emojiOf("DEU"))
        assertNull(CountryFlag.emojiOf("Germany"))
        assertNull(CountryFlag.emojiOf("1A"))
    }

    @Test
    fun theExitLabelShowsWhatIsKnown() {
        assertEquals("🇩🇪 DE  1.2.3.4", CountryFlag.exitLabel("de", "1.2.3.4"))
        assertEquals("Germany  1.2.3.4", CountryFlag.exitLabel("Germany", " 1.2.3.4 "))
        assertEquals("1.2.3.4", CountryFlag.exitLabel(null, "1.2.3.4"))
        assertEquals("🇩🇪 DE", CountryFlag.exitLabel("DE", ""))
        assertNull(CountryFlag.exitLabel(" ", null))
    }
}
