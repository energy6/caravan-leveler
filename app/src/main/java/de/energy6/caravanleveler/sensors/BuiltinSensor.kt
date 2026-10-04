package de.energy6.caravanleveler.sensors

import android.app.Activity
import android.content.Context
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

const val SENSOR_BUILTIN_ID = "De:vi:ce:bu:il:ti"
const val SENSOR_BUILTIN_NAME = "Built-in"

class BuiltinSensor(context: Context) : AbstractSensor(), SensorEventListener {
    companion object {
        private const val SENSOR_DELAY_MICROS = 100 * 1000 // 100ms
    }

    private val mSensorService by lazy { context.getSystemService(Activity.SENSOR_SERVICE) as SensorManager }
    private val mRotationSensor by lazy { mSensorService.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) }
    private var mLastAccuracy = SensorManager.SENSOR_STATUS_NO_CONTACT

    override val id : String
        get() = SENSOR_BUILTIN_ID

    override val isBuiltin: Boolean
        get() = true

    override var coordinates
        get() = Sensor.Coordinates()
        set(_) { }

    override fun onSensorChanged(event: SensorEvent) {
        assert(event.sensor == mRotationSensor)
        if (mLastAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) {
            return
        }
        mRotation.value = event.values.clone()
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
        assert(sensor == mRotationSensor)
        if (mLastAccuracy != accuracy) {
            mLastAccuracy = accuracy
        }
    }

    override fun connect() {
        if ((mConnected.value == Sensor.ConnectionState.DISCONNECTED) and
            (mRotationSensor != null)) {
            mConnected.value = Sensor.ConnectionState.CONNECTED
            mSensorService.registerListener(this, mRotationSensor, SENSOR_DELAY_MICROS)
        }
    }

    override fun disconnect() {
        if (mConnected.value == Sensor.ConnectionState.CONNECTED) {
            mConnected.value = Sensor.ConnectionState.DISCONNECTED
            mSensorService.unregisterListener(this)
        }
    }
}
