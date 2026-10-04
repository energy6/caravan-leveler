package de.energy6.caravanleveler

import android.content.Context
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.energy6.caravanleveler.sensors.Sensor
import de.energy6.caravanleveler.math.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class CalibrationValue(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    fun toQuaternion() = Quaternion(x, y, z, w)

    companion object {
        fun from(quaternion: Quaternion): CalibrationValue {
            val normalized = Quaternion(quaternion)
            return CalibrationValue(normalized.x, normalized.y, normalized.z, normalized.w)
        }
    }
}

data class VehicleCalibrationKey(
    val sensorId: String,
    val coordinates: Sensor.Coordinates
) {
    val storageKey: String
        get() = "$sensorId|${coordinates.xaxis.name}|${coordinates.yaxis.name}"

    companion object {
        fun fromStorageKey(value: String): VehicleCalibrationKey? {
            val parts = value.split('|')
            if (parts.size != 3) return null
            return try {
                VehicleCalibrationKey(
                    sensorId = parts[0],
                    coordinates = Sensor.Coordinates(
                        Sensor.Axis.valueOf(parts[1]),
                        Sensor.Axis.valueOf(parts[2])
                    )
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

data class CalibrationState(
    val device: CalibrationValue? = null,
    val vehicles: Map<VehicleCalibrationKey, CalibrationValue> = emptyMap()
) {
    fun deviceCorrection(): Quaternion? = device?.toQuaternion()

    fun vehicleCorrection(sensorId: String, coordinates: Sensor.Coordinates): Quaternion? =
        vehicles[VehicleCalibrationKey(sensorId, coordinates)]?.toQuaternion()
}

interface ICalibrationRepository {
    val data: StateFlow<CalibrationState>

    fun saveDevice(correction: Quaternion)
    fun saveVehicle(sensorId: String, coordinates: Sensor.Coordinates, correction: Quaternion)
}

@Singleton
class CalibrationRepository @Inject constructor(@ApplicationContext context: Context) :
    ICalibrationRepository {
    companion object {
        private const val STORE_NAME = "calibration"
        private const val SCHEMA_VERSION_KEY = "schema_version"
        private const val CURRENT_SCHEMA_VERSION = 2
        private const val DEVICE_PREFIX = "device"
        private const val VEHICLE_KEYS = "vehicle_keys"
        private const val VEHICLE_PREFIX = "vehicle"
    }

    private val store = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    init {
        if (store.getInt(SCHEMA_VERSION_KEY, 0) != CURRENT_SCHEMA_VERSION) {
            store.edit {
                clear()
                putInt(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
            }
        }
    }

    private val state = MutableStateFlow(load())
    override val data = state.asStateFlow()

    override fun saveDevice(correction: Quaternion) {
        val value = CalibrationValue.from(correction)
        val vehicleKeys = store.getStringSet(VEHICLE_KEYS, emptySet()).orEmpty()
        store.edit {
            vehicleKeys.forEach { storedKey ->
                removeCalibration("$VEHICLE_PREFIX.$storedKey")
            }
            remove(VEHICLE_KEYS)
            putCalibration(DEVICE_PREFIX, value)
            putInt(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
        }
        state.value = CalibrationState(device = value)
    }

    override fun saveVehicle(
        sensorId: String,
        coordinates: Sensor.Coordinates,
        correction: Quaternion
    ) {
        val key = VehicleCalibrationKey(sensorId, coordinates)
        val value = CalibrationValue.from(correction)
        store.edit {
            val keys = (store.getStringSet(VEHICLE_KEYS, emptySet()) ?: emptySet()).toMutableSet()
            keys += key.storageKey
            putStringSet(VEHICLE_KEYS, keys)
            putCalibration("$VEHICLE_PREFIX.${key.storageKey}", value)
            putInt(SCHEMA_VERSION_KEY, CURRENT_SCHEMA_VERSION)
        }
        state.update { current ->
            current.copy(vehicles = current.vehicles + (key to value))
        }
    }

    private fun load(): CalibrationState {
        val device = readCalibration(DEVICE_PREFIX)
        val vehicles = (store.getStringSet(VEHICLE_KEYS, emptySet()) ?: emptySet())
            .mapNotNull { storedKey ->
                val key = VehicleCalibrationKey.fromStorageKey(storedKey) ?: return@mapNotNull null
                val value = readCalibration("$VEHICLE_PREFIX.$storedKey") ?: return@mapNotNull null
                key to value
            }
            .toMap()
        return CalibrationState(device, vehicles)
    }

    private fun android.content.SharedPreferences.Editor.putCalibration(
        prefix: String,
        value: CalibrationValue
    ) {
        putBoolean("$prefix.present", true)
        putFloat("$prefix.x", value.x)
        putFloat("$prefix.y", value.y)
        putFloat("$prefix.z", value.z)
        putFloat("$prefix.w", value.w)
    }

    private fun android.content.SharedPreferences.Editor.removeCalibration(prefix: String) {
        remove("$prefix.present")
        remove("$prefix.x")
        remove("$prefix.y")
        remove("$prefix.z")
        remove("$prefix.w")
    }

    private fun readCalibration(prefix: String): CalibrationValue? {
        if (!store.getBoolean("$prefix.present", false)) return null
        return CalibrationValue(
            store.getFloat("$prefix.x", 0f),
            store.getFloat("$prefix.y", 0f),
            store.getFloat("$prefix.z", 0f),
            store.getFloat("$prefix.w", 1f)
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CalibrationModule {
    @Binds
    abstract fun bindCalibrationRepository(
        calibrationRepository: CalibrationRepository
    ): ICalibrationRepository
}
