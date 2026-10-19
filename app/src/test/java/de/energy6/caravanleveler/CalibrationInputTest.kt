package de.energy6.caravanleveler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CalibrationInputTest {
    @Test
    fun `signed decimal commas and dots are normalized without changing component order`() {
        val q = requireNotNull(parseCalibrationInput(listOf("-1,0", "2.0", " 3 ", "4")))
        val length = kotlin.math.sqrt(30.0).toFloat()
        assertEquals(-1f / length, q.x, 1e-6f)
        assertEquals(2f / length, q.y, 1e-6f)
        assertEquals(3f / length, q.z, 1e-6f)
        assertEquals(4f / length, q.w, 1e-6f)
    }

    @Test
    fun `zero nonfinite missing and malformed components cannot overwrite calibration`() {
        listOf(
            listOf("0", "0", "0", "0"), listOf("NaN", "0", "0", "1"),
            listOf("Infinity", "0", "0", "1"), listOf("1e400", "0", "0", "1"),
            listOf("0", "", "0", "1"), listOf("1,2.3", "0", "0", "1"),
            listOf("1", "0", "0")
        ).forEach { assertNull(parseCalibrationInput(it), it.toString()) }
    }

    @Test
    fun `very large and small finite inputs retain their rotation`() {
        for (scale in listOf("1e300", "1e-300")) {
            val q = requireNotNull(parseCalibrationInput(listOf(scale, "0", "0", scale)))
            assertEquals(kotlin.math.sqrt(0.5f), q.x, 1e-6f)
            assertEquals(q.x, q.w)
            assertEquals(0f, q.y)
            assertEquals(0f, q.z)
        }
    }
}
