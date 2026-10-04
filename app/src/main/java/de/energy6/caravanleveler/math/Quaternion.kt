package de.energy6.caravanleveler.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable, normalized Hamilton quaternion using the right-hand rule. */
class Quaternion private constructor(components: Components) {
    val x: Float = components.x
    val y: Float = components.y
    val z: Float = components.z
    val w: Float = components.w

    constructor() : this(Components(0f, 0f, 0f, 1f))

    constructor(x: Float, y: Float, z: Float, w: Float) :
        this(normalize(x, y, z, w))

    constructor(other: Quaternion) : this(other.x, other.y, other.z, other.w)

    fun normalized(): Quaternion = Quaternion(x, y, z, w)

    fun inverted(): Quaternion = Quaternion(-x, -y, -z, w)

    override fun equals(other: Any?): Boolean =
        other is Quaternion && almostEqual(dot(this, other), 1f)

    override fun hashCode(): Int {
        var result = w.toBits()
        result = 31 * result + x.toBits()
        result = 31 * result + y.toBits()
        result = 31 * result + z.toBits()
        return result
    }

    override fun toString(): String = "[x=$x, y=$y, z=$z, w=$w]"

    private data class Components(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float
    )

    companion object {
        private const val SLERP_THRESHOLD = 0.9995f

        fun identity(): Quaternion = Quaternion()

        fun multiply(lhs: Quaternion, rhs: Quaternion): Quaternion = Quaternion(
            lhs.w * rhs.x + lhs.x * rhs.w + lhs.y * rhs.z - lhs.z * rhs.y,
            lhs.w * rhs.y - lhs.x * rhs.z + lhs.y * rhs.w + lhs.z * rhs.x,
            lhs.w * rhs.z + lhs.x * rhs.y - lhs.y * rhs.x + lhs.z * rhs.w,
            lhs.w * rhs.w - lhs.x * rhs.x - lhs.y * rhs.y - lhs.z * rhs.z
        )

        fun rotateVector(rotation: Quaternion, vector: Vector3): Vector3 {
            val axis = Vector3(rotation.x, rotation.y, rotation.z)
            val axisDotVector = Vector3.dot(axis, vector)
            val axisDotAxis = Vector3.dot(axis, axis)
            return axis.scaled(2f * axisDotVector) +
                vector.scaled(rotation.w * rotation.w - axisDotAxis) +
                Vector3.cross(axis, vector).scaled(2f * rotation.w)
        }

        fun inverseRotateVector(rotation: Quaternion, vector: Vector3): Vector3 =
            rotateVector(rotation.inverted(), vector)

        fun slerp(start: Quaternion, end: Quaternion, fraction: Float): Quaternion {
            val first = start.normalized()
            var second = end.normalized()
            var cosine = dot(first, second)
            if (cosine < 0f) {
                second = raw(-second.x, -second.y, -second.z, -second.w)
                cosine = -cosine
            }

            if (cosine > SLERP_THRESHOLD) {
                return Quaternion(
                    first.x + fraction * (second.x - first.x),
                    first.y + fraction * (second.y - first.y),
                    first.z + fraction * (second.z - first.z),
                    first.w + fraction * (second.w - first.w)
                )
            }

            val clampedCosine = cosine.coerceIn(-1f, 1f)
            val angle = acos(clampedCosine)
            val scaledAngle = angle * fraction
            val denominator = sin(angle)
            val firstWeight = cos(scaledAngle) - clampedCosine * sin(scaledAngle) / denominator
            val secondWeight = sin(scaledAngle) / denominator
            return Quaternion(
                first.x * firstWeight + second.x * secondWeight,
                first.y * firstWeight + second.y * secondWeight,
                first.z * firstWeight + second.z * secondWeight,
                first.w * firstWeight + second.w * secondWeight
            )
        }

        fun axisAngle(axis: Vector3, degrees: Float): Quaternion {
            val halfAngle = Math.toRadians(degrees.toDouble()) / 2.0
            val factor = sin(halfAngle).toFloat()
            return Quaternion(
                axis.x * factor,
                axis.y * factor,
                axis.z * factor,
                cos(halfAngle).toFloat()
            )
        }

        /** Builds a rotation by applying the Z, Y and X angles in that order. */
        fun eulerAngles(angles: Vector3): Quaternion {
            val xRotation = axisAngle(Vector3.right(), angles.x)
            val yRotation = axisAngle(Vector3.up(), angles.y)
            val zRotation = axisAngle(Vector3.back(), angles.z)
            return multiply(multiply(yRotation, xRotation), zRotation)
        }

        fun rotationBetweenVectors(start: Vector3, end: Vector3): Quaternion {
            val from = start.normalized()
            val to = end.normalized()
            val cosine = Vector3.dot(from, to)
            if (cosine < -0.999f) {
                var axis = Vector3.cross(Vector3.back(), from)
                if (axis.lengthSquared() < 0.01f) {
                    axis = Vector3.cross(Vector3.right(), from)
                }
                return axisAngle(axis.normalized(), 180f)
            }

            val axis = Vector3.cross(from, to)
            val length = sqrt((1f + cosine) * 2f)
            val inverseLength = 1f / length
            return Quaternion(
                axis.x * inverseLength,
                axis.y * inverseLength,
                axis.z * inverseLength,
                length * 0.5f
            )
        }

        fun lookRotation(forward: Vector3, up: Vector3): Quaternion {
            val forwardRotation = rotationBetweenVectors(Vector3.forward(), forward)
            val right = Vector3.cross(forward, up)
            val orthogonalUp = Vector3.cross(right, forward)
            val rotatedUp = rotateVector(forwardRotation, Vector3.up())
            val upRotation = rotationBetweenVectors(rotatedUp, orthogonalUp)
            return multiply(upRotation, forwardRotation)
        }

        private fun dot(lhs: Quaternion, rhs: Quaternion): Float =
            lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z + lhs.w * rhs.w

        private fun normalize(x: Float, y: Float, z: Float, w: Float): Components {
            val squaredLength = x * x + y * y + z * z + w * w
            if (almostEqual(squaredLength, 0f)) return Components(0f, 0f, 0f, 1f)
            if (squaredLength == 1f) return Components(x, y, z, w)
            val inverseLength = (1.0 / sqrt(squaredLength.toDouble())).toFloat()
            return Components(
                x * inverseLength,
                y * inverseLength,
                z * inverseLength,
                w * inverseLength
            )
        }

        private fun raw(x: Float, y: Float, z: Float, w: Float): Quaternion =
            Quaternion(Components(x, y, z, w))

        private fun almostEqual(lhs: Float, rhs: Float): Boolean {
            val difference = abs(lhs - rhs)
            if (difference <= 1e-10f) return true
            return difference <= max(abs(lhs), abs(rhs)) * Math.ulp(1f)
        }
    }
}
