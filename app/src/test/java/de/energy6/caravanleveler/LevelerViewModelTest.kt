package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
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

import de.energy6.caravanleveler.IsCloseTo.closeTo
import de.energy6.caravanleveler.math.*

class LevelerViewModelTest {
    companion object {
        @JvmStatic
        fun sensor_data() = listOf(
            Arguments.of(
                Quaternion.identity(),
                LevelerCaravanState(
                    axis = 0.0f,
                    compass = Quaternion.identity(),
                    position = Vector3.zero(),
                    rotation = Quaternion.identity(),
                    stabilizer = 0.0f
                )
            ),
            Arguments.of(
                Quaternion.eulerAngles(Vector3(5f, 2f, 0f)),
                LevelerCaravanState(
                    axis = 7.329f,
                    compass = Quaternion(0f, 0f, 7.6126E-4f, 0.9999997f),
                    position = Vector3(0f, 0f, 0.005583919f),
                    rotation = Quaternion(0.04359946f, 0.01746899f, -8.401694E-7f, 0.99889636f),
                    stabilizer = -26.146f
                )
            ),
            Arguments.of(
                Quaternion.eulerAngles(Vector3(0f, 0f, 230f)),
                LevelerCaravanState(
                    axis = 0.0f,
                    compass = Quaternion.eulerAngles(Vector3(0f, 0f, -230f)),
                    position = Vector3.zero(),
                    rotation = Quaternion.eulerAngles(Vector3(0f, 0f, 0f)),
                    stabilizer = 0.0f
                )
            ),
            Arguments.of(
                Quaternion.eulerAngles(Vector3(0f, 0f, -20f)) * Quaternion.eulerAngles(Vector3(-3f, 1f, 0f)),
                LevelerCaravanState(
                    axis = 3.665f,
                    compass = Quaternion.eulerAngles(Vector3(0f, 0f, 20f)),
                    position = Vector3(0f, 0f, 0.0027923856f),
                    rotation = Quaternion(-0.026173392f, 0.00873123f, -6.499887E-5f, 0.99961936f),
                    stabilizer = 15.701f
                )
            )
        )
    }

    private val mStatusFake = MutableStateFlow(SensorStatus())
    private val mDataFake = MutableStateFlow(SensorData())
    private val mPrefDataFake = MutableStateFlow(UserPreferences())
    private val mCalibrationFake = MutableStateFlow(CalibrationState())
    private val mCalibrationRepository = mock<ICalibrationRepository> {
        on { data } doReturn mCalibrationFake
    }
    private val mClock = mock<MonotonicClock> {
        on { nowNanos() } doReturn 1_000_000_000L
    }

    @Test
    fun `test initial ui state`() = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { data } doReturn mDataFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = LevelerViewModel(
            sensorRepository,
            userPreferencesRepository,
            mCalibrationRepository,
            mClock
        )

        val uiState = viewModel.uiState.first()
        assertThat(uiState.showCompass, equalTo(false))
        assertThat(uiState.calibrationButtonVisible, equalTo(false))
        assertThat(uiState.calibrationDialog, equalTo(null))

        val cameraState = viewModel.cameraState.first()
        assertThat(
            cameraState.direction,
            equalTo(Quaternion.lookRotation(Vector3.up(), Vector3.back()))
        )
        assertThat(cameraState.position, equalTo(Vector3(0f, -1f, 0.080861375f)))
        assertThat(cameraState.rotation, equalTo(120f))
        assertThat(cameraState.verticalFovDegrees, equalTo(52.16955f))

        viewModel.rotateCamera()
        val sideCameraState = viewModel.cameraState.first()
        assertThat(
            sideCameraState.direction,
            equalTo(Quaternion.lookRotation(Vector3.left(), Vector3.back()))
        )
        assertThat(sideCameraState.position, equalTo(Vector3(1f, 0.018092765f, 0.09227779f)))
        assertThat(sideCameraState.rotation, equalTo(240f))
        assertThat(sideCameraState.verticalFovDegrees, equalTo(74.79099f))

        viewModel.rotateCamera()
        val topCameraState = viewModel.cameraState.first()
        assertThat(
            topCameraState.direction,
            equalTo(Quaternion.lookRotation(Vector3.forward(), Vector3.up()))
        )
        assertThat(topCameraState.position, equalTo(Vector3(0f, 0f, 1f)))
        assertThat(topCameraState.rotation, equalTo(360f))
        assertThat(topCameraState.verticalFovDegrees, equalTo(67.385414f))

        val caravanState = viewModel.caravanState.first()
        assertThat(caravanState.axis, closeTo(0f, 0.001f))
        assertThat(caravanState.compass, equalTo(Quaternion.identity()))
        assertThat(caravanState.position, equalTo(Vector3.zero()))
        assertThat(caravanState.rotation, equalTo(Quaternion.identity()))
        assertThat(caravanState.stabilizer, closeTo(0f, 0.001f))
    }

    @ParameterizedTest
    @MethodSource("sensor_data")
    fun `test sensor data update`(q: Quaternion, expected: LevelerCaravanState)  = runTest {
        Dispatchers.setupForTest()

        val sensorRepository = mock<ISensorRepository> {
            on { data } doReturn mDataFake
            on { status } doReturn mStatusFake
        }

        val userPreferencesRepository = mock<IUserPreferencesRepository> {
            on { data } doReturn mPrefDataFake
        }

        val viewModel = LevelerViewModel(
            sensorRepository,
            userPreferencesRepository,
            mCalibrationRepository,
            mClock
        )

        var caravanState = viewModel.caravanState.first()

        mDataFake.update { SensorData(q, timestampNanos = 1L, isValid = true) }

        caravanState = viewModel.caravanState.first {
            it != caravanState
        }

        assertThat(caravanState.axis, closeTo(expected.axis, 0.001f))
        assertThat(caravanState.compass, equalTo(expected.compass))
        assertThat(caravanState.position, equalTo(expected.position))
        assertThat(caravanState.rotation, equalTo(expected.rotation))
        assertThat(caravanState.stabilizer, closeTo(expected.stabilizer, 0.001f))
    }
}
