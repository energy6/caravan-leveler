package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import de.energy6.caravanleveler.IsCloseTo.closeTo
import de.energy6.caravanleveler.math.times
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    @Test
    fun `calibration button appears only after three stable seconds`() = runTest {
        withViewModel { viewModel, sensor, clock, _ ->
            viewModel.setCalibrationMonitoring(true)
            runCurrent()

            feedStableDevice(sensor, clock, intervals = 30)
            assertThat(viewModel.uiState.value.calibrationButtonVisible, equalTo(false))

            feedStableDevice(sensor, clock, intervals = 1)
            assertThat(viewModel.uiState.value.calibrationButtonVisible, equalTo(true))

            clock.advanceBy(100)
            sensor.deviceData.value = reading(
                Quaternion.eulerAngles(Vector3(0f, 0f, 5f)),
                clock.nowNanos()
            )
            advanceTimeBy(100)
            runCurrent()
            assertThat(viewModel.uiState.value.calibrationButtonVisible, equalTo(false))
        }
    }

    @Test
    fun `target is device without device calibration even when vehicle is connected`() = runTest {
        withViewModel(status = externalStatus()) { viewModel, sensor, clock, _ ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()

            assertThat(
                viewModel.uiState.value.calibrationDialog?.target,
                equalTo(CalibrationTarget.DEVICE)
            )
            assertThat(sensor.isMonitoringCalibration, equalTo(true))
        }
    }

    @Test
    fun `target is vehicle only after device calibration and a separate tap`() = runTest {
        val initialCalibration = CalibrationState(
            device = CalibrationValue.from(Quaternion.identity())
        )
        withViewModel(
            status = externalStatus(),
            calibrationState = initialCalibration
        ) { viewModel, sensor, clock, _ ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()

            assertThat(
                viewModel.uiState.value.calibrationDialog?.target,
                equalTo(CalibrationTarget.VEHICLE)
            )
            assertThat(sensor.isMonitoringCalibration, equalTo(false))
        }
    }

    @Test
    fun `device calibration saves only after independent fifth position`() = runTest {
        withViewModel(status = externalStatus()) { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()

            val positions = yawPositions()
            positions.take(4).forEach { captureDevicePosition(viewModel, sensor, clock, it) }

            val waitingForValidation = requireNotNull(viewModel.uiState.value.calibrationDialog)
            assertThat(waitingForValidation.position, equalTo(5))
            assertThat(waitingForValidation.hasCandidate, equalTo(true))
            assertThat(calibration.deviceSaves.size, equalTo(0))

            captureDevicePosition(viewModel, sensor, clock, positions.last())

            assertThat(calibration.deviceSaves.size, equalTo(1))
            assertThat(calibration.vehicleSaves.size, equalTo(0))
            assertThat(viewModel.uiState.value.calibrationDialog, equalTo(null))
            assertThat(sensor.isMonitoringCalibration, equalTo(false))
        }
    }

    @Test
    fun `failed fifth position preserves previous calibration and offers full restart`() = runTest {
        val previous = CalibrationValue.from(
            Quaternion.eulerAngles(Vector3(1f, -1f, 0f))
        )
        withViewModel(
            calibrationState = CalibrationState(device = previous)
        ) { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()

            val positions = yawPositions()
            positions.take(4).forEach { captureDevicePosition(viewModel, sensor, clock, it) }
            val invalidValidation = Quaternion.eulerAngles(Vector3(0.3f, 0f, 0f)) *
                positions.last()
            captureDevicePosition(viewModel, sensor, clock, invalidValidation)

            val dialog = requireNotNull(viewModel.uiState.value.calibrationDialog)
            assertThat(dialog.phase, equalTo(CalibrationDialogPhase.VALIDATION_FAILED))
            assertThat(dialog.error, equalTo(CalibrationError.VALIDATION_FAILED))
            assertThat(dialog.validationErrorDegrees!! > 0.2f, equalTo(true))
            assertThat(calibration.deviceSaves.size, equalTo(0))
            assertThat(calibration.data.value.device, equalTo(previous))

            viewModel.startCalibration()
            runCurrent()
            assertThat(viewModel.uiState.value.calibrationDialog?.position, equalTo(1))
        }
    }

    @Test
    fun `unstable fifth capture can be retried without saving`() = runTest {
        withViewModel { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()
            val positions = yawPositions()
            positions.take(4).forEach { captureDevicePosition(viewModel, sensor, clock, it) }

            feedUntilCapturing(viewModel, sensor, clock, positions.last())
            clock.advanceBy(600)
            advanceTimeBy(100)
            runCurrent()

            val failed = requireNotNull(viewModel.uiState.value.calibrationDialog)
            assertThat(failed.phase, equalTo(CalibrationDialogPhase.RETRYABLE_ERROR))
            assertThat(calibration.deviceSaves.size, equalTo(0))

            viewModel.startCalibration()
            runCurrent()
            captureDevicePosition(viewModel, sensor, clock, positions.last())
            assertThat(calibration.deviceSaves.size, equalTo(1))
        }
    }

    @Test
    fun `vehicle calibration is stored for the connected sensor and axes`() = runTest {
        val coordinates = Sensor.Coordinates(Sensor.Axis.Y, Sensor.Axis.MINUS_X)
        val status = externalStatus(sensorId = "AA:BB:CC:DD:EE:FF", coordinates = coordinates)
        val initialCalibration = CalibrationState(
            device = CalibrationValue.from(Quaternion.identity())
        )
        withViewModel(
            status = status,
            calibrationState = initialCalibration
        ) { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()

            val device = Quaternion.eulerAngles(Vector3(2f, -1f, 15f))
            val vehicle = Quaternion.eulerAngles(Vector3(-1f, 3f, -8f))
            feedVehicleCapture(sensor, clock, correctedDevice = device, rawVehicle = vehicle)

            assertThat(calibration.vehicleSaves.size, equalTo(1))
            val saved = calibration.vehicleSaves.single()
            assertThat(saved.key, equalTo(VehicleCalibrationKey(status.selectedSensorId, coordinates)))
            assertThat(
                CalibrationMath.angularDistanceDegrees(
                    device,
                    CalibrationMath.apply(saved.correction, vehicle)
                ),
                closeTo(0f, 0.001f)
            )
            assertThat(viewModel.uiState.value.calibrationDialog, equalTo(null))
        }
    }

    @Test
    fun `cancel preserves the previous calibration`() = runTest {
        val previous = CalibrationValue.from(Quaternion.eulerAngles(Vector3(1f, -1f, 0f)))
        withViewModel(
            calibrationState = CalibrationState(device = previous)
        ) { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()
            feedStableMotion(viewModel, sensor, clock, Quaternion.identity(), 10)

            viewModel.cancelCalibration()
            runCurrent()

            assertThat(calibration.data.value.device, equalTo(previous))
            assertThat(calibration.deviceSaves.size, equalTo(0))
            assertThat(viewModel.uiState.value.calibrationDialog, equalTo(null))
            assertThat(sensor.isMonitoringCalibration, equalTo(false))
        }
    }

    @Test
    fun `vehicle disconnect reports an error without replacing calibration`() = runTest {
        val coordinates = Sensor.Coordinates()
        val key = VehicleCalibrationKey("AA:BB:CC:DD:EE:FF", coordinates)
        val previousVehicle = CalibrationValue.from(
            Quaternion.eulerAngles(Vector3(0.5f, -0.5f, 2f))
        )
        val initialCalibration = CalibrationState(
            device = CalibrationValue.from(Quaternion.identity()),
            vehicles = mapOf(key to previousVehicle)
        )
        withViewModel(
            status = externalStatus(sensorId = key.sensorId, coordinates = coordinates),
            calibrationState = initialCalibration
        ) { viewModel, sensor, clock, calibration ->
            makeButtonVisible(viewModel, sensor, clock)
            viewModel.openCalibration()
            viewModel.startCalibration()
            runCurrent()

            sensor.status.value = sensor.status.value.copy(
                connected = Sensor.ConnectionState.DISCONNECTED
            )
            feedVehicleCapture(sensor, clock, sampleCount = 1)

            assertThat(
                viewModel.uiState.value.calibrationDialog?.error,
                equalTo(CalibrationError.VEHICLE_DISCONNECTED)
            )
            assertThat(calibration.vehicleSaves.size, equalTo(0))
            assertThat(calibration.data.value.vehicles[key], equalTo(previousVehicle))
        }
    }

    private suspend fun TestScope.withViewModel(
        status: SensorStatus = SensorStatus(connected = Sensor.ConnectionState.CONNECTED),
        calibrationState: CalibrationState = CalibrationState(),
        test: suspend TestScope.(
            LevelerViewModel,
            FakeSensorRepository,
            FakeClock,
            FakeCalibrationRepository
        ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sensor = FakeSensorRepository(status)
        val clock = FakeClock()
        val calibration = FakeCalibrationRepository(calibrationState)
        val viewModel = LevelerViewModel(
            sensor,
            FakePreferencesRepository(),
            calibration,
            clock
        )
        try {
            runCurrent()
            test(viewModel, sensor, clock, calibration)
        } finally {
            viewModel.setCalibrationMonitoring(false)
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private suspend fun TestScope.makeButtonVisible(
        viewModel: LevelerViewModel,
        sensor: FakeSensorRepository,
        clock: FakeClock
    ) {
        viewModel.setCalibrationMonitoring(true)
        runCurrent()
        feedStableDevice(sensor, clock, intervals = 31)
        check(viewModel.uiState.value.calibrationButtonVisible)
    }

    private suspend fun TestScope.feedStableDevice(
        sensor: FakeSensorRepository,
        clock: FakeClock,
        intervals: Int
    ) {
        repeat(intervals) {
            clock.advanceBy(100)
            sensor.deviceData.value = reading(Quaternion.identity(), clock.nowNanos())
            sensor.deviceRawData.value = sensor.deviceData.value
            advanceTimeBy(100)
            runCurrent()
        }
    }

    private suspend fun TestScope.captureDevicePosition(
        viewModel: LevelerViewModel,
        sensor: FakeSensorRepository,
        clock: FakeClock,
        rotation: Quaternion
    ) {
        feedUntilCapturing(viewModel, sensor, clock, rotation)
        repeat(20) {
            emitMotion(sensor, clock, rotation, 100)
            advanceTimeBy(100)
            runCurrent()
        }
    }

    private suspend fun TestScope.feedUntilCapturing(
        viewModel: LevelerViewModel,
        sensor: FakeSensorRepository,
        clock: FakeClock,
        rotation: Quaternion
    ) {
        var attempts = 0
        while (viewModel.uiState.value.calibrationDialog?.phase !=
            CalibrationDialogPhase.CAPTURING
        ) {
            check(attempts++ < 100) {
                "Calibration did not reach capture: ${viewModel.uiState.value.calibrationDialog}"
            }
            emitMotion(sensor, clock, rotation, 50)
            advanceTimeBy(50)
            runCurrent()
        }
    }

    private suspend fun TestScope.feedStableMotion(
        viewModel: LevelerViewModel,
        sensor: FakeSensorRepository,
        clock: FakeClock,
        rotation: Quaternion,
        intervals: Int
    ) {
        repeat(intervals) {
            emitMotion(sensor, clock, rotation, 50)
            advanceTimeBy(50)
            runCurrent()
        }
        check(viewModel.uiState.value.calibrationDialog != null)
    }

    private fun emitMotion(
        sensor: FakeSensorRepository,
        clock: FakeClock,
        rotation: Quaternion,
        milliseconds: Long
    ) {
        clock.advanceBy(milliseconds)
        val timestamp = clock.nowNanos()
        sensor.calibrationMotionData.value = CalibrationMotionData(
            absoluteRotation = rotation,
            absoluteTimestampNanos = timestamp,
            motionRotation = rotation,
            motionTimestampNanos = timestamp,
            gravity = Vector3(0f, 0f, 9.81f),
            gravityTimestampNanos = timestamp,
            angularVelocity = Vector3.zero(),
            gyroscopeTimestampNanos = timestamp,
            capabilities = SUPPORTED_CAPABILITIES,
            isActive = true
        )
    }

    private suspend fun TestScope.feedVehicleCapture(
        sensor: FakeSensorRepository,
        clock: FakeClock,
        sampleCount: Int = 20,
        correctedDevice: Quaternion = Quaternion.identity(),
        rawVehicle: Quaternion = Quaternion.identity()
    ) {
        repeat(sampleCount) {
            clock.advanceBy(100)
            val timestamp = clock.nowNanos()
            sensor.deviceData.value = reading(correctedDevice, timestamp)
            sensor.rawData.value = reading(rawVehicle, timestamp)
            advanceTimeBy(100)
            runCurrent()
        }
    }

    private fun yawPositions(): List<Quaternion> = listOf(0f, 90f, 180f, 270f, 360f)
        .map { angle -> Quaternion.eulerAngles(Vector3(0f, 0f, angle)) }

    private fun reading(rotation: Quaternion, timestamp: Long) = SensorData(
        q = rotation,
        timestampNanos = timestamp,
        isValid = true
    )

    private fun externalStatus(
        sensorId: String = "AA:BB:CC:DD:EE:FF",
        coordinates: Sensor.Coordinates = Sensor.Coordinates()
    ) = SensorStatus(
        connected = Sensor.ConnectionState.CONNECTED,
        selectedSensorId = sensorId,
        isBuiltin = false,
        coordinates = coordinates
    )

    private class FakeClock(var now: Long = 1_000_000_000L) : MonotonicClock {
        override fun nowNanos(): Long = now

        fun advanceBy(milliseconds: Long) {
            now += milliseconds * 1_000_000L
        }
    }

    private class FakeSensorRepository(initialStatus: SensorStatus) : ISensorRepository {
        override val status = MutableStateFlow(initialStatus)
        override val data = MutableStateFlow(SensorData())
        override val rawData = MutableStateFlow(SensorData())
        override val deviceData = MutableStateFlow(SensorData())
        override val deviceRawData = MutableStateFlow(SensorData())
        override val calibrationMotionData = MutableStateFlow(
            CalibrationMotionData(capabilities = SUPPORTED_CAPABILITIES)
        )
        override val scan: Flow<ScanResult> = MutableStateFlow(ScanResult())
        var isMonitoringDevice = false
        var isMonitoringCalibration = false

        override fun selectSensor(
            id: String,
            coordinates: Sensor.Coordinates,
            autoConnect: Boolean
        ) = Unit

        override fun connect(state: Boolean) = Unit

        override fun monitorDevice(state: Boolean) {
            isMonitoringDevice = state
        }

        override fun monitorCalibrationMotion(state: Boolean) {
            isMonitoringCalibration = state
            calibrationMotionData.value = calibrationMotionData.value.copy(isActive = state)
        }

        override fun scan(state: Boolean) = Unit
        override fun permissionsRequested() = Unit
    }

    private class FakePreferencesRepository : IUserPreferencesRepository {
        override val data: Flow<UserPreferences> = MutableStateFlow(UserPreferences())
        override fun addKnownSensor(id: String, name: String) = Unit
    }

    private data class VehicleSave(
        val key: VehicleCalibrationKey,
        val correction: Quaternion
    )

    private class FakeCalibrationRepository(initialState: CalibrationState) :
        ICalibrationRepository {
        override val data: StateFlow<CalibrationState>
            get() = mutableData
        private val mutableData = MutableStateFlow(initialState)
        val deviceSaves = mutableListOf<Quaternion>()
        val vehicleSaves = mutableListOf<VehicleSave>()

        override fun saveDevice(correction: Quaternion) {
            deviceSaves += correction
            mutableData.value = CalibrationState(device = CalibrationValue.from(correction))
        }

        override fun saveVehicle(
            sensorId: String,
            coordinates: Sensor.Coordinates,
            correction: Quaternion
        ) {
            val key = VehicleCalibrationKey(sensorId, coordinates)
            vehicleSaves += VehicleSave(key, correction)
            mutableData.value = mutableData.value.copy(
                vehicles = mutableData.value.vehicles +
                    (key to CalibrationValue.from(correction))
            )
        }
    }

    companion object {
        private val SUPPORTED_CAPABILITIES = CalibrationSensorCapabilities(
            hasRotationVector = true,
            hasGameRotationVector = true,
            hasGravity = true,
            hasGyroscope = true
        )
    }
}
