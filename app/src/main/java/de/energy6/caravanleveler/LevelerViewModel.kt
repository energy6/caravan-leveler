package de.energy6.caravanleveler

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import dagger.hilt.android.lifecycle.HiltViewModel
import de.energy6.caravanleveler.math.times
import de.energy6.caravanleveler.math.toOrientation
import de.energy6.caravanleveler.math.toRadians
import de.energy6.caravanleveler.math.unaryMinus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

enum class CalibrationTarget {
    DEVICE,
    VEHICLE
}

enum class CalibrationError {
    UNSTABLE,
    SENSOR_UNAVAILABLE,
    VEHICLE_DISCONNECTED,
    INVALID_ROTATION,
    INCONSISTENT_POSITIONS,
    VALIDATION_FAILED
}

enum class CalibrationDialogPhase {
    READY,
    TURNING,
    STABILIZING,
    CAPTURING,
    VALIDATING,
    RETRYABLE_ERROR,
    RESTART_REQUIRED,
    VALIDATION_FAILED
}

data class CalibrationDialogState(
    val target: CalibrationTarget,
    val phase: CalibrationDialogPhase = CalibrationDialogPhase.READY,
    val position: Int = 1,
    val positionCount: Int = if (target == CalibrationTarget.DEVICE) 5 else 1,
    val relativeTurnDegrees: Float = 0f,
    val totalTurnDegrees: Float = 0f,
    val isLiveRotation: Boolean = false,
    val turnTargetReached: Boolean = false,
    val stabilityProgress: Float = 0f,
    val captureProgress: Float = 0f,
    val hasCandidate: Boolean = false,
    val validationErrorDegrees: Float? = null,
    val error: CalibrationError? = null
) {
    val isRunning: Boolean
        get() = phase in setOf(
            CalibrationDialogPhase.TURNING,
            CalibrationDialogPhase.STABILIZING,
            CalibrationDialogPhase.CAPTURING,
            CalibrationDialogPhase.VALIDATING
        )
}

sealed interface CalibrationEvent {
    data class Succeeded(val target: CalibrationTarget) : CalibrationEvent
}

data class LevelerUiState(
    val showCompass: Boolean = false,
    val calibrationButtonVisible: Boolean = false,
    val calibrationDialog: CalibrationDialogState? = null
)

data class LevelerCameraState(
    val rotation: Float = 120.0f,
    val position: Vector3 = -VIEWPANES[0].first,
    val direction: Quaternion = Quaternion.lookRotation(VIEWPANES[0].first, VIEWPANES[0].second),
    val verticalFovDegrees: Float = 90f
)

data class LevelerCaravanState(
    val rotation: Quaternion = Quaternion(),
    val position: Vector3 = Vector3.zero(),
    val compass: Quaternion = Quaternion(),
    val axis: Float = 0f,
    val stabilizer: Float = 0f
)

private val VIEWPANES = arrayListOf(
    Pair(Vector3.up(), Vector3.back()),
    Pair(Vector3.left(), Vector3.back()),
    Pair(Vector3.forward(), Vector3.up())
)

private const val CALIBRATION_SAMPLE_COUNT = 20
private const val CALIBRATION_SAMPLE_INTERVAL_MILLIS = 100L
private const val CALIBRATION_MONITOR_INTERVAL_MILLIS = 50L
private const val MINIMUM_DISTINCT_SAMPLE_COUNT = 10
private const val MINIMUM_TOTAL_TURN_DEGREES = 345f
private const val MAXIMUM_TOTAL_TURN_DEGREES = 375f
private const val LIVE_ROTATION_THRESHOLD_DEGREES = 3f
private const val MAXIMUM_GYROSCOPE_AXIS_DEVIATION_DEGREES = 25f
private const val MAXIMUM_VALIDATION_ERROR_DEGREES = 0.2f
private const val MAXIMUM_STATIC_GYROSCOPE_RADIANS_PER_SECOND = 0.035f
private const val MINIMUM_GRAVITY_METERS_PER_SECOND_SQUARED = 7f
private const val MAXIMUM_GRAVITY_METERS_PER_SECOND_SQUARED = 12f
private const val MAXIMUM_GRAVITY_DIRECTION_SPREAD_DEGREES = 1f

