package de.energy6.caravanleveler.sensors

import android.hardware.SensorManager
import androidx.annotation.StringRes
import de.energy6.caravanleveler.R
import kotlinx.coroutines.flow.StateFlow

interface Sensor {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING;

        val isBusy: Boolean
            get() { return (this == CONNECTING) or (this == DISCONNECTING) }
        val isConnected : Boolean
            get() { return (this == CONNECTED) }
        val isConnecting : Boolean
            get() { return (this == CONNECTING) }
    }

    enum class Axis(val id: Int, @StringRes val label: Int) {
        X(SensorManager.AXIS_X, R.string.X),
        Y(SensorManager.AXIS_Y, R.string.Y),
        Z(SensorManager.AXIS_Z, R.string.Z),
        MINUS_X(SensorManager.AXIS_MINUS_X, R.string.MINUS_X),
        MINUS_Y(SensorManager.AXIS_MINUS_Y, R.string.MINUS_Y),
        MINUS_Z(SensorManager.AXIS_MINUS_Z, R.string.MINUS_Z);

        fun parallelTo(rhs: Axis) : Boolean =
            (abs() == rhs.abs())

        fun abs() = when(this) {
            MINUS_X -> X
            MINUS_Y -> Y
            MINUS_Z -> Z
            else -> this
        }

        fun rot() = when(this) {
            X -> Y
            Y -> Z
            Z -> MINUS_X
            MINUS_X -> MINUS_Y
            MINUS_Y -> MINUS_Z
            MINUS_Z -> X
        }
    }

    data class Coordinates(
        val xaxis: Axis = Axis.X,
        val yaxis: Axis = Axis.Y
    )

    val id : String
    val isBuiltin: Boolean
    var coordinates: Coordinates
    val connected : StateFlow<ConnectionState>
    val rotation : StateFlow<FloatArray>

    fun connect()
    fun disconnect()
}
