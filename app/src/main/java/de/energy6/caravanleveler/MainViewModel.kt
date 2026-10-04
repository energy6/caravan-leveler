package de.energy6.caravanleveler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isConnectEnabled : Boolean = false,
    val isConnected : Boolean = false,
    val requestPermissions: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val mSensorRepository: ISensorRepository,
    private val mUserPreferencesRepository: IUserPreferencesRepository) : ViewModel() {

    val uiState = mSensorRepository.scan.combine(mSensorRepository.status) { scan, status ->
        MainUiState(
            isConnectEnabled = status.connectable and !status.connected.isBusy,
            isConnected = status.connected.isConnected || status.connected.isConnecting,
            requestPermissions = scan.requestPermissions
        )
    }

    init {
        viewModelScope.launch {
            mUserPreferencesRepository.data.collect {
                mSensorRepository.selectSensor(it.selectedSensor, it.coordinates, it.autoConnect)
            }
        }
    }

    fun connect(state: Boolean) {
        mSensorRepository.connect(state)
    }

    fun permissionsRequested() {
        mSensorRepository.permissionsRequested()
    }

}
