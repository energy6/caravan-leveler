package de.energy6.caravanleveler

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_NAME
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PreferencesUiState(
    val knownSensors: Map<String, String> = mapOf(SENSOR_BUILTIN_ID to SENSOR_BUILTIN_NAME),
    val selectedSensorId: String = SENSOR_BUILTIN_ID,
    val bleScan: Boolean = false,
    val autoConnect: Boolean = false,
    val coordinates: Sensor.Coordinates = Sensor.Coordinates(),
    val deviceCalibration: CalibrationValue? = null,
    val vehicleCalibration: CalibrationValue? = null,
    val snapshotCount: Int = 0,
    val snapshotBusy: Boolean = false
)

sealed interface CalibrationEditorTarget {
    data object Device : CalibrationEditorTarget
    data class Vehicle(val key: VehicleCalibrationKey) : CalibrationEditorTarget
}

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val mSensorRepository: ISensorRepository,
    private val mPreferencesRepository: IUserPreferencesRepository,
    private val calibrations: ICalibrationRepository,
    private val snapshots: SensorSnapshotRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val mutableMessages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages = mutableMessages.asSharedFlow()

    val uiState = combine(
        mSensorRepository.scan, mPreferencesRepository.data, calibrations.data, snapshots.count
    ) { scan, preferences, calibration, count ->
        PreferencesUiState(
            knownSensors = preferences.knownSensors + scan.sensors,
            selectedSensorId = preferences.selectedSensor,
            bleScan = scan.active,
            autoConnect = preferences.autoConnect,
            coordinates = preferences.coordinates,
            deviceCalibration = calibration.device,
            vehicleCalibration = calibration.vehicles[
                VehicleCalibrationKey(preferences.selectedSensor, preferences.coordinates)
            ],
            snapshotCount = count
        )
    }.combine(busy) { state, active -> state.copy(snapshotBusy = active) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PreferencesUiState())

    init {
        snapshotAction(null) { snapshots.refresh() }
    }

    fun bleScan(state: Boolean): Boolean {
        mSensorRepository.scan(state)
        return true
    }

    fun addKnownSensor(id: String, name: String) {
        mPreferencesRepository.addKnownSensor(id, name)
    }

    fun saveCalibration(target: CalibrationEditorTarget, components: List<String>): Boolean {
        val correction = parseCalibrationInput(components) ?: return false
        when (target) {
            CalibrationEditorTarget.Device -> {
                if (calibrations.data.value.deviceCorrection() != correction) calibrations.saveDevice(correction)
            }
            is CalibrationEditorTarget.Vehicle -> calibrations.saveVehicle(
                target.key.sensorId, target.key.coordinates, correction
            )
        }
        return true
    }

    fun resetCalibration(target: CalibrationEditorTarget) {
        when (target) {
            CalibrationEditorTarget.Device -> calibrations.resetDevice()
            is CalibrationEditorTarget.Vehicle -> calibrations.resetVehicle(target.key.sensorId, target.key.coordinates)
        }
    }

    fun monitorDevice(enabled: Boolean) = mSensorRepository.monitorDevice(enabled)
    fun captureSnapshot() = snapshotAction(R.string.snapshot_saved) { snapshots.capture() }
    fun clearSnapshots() = snapshotAction(R.string.snapshots_deleted) { snapshots.clear() }

    fun exportSnapshots(uri: Uri) = snapshotAction(R.string.snapshots_exported) {
        val json = snapshots.export()
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Cannot open snapshot destination")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
    }

    private fun snapshotAction(success: Int?, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                action()
                success?.let { mutableMessages.emit(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableMessages.emit(R.string.snapshot_failed)
            } finally {
                busy.value = false
            }
        }
    }
}
