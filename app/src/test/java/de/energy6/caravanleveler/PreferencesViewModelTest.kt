package de.energy6.caravanleveler

import android.content.Context
import androidx.lifecycle.ViewModelStore
import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesViewModelTest {
    @Test
    fun `expert calibration follows configured sensor and axes even while disconnected`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val axes = Sensor.Coordinates(Sensor.Axis.MINUS_X, Sensor.Axis.Z)
            val key = VehicleCalibrationKey("AA:BB:CC:DD:EE:01", axes)
            val otherKey = VehicleCalibrationKey(key.sensorId, Sensor.Coordinates())
            val selected = CalibrationValue.from(Quaternion.eulerAngles(Vector3(1f, 2f, 3f)))
            val other = CalibrationValue.from(Quaternion.eulerAngles(Vector3(3f, 2f, 1f)))
            val calibrationData = MutableStateFlow(CalibrationState(vehicles = mapOf(key to selected, otherKey to other)))
            val calibration = mock<ICalibrationRepository> { on { data } doReturn calibrationData }
            val settings = MutableStateFlow(UserPreferences(selectedSensor = key.sensorId, coordinates = axes))
            val preferences = mock<IUserPreferencesRepository> { on { data } doReturn settings }
            val sensors = mock<ISensorRepository> { on { scan } doReturn MutableStateFlow(ScanResult()) }
            val snapshots = mock<SensorSnapshotRepository> { on { count } doReturn MutableStateFlow(0) }
            val model = PreferencesViewModel(sensors, preferences, calibration, snapshots, mock<Context>())
            store.put("preferences", model)
            runCurrent()
            assertEquals(selected, model.uiState.value.vehicleCalibration)
            assertEquals(axes, model.uiState.value.coordinates)

            // Pin the dialog target before changing selection; edits must stay with that key.
            val target = CalibrationEditorTarget.Vehicle(key)
            settings.value = settings.value.copy(coordinates = otherKey.coordinates)
            runCurrent()
            assertEquals(other, model.uiState.value.vehicleCalibration)
            assertFalse(model.saveCalibration(target, listOf("NaN", "0", "0", "1")))
            verify(calibration, never()).saveVehicle(any(), any(), any())
            assertTrue(model.saveCalibration(target, listOf("0", "0", "0", "2")))
            verify(calibration).saveVehicle(eq(key.sensorId), eq(axes), eq(Quaternion.identity()))
            model.resetCalibration(target)
            verify(calibration).resetVehicle(key.sensorId, axes)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `saving unchanged device correction preserves dependent calibrations`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val calibration = mock<ICalibrationRepository> {
                on { data } doReturn MutableStateFlow(CalibrationState(device = CalibrationValue.from(Quaternion.identity())))
            }
            val sensors = mock<ISensorRepository> { on { scan } doReturn MutableStateFlow(ScanResult()) }
            val preferences = mock<IUserPreferencesRepository> { on { data } doReturn MutableStateFlow(UserPreferences()) }
            val snapshots = mock<SensorSnapshotRepository> { on { count } doReturn MutableStateFlow(0) }
            val model = PreferencesViewModel(sensors, preferences, calibration, snapshots, mock<Context>())
            store.put("preferences", model)
            runCurrent()
            assertTrue(model.saveCalibration(CalibrationEditorTarget.Device, listOf("0", "0", "0", "1")))
            verify(calibration, never()).saveDevice(any())
            model.resetCalibration(CalibrationEditorTarget.Device)
            verify(calibration).resetDevice()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
