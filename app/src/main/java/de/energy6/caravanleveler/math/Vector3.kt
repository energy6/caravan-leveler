package de.energy6.caravanleveler.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/** Immutable three-dimensional vector used by the application domain. */
class Vector3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    constructor(other: Vector3) : this(other.x, other.y, other.z)

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun length(): Float = sqrt(lengthSquared())

    fun normalized(): Vector3 {
        val squaredLength = lengthSquared()
        if (almostEqual(squaredLength, 0f)) return zero()
        if (squaredLength == 1f) return this
        return scaled((1.0 / sqrt(squaredLength.toDouble())).toFloat())
    }

    fun scaled(factor: Float): Vector3 = Vector3(x * factor, y * factor, z * factor)

    fun negated(): Vector3 = Vector3(-x, -y, -z)

    override fun equals(other: Any?): Boolean =
        other is Vector3 && equals(this, other)

    override fun hashCode(): Int {
        var result = x.toBits()
        result = 31 * result + y.toBits()
        result = 31 * result + z.toBits()
        return result
    }

    override fun toString(): String = "[x=$x, y=$y, z=$z]"

    companion object {
        fun add(lhs: Vector3, rhs: Vector3): Vector3 =
            Vector3(lhs.x + rhs.x, lhs.y + rhs.y, lhs.z + rhs.z)

        fun subtract(lhs: Vector3, rhs: Vector3): Vector3 =
            Vector3(lhs.x - rhs.x, lhs.y - rhs.y, lhs.z - rhs.z)

        fun dot(lhs: Vector3, rhs: Vector3): Float =
            lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z

        fun cross(lhs: Vector3, rhs: Vector3): Vector3 = Vector3(
            lhs.y * rhs.z - lhs.z * rhs.y,
            lhs.z * rhs.x - lhs.x * rhs.z,
            lhs.x * rhs.y - lhs.y * rhs.x
        )

        fun angleBetweenVectors(lhs: Vector3, rhs: Vector3): Float {
            val combinedLength = lhs.length() * rhs.length()
            if (almostEqual(combinedLength, 0f)) return 0f
            val cosine = (dot(lhs, rhs) / combinedLength).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cosine).toDouble()).toFloat()
        }

        fun equals(lhs: Vector3, rhs: Vector3): Boolean =
            almostEqual(lhs.x, rhs.x) &&
                almostEqual(lhs.y, rhs.y) &&
                almostEqual(lhs.z, rhs.z)

        fun zero(): Vector3 = Vector3()
        fun one(): Vector3 = Vector3(1f, 1f, 1f)
        fun forward(): Vector3 = Vector3(0f, 0f, -1f)
        fun back(): Vector3 = Vector3(0f, 0f, 1f)
        fun up(): Vector3 = Vector3(0f, 1f, 0f)
        fun down(): Vector3 = Vector3(0f, -1f, 0f)
        fun right(): Vector3 = Vector3(1f, 0f, 0f)
        fun left(): Vector3 = Vector3(-1f, 0f, 0f)

        private fun almostEqual(lhs: Float, rhs: Float): Boolean {
            val difference = abs(lhs - rhs)
            if (difference <= 1e-10f) return true
            return difference <= max(abs(lhs), abs(rhs)) * Math.ulp(1f)
        }
    }
}
