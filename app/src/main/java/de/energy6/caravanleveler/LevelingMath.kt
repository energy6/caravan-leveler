package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.*
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared by the live display and reproducible diagnostic snapshots. Distances are in metres. */
internal fun calculateLeveling(corrected: Quaternion, width: Float, length: Float): LevelerCaravanState {
    val headingLength = sqrt(corrected.w * corrected.w + corrected.z * corrected.z)
    val compass = if (headingLength > 1e-6f) {
        Quaternion(0f, 0f, -corrected.z / headingLength, corrected.w / headingLength)
    } else {
        Quaternion.identity()
    }
    val rotation = compass * corrected
    val orientation = corrected.toOrientation()
    return LevelerCaravanState(
        rotation = rotation,
        position = Vector3.back() * abs(sin(rotation.toOrientation().y.toRadians())) * 0.16f,
        compass = compass,
        axis = sin(orientation.y.toRadians()) * width * 100f,
        stabilizer = -sin(orientation.x.toRadians()) * length * 100f
    )
}
