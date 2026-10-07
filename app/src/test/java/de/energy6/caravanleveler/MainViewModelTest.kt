package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class MainViewModelTest {
    companion object {
        @JvmStatic
        fun connection_states() = listOf(
            Arguments.of(
                Sensor.ConnectionState.DISCONNECTED,
                true, false
            ),
            Arguments.of(
                Sensor.ConnectionState.CONNECTING,
                false, true
            ),
            Arguments.of(
                Sensor.ConnectionState.CONNECTED,
                true, true
            ),
            Arguments.of(
                Sensor.ConnectionState.DISCONNECTING,
                false, false
            ),
        )
    }

    private val mScanFake = MutableStateFlow(ScanResult())
    private val mStatusFake = MutableStateFlow(SensorStatus())
    private val mPrefDataFake = MutableStateFlow(UserPreferences())

    @Test
    fun `test initial ui state`() = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { scan } doReturn mScanFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = MainViewModel(sensorRepository, userPreferencesRepository)

        val uiState = viewModel.uiState.first()
        assertThat(uiState.isConnectEnabled, equalTo(true))
        assertThat(uiState.isConnected, equalTo(false))
        assertThat(uiState.requestPermissions, equalTo(false))

        verify(sensorRepository).selectSensor(SENSOR_BUILTIN_ID, Sensor.Coordinates(), false)
    }

    @Test
    fun `test another sensor is selected via preferences`() = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { scan } doReturn mScanFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = MainViewModel(sensorRepository, userPreferencesRepository)

        val expectSensor = "AnotherSensor"
        val expectCoordinates = Sensor.Coordinates(Sensor.Axis.Z, Sensor.Axis.X)
        val expectAutoConnect = true

        mPrefDataFake.update {
            it.copy(
                selectedSensor = expectSensor,
                coordinates = expectCoordinates,
                autoConnect = expectAutoConnect
            )
        }

        val uiState = viewModel.uiState.first()
        assertThat(uiState.isConnectEnabled, equalTo(true))
        assertThat(uiState.isConnected, equalTo(false))
        assertThat(uiState.requestPermissions, equalTo(false))

        verify(sensorRepository).selectSensor(expectSensor, expectCoordinates, expectAutoConnect)
    }

    @Test
    fun `test permission request`() = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { scan } doReturn mScanFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = MainViewModel(sensorRepository, userPreferencesRepository)

        mScanFake.update { it.copy(requestPermissions = true) }

        var uiState = viewModel.uiState.first()
        assertThat(uiState.requestPermissions, equalTo(true))

        mScanFake.update { it.copy(requestPermissions = false) }

        uiState = viewModel.uiState.first()
        assertThat(uiState.requestPermissions, equalTo(false))
    }

    @ParameterizedTest
    @MethodSource("connection_states")
    fun `test connection states`(state: Sensor.ConnectionState,
                                 connectEnabled: Boolean,
                                 connected: Boolean) = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { scan } doReturn mScanFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = MainViewModel(sensorRepository, userPreferencesRepository)

        mStatusFake.update { it.copy(connectable = true, connected = state) }

        val uiState = viewModel.uiState.first()
        assertThat(uiState.isConnectEnabled, equalTo(connectEnabled))
        assertThat(uiState.isConnected, equalTo(connected))
    }
}
