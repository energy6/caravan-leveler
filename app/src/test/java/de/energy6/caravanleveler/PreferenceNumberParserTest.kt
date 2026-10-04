package de.energy6.caravanleveler

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PreferenceNumberParserTest {

    private fun formatter(locale: Locale) =
        (NumberFormat.getNumberInstance(locale) as DecimalFormat).apply {
            isGroupingUsed = false
        }

    @Test
    fun `accepts complete positive numbers with the locale decimal separator`() {
        assertEquals(2.1, formatter(Locale.US).parsePositiveNumber("2.10")?.toDouble())
        assertEquals(2.1, formatter(Locale.GERMANY).parsePositiveNumber("2,10")?.toDouble())
        assertEquals(0.5, formatter(Locale.US).parsePositiveNumber(" 0.5 ")?.toDouble())
    }

    @Test
    fun `rejects partial malformed and non-positive values`() {
        val formatter = formatter(Locale.US)

        listOf("", " ", "0", "-1", "2.10abc", "2,10", "1e3").forEach { value ->
            assertNull(formatter.parsePositiveNumber(value), "Expected '$value' to be rejected")
        }
    }

    @Test
    fun `rejects a decimal separator from a different locale`() {
        assertNull(formatter(Locale.GERMANY).parsePositiveNumber("2.10"))
    }
}
