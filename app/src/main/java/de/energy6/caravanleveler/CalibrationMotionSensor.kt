package de.energy6.caravanleveler

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CalibrationSensorCapabilities(
    val hasRotationVector: Boolean = false,
    val hasGameRotationVector: Boolean = false,
    val hasGravity: Boolean = false,
    val hasGyroscope: Boolean = false
) {
    val isSupported: Boolean
        get() = hasRotationVector && hasGravity && hasGyroscope
}

data class CalibrationMotionData(
    val absoluteRotation: Quaternion = Quaternion(),
    val absoluteTimestampNanos: Long = 0L,
    val motionRotation: Quaternion = Quaternion(),
    val motionTimestampNanos: Long = 0L,
    val gravity: Vector3 = Vector3.zero(),
    val gravityTimestampNanos: Long = 0L,
    val angularVelocity: Vector3 = Vector3.zero(),
    val gyroscopeTimestampNanos: Long = 0L,
    val capabilities: CalibrationSensorCapabilities = CalibrationSensorCapabilities(),
    val isActive: Boolean = false
) {
    fun isFresh(
        nowNanos: Long,
        staleAfterNanos: Long = StabilityDetector.DEFAULT_STALE_AFTER_NANOS
    ): Boolean = capabilities.isSupported && isActive &&
        listOf(
            absoluteTimestampNanos,
            motionTimestampNanos,
            gravityTimestampNanos,
            gyroscopeTimestampNanos
        ).all { timestamp ->
            timestamp > 0L && nowNanos >= timestamp && nowNanos - timestamp <= staleAfterNanos
        }
}

/**
 * High-rate Android sensor stream used only by the multi-position device calibration.
 * Regular level measurements continue to use [de.energy6.caravanleveler.sensors.BuiltinSensor].
 */
class CalibrationMotionSensor(context: Context) : SensorEventListener {
    companion object {
        private const val SENSOR_DELAY_MICROS = 20_000 // 50 Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val capabilities = CalibrationSensorCapabilities(
        hasRotationVector = rotationSensor != null,
        hasGameRotationVector = gameRotationSensor != null,
        hasGravity = gravitySensor != null,
        hasGyroscope = gyroscopeSensor != null
    )

    private val mutableData = MutableStateFlow(CalibrationMotionData(capabilities = capabilities))
    val data: StateFlow<CalibrationMotionData> = mutableData.asStateFlow()

    @Synchronized
    fun start() {
        if (mutableData.value.isActive) return
        mutableData.value = CalibrationMotionData(
            capabilities = capabilities,
            isActive = true
        )
        rotationSensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_MICROS) }
        gameRotationSensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_MICROS) }
        gravitySensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_MICROS) }
        gyroscopeSensor?.let { sensorManager.registerListener(this, it, SENSOR_DELAY_MICROS) }
    }

    @Synchronized
    fun stop() {
        if (!mutableData.value.isActive) return
        sensorManager.unregisterListener(this)
        mutableData.value = CalibrationMotionData(capabilities = capabilities)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotation = event.values.toQuaternion()
                mutableData.update { current ->
                    current.copy(
                        absoluteRotation = rotation,
                        absoluteTimestampNanos = event.timestamp,
                        motionRotation = if (capabilities.hasGameRotationVector) {
                            current.motionRotation
                        } else {
                            rotation
                        },
                        motionTimestampNanos = if (capabilities.hasGameRotationVector) {
                            current.motionTimestampNanos
                        } else {
                            event.timestamp
                        }
                    )
                }
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> mutableData.update { current ->
                current.copy(
                    motionRotation = event.values.toQuaternion(),
                    motionTimestampNanos = event.timestamp
                )
            }

            Sensor.TYPE_GRAVITY -> mutableData.update { current ->
                current.copy(
                    gravity = event.values.toVector3(),
                    gravityTimestampNanos = event.timestamp
                )
            }

            Sensor.TYPE_GYROSCOPE -> mutableData.update { current ->
                current.copy(
                    angularVelocity = event.values.toVector3(),
                    gyroscopeTimestampNanos = event.timestamp
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun FloatArray.toQuaternion(): Quaternion {
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, this)
        return Quaternion(quaternion[1], quaternion[2], quaternion[3], quaternion[0])
    }

    private fun FloatArray.toVector3(): Vector3 = Vector3(
        getOrElse(0) { 0f },
        getOrElse(1) { 0f },
        getOrElse(2) { 0f }
    )
}
