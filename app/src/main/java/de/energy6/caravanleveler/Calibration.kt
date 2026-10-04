package de.energy6.caravanleveler

import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import de.energy6.caravanleveler.math.DEG_PER_RAD
import de.energy6.caravanleveler.math.plus
import de.energy6.caravanleveler.math.times
import de.energy6.caravanleveler.math.toOrientation
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

private const val STABILITY_THRESHOLD_EPSILON_DEGREES = 0.001f

data class CalibrationPoint(
    val measured: Quaternion,
    val reference: Quaternion
)

data class DeviceCalibrationPose(
    val absoluteRotation: Quaternion,
    val motionRotation: Quaternion,
    val gravity: Vector3
)

data class TurnMeasurement(
    val angleDegrees: Float,
    val axis: Vector3
)

data class DeviceCalibrationSolution(
    val correction: Quaternion,
    val turnAnglesDegrees: List<Float>,
    val maxPlaneResidualDegrees: Float
)

object CalibrationMath {
    const val MINIMUM_QUARTER_TURN_DEGREES = 75f
    const val MAXIMUM_QUARTER_TURN_DEGREES = 105f
    private const val MAXIMUM_AXIS_DIFFERENCE_DEGREES = 8f
    private const val MAXIMUM_SOLVER_RESIDUAL_DEGREES = 0.5f
    private const val MAXIMUM_WORLD_GRAVITY_SPREAD_DEGREES = 1f