@HiltViewModel
class LevelerViewModel @Inject constructor(
    private val sensorRepository: ISensorRepository,
    private val userPreferencesRepository: IUserPreferencesRepository,
    private val calibrationRepository: ICalibrationRepository,
    private val clock: MonotonicClock
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(LevelerUiState())
    val uiState = mutableUiState.asStateFlow()

    private val mutableCalibrationEvents = MutableSharedFlow<CalibrationEvent>(extraBufferCapacity = 1)
    val calibrationEvents = mutableCalibrationEvents.asSharedFlow()

    private val mutableCameraState = MutableStateFlow(LevelerCameraState())
    val cameraState = mutableCameraState.asStateFlow()

    private val mutableCaravanState = MutableStateFlow(LevelerCaravanState())
    val caravanState = mutableCaravanState.asStateFlow()

    private val stabilityDetector = StabilityDetector()
    private var monitoringJob: Job? = null
    private var calibrationJob: Job? = null
    private var vehicleSession: VehicleCalibrationKey? = null
    private var deviceSession: DeviceCalibrationSession? = null
    private var viewPane = 0
    private var width = 2.1f
    private var length = 3.0f

    private sealed interface CaptureResult {
        data class Success(val correction: Quaternion) : CaptureResult
        data class Failure(val error: CalibrationError) : CaptureResult
    }

    private data class DeviceCalibrationSession(
        val poses: MutableList<DeviceCalibrationPose> = mutableListOf(),
        val turnAnglesDegrees: MutableList<Float> = mutableListOf(),
        val turnAxes: MutableList<Vector3> = mutableListOf(),
        var candidate: DeviceCalibrationSolution? = null
    )

    private data class TurnObservation(
        val gyroscopeSamples: List<Vector3>
    )

    private sealed interface PoseResult {
        data class Success(val pose: DeviceCalibrationPose) : PoseResult
        data class Failure(val error: CalibrationError) : PoseResult
    }

    private fun calcLevel(angle: Float, distance: Float) = sin(angle.toRadians()) * distance

    init {
        viewModelScope.launch {
            sensorRepository.data.collect { data ->
                if (!data.isValid) return@collect
                val corrected = data.q
                val headingLength = sqrt(
                    corrected.w * corrected.w + corrected.z * corrected.z
                )
                val compass = if (headingLength > 1e-6f) {
                    Quaternion(
                        0f,
                        0f,
                        -corrected.z / headingLength,
                        corrected.w / headingLength
                    )
                } else {
                    Quaternion.identity()
                }
                val rotation = compass * corrected
                val orientation = corrected.toOrientation()
                val axis = calcLevel(orientation.y, width * 100.0f)
                val stabilizer = -calcLevel(orientation.x, length * 100.0f)
                val position = Vector3.back() *
                    abs(sin(rotation.toOrientation().y.toRadians())) * 0.16f
                mutableCaravanState.value = LevelerCaravanState(
                    rotation,
                    position,
                    compass,
                    axis,
                    stabilizer
                )
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.data.collect { preferences ->
                mutableUiState.update { it.copy(showCompass = preferences.showCompass) }
                width = preferences.caravanWidth
                length = preferences.caravanLength
            }
        }
        viewModelScope.launch {
            calibrationRepository.data.collect {
                stabilityDetector.reset()
            }
        }
    }

    fun setCalibrationMonitoring(enabled: Boolean) {
        if (enabled && monitoringJob == null) {
            sensorRepository.monitorDevice(true)
            monitoringJob = viewModelScope.launch {
                while (true) {
                    val now = clock.nowNanos()
                    val ready = stabilityDetector.update(sensorRepository.deviceData.value, now)
                    mutableUiState.update { state ->
                        state.copy(
                            calibrationButtonVisible = ready && state.calibrationDialog == null
                        )
                    }
                    delay(CALIBRATION_SAMPLE_INTERVAL_MILLIS)
                }
            }
        } else if (!enabled) {
            calibrationJob?.cancel()
            calibrationJob = null
            monitoringJob?.cancel()
            monitoringJob = null
            vehicleSession = null
            deviceSession = null
            stabilityDetector.reset()
            sensorRepository.monitorCalibrationMotion(false)
            sensorRepository.monitorDevice(false)
            mutableUiState.update {
                it.copy(calibrationButtonVisible = false, calibrationDialog = null)
            }
        }
    }

    fun openCalibration() {
        val currentState = mutableUiState.value
        if (!currentState.calibrationButtonVisible || currentState.calibrationDialog != null) return

        val calibrations = calibrationRepository.data.value
        val sensorStatus = sensorRepository.status.value
        val externalConnected = !sensorStatus.isBuiltin && sensorStatus.connected.isConnected
        val target = if (calibrations.device == null || !externalConnected) {
            CalibrationTarget.DEVICE
        } else {
            CalibrationTarget.VEHICLE
        }
        vehicleSession = if (target == CalibrationTarget.VEHICLE) {
            VehicleCalibrationKey(sensorStatus.selectedSensorId, sensorStatus.coordinates)
        } else {
            null
        }
        deviceSession = if (target == CalibrationTarget.DEVICE) {
            sensorRepository.monitorCalibrationMotion(true)
            DeviceCalibrationSession()
        } else {
            null
        }
        mutableUiState.update {
            it.copy(
                calibrationButtonVisible = false,
                calibrationDialog = CalibrationDialogState(target)
            )
        }
    }

    fun startCalibration() {
        val dialog = mutableUiState.value.calibrationDialog ?: return
        if (dialog.isRunning || calibrationJob?.isActive == true) return

        if (dialog.target == CalibrationTarget.DEVICE &&
            dialog.phase in setOf(
                CalibrationDialogPhase.RESTART_REQUIRED,
                CalibrationDialogPhase.VALIDATION_FAILED
            )
        ) {
            deviceSession = DeviceCalibrationSession()
        }
        calibrationJob = viewModelScope.launch {
            try {
                if (dialog.target == CalibrationTarget.DEVICE) {
                    runDeviceCalibration()
                } else {
                    mutableUiState.update { state ->
                        state.copy(
                            calibrationDialog = state.calibrationDialog?.copy(
                                phase = CalibrationDialogPhase.CAPTURING,
                                captureProgress = 0f,
                                error = null
                            )
                        )
                    }
                    when (val result = captureVehicle()) {
                        is CaptureResult.Success -> completeCalibration(
                            CalibrationTarget.VEHICLE,
                            result.correction
                        )
                        is CaptureResult.Failure -> showRetryableError(result.error)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } finally {
                calibrationJob = null
            }
        }
    }

    fun cancelCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        vehicleSession = null
        deviceSession = null
        sensorRepository.monitorCalibrationMotion(false)
        mutableUiState.update { it.copy(calibrationDialog = null) }
    }

    fun rotateCamera() {
        viewPane = (viewPane + 1).mod(VIEWPANES.size)
        mutableCameraState.update {
            it.copy(
                rotation = (viewPane + 1) * 120f,
                position = -VIEWPANES[viewPane].first,
                direction = Quaternion.lookRotation(VIEWPANES[viewPane].first, VIEWPANES[viewPane].second)
            )
        }
    }

    private suspend fun runDeviceCalibration() {
        val session = deviceSession ?: DeviceCalibrationSession().also { deviceSession = it }
        if (!sensorRepository.calibrationMotionData.value.capabilities.isSupported) {
            showRetryableError(CalibrationError.SENSOR_UNAVAILABLE)
            return
        }

        while (session.poses.size < 5) {
            val position = session.poses.size + 1
            val turn = awaitDevicePoseReady(session, position) ?: return
            when (val captured = captureDevicePose(session, position)) {
                is PoseResult.Failure -> {
                    showRetryableError(captured.error)
                    return
                }
                is PoseResult.Success -> {
                    if (!acceptDevicePose(session, captured.pose, turn)) return
                }
            }
        }

        val candidate = session.candidate
        if (candidate == null) {
            showRestartError(CalibrationError.INCONSISTENT_POSITIONS)
            return
        }
        mutableUiState.update { state ->
            state.copy(
                calibrationDialog = state.calibrationDialog?.copy(
                    phase = CalibrationDialogPhase.VALIDATING,
                    captureProgress = 1f,
                    error = null
                )
            )
        }
        val validationError = CalibrationMath.deviceValidationErrorDegrees(
            candidate.correction,
            session.poses.first(),
            session.poses.last()
        )
        if (validationError > MAXIMUM_VALIDATION_ERROR_DEGREES) {
            mutableUiState.update { state ->
                state.copy(
                    calibrationDialog = state.calibrationDialog?.copy(
                        phase = CalibrationDialogPhase.VALIDATION_FAILED,
                        validationErrorDegrees = validationError,
                        error = CalibrationError.VALIDATION_FAILED
                    )
                )
            }
            return
        }
        completeCalibration(CalibrationTarget.DEVICE, candidate.correction)
    }

    private suspend fun awaitDevicePoseReady(
        session: DeviceCalibrationSession,
        position: Int
    ): TurnObservation? {
        val detector = StabilityDetector()
        val gyroscopeSamples = mutableListOf<Vector3>()
        var liveRotationStarted = false

        while (true) {
            val now = clock.nowNanos()
            val motion = sensorRepository.calibrationMotionData.value
            if (!motion.capabilities.isSupported) {
                showRetryableError(CalibrationError.SENSOR_UNAVAILABLE)
                return null
            }
            if (!motion.isFresh(now)) {
                detector.reset()
                updateDeviceProgress(
                    position = position,
                    phase = if (position == 1) {
                        CalibrationDialogPhase.STABILIZING
                    } else {
                        CalibrationDialogPhase.TURNING
                    },
                    error = CalibrationError.SENSOR_UNAVAILABLE
                )
                delay(CALIBRATION_MONITOR_INTERVAL_MILLIS)
                continue
            }

            val turn = if (position > 1) {
                CalibrationMath.measureTurn(
                    session.poses.last().motionRotation,
                    motion.motionRotation
                )
            } else {
                null
            }
            val expectedAxis = session.turnAxes.firstOrNull()
            val axisDifference = if (turn != null && expectedAxis != null) {
                CalibrationMath.angleBetweenVectorsDegrees(turn.axis, expectedAxis)
            } else {
                0f
            }
            val directionValid = expectedAxis == null || axisDifference < 90f
            val relativeTurn = when {
                turn == null -> 0f
                directionValid -> turn.angleDegrees
                else -> -turn.angleDegrees
            }
            val totalTurn = session.turnAnglesDegrees.sum() + relativeTurn
            if (kotlin.math.abs(relativeTurn) >= LIVE_ROTATION_THRESHOLD_DEGREES) {
                liveRotationStarted = true
            }

            val axisValid = expectedAxis == null || axisDifference <= 8f
            val quarterTurnReached = relativeTurn in
                CalibrationMath.MINIMUM_QUARTER_TURN_DEGREES..
                    CalibrationMath.MAXIMUM_QUARTER_TURN_DEGREES && axisValid
            val targetReached = position == 1 ||
                (quarterTurnReached &&
                    (position < 5 || totalTurn in
                        MINIMUM_TOTAL_TURN_DEGREES..MAXIMUM_TOTAL_TURN_DEGREES))

            val angularSpeed = motion.angularVelocity.length()
            if (position > 1 && !targetReached && angularSpeed >= 0.08f) {
                gyroscopeSamples += Vector3(motion.angularVelocity)
            }

            if (!targetReached) {
                detector.reset()
                updateDeviceProgress(
                    position = position,
                    phase = CalibrationDialogPhase.TURNING,
                    relativeTurnDegrees = relativeTurn,
                    totalTurnDegrees = totalTurn,
                    liveRotation = liveRotationStarted,
                    targetReached = false,
                    error = if (turn != null && !axisValid && turn.angleDegrees >= 30f) {
                        CalibrationError.INVALID_ROTATION
                    } else {
                        null
                    }
                )
                delay(CALIBRATION_MONITOR_INTERVAL_MILLIS)
                continue
            }

            val reading = SensorData(
                q = motion.motionRotation,
                timestampNanos = motion.motionTimestampNanos,
                isValid = true
            )
            val stable = if (angularSpeed <= MAXIMUM_STATIC_GYROSCOPE_RADIANS_PER_SECOND) {
                detector.update(reading, now)
            } else {
                detector.reset()
                false
            }
            updateDeviceProgress(
                position = position,
                phase = CalibrationDialogPhase.STABILIZING,
                relativeTurnDegrees = relativeTurn,
                totalTurnDegrees = totalTurn,
                liveRotation = liveRotationStarted,
                targetReached = true,
                stabilityProgress = detector.progress,
                error = null
            )
            if (stable) return TurnObservation(gyroscopeSamples.toList())
            delay(CALIBRATION_MONITOR_INTERVAL_MILLIS)
        }
    }

    private suspend fun captureDevicePose(
        session: DeviceCalibrationSession,
        position: Int
    ): PoseResult {
        val samples = mutableListOf<CalibrationMotionData>()
        repeat(CALIBRATION_SAMPLE_COUNT) { index ->
            delay(CALIBRATION_SAMPLE_INTERVAL_MILLIS)
            val now = clock.nowNanos()
            val motion = sensorRepository.calibrationMotionData.value
            if (!motion.isFresh(now)) {
                return PoseResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
            }
            if (position > 1 && !isTurnTargetReached(session, position, motion.motionRotation)) {
                return PoseResult.Failure(CalibrationError.UNSTABLE)
            }
            if (samples.none { it.motionTimestampNanos == motion.motionTimestampNanos }) {
                samples += motion
            }
            updateDeviceProgress(
                position = position,
                phase = CalibrationDialogPhase.CAPTURING,
                relativeTurnDegrees = mutableUiState.value.calibrationDialog
                    ?.relativeTurnDegrees ?: 0f,
                totalTurnDegrees = mutableUiState.value.calibrationDialog
                    ?.totalTurnDegrees ?: 0f,
                liveRotation = mutableUiState.value.calibrationDialog?.isLiveRotation == true,
                targetReached = true,
                stabilityProgress = 1f,
                captureProgress = (index + 1f) / CALIBRATION_SAMPLE_COUNT,
                error = null
            )
        }

        if (samples.size < MINIMUM_DISTINCT_SAMPLE_COUNT) {
            return PoseResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        }
        val motionReadings = samples.map { sample ->
            SensorData(sample.motionRotation, sample.motionTimestampNanos, true)
        }
        if (!CalibrationMath.isStableSequence(motionReadings) ||
            samples.any {
                it.angularVelocity.length() > MAXIMUM_STATIC_GYROSCOPE_RADIANS_PER_SECOND
            }
        ) {
            return PoseResult.Failure(CalibrationError.UNSTABLE)
        }
        val gravity = averageGravity(samples.map { it.gravity })
            ?: return PoseResult.Failure(CalibrationError.UNSTABLE)
        val absolute = CalibrationMath.average(samples.map { it.absoluteRotation })
            ?: return PoseResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        val relative = CalibrationMath.average(samples.map { it.motionRotation })
            ?: return PoseResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        return PoseResult.Success(DeviceCalibrationPose(absolute, relative, gravity))
    }

    private fun acceptDevicePose(
        session: DeviceCalibrationSession,
        pose: DeviceCalibrationPose,
        observation: TurnObservation
    ): Boolean {
        val previous = session.poses.lastOrNull()
        if (previous != null) {
            val turn = CalibrationMath.measureTurn(previous.motionRotation, pose.motionRotation)
                ?: return showRestartError(CalibrationError.INVALID_ROTATION)
            if (turn.angleDegrees !in
                CalibrationMath.MINIMUM_QUARTER_TURN_DEGREES..
                    CalibrationMath.MAXIMUM_QUARTER_TURN_DEGREES
            ) {
                return showRestartError(CalibrationError.INVALID_ROTATION)
            }
            val expectedAxis = session.turnAxes.firstOrNull()
            if (expectedAxis != null &&
                CalibrationMath.angleBetweenVectorsDegrees(turn.axis, expectedAxis) > 8f
            ) {
                return showRestartError(CalibrationError.INVALID_ROTATION)
            }
            val gyroDeviation = CalibrationMath.gyroscopeAxisDeviationDegrees(
                observation.gyroscopeSamples,
                turn.axis
            )
            if (gyroDeviation != null &&
                gyroDeviation > MAXIMUM_GYROSCOPE_AXIS_DEVIATION_DEGREES
            ) {
                return showRestartError(CalibrationError.INVALID_ROTATION)
            }
            session.turnAnglesDegrees += turn.angleDegrees
            session.turnAxes += turn.axis
        }
        session.poses += pose

        if (session.poses.size == 4) {
            val solution = CalibrationMath.solveDeviceCalibration(session.poses.toList())
                ?: return showRestartError(CalibrationError.INCONSISTENT_POSITIONS)
            session.candidate = solution
        }
        if (session.poses.size == 5) {
            val totalTurn = session.turnAnglesDegrees.sum()
            if (totalTurn !in MINIMUM_TOTAL_TURN_DEGREES..MAXIMUM_TOTAL_TURN_DEGREES) {
                return showRestartError(CalibrationError.INVALID_ROTATION)
            }
        }

        val nextPosition = (session.poses.size + 1).coerceAtMost(5)
        updateDeviceProgress(
            position = nextPosition,
            phase = if (session.poses.size < 5) {
                CalibrationDialogPhase.TURNING
            } else {
                CalibrationDialogPhase.VALIDATING
            },
            totalTurnDegrees = session.turnAnglesDegrees.sum(),
            hasCandidate = session.candidate != null,
            error = null
        )
        return true
    }

    private fun isTurnTargetReached(
        session: DeviceCalibrationSession,
        position: Int,
        currentRotation: Quaternion
    ): Boolean {
        val turn = CalibrationMath.measureTurn(
            session.poses.last().motionRotation,
            currentRotation
        ) ?: return false
        val expectedAxis = session.turnAxes.firstOrNull()
        if (expectedAxis != null &&
            CalibrationMath.angleBetweenVectorsDegrees(turn.axis, expectedAxis) > 8f
        ) {
            return false
        }
        if (turn.angleDegrees !in
            CalibrationMath.MINIMUM_QUARTER_TURN_DEGREES..
                CalibrationMath.MAXIMUM_QUARTER_TURN_DEGREES
        ) {
            return false
        }
        return position < 5 ||
            session.turnAnglesDegrees.sum() + turn.angleDegrees in
                MINIMUM_TOTAL_TURN_DEGREES..MAXIMUM_TOTAL_TURN_DEGREES
    }

    private fun averageGravity(samples: List<Vector3>): Vector3? {
        if (samples.isEmpty() || samples.any {
                it.length() !in MINIMUM_GRAVITY_METERS_PER_SECOND_SQUARED..
                    MAXIMUM_GRAVITY_METERS_PER_SECOND_SQUARED
            }
        ) {
            return null
        }
        val sum = samples.fold(Vector3.zero()) { current, value -> Vector3.add(current, value) }
        if (sum.length() < 1e-6f) return null
        val average = sum.scaled(1f / samples.size)
        if (samples.any {
                CalibrationMath.angleBetweenVectorsDegrees(it, average) >
                    MAXIMUM_GRAVITY_DIRECTION_SPREAD_DEGREES
            }
        ) {
            return null
        }
        return average
    }

    private fun updateDeviceProgress(
        position: Int,
        phase: CalibrationDialogPhase,
        relativeTurnDegrees: Float = 0f,
        totalTurnDegrees: Float = 0f,
        liveRotation: Boolean = false,
        targetReached: Boolean = false,
        stabilityProgress: Float = 0f,
        captureProgress: Float = 0f,
        hasCandidate: Boolean = deviceSession?.candidate != null,
        error: CalibrationError?
    ) {
        mutableUiState.update { state ->
            val dialog = state.calibrationDialog ?: return@update state
            state.copy(
                calibrationDialog = dialog.copy(
                    phase = phase,
                    position = position,
                    relativeTurnDegrees = relativeTurnDegrees,
                    totalTurnDegrees = totalTurnDegrees,
                    isLiveRotation = liveRotation,
                    turnTargetReached = targetReached,
                    stabilityProgress = stabilityProgress,
                    captureProgress = captureProgress,
                    hasCandidate = hasCandidate,
                    error = error
                )
            )
        }
    }

    private fun showRetryableError(error: CalibrationError) {
        mutableUiState.update { state ->
            state.copy(
                calibrationDialog = state.calibrationDialog?.copy(
                    phase = CalibrationDialogPhase.RETRYABLE_ERROR,
                    error = error
                )
            )
        }
    }

    private fun showRestartError(error: CalibrationError): Boolean {
        mutableUiState.update { state ->
            state.copy(
                calibrationDialog = state.calibrationDialog?.copy(
                    phase = CalibrationDialogPhase.RESTART_REQUIRED,
                    error = error
                )
            )
        }
        return false
    }

    private suspend fun captureVehicle(): CaptureResult {
        val deviceSamples = mutableListOf<SensorData>()
        val vehicleSamples = mutableListOf<SensorData>()
        val points = mutableListOf<CalibrationPoint>()

        repeat(CALIBRATION_SAMPLE_COUNT) { index ->
            delay(CALIBRATION_SAMPLE_INTERVAL_MILLIS)
            val now = clock.nowNanos()
            val device = sensorRepository.deviceData.value
            if (!device.isFresh(now)) {
                return CaptureResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
            }
            deviceSamples += device

            val expectedVehicle = vehicleSession
                ?: return CaptureResult.Failure(CalibrationError.VEHICLE_DISCONNECTED)
            val status = sensorRepository.status.value
            if (!status.connected.isConnected || status.isBuiltin ||
                status.selectedSensorId != expectedVehicle.sensorId ||
                status.coordinates != expectedVehicle.coordinates
            ) {
                return CaptureResult.Failure(CalibrationError.VEHICLE_DISCONNECTED)
            }
            val vehicle = sensorRepository.rawData.value
            if (!vehicle.isFresh(now)) {
                return CaptureResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
            }
            if (vehicleSamples.none { it.timestampNanos == vehicle.timestampNanos }) {
                vehicleSamples += vehicle
                points += CalibrationPoint(vehicle.q, device.q)
            }
            mutableUiState.update { state ->
                state.copy(
                    calibrationDialog = state.calibrationDialog?.copy(
                        captureProgress = (index + 1f) / CALIBRATION_SAMPLE_COUNT
                    )
                )
            }
        }

        val distinctDeviceSamples = deviceSamples.distinctBy { it.timestampNanos }
        if (distinctDeviceSamples.size < MINIMUM_DISTINCT_SAMPLE_COUNT) {
            return CaptureResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        }
        if (!CalibrationMath.isStableSequence(distinctDeviceSamples)) {
            return CaptureResult.Failure(CalibrationError.UNSTABLE)
        }

        return if (vehicleSamples.size < MINIMUM_DISTINCT_SAMPLE_COUNT) {
            CaptureResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        } else if (!CalibrationMath.isMotionStableSequence(vehicleSamples)) {
            CaptureResult.Failure(CalibrationError.UNSTABLE)
        } else {
            CalibrationMath.vehicleCorrection(points)?.let(CaptureResult::Success)
                ?: CaptureResult.Failure(CalibrationError.SENSOR_UNAVAILABLE)
        }
    }

    private fun completeCalibration(target: CalibrationTarget, correction: Quaternion) {
        when (target) {
            CalibrationTarget.DEVICE -> calibrationRepository.saveDevice(correction)
            CalibrationTarget.VEHICLE -> {
                val vehicle = vehicleSession ?: return
                calibrationRepository.saveVehicle(
                    vehicle.sensorId,
                    vehicle.coordinates,
                    correction
                )
            }
        }
        vehicleSession = null
        deviceSession = null
        sensorRepository.monitorCalibrationMotion(false)
        mutableUiState.update { it.copy(calibrationDialog = null) }
        mutableCalibrationEvents.tryEmit(CalibrationEvent.Succeeded(target))
    }
}
