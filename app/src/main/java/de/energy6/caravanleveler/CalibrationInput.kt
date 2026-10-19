package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Quaternion
import kotlin.math.sqrt

/** Accept decimal dots and commas; normalize in double precision before creating the quaternion. */
internal fun parseCalibrationInput(components: List<String>): Quaternion? {
    if (components.size != 4) return null
    val values = components.map {
        it.trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    }
    val scale = values.maxOf { kotlin.math.abs(it) }
    if (scale == 0.0) return null
    val scaled = values.map { it / scale }
    val length = sqrt(scaled.sumOf { it * it })
    val normalized = scaled.map { (it / length).toFloat() }
    return Quaternion(normalized[0], normalized[1], normalized[2], normalized[3])
}
