package de.energy6.caravanleveler

import java.text.NumberFormat
import java.text.ParsePosition

internal fun NumberFormat.parsePositiveNumber(text: String): Number? {
    val candidate = text.trim()
    if (candidate.isEmpty()) return null

    val position = ParsePosition(0)
    val parsed = parse(candidate, position) ?: return null
    val value = parsed.toDouble()

    return parsed.takeIf {
        position.errorIndex < 0 &&
            position.index == candidate.length &&
            value.isFinite() &&
            value > 0.0
    }
}
