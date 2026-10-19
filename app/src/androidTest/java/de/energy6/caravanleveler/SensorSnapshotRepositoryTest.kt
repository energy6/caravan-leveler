package de.energy6.caravanleveler

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import de.energy6.caravanleveler.math.times
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SensorSnapshotRepositoryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "snapshot-repository-test")
    private val store = target.getSharedPreferences("snapshot-repository-test", Context.MODE_PRIVATE)
    private val context = object : ContextWrapper(target) {
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    }
    private val sensors = SnapshotSensors()
    private val coordinates = Sensor.Coordinates(Sensor.Axis.MINUS_X, Sensor.Axis.Z)
    private val preferences = object : IUserPreferencesRepository {
        override val data = MutableStateFlow(UserPreferences(
            selectedSensor = SENSOR_ID, coordinates = coordinates,
            caravanWidth = 2.4f, caravanLength = 3.7f
        ))
        override fun addKnownSensor(id: String, name: String) = Unit
    }
    private val clock = object : MonotonicClock { override fun nowNanos() = 5_000_000_000L }
    private fun repository(calibrations: ICalibrationRepository) =
        SensorSnapshotRepository(context, sensors, preferences, calibrations, clock)

    @Before
    @After
    fun clear() {
        directory.deleteRecursively()
        directory.mkdirs()
        store.edit().clear().commit()
    }

    @Test
    fun snapshotSurvivesRestartAndReplaysTheExportedCorrectionsWithItsOriginalCalibration() = runTest {
        val calibrations = CalibrationRepository(context)
        val reference = Quaternion.axisAngle(Vector3.up(), -2f) * Quaternion.axisAngle(Vector3.right(), 3f)
        val alignment = Quaternion.axisAngle(Vector3.back(), -95f)
        val raw = alignment.inverted() * reference
        calibrations.saveDevice(Quaternion.identity())
        calibrations.saveVehicle(SENSOR_ID, coordinates, alignment)
        sensors.status.value = SensorStatus(selectedSensorId = SENSOR_ID, coordinates = coordinates,
            isBuiltin = false, connected = Sensor.ConnectionState.CONNECTED)
        sensors.rawData.value = SensorData(raw, 4_900_000_000L, true)
        sensors.deviceRawData.value = SensorData(reference, 4_950_000_000L, true)
        val first = repository(calibrations)
        first.capture()

        calibrations.saveVehicle(SENSOR_ID, coordinates, Quaternion.identity())
        first.capture()
        val restored = repository(CalibrationRepository(context))
        restored.refresh()
        assertEquals(2, restored.count.value)
        val document = JSONObject(restored.export())
        assertEquals(1, document.getInt("schemaVersion"))
        val capture = document.getJSONArray("snapshots").getJSONObject(0)
        assertEquals(SENSOR_ID, capture.getJSONObject("sensor").getString("id"))
        assertEquals("MINUS_X", capture.getJSONObject("sensor").getJSONObject("coordinates").getString("x"))
        assertEquals("Z", capture.getJSONObject("sensor").getJSONObject("coordinates").getString("y"))
        val readings = capture.getJSONObject("readings")
        assertTrue(readings.getJSONObject("raw").getBoolean("isFresh"))
        assertEquals(4_900_000_000L, readings.getJSONObject("raw").getLong("timestampNanos"))
        val replay = CalibrationMath.applyVehicle(
            capture.getJSONObject("calibration").getJSONObject("vehicle").rotation(),
            readings.getJSONObject("raw").getJSONObject("quaternion").rotation()
        )
        val settings = capture.getJSONObject("settings")
        val level = calculateLeveling(replay, settings.getDouble("caravanWidthMeters").toFloat(),
            settings.getDouble("caravanLengthMeters").toFloat())
        assertEquals(capture.getJSONObject("corrections").getDouble("axisCm"), level.axis.toDouble(), 1e-4)
        assertEquals(capture.getJSONObject("corrections").getDouble("stabilizerCm"), level.stabilizer.toDouble(), 1e-4)
        assertTrue(CalibrationMath.angularDistanceDegrees(reference, replay) < 0.05f)

        restored.clear()
        assertEquals(0, restored.count.value)
        assertEquals(0, JSONObject(repository(calibrations).export()).getJSONArray("snapshots").length())
        assertNotNull(CalibrationRepository(context).data.value.device)
    }

    @Test
    fun unavailableStaleAndNonfiniteReadingsRemainExplicitInExport() = runTest {
        val repository = repository(CalibrationRepository(context))
        sensors.rawData.value = SensorData(Quaternion(Float.NaN, 0f, 0f, 1f), 1L, false)
        sensors.deviceRawData.value = SensorData(timestampNanos = 1L, isValid = true)
        repository.capture()
        val capture = JSONObject(repository.export()).getJSONArray("snapshots").getJSONObject(0)
        assertFalse(capture.getJSONObject("readings").getJSONObject("raw").getBoolean("isValid"))
        assertFalse(capture.getJSONObject("readings").getJSONObject("deviceRaw").getBoolean("isFresh"))
        assertTrue(capture.getJSONObject("readings").getJSONObject("raw").getJSONObject("quaternion").isNull("x"))
        assertTrue(capture.isNull("corrections"))
    }

    private fun JSONObject.rotation() = Quaternion(
        getDouble("x").toFloat(), getDouble("y").toFloat(), getDouble("z").toFloat(), getDouble("w").toFloat()
    )

    private class SnapshotSensors : ISensorRepository {
        override val status = MutableStateFlow(SensorStatus())
        override val data = MutableStateFlow(SensorData())
        override val rawData = MutableStateFlow(SensorData())
        override val deviceData = MutableStateFlow(SensorData())
        override val deviceRawData = MutableStateFlow(SensorData())
        override val calibrationMotionData = MutableStateFlow(CalibrationMotionData())
        override val scan = MutableStateFlow(ScanResult())
        override fun selectSensor(id: String, coordinates: Sensor.Coordinates, autoConnect: Boolean) = Unit
        override fun connect(state: Boolean) = Unit
        override fun monitorDevice(state: Boolean) = Unit
        override fun monitorCalibrationMotion(state: Boolean) = Unit
        override fun scan(state: Boolean) = Unit
        override fun permissionsRequested() = Unit
    }

    companion object { private const val SENSOR_ID = "AA:BB:CC:DD:EE:01" }
}
