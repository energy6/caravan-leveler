package de.energy6.caravanleveler

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import de.energy6.caravanleveler.sensors.Sensor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalibrationRepositoryTest {
    private val targetContext: Context by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
    }
    private val testStore: SharedPreferences by lazy {
        targetContext.getSharedPreferences("calibration_repository_test", Context.MODE_PRIVATE)
    }
    private val context: Context by lazy {
        object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                testStore
        }
    }

    @Before
    @After
    fun clearStore() {
        testStore.edit().clear().commit()
    }

    @Test
    fun calibrationsSurviveRepositoryRecreationAndRemainSensorSpecific() {
        val firstCoordinates = Sensor.Coordinates()
        val secondCoordinates = Sensor.Coordinates(Sensor.Axis.Y, Sensor.Axis.MINUS_X)
        val device = Quaternion.eulerAngles(Vector3(1f, -2f, 3f))
        val firstVehicle = Quaternion.eulerAngles(Vector3(-1f, 0.5f, 4f))
        val secondVehicle = Quaternion.eulerAngles(Vector3(2f, -0.5f, -6f))
        CalibrationRepository(context).apply {
            saveDevice(device)
            saveVehicle("AA:BB:CC:DD:EE:01", firstCoordinates, firstVehicle)
            saveVehicle("AA:BB:CC:DD:EE:02", secondCoordinates, secondVehicle)
        }

        val restored = CalibrationRepository(context).data.value

        assertSameRotation(device, restored.deviceCorrection())
        assertSameRotation(
            firstVehicle,
            restored.vehicleCorrection("AA:BB:CC:DD:EE:01", firstCoordinates)
        )
        assertSameRotation(
            secondVehicle,
            restored.vehicleCorrection("AA:BB:CC:DD:EE:02", secondCoordinates)
        )
        assertEquals(2, restored.vehicles.size)
        assertNull(
            restored.vehicleCorrection(
                "AA:BB:CC:DD:EE:01",
                Sensor.Coordinates(Sensor.Axis.Z, Sensor.Axis.X)
            )
        )
    }

    @Test
    fun savingTheSameVehicleAndAxesOverwritesAtomically() {
        val coordinates = Sensor.Coordinates()
        val first = Quaternion.eulerAngles(Vector3(1f, 2f, 3f))
        val replacement = Quaternion.eulerAngles(Vector3(-3f, -2f, -1f))
        CalibrationRepository(context).apply {
            saveVehicle("AA:BB:CC:DD:EE:FF", coordinates, first)
            saveVehicle("AA:BB:CC:DD:EE:FF", coordinates, replacement)
        }

        val restored = CalibrationRepository(context).data.value

        assertEquals(1, restored.vehicles.size)
        assertSameRotation(
            replacement,
            restored.vehicleCorrection("AA:BB:CC:DD:EE:FF", coordinates)
        )
    }

    @Test
    fun savingANewDeviceCalibrationClearsDependentVehicleCalibrations() {
        val coordinates = Sensor.Coordinates()
        val repository = CalibrationRepository(context)
        repository.saveVehicle(
            "AA:BB:CC:DD:EE:FF",
            coordinates,
            Quaternion.eulerAngles(Vector3(1f, 2f, 3f))
        )

        repository.saveDevice(Quaternion.eulerAngles(Vector3(-1f, -2f, 0f)))

        val restored = CalibrationRepository(context).data.value
        assertTrue(restored.device != null)
        assertTrue(restored.vehicles.isEmpty())
    }

    @Test
    fun incompatibleLegacyValuesAreRemovedSilently() {
        testStore.edit()
            .putBoolean("device.present", true)
            .putFloat("device.x", 0.1f)
            .putFloat("device.y", 0.2f)
            .putFloat("device.z", 0.3f)
            .putFloat("device.w", 0.9f)
            .commit()

        val restored = CalibrationRepository(context).data.value

        assertNull(restored.device)
        assertTrue(restored.vehicles.isEmpty())
    }

    private fun assertSameRotation(expected: Quaternion, actual: Quaternion?) {
        assertTrue(actual != null)
        assertTrue(CalibrationMath.angularDistanceDegrees(expected, actual!!) < 0.1f)
    }
}
