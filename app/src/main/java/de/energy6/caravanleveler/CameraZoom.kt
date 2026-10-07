package de.energy6.caravanleveler

private const val MIN_VERTICAL_FOV_DEGREES = 30f
private const val MAX_VERTICAL_FOV_DEGREES = 160f

internal fun calculateZoomFov(
    currentVerticalFovDegrees: Float,
    previousPointerDistance: Float,
    currentPointerDistance: Float
): Float {
    val scale = 1f + (
        (previousPointerDistance - currentPointerDistance) / 100f
    ).coerceIn(-0.1f, 0.1f)
    return (currentVerticalFovDegrees * scale).coerceIn(
        MIN_VERTICAL_FOV_DEGREES,
        MAX_VERTICAL_FOV_DEGREES
    )
}
