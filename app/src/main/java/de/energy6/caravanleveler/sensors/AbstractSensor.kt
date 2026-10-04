package de.energy6.caravanleveler.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class AbstractSensor : Sensor {

    protected val mConnected = MutableStateFlow(Sensor.ConnectionState.DISCONNECTED)
    override val connected = mConnected.asStateFlow()

    protected val mRotation = MutableStateFlow(FloatArray(5))
    override val rotation = mRotation.asStateFlow()

}