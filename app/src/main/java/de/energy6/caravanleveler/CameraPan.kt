package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Vector3
import kotlin.math.tan

internal fun calculatePanMovement(
    pointerDeltaX: Float,
    pointerDeltaY: Float,
    distanceToPlane: Float,
    verticalFovDegrees: Float,
    viewportHeightPixels: Int,
    cameraRight: Vector3,
    cameraUp: Vector3
): Vector3? {
    if (
        !pointerDeltaX.isFinite() ||
        !pointerDeltaY.isFinite() ||
        !distanceToPlane.isFinite() ||
        distanceToPlane <= 0f ||
        !verticalFovDegrees.isFinite() ||
        verticalFovDegrees <= 0f ||
        verticalFovDegrees >= 180f ||
        viewportHeightPixels <= 0 ||
        cameraRight.lengthSquared() == 0f ||
        cameraUp.lengthSquared() == 0f
    ) {
        return null
    }

    val visibleHeight = 2f * distanceToPlane *
        tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0).toFloat()
    val worldUnitsPerPixel = visibleHeight / viewportHeightPixels
    val horizontalMovement = cameraRight.normalized().scaled(-pointerDeltaX * worldUnitsPerPixel)
    val verticalMovement = cameraUp.normalized().scaled(pointerDeltaY * worldUnitsPerPixel)
    return Vector3.add(horizontalMovement, verticalMovement).takeIf { it.isFinite() }
}

private fun Vector3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
