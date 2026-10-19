package de.energy6.caravanleveler

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.energy6.caravanleveler.math.Quaternion
import de.energy6.caravanleveler.math.Vector3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Local, versioned captures of the inputs needed to replay calibration and leveling math. */
@Singleton
class SensorSnapshotRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensors: ISensorRepository,
    private val preferences: IUserPreferencesRepository,
    private val calibrations: ICalibrationRepository,
    private val clock: MonotonicClock
) {
    private val file = AtomicFile(File(context.filesDir, "sensor-snapshots.json"))
    private val mutex = Mutex()
    private val mutableCount = MutableStateFlow(0)
    val count = mutableCount.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { mutableCount.value = read().length() }
    }

    suspend fun capture() {
        val settings = preferences.data.first()
        val calibration = calibrations.data.value
        val status = sensors.status.value
        val raw = sensors.rawData.value
        val deviceRaw = sensors.deviceRawData.value
        val now = clock.nowNanos()
        val correction = if (status.isBuiltin) calibration.deviceCorrection() else
            calibration.vehicleCorrection(status.selectedSensorId, status.coordinates)
        val corrected = raw.copy(q = if (status.isBuiltin) CalibrationMath.apply(correction, raw.q)
            else CalibrationMath.applyVehicle(correction, raw.q))
        val deviceCorrected = deviceRaw.copy(q = CalibrationMath.apply(calibration.deviceCorrection(), deviceRaw.q))
        val app = context.packageManager.getPackageInfo(context.packageName, 0)
        val snapshot = JSONObject()
            .put("capturedAt", Instant.now().toString())
            .put("elapsedRealtimeNanos", now)
            .put("appVersion", app.versionName)
            .put("appVersionCode", app.longVersionCode)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("requestedSensorId", settings.selectedSensor)
            .put("sensor", JSONObject()
                .put("id", status.selectedSensorId)
                .put("isBuiltin", status.isBuiltin)
                .put("connection", status.connected.name)
                .put("connectable", status.connectable)
                .put("coordinates", coordinates(status.coordinates)))
            .put("settings", JSONObject()
                .put("coordinates", coordinates(settings.coordinates))
                .put("caravanWidthMeters", settings.caravanWidth)
                .put("caravanLengthMeters", settings.caravanLength))
            .put("calibration", JSONObject()
                .put("device", calibration.deviceCorrection()?.let(::quaternion) ?: JSONObject.NULL)
                .put("vehicle", calibration.vehicleCorrection(status.selectedSensorId, status.coordinates)
                    ?.let(::quaternion) ?: JSONObject.NULL)
                .put("configuredVehicle", calibration.vehicleCorrection(settings.selectedSensor, settings.coordinates)
                    ?.let(::quaternion) ?: JSONObject.NULL)
                .put("deviceApplication", "measured * correction")
                .put("vehicleApplication", "correction * measured"))
            .put("readings", JSONObject()
                .put("raw", reading(raw, now))
                .put("corrected", reading(corrected, now))
                .put("deviceRaw", reading(deviceRaw, now))
                .put("deviceCorrected", reading(deviceCorrected, now)))
            .put("deviceMotion", motion(sensors.calibrationMotionData.value))
            .put("corrections", corrections(corrected, settings))
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val snapshots = read().put(snapshot)
                write(snapshots)
                mutableCount.value = snapshots.length()
            }
        }
    }

    suspend fun export(): String = withContext(Dispatchers.IO) {
        mutex.withLock { document(read()).toString(2) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(JSONArray())
            mutableCount.value = 0
        }
    }

    private fun read(): JSONArray {
        val text = try {
            file.openRead().bufferedReader().use { it.readText() }
        } catch (_: java.io.FileNotFoundException) {
            return JSONArray()
        }
        val document = JSONObject(text)
        check(document.getInt("schemaVersion") == 1) { "Unsupported snapshot schema" }
        return document.getJSONArray("snapshots")
    }

    private fun write(snapshots: JSONArray) {
        val output = file.startWrite()
        try {
            output.write(document(snapshots).toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun document(snapshots: JSONArray) = JSONObject()
        .put("format", "caravan-leveler-sensor-snapshots")
        .put("schemaVersion", 1)
        .put("quaternionOrder", "x,y,z,w")
        .put("rawReadingStage", "orientation after sensor filtering and coordinate remapping, before calibration")
        .put("snapshots", snapshots)

    private fun coordinates(value: de.energy6.caravanleveler.sensors.Sensor.Coordinates) = JSONObject()
        .put("x", value.xaxis.name).put("y", value.yaxis.name)

    private fun quaternion(q: Quaternion) = JSONObject()
        .put("x", finite(q.x)).put("y", finite(q.y)).put("z", finite(q.z)).put("w", finite(q.w))

    private fun vector(v: Vector3) = JSONObject()
        .put("x", finite(v.x)).put("y", finite(v.y)).put("z", finite(v.z))

    private fun finite(value: Float): Any = if (value.isFinite()) value else JSONObject.NULL

    private fun reading(value: SensorData, now: Long) = JSONObject()
        .put("quaternion", quaternion(value.q))
        .put("timestampNanos", value.timestampNanos)
        .put("isValid", value.isValid)
        .put("isFinite", listOf(value.q.x, value.q.y, value.q.z, value.q.w).all { it.isFinite() })
        .put("isFresh", value.isFresh(now))

    private fun corrections(value: SensorData, settings: UserPreferences): Any {
        if (!value.isValid || listOf(value.q.x, value.q.y, value.q.z, value.q.w).any { !it.isFinite() }) return JSONObject.NULL
        val level = calculateLeveling(value.q, settings.caravanWidth, settings.caravanLength)
        return JSONObject().put("axisCm", finite(level.axis)).put("stabilizerCm", finite(level.stabilizer))
    }

    private fun motion(value: CalibrationMotionData) = JSONObject()
        .put("isActive", value.isActive)
        .put("absoluteRotation", quaternion(value.absoluteRotation))
        .put("absoluteTimestampNanos", value.absoluteTimestampNanos)
        .put("motionRotation", quaternion(value.motionRotation))
        .put("motionTimestampNanos", value.motionTimestampNanos)
        .put("gravity", vector(value.gravity))
        .put("gravityTimestampNanos", value.gravityTimestampNanos)
        .put("angularVelocity", vector(value.angularVelocity))
        .put("gyroscopeTimestampNanos", value.gyroscopeTimestampNanos)
}
