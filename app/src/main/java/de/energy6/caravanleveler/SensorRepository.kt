package de.energy6.caravanleveler

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.energy6.caravanleveler.math.toQuaternion
import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_NAME
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SensorStatus(
    val connectable: Boolean = true,
    val connected: Sensor.ConnectionState = Sensor.ConnectionState.DISCONNECTED,
    val selectedSensorId: String = SENSOR_BUILTIN_ID,
    val isBuiltin: Boolean = true,
    val coordinates: Sensor.Coordinates = Sensor.Coordinates()
)

data class SensorData(
    val q: Quaternion = Quaternion(),
    val timestampNanos: Long = 0L,
    val isValid: Boolean = false
)

data class ScanResult(
    val sensors: Map<String, String> = mapOf(SENSOR_BUILTIN_ID to SENSOR_BUILTIN_NAME),
    val active: Boolean = false,
    val requestPermissions: Boolean = false
)

interface ISensorRepository {
    val status: StateFlow<SensorStatus>
    val data: StateFlow<SensorData>
    val rawData: StateFlow<SensorData>
    val deviceData: StateFlow<SensorData>
    val deviceRawData: StateFlow<SensorData>
    val calibrationMotionData: StateFlow<CalibrationMotionData>
    val scan: Flow<ScanResult>

    fun selectSensor(
        id: String,
        coordinates: Sensor.Coordinates = Sensor.Coordinates(),
        autoConnect: Boolean = false
    )

    fun connect(state: Boolean)
    fun monitorDevice(state: Boolean)
    fun monitorCalibrationMotion(state: Boolean)
    fun scan(state: Boolean)
    fun permissionsRequested()
}

