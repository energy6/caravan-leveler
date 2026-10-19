package de.energy6.caravanleveler

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import de.energy6.caravanleveler.math.times
import de.energy6.caravanleveler.math.toRotationVector
import de.energy6.caravanleveler.sensors.AbstractSensor
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.Sensor
import de.energy6.caravanleveler.sensors.WT901BLECLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Real decoder, Android coordinate remapping, repository, persistence and view model;
 * only the radio and phone hardware are simulated. No BLE permissions/device needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BleLevelingIntegrationTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = targetContext.getSharedPreferences("ble_leveling_test", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(targetContext) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    }
    private val viewModels = ViewModelStore()

    @Before
    @After
    fun clearStore() {
        assertTrue(store.edit().clear().commit())
    }

    @After
    fun cleanUp() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun uncalibratedBlePacketsMatch2022CorrectionsForEveryAxisMapping() = integrationTest {
        val rig = rig()
        // The scalar expectations follow bea20cc (internal, 2022-03-01):
        // width * sin(roll), -length * sin(pitch), in centimetres.
        for (x in Sensor.Axis.entries) {
            for (y in Sensor.Axis.entries.filterNot { it.parallelTo(x) }) {
                val coordinates = Sensor.Coordinates(x, y)
                rig.repository.selectSensor(BLE_ID, coordinates, autoConnect = true)
                runCurrent()
                for (heading in listOf(0f, 60f, 135f, 230f, 359f)) {
                    rig.settle(pitch = 3f, roll = -2f, heading = heading)
                    rig.assertCorrections(3f, -2f, "$coordinates, heading=$heading")
                }
            }
        }
    }

    @Test
    fun vehicleCalibrationPreservesTiltChangesWithDifferentPhoneAndBleHeadings() = integrationTest {
        verifyVehicleCalibration(ROTATED_AXES)
    }

    @Test
    fun vehicleCalibrationWithSensorXPointingLeftAndYPointingUp() = integrationTest {
        verifyVehicleCalibration(SENSOR_X_LEFT_Y_UP)
    }

    private fun TestScope.verifyVehicleCalibration(coordinates: Sensor.Coordinates) {
        val rig = rig()
        rig.calibrations.saveDevice(PHONE_BIAS.inverted())
        rig.repository.selectSensor(BLE_ID, coordinates, autoConnect = true)
        rig.viewModel.setCalibrationMonitoring(true)
        runCurrent()

        // Calibrate on a sloping floor; each sensor has its own magnetic heading.
        repeat(36) { rig.tick(2f, -1f, 110f) }
        assertTrue(rig.viewModel.uiState.value.calibrationButtonVisible)
        rig.viewModel.openCalibration()
        assertEquals(CalibrationTarget.VEHICLE, rig.viewModel.uiState.value.calibrationDialog?.target)
        rig.viewModel.startCalibration()
        runCurrent()
        repeat(22) { rig.tick(2f, -1f, 110f) }
        assertNull(rig.viewModel.uiState.value.calibrationDialog)
        assertNotNull(rig.calibrations.data.value.vehicleCorrection(BLE_ID, coordinates))
        rig.assertCorrections(2f, -1f, "calibration pose")

        // The calibration pose alone cannot distinguish left/right multiplication.
        // Returning to level and changing each tilt exposes rotated/sign-swapped values.
        for ((pitch, roll) in listOf(0f to 0f, 4f to -3f, -3f to 2f, 0f to 5f, -4f to 0f)) {
            rig.settle(pitch, roll, 110f)
            rig.assertCorrections(pitch, roll, "after calibration: pitch=$pitch roll=$roll")
        }
        rig.viewModel.setCalibrationMonitoring(false)

        // Restore through the real persisted calibration repository and replay BLE packets.
        val restored = rig(CalibrationRepository(context))
        restored.repository.selectSensor(BLE_ID, coordinates, autoConnect = true)
        runCurrent()
        restored.settle(-2f, 3f, 110f)
        restored.assertCorrections(-2f, 3f, "restored calibration")

        // A correction belongs to one sensor and one coordinate mapping.
        restored.repository.selectSensor(BLE_ID, Sensor.Coordinates(), autoConnect = true)
        runCurrent()
        restored.settle(3f, -2f, 110f)
        restored.assertCorrections(3f, -2f, "different axes")
        restored.repository.selectSensor(SECOND_BLE_ID, coordinates, autoConnect = true)
        runCurrent()
        restored.settle(-3f, 2f, 110f)
        restored.assertCorrections(-3f, 2f, "different sensor")
        restored.repository.connect(false)
        runCurrent()
        assertFalse(restored.repository.rawData.value.isValid)
        assertFalse(restored.repository.data.value.isValid)
    }

    private fun integrationTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            viewModels.clear()
        }
    }

    private fun TestScope.rig(calibrations: CalibrationRepository = CalibrationRepository(context)): Rig {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = SimulatedSensorManager()
        val clock = object : MonotonicClock {
            override fun nowNanos() = (testScheduler.currentTime + 1) * 1_000_000L
        }
        val repository = SensorRepository(context, calibrations, clock, manager, backgroundScope)
        val preferences = object : IUserPreferencesRepository {
            override val data = MutableStateFlow(UserPreferences(caravanWidth = 2.4f, caravanLength = 3.7f))
            override fun addKnownSensor(id: String, name: String) = Unit
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LevelerViewModel(repository, preferences, calibrations, clock) as T
        }
        val viewModel = ViewModelProvider(viewModels, factory)["rig-${nextRig++}", LevelerViewModel::class.java]
        runCurrent()
        return Rig(this, manager, repository, calibrations, viewModel)
    }

    private inner class Rig(
        val scope: TestScope,
        val manager: SimulatedSensorManager,
        val repository: SensorRepository,
        val calibrations: CalibrationRepository,
        val viewModel: LevelerViewModel
    ) {
        fun tick(pitch: Float, roll: Float, heading: Float) = with(scope) {
            val external = manager.sensors.getValue(repository.status.value.selectedSensorId)
            external.receive(pitch, roll, heading)
            manager.phone.emit(
                Quaternion.axisAngle(Vector3.back(), 15f) *
                    Quaternion.axisAngle(Vector3.up(), roll) *
                    Quaternion.axisAngle(Vector3.right(), pitch) * PHONE_BIAS
            )
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
        }

        fun settle(pitch: Float, roll: Float, heading: Float) {
            repeat(16) { tick(pitch, roll, heading) }
            assertTrue(repository.data.value.isValid)
        }

        fun assertCorrections(pitch: Float, roll: Float, label: String) {
            val state = viewModel.caravanState.value
            // Each acceleration reading is quantized to 16g / 32768. The calibration
            // and live sample together can differ by ~0.18 cm over a 3.7 m baseline.
            assertEquals("$label: wheel correction", 240f * sin(Math.toRadians(roll.toDouble())).toFloat(), state.axis, 0.2f)
            assertEquals("$label: jockey wheel correction", -370f * sin(Math.toRadians(pitch.toDouble())).toFloat(), state.stabilizer, 0.2f)
            assertTrue("$label: finite scene rotation", listOf(state.rotation.x, state.rotation.y, state.rotation.z, state.rotation.w).all { it.isFinite() })
        }
    }

    private class SimulatedSensorManager : ISensorManager {
        val phone = SimulatedSensor(SENSOR_BUILTIN_ID)
        val sensors = mapOf(BLE_ID to SimulatedSensor(BLE_ID), SECOND_BLE_ID to SimulatedSensor(SECOND_BLE_ID))
        override val devices = MutableStateFlow(SensorDevices())
        override fun getBuiltinSensor(): Sensor = phone
        override suspend fun getSensor(id: String): Sensor? = sensors[id]
        override fun setOnRequestPermissionsListener(function: () -> Unit) = Unit
        override fun startBleScan() = false
        override fun stopBleScan() = Unit
    }

    private class SimulatedSensor(override val id: String) : AbstractSensor() {
        private val decoder = WT901BLECLDecoder()
        override val isBuiltin = id == SENSOR_BUILTIN_ID
        override var coordinates = Sensor.Coordinates()
        override fun connect() { mConnected.value = Sensor.ConnectionState.CONNECTED }
        override fun disconnect() { mConnected.value = Sensor.ConnectionState.DISCONNECTED }
        fun emit(rotation: Quaternion) { mRotation.value = rotation.toRotationVector() }

        fun receive(pitch: Float, roll: Float, heading: Float) {
            // Construct physical sensor vectors with independent double-precision matrices,
            // then encode real 0x61 acceleration / 0x71 register-0x3A magnetic notifications.
            val rotation = multiply(multiply(rz(heading), ry(roll)), rx(pitch))
            val x = axis(coordinates.xaxis)
            val y = axis(coordinates.yaxis)
            val z = doubleArrayOf(x[1]*y[2]-x[2]*y[1], x[2]*y[0]-x[0]*y[2], x[0]*y[1]-x[1]*y[0])
            val remap = arrayOf(x, y, z)
            val rawRotation = multiply(rotation, transpose(remap))
            val gravity = transform(transpose(rawRotation), doubleArrayOf(0.0, 0.0, 9.81))
            val magnetic = transform(transpose(rawRotation), doubleArrayOf(0.0, 45.0, -15.0))
            val magPacket = ByteArray(20).apply {
                this[0] = 0x55; this[1] = 0x71; this[2] = 0x3A
                magnetic.forEachIndexed { index, value -> putShort(4 + 2*index, (value * 10).roundToInt()) }
            }
            val accelerationPacket = ByteArray(20).apply {
                this[0] = 0x55; this[1] = 0x61
                gravity.forEachIndexed { index, value -> putShort(2 + 2*index, (value / (16 * 9.81) * 32768).roundToInt()) }
            }
            decoder.decode(magPacket, coordinates)
            decoder.decode(accelerationPacket, coordinates)?.let { mRotation.value = it }
        }
    }

    companion object {
        private const val BLE_ID = "AA:BB:CC:DD:EE:01"
        private const val SECOND_BLE_ID = "AA:BB:CC:DD:EE:02"
        private val ROTATED_AXES = Sensor.Coordinates(Sensor.Axis.Y, Sensor.Axis.MINUS_X)
        // Settings labels: sensor X towards the caravan's left, sensor Y towards its top.
        private val SENSOR_X_LEFT_Y_UP = Sensor.Coordinates(Sensor.Axis.MINUS_X, Sensor.Axis.Z)
        private val PHONE_BIAS = Quaternion.eulerAngles(Vector3(1f, -0.7f, 0f))
        private var nextRig = 0
        private fun ByteArray.putShort(index: Int, value: Int) {
            this[index] = value.toByte()
            this[index + 1] = (value shr 8).toByte()
        }
        private fun axis(axis: Sensor.Axis): DoubleArray = when (axis) {
            Sensor.Axis.X -> doubleArrayOf(1.0, 0.0, 0.0)
            Sensor.Axis.Y -> doubleArrayOf(0.0, 1.0, 0.0)
            Sensor.Axis.Z -> doubleArrayOf(0.0, 0.0, 1.0)
            Sensor.Axis.MINUS_X -> doubleArrayOf(-1.0, 0.0, 0.0)
            Sensor.Axis.MINUS_Y -> doubleArrayOf(0.0, -1.0, 0.0)
            Sensor.Axis.MINUS_Z -> doubleArrayOf(0.0, 0.0, -1.0)
        }
        private fun rx(degrees: Float): Array<DoubleArray> {
            val a = Math.toRadians(degrees.toDouble()); val c = cos(a); val s = sin(a)
            return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, c, -s), doubleArrayOf(0.0, s, c))
        }
        private fun ry(degrees: Float): Array<DoubleArray> {
            val a = Math.toRadians(degrees.toDouble()); val c = cos(a); val s = sin(a)
            return arrayOf(doubleArrayOf(c, 0.0, s), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-s, 0.0, c))
        }
        private fun rz(degrees: Float): Array<DoubleArray> {
            val a = Math.toRadians(degrees.toDouble()); val c = cos(a); val s = sin(a)
            return arrayOf(doubleArrayOf(c, -s, 0.0), doubleArrayOf(s, c, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        }
        private fun transpose(m: Array<DoubleArray>) = Array(3) { row -> DoubleArray(3) { col -> m[col][row] } }
        private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(3) { row -> DoubleArray(3) { col -> (0..2).sumOf { a[row][it] * b[it][col] } } }
        private fun transform(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { row -> (0..2).sumOf { m[row][it] * v[it] } }
    }
}
