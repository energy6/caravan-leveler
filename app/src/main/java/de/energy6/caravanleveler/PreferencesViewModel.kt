package de.energy6.caravanleveler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_NAME
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreferencesUiState(
    val knownSensors: Map<String, String> = mapOf(SENSOR_BUILTIN_ID to SENSOR_BUILTIN_NAME),
    val bleScan: Boolean = false,
    val autoConnect : Boolean = false,
    val coordinates: Sensor.Coordinates = Sensor.Coordinates()
)

@HiltViewModel
class PreferencesViewModel
@Inject constructor(
    private val mSensorRepository: ISensorRepository,
    private val mPreferencesRepository: IUserPreferencesRepository) : ViewModel() {

    lateinit var uiState : StateFlow<PreferencesUiState>
        private set

    init {
        viewModelScope.launch {
            uiState = mSensorRepository.scan.combine(mPreferencesRepository.data) { scan, data ->
                PreferencesUiState(
                    knownSensors = data.knownSensors + scan.sensors,
                    bleScan = scan.active,
                    autoConnect = data.autoConnect,
                    coordinates = data.coordinates
                )
            }.stateIn(viewModelScope)
        }
    }

    fun bleScan(state: Boolean) : Boolean {
        mSensorRepository.scan(state)
        return true
    }

    fun addKnownSensor(id: String, name: String) {
        mPreferencesRepository.addKnownSensor(id, name)
    }
}