@Singleton
class SensorRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val calibrationRepository: ICalibrationRepository,
    private val clock: MonotonicClock
) : ISensorRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sensorManager = SensorManager(context)
    private val calibrationMotionSensor = CalibrationMotionSensor(context)
    private var sensor: Sensor? = sensorManager.getBuiltinSensor()
    private val referenceSensor = sensorManager.getBuiltinSensor()

    private var statusJob: Job? = null
    private var dataJob: Job? = null
    private var selectionJob: Job? = null
    private var deviceDataJob: Job? = null
    private var isMonitoringDevice = false

    private val mutableStatus = MutableStateFlow(SensorStatus())
    override val status = mutableStatus.asStateFlow()

    private val mutableRawData = MutableStateFlow(SensorData())
    override val rawData = mutableRawData.asStateFlow()

    private val mutableDeviceRawData = MutableStateFlow(SensorData())
    override val deviceRawData = mutableDeviceRawData.asStateFlow()
    override val calibrationMotionData = calibrationMotionSensor.data

    override val data = combine(
        mutableRawData,
        calibrationRepository.data,
        mutableStatus
    ) { reading, calibrations, currentStatus ->
        if (!reading.isValid) {
            reading
        } else {
            val correction = if (currentStatus.isBuiltin) {
                calibrations.deviceCorrection()
            } else {
                calibrations.vehicleCorrection(
                    currentStatus.selectedSensorId,
                    currentStatus.coordinates
                )
            }
            reading.copy(q = CalibrationMath.apply(correction, reading.q))
        }
    }.stateIn(scope, SharingStarted.Eagerly, SensorData())

    override val deviceData = combine(
        mutableDeviceRawData,
        calibrationRepository.data
    ) { reading, calibrations ->
        if (!reading.isValid) reading
        else reading.copy(q = CalibrationMath.apply(calibrations.deviceCorrection(), reading.q))
    }.stateIn(scope, SharingStarted.Eagerly, SensorData())

    private val mutableScan = MutableStateFlow(ScanResult())
    override val scan: Flow<ScanResult>
        get() = mutableScan.combine(sensorManager.devices) { scan, devices ->
            scan.copy(sensors = devices.devices)
        }

    init {
        sensorManager.setOnRequestPermissionsListener {
            mutableScan.update { it.copy(requestPermissions = true) }
        }
        restartJobs()
    }

    private fun restartJobs() {
        statusJob?.cancel()
        dataJob?.cancel()
        mutableRawData.value = SensorData()
        val currentSensor = sensor ?: return

        statusJob = scope.launch {
            currentSensor.connected.collect { connection ->
                mutableStatus.update { current ->
                    current.copy(
                        connected = connection,
                        selectedSensorId = currentSensor.id,
                        isBuiltin = currentSensor.isBuiltin,
                        coordinates = currentSensor.coordinates
                    )
                }
                if (!connection.isConnected) mutableRawData.value = SensorData()
            }
        }
        dataJob = scope.launch {
            currentSensor.rotation.collect { rotation ->
                if (currentSensor.connected.value.isConnected) {
                    rotation.toSensorData()?.let { mutableRawData.value = it }
                }
            }
        }
    }

    override fun selectSensor(
        id: String,
        coordinates: Sensor.Coordinates,
        autoConnect: Boolean
    ) {
        if (id != sensor?.id) {
            sensor?.disconnect()
            sensor = null
            mutableRawData.value = SensorData()
            mutableStatus.update {
                it.copy(
                    connectable = false,
                    connected = Sensor.ConnectionState.DISCONNECTED,
                    selectedSensorId = id,
                    isBuiltin = id == SENSOR_BUILTIN_ID,
                    coordinates = coordinates
                )
            }
            selectionJob?.cancel()
            selectionJob = scope.launch {
                val selected = sensorManager.getSensor(id) ?: sensorManager.getBuiltinSensor()
                selected.coordinates = coordinates
                sensor = selected
                mutableStatus.update {
                    it.copy(
                        connectable = true,
                        selectedSensorId = selected.id,
                        isBuiltin = selected.isBuiltin,
                        coordinates = selected.coordinates
                    )
                }
                restartJobs()
                if (autoConnect) selected.connect()
            }
        } else {
            sensor?.coordinates = coordinates
            if (mutableStatus.value.coordinates != coordinates) {
                mutableRawData.value = SensorData()
            }
            mutableStatus.update { it.copy(coordinates = coordinates) }
        }
    }

    override fun connect(state: Boolean) {
        if (state) sensor?.connect() else sensor?.disconnect()
    }

    @Synchronized
    override fun monitorDevice(state: Boolean) {
        if (state == isMonitoringDevice) return
        isMonitoringDevice = state
        if (state) {
            mutableDeviceRawData.value = SensorData()
            referenceSensor.connect()
            deviceDataJob = scope.launch {
                referenceSensor.rotation.collect { rotation ->
                    if (referenceSensor.connected.value.isConnected) {
                        rotation.toSensorData()?.let { mutableDeviceRawData.value = it }
                    }
                }
            }
        } else {
            deviceDataJob?.cancel()
            deviceDataJob = null
            referenceSensor.disconnect()
            mutableDeviceRawData.value = SensorData()
        }
    }

    override fun monitorCalibrationMotion(state: Boolean) {
        if (state) calibrationMotionSensor.start() else calibrationMotionSensor.stop()
    }

    override fun scan(state: Boolean) {
        if (state) {
            mutableScan.update { it.copy(active = true) }
            val activated = sensorManager.startBleScan()
            mutableScan.update { it.copy(active = activated) }
        } else {
            sensorManager.stopBleScan()
            mutableScan.update { it.copy(active = false) }
        }
    }

    override fun permissionsRequested() {
        mutableScan.update { it.copy(requestPermissions = false) }
    }

    private fun FloatArray.toSensorData(): SensorData? {
        if (size !in 4..5 || take(4).all { abs(it) < 1e-7f }) return null
        return SensorData(
            q = toQuaternion(),
            timestampNanos = clock.nowNanos(),
            isValid = true
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SensorModule {
    @Binds
    abstract fun bindSensorRepository(sensorRepository: SensorRepository): ISensorRepository
}