    fun average(samples: List<Quaternion>): Quaternion? {
        if (samples.isEmpty()) return null

        val reference = samples.first()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var w = 0.0
        samples.forEach { sample ->
            val sign = if (dot(reference, sample) < 0f) -1f else 1f
            x += sample.x * sign
            y += sample.y * sign
            z += sample.z * sign
            w += sample.w * sign
        }
        return Quaternion(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    /**
     * Solves the fixed sensor/case tilt from four positions on one plane. A fifth pose must be
     * checked separately with [deviceValidationErrorDegrees].
     */
    fun solveDeviceCalibration(poses: List<DeviceCalibrationPose>): DeviceCalibrationSolution? {
        if (poses.size != 4) return null

        val turns = poses.zipWithNext().map { (previous, current) ->
            measureTurn(previous.motionRotation, current.motionRotation) ?: return null
        }
        if (turns.any {
                it.angleDegrees !in MINIMUM_QUARTER_TURN_DEGREES..MAXIMUM_QUARTER_TURN_DEGREES
            }
        ) {
            return null
        }

        val referenceAxis = turns.first().axis
        val minimumAxisDot = cos(MAXIMUM_AXIS_DIFFERENCE_DEGREES / DEG_PER_RAD)
        if (turns.drop(1).any { vectorDot(referenceAxis, it.axis) < minimumAxisDot }) {
            return null
        }
        val measuredAxis = normalizedVector(
            turns.fold(Vector3.zero()) { sum, turn -> sum + turn.axis }
        ) ?: return null
        // The rotation direction determines the axis sign. The physical support normal always
        // points through the display, regardless of clockwise/counter-clockwise guidance.
        val supportNormal = if (measuredAxis.z < 0f) measuredAxis.negated() else measuredAxis

        val correction = rotationBetweenVectors(DEVICE_NORMAL, supportNormal) ?: return null
        val normals = poses.map { correctedPlaneNormal(it.motionRotation, correction) }
        val averageNormal = normalizedVector(
            normals.fold(Vector3.zero()) { sum, normal -> sum + normal }
        ) ?: return null
        val maxResidual = normals.maxOf { angleBetweenVectorsDegrees(it, averageNormal) }
        if (maxResidual > MAXIMUM_SOLVER_RESIDUAL_DEGREES) return null

        val worldGravities = poses.map { worldGravity(it) }
        val averageGravity = normalizedVector(
            worldGravities.fold(Vector3.zero()) { sum, gravity -> sum + gravity }
        ) ?: return null
        if (worldGravities.any {
                angleBetweenVectorsDegrees(it, averageGravity) >
                    MAXIMUM_WORLD_GRAVITY_SPREAD_DEGREES
            }
        ) {
            return null
        }

        return DeviceCalibrationSolution(
            correction = correction,
            turnAnglesDegrees = turns.map { it.angleDegrees },
            maxPlaneResidualDegrees = maxResidual
        )
    }

    fun vehicleCorrection(samples: List<CalibrationPoint>): Quaternion? = average(
        samples.map { sample -> sample.measured.inverted() * sample.reference }
    )

    fun apply(correction: Quaternion?, measured: Quaternion): Quaternion =
        correction?.let { measured * it } ?: measured

    fun measureTurn(previous: Quaternion, current: Quaternion): TurnMeasurement? {
        val delta = normalizedQuaternion(previous.inverted() * current) ?: return null
        val shortest = if (delta.w < 0f) {
            Quaternion(-delta.x, -delta.y, -delta.z, -delta.w)
        } else {
            delta
        }
        val w = shortest.w.coerceIn(-1f, 1f)
        val angle = 2f * acos(w) * DEG_PER_RAD
        val divisor = sqrt(max(0f, 1f - w * w))
        if (divisor < 1e-5f || angle < STABILITY_THRESHOLD_EPSILON_DEGREES) return null
        val axis = normalizedVector(
            Vector3(shortest.x / divisor, shortest.y / divisor, shortest.z / divisor)
        ) ?: return null
        return TurnMeasurement(angle, axis)
    }

    fun deviceValidationErrorDegrees(
        correction: Quaternion,
        firstPose: DeviceCalibrationPose,
        validationPose: DeviceCalibrationPose
    ): Float = maxOf(
        angleBetweenVectorsDegrees(
            correctedPlaneNormal(firstPose.motionRotation, correction),
            correctedPlaneNormal(validationPose.motionRotation, correction)
        ),
        angleBetweenVectorsDegrees(worldGravity(firstPose), worldGravity(validationPose))
    )

    fun correctedPlaneNormal(rotation: Quaternion, correction: Quaternion): Vector3 =
        Quaternion.rotateVector(rotation * correction, DEVICE_NORMAL).normalized()

    private fun worldGravity(pose: DeviceCalibrationPose): Vector3 =
        Quaternion.rotateVector(pose.motionRotation, pose.gravity.normalized()).normalized()

    fun gyroscopeAxisDeviationDegrees(
        samples: List<Vector3>,
        expectedAxis: Vector3,
        minimumSpeedRadiansPerSecond: Float = 0.08f
    ): Float? {
        val moving = samples.mapNotNull { velocity ->
            val speed = velocity.length()
            if (speed < minimumSpeedRadiansPerSecond) null else velocity to speed
        }
        if (moving.isEmpty()) return null
        val weightedSquareError = moving.sumOf { (velocity, speed) ->
            val axis = velocity.normalized()
            val angle = angleBetweenVectorsDegrees(axis, expectedAxis)
                .let { minOf(it, 180f - it) }
            (angle * angle * speed).toDouble()
        }
        val totalWeight = moving.sumOf { it.second.toDouble() }
        return sqrt(weightedSquareError / totalWeight).toFloat()
    }

    fun angleBetweenVectorsDegrees(lhs: Vector3, rhs: Vector3): Float {
        val left = normalizedVector(lhs) ?: return 180f
        val right = normalizedVector(rhs) ?: return 180f
        return acos(vectorDot(left, right).coerceIn(-1f, 1f)) * DEG_PER_RAD
    }

    fun angularDistanceDegrees(lhs: Quaternion, rhs: Quaternion): Float {
        val cosine = abs(dot(lhs, rhs)).coerceIn(0f, 1f)
        return 2f * acos(cosine) * DEG_PER_RAD
    }

    fun isNearlyLevel(rotation: Quaternion, maxTiltDegrees: Float): Boolean {
        val orientation = rotation.toOrientation()
        return sqrt(orientation.x * orientation.x + orientation.y * orientation.y) <=
            maxTiltDegrees + STABILITY_THRESHOLD_EPSILON_DEGREES
    }

    fun isStableSequence(
        samples: List<SensorData>,
        maxTiltDegrees: Float = StabilityDetector.DEFAULT_MAX_TILT_DEGREES,
        maxAngularSpeedDegreesPerSecond: Float =
            StabilityDetector.DEFAULT_MAX_ANGULAR_SPEED_DEGREES_PER_SECOND
    ): Boolean {
        if (samples.isEmpty() || samples.any { !it.isValid }) return false
        if (samples.any { !isNearlyLevel(it.q, maxTiltDegrees) }) return false

        return isMotionStableSequence(samples, maxAngularSpeedDegreesPerSecond)
    }

    fun isMotionStableSequence(
        samples: List<SensorData>,
        maxAngularSpeedDegreesPerSecond: Float =
            StabilityDetector.DEFAULT_MAX_ANGULAR_SPEED_DEGREES_PER_SECOND
    ): Boolean {
        if (samples.isEmpty() || samples.any { !it.isValid }) return false

        return samples.zipWithNext().all { (previous, current) ->
            if (current.timestampNanos <= previous.timestampNanos) return@all false
            val elapsedSeconds = (current.timestampNanos - previous.timestampNanos) / 1_000_000_000f
            angularDistanceDegrees(previous.q, current.q) / elapsedSeconds <=
                maxAngularSpeedDegreesPerSecond + STABILITY_THRESHOLD_EPSILON_DEGREES
        }
    }

    private fun dot(lhs: Quaternion, rhs: Quaternion): Float =
        lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z + lhs.w * rhs.w

    private fun normalizedQuaternion(value: Quaternion): Quaternion? {
        val length = sqrt(
            value.x * value.x + value.y * value.y + value.z * value.z + value.w * value.w
        )
        if (length < 1e-7f) return null
        return Quaternion(value.x / length, value.y / length, value.z / length, value.w / length)
    }

    private fun normalizedVector(value: Vector3): Vector3? =
        if (value.length() < 1e-7f) null else value.normalized()

    private fun vectorDot(lhs: Vector3, rhs: Vector3): Float =
        lhs.x * rhs.x + lhs.y * rhs.y + lhs.z * rhs.z

    private fun rotationBetweenVectors(from: Vector3, to: Vector3): Quaternion? {
        val start = normalizedVector(from) ?: return null
        val end = normalizedVector(to) ?: return null
        val dot = vectorDot(start, end).coerceIn(-1f, 1f)
        if (dot > 0.999999f) return Quaternion.identity()
        if (dot < -0.999999f) return null
        val cross = Vector3.cross(start, end)
        return normalizedQuaternion(Quaternion(cross.x, cross.y, cross.z, 1f + dot))
    }

    private val DEVICE_NORMAL = Vector3(0f, 0f, 1f)
}

class StabilityDetector(
    private val stableDurationNanos: Long = DEFAULT_STABLE_DURATION_NANOS,
    private val maxTiltDegrees: Float = DEFAULT_MAX_TILT_DEGREES,
    private val maxAngularSpeedDegreesPerSecond: Float =
        DEFAULT_MAX_ANGULAR_SPEED_DEGREES_PER_SECOND,
    private val staleAfterNanos: Long = DEFAULT_STALE_AFTER_NANOS
) {
    companion object {
        const val DEFAULT_STABLE_DURATION_NANOS = 3_000_000_000L
        const val DEFAULT_MAX_TILT_DEGREES = 8f
        const val DEFAULT_MAX_ANGULAR_SPEED_DEGREES_PER_SECOND = 2f
        const val DEFAULT_STALE_AFTER_NANOS = 500_000_000L
    }

    private var stableSinceNanos: Long? = null
    private var previousReading: SensorData? = null
    var progress: Float = 0f
        private set

    fun update(reading: SensorData, nowNanos: Long): Boolean {
        if (!reading.isFresh(nowNanos, staleAfterNanos) ||
            !CalibrationMath.isNearlyLevel(reading.q, maxTiltDegrees)
        ) {
            reset()
            return false
        }

        val previous = previousReading
        if (previous != null && reading.timestampNanos > previous.timestampNanos) {
            val elapsedSeconds = (reading.timestampNanos - previous.timestampNanos) / 1_000_000_000f
            val angularSpeed = CalibrationMath.angularDistanceDegrees(previous.q, reading.q) /
                elapsedSeconds
            if (angularSpeed >
                maxAngularSpeedDegreesPerSecond + STABILITY_THRESHOLD_EPSILON_DEGREES
            ) {
                stableSinceNanos = nowNanos
                previousReading = reading
                progress = 0f
                return false
            }
        }

        if (stableSinceNanos == null) stableSinceNanos = nowNanos
        if (previous == null || reading.timestampNanos >= previous.timestampNanos) {
            previousReading = reading
        }
        val elapsed = nowNanos - (stableSinceNanos ?: nowNanos)
        progress = if (stableDurationNanos == 0L) 1f else {
            (elapsed.toDouble() / stableDurationNanos).toFloat().coerceIn(0f, 1f)
        }
        return elapsed >= stableDurationNanos
    }

    fun reset() {
        stableSinceNanos = null
        previousReading = null
        progress = 0f
    }
}

fun SensorData.isFresh(
    nowNanos: Long,
    staleAfterNanos: Long = StabilityDetector.DEFAULT_STALE_AFTER_NANOS
): Boolean = isValid && timestampNanos > 0L && nowNanos >= timestampNanos &&
    nowNanos - timestampNanos <= staleAfterNanos
