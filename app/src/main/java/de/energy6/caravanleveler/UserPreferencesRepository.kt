package de.energy6.caravanleveler

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_NAME
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class UserPreferences(
    val showCompass : Boolean = false,
    val selectedSensor : String = SENSOR_BUILTIN_ID,
    val knownSensors : Map<String, String> = mapOf(SENSOR_BUILTIN_ID to SENSOR_BUILTIN_NAME),
    val autoConnect : Boolean = false,
    val coordinates : Sensor.Coordinates = Sensor.Coordinates(),
    val caravanWidth : Float = 2.1f,
    val caravanLength : Float = 3.0f
)

const val PREF_COMPASS = "compass"
const val PREF_SENSOR = "sensor"
const val PREF_KNOWN_SENSORS = "known_sensors"
const val PREF_SENSOR_NAME = "sensor_name"
const val PREF_AUTO_CONNECT = "auto_connect"
const val PREF_SENSOR_AXIS_X = "sensor_axis_x"
const val PREF_SENSOR_AXIS_Y = "sensor_axis_y"
const val PREF_CARAVAN_WIDTH = "width"
const val PREF_CARAVAN_LENGTH = "length"

interface IUserPreferencesRepository {
    val data : Flow<UserPreferences>

    fun addKnownSensor(id: String, name: String)
}

@Singleton
class UserPreferencesRepository @Inject constructor(@ApplicationContext context: Context)
    : SharedPreferences.OnSharedPreferenceChangeListener, IUserPreferencesRepository {

    private val mPrefsStore = PreferenceManager.getDefaultSharedPreferences(context)
    private val mDataStore  = context.getSharedPreferences("sensors", MODE_PRIVATE)

    private val mData = MutableStateFlow(UserPreferences())
    override val data: Flow<UserPreferences>
        get() = mData.asStateFlow()

    init {
        with(mPrefsStore) {
            mData.update { data ->
                data.copy(
                    showCompass = getBoolean(PREF_COMPASS, data.showCompass),
                    selectedSensor = getString(PREF_SENSOR, data.selectedSensor)
                        ?: data.selectedSensor,
                    knownSensors = loadKnownSensors(),
                    autoConnect = getBoolean(PREF_AUTO_CONNECT, data.autoConnect),
                    coordinates = Sensor.Coordinates(getEnum(PREF_SENSOR_AXIS_X, Sensor.Axis.X),
                                getEnum(PREF_SENSOR_AXIS_Y, Sensor.Axis.Y)),
                    caravanWidth = getNumber(PREF_CARAVAN_WIDTH, data.caravanWidth),
                    caravanLength = getNumber(PREF_CARAVAN_LENGTH, data.caravanLength)
                )
            }
            registerOnSharedPreferenceChangeListener(this@UserPreferencesRepository)
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        with(sp) {
            mData.update {
                when (key) {
                    PREF_COMPASS -> it.copy(showCompass = getBoolean(key, it.showCompass))
                    PREF_SENSOR -> {
                        val selectedSensor = getString(key, it.selectedSensor)
                            ?: it.selectedSensor
                        it.copy(
                            selectedSensor = selectedSensor,
                            autoConnect = loadAutoConnect(selectedSensor, it.autoConnect),
                            coordinates = loadCoordinates(selectedSensor, it.coordinates)
                        )
                    }
                    PREF_AUTO_CONNECT -> it.copy(
                        autoConnect = saveAutoConnect(
                            it.selectedSensor,
                            getBoolean(key, it.autoConnect)
                        )
                    )
                    PREF_SENSOR_AXIS_X -> it.copy(
                        coordinates = saveCoordinates(
                            it.selectedSensor,
                            getEnum(key, it.coordinates.xaxis),
                            it.coordinates.yaxis
                        )
                    )
                    PREF_SENSOR_AXIS_Y -> it.copy(
                        coordinates = saveCoordinates(
                            it.selectedSensor,
                            it.coordinates.xaxis,
                            getEnum(key, it.coordinates.yaxis)
                        )
                    )
                    PREF_CARAVAN_WIDTH -> it.copy(
                        caravanWidth = getNumber(key, it.caravanWidth)
                    )
                    PREF_CARAVAN_LENGTH -> it.copy(
                        caravanLength = getNumber(key, it.caravanLength)
                    )
                    else -> it
                }
            }
        }
    }

    override fun addKnownSensor(id: String, name: String) {
        with(mDataStore) {
            val knownSensors = mData.value.knownSensors
                .toMutableMap().also { sensors ->
                    sensors[id] = name
                    edit {
                        sensors.forEach { (k, v) ->
                            putString("${PREF_SENSOR_NAME}[${k}]", v)
                        }
                        putStringSet(PREF_KNOWN_SENSORS, sensors.keys)
                    }
                }
            mData.update { data -> data.copy(knownSensors = knownSensors) }
        }
    }

    private inline fun <reified T: Enum<T>> SharedPreferences.Editor.putEnum(key: String, value: T) =
        putString(key, value.name)

    private inline fun <reified T: Enum<T>> SharedPreferences.getEnum(key: String, defValue: T) : T {
        return try {
            enumValueOf(getString(key, defValue.name) ?: defValue.name)
        } catch (e: IllegalArgumentException) {
            defValue
        }
    }

    private fun SharedPreferences.getNumber(key: String, defaultValue: Float): Float =
        when (val storedValue = all[key]) {
            is Number -> storedValue.toFloat()
            is String -> storedValue.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }

    private fun loadKnownSensors() : Map<String, String> {
        with(mDataStore) {
            return (getStringSet(PREF_KNOWN_SENSORS, setOf(SENSOR_BUILTIN_ID))
                ?: setOf(SENSOR_BUILTIN_ID)).associateWith {
                getString("${PREF_SENSOR_NAME}[${it}]", it) ?: it
            }
        }
    }

    private fun loadAutoConnect(sensorId: String, default: Boolean) : Boolean {
        return mDataStore.getBoolean("${PREF_AUTO_CONNECT}[${sensorId}]", default)
    }

    private fun saveAutoConnect(sensorId: String, value: Boolean) : Boolean {
        mDataStore.edit {
            putBoolean("${PREF_AUTO_CONNECT}[${sensorId}]", value)
        }
        return value
    }

    private fun loadCoordinates(sensorId: String, value: Sensor.Coordinates): Sensor.Coordinates {
        with(mDataStore) {
            return Sensor.Coordinates(
                getEnum("${PREF_SENSOR_AXIS_X}[${sensorId}]", value.xaxis),
                getEnum("${PREF_SENSOR_AXIS_Y}[${sensorId}]", value.yaxis)
            )
        }
    }

    private fun saveCoordinates(sensorId: String, xaxis: Sensor.Axis, yaxis: Sensor.Axis) : Sensor.Coordinates {
        mDataStore.edit {
            putEnum("${PREF_SENSOR_AXIS_X}[${sensorId}]", xaxis)
            putEnum("${PREF_SENSOR_AXIS_Y}[${sensorId}]", yaxis)
        }
        return Sensor.Coordinates(xaxis, yaxis)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UserPreferencesModule {

    @Binds
    abstract fun bindUserPreferencesRepository(
        userPreferencesRepository: UserPreferencesRepository
    ): IUserPreferencesRepository
}
