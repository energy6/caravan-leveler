package de.energy6.caravanleveler

import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import de.energy6.caravanleveler.IsCloseTo.closeTo
import de.energy6.caravanleveler.math.times
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class CalibrationTest {
    @Test
    fun `quaternion average treats opposite signs as the same rotation`() {
        val rotation = Quaternion.eulerAngles(Vector3(3f, -2f, 47f))
        val opposite = Quaternion(-rotation.x, -rotation.y, -rotation.z, -rotation.w)

        val average = requireNotNull(CalibrationMath.average(listOf(rotation, opposite)))

        assertThat(CalibrationMath.angularDistanceDegrees(rotation, average), closeTo(0f, 0.001f))
    }

    @Test
    fun `four positions solve a body fixed correction on a tilted surface`() {
        val expectedCorrection = Quaternion.eulerAngles(Vector3(3f, -2f, 0f))
        val poses = calibrationPoses(expectedCorrection, listOf(0f, 88f, 179f, 271f))

        val solution = requireNotNull(CalibrationMath.solveDeviceCalibration(poses))
        val normals = poses.map {
            CalibrationMath.correctedPlaneNormal(it.motionRotation, solution.correction)
        }

        normals.drop(1).forEach { normal ->
            assertThat(
                CalibrationMath.angleBetweenVectorsDegrees(normals.first(), normal),
                closeTo(0f, 0.01f)
            )
        }
        assertThat(solution.turnAnglesDegrees.size, equalTo(3))
        assertThat(solution.maxPlaneResidualDegrees, closeTo(0f, 0.01f))
    }

    @Test
    fun `solver accepts exactly four calculation poses and excludes validation pose`() {
        val correction = Quaternion.eulerAngles(Vector3(2f, -1f, 0f))
        val fivePoses = calibrationPoses(correction, listOf(0f, 90f, 180f, 270f, 360f))

        assertThat(CalibrationMath.solveDeviceCalibration(fivePoses), equalTo(null))
        assertThat(CalibrationMath.solveDeviceCalibration(fivePoses.take(4)) != null, equalTo(true))
    }

    @Test
    fun `solver accepts clockwise turns whose quaternion axis points away from display`() {
        val correction = Quaternion.eulerAngles(Vector3(2f, -1f, 0f))
        val clockwise = calibrationPoses(correction, listOf(0f, -90f, -180f, -270f))

        val solution = CalibrationMath.solveDeviceCalibration(clockwise)

        assertThat(solution != null, equalTo(true))
        assertThat(requireNotNull(solution).maxPlaneResidualDegrees, closeTo(0f, 0.01f))
    }

    @Test
    fun `validation accepts 0 point 19 degrees and rejects 0 point 21 degrees`() {
        val correction = Quaternion.eulerAngles(Vector3(2f, -1f, 0f))
        val poses = calibrationPoses(correction, listOf(0f, 90f, 180f, 270f, 360f))
        val solution = requireNotNull(CalibrationMath.solveDeviceCalibration(poses.take(4)))
        val accepted = poses.last().copy(
            motionRotation = Quaternion.eulerAngles(Vector3(0.19f, 0f, 0f)) *
                poses.last().motionRotation
        )
        val rejected = poses.last().copy(
            motionRotation = Quaternion.eulerAngles(Vector3(0.21f, 0f, 0f)) *
                poses.last().motionRotation
        )

        assertThat(
            CalibrationMath.deviceValidationErrorDegrees(
                solution.correction,
                poses.first(),
                accepted
            ),
            closeTo(0.19f, 0.01f)
        )
        assertThat(
            CalibrationMath.deviceValidationErrorDegrees(
                solution.correction,
                poses.first(),
                rejected
            ),
            closeTo(0.21f, 0.01f)
        )
    }

    @Test
    fun `vehicle correction aligns future vehicle measurements with device reference`() {
        val rawVehicle = Quaternion.eulerAngles(Vector3(-1.5f, 2.5f, 18f))
        val deviceReference = Quaternion.eulerAngles(Vector3(3f, -4f, 31f))
        val correction = requireNotNull(
            CalibrationMath.vehicleCorrection(
                List(20) { CalibrationPoint(rawVehicle, deviceReference) }
            )
        )

        val correctedVehicle = CalibrationMath.apply(correction, rawVehicle)

        assertThat(
            CalibrationMath.angularDistanceDegrees(deviceReference, correctedVehicle),
            closeTo(0f, 0.001f)
        )
    }

    @Test
    fun `stability requires three continuous seconds`() {
        val detector = StabilityDetector()
        val start = 1_000_000_000L
        var ready = false

        repeat(31) { index ->
            val now = start + index * 100_000_000L
            ready = detector.update(validReading(Quaternion.identity(), now), now)
            if (index < 30) assertThat(ready, equalTo(false))
        }

        assertThat(ready, equalTo(true))
    }

    @Test
    fun `movement resets stability window`() {
        val detector = StabilityDetector()
        val start = 1_000_000_000L
        repeat(20) { index ->
            val now = start + index * 100_000_000L
            detector.update(validReading(Quaternion.identity(), now), now)
        }

        val movementTime = start + 2_000_000_000L
        assertThat(
            detector.update(
                validReading(Quaternion.eulerAngles(Vector3(0f, 0f, 5f)), movementTime),
                movementTime
            ),
            equalTo(false)
        )

        val shortlyAfter = movementTime + 2_900_000_000L
        assertThat(
            detector.update(validReading(Quaternion.eulerAngles(Vector3(0f, 0f, 5f)), shortlyAfter), shortlyAfter),
            equalTo(false)
        )
        val readyAt = movementTime + 3_000_000_000L
        assertThat(
            detector.update(validReading(Quaternion.eulerAngles(Vector3(0f, 0f, 5f)), readyAt), readyAt),
            equalTo(true)
        )
    }

    @Test
    fun `stale and excessive tilt readings are rejected`() {
        val detector = StabilityDetector(stableDurationNanos = 0L)
        val now = 2_000_000_000L

        assertThat(
            detector.update(validReading(Quaternion.identity(), now - 600_000_000L), now),
            equalTo(false)
        )
        assertThat(
            detector.update(
                validReading(Quaternion.eulerAngles(Vector3(9f, 0f, 0f)), now),
                now
            ),
            equalTo(false)
        )
    }

    @Test
    fun `stability enforces tilt and angular speed thresholds`() {
        val start = 1_000_000_000L
        val accepted = listOf(
            validReading(Quaternion.eulerAngles(Vector3(8f, 0f, 0f)), start),
            validReading(Quaternion.eulerAngles(Vector3(8f, 0f, 1.9f)), start + 1_000_000_000L)
        )
        val tooFast = listOf(
            validReading(Quaternion.identity(), start),
            validReading(Quaternion.eulerAngles(Vector3(0f, 0f, 2.1f)), start + 1_000_000_000L)
        )
        val tooTilted = listOf(
            validReading(Quaternion.eulerAngles(Vector3(8.1f, 0f, 0f)), start)
        )

        assertThat(CalibrationMath.isStableSequence(accepted), equalTo(true))
        assertThat(CalibrationMath.isStableSequence(tooFast), equalTo(false))
        assertThat(CalibrationMath.isStableSequence(tooTilted), equalTo(false))
    }

    @Test
    fun `non-monotonic samples are rejected`() {
        val samples = listOf(
            validReading(Quaternion.identity(), 2_000_000_000L),
            validReading(Quaternion.identity(), 1_000_000_000L)
        )

        assertThat(CalibrationMath.isStableSequence(samples), equalTo(false))
    }

    @Test
    fun `vehicle calibration key includes coordinate mapping`() {
        val first = VehicleCalibrationKey(
            "AA:BB:CC:DD:EE:FF",
            de.energy6.caravanleveler.sensors.Sensor.Coordinates()
        )
        val restored = VehicleCalibrationKey.fromStorageKey(first.storageKey)
        val remapped = first.copy(
            coordinates = de.energy6.caravanleveler.sensors.Sensor.Coordinates(
                de.energy6.caravanleveler.sensors.Sensor.Axis.Y,
                de.energy6.caravanleveler.sensors.Sensor.Axis.MINUS_X
            )
        )

        assertThat(restored, equalTo(first))
        assertThat(remapped, equalTo(VehicleCalibrationKey.fromStorageKey(remapped.storageKey)))
        assertThat(remapped == first, equalTo(false))
    }

    private fun validReading(rotation: Quaternion, timestamp: Long) = SensorData(
        q = rotation,
        timestampNanos = timestamp,
        isValid = true
    )

    private fun calibrationPoses(
        correction: Quaternion,
        angles: List<Float>
    ): List<DeviceCalibrationPose> {
        val plane = Quaternion.eulerAngles(Vector3(2f, -1f, 25f))
        return angles.map { angle ->
            val corrected = plane * Quaternion.eulerAngles(Vector3(0f, 0f, angle))
            val raw = corrected * correction.inverted()
            val gravity = Quaternion.inverseRotateVector(raw, Vector3(0f, 0f, 9.81f))
            DeviceCalibrationPose(raw, raw, gravity)
        }
    }

}
