package de.energy6.caravanleveler.sensors

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.hardware.SensorManager
import android.os.ParcelUuid
import de.energy6.caravanleveler.math.getRotationVectorFromMatrix
import java.util.*

private val WITMOTION_SERVICE_UUID  = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
private val WITMOTION_NOTIFY_UUID     = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
private val WITMOTION_WRITE_UUID     = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")
// private val BATTERY_SERVICE_UUID    = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

class WT901BLECL(context: Context, device: BluetoothDevice) : BleSensor(context, device) {

    private class FilterMedian(private val depth : Int) {
        private val history = arrayListOf<ArrayList<Float>>()

        fun filter(value: FloatArray) = value.mapIndexed { index, fl ->
                if (history.size <= index) {
                    history.add(arrayListOf())
                }
                history[index].run {
                    add(fl)
                    if (size > depth) {
                        removeAt(0)
                        val median = sorted().subList((depth/4), depth*3/4)
                        median.reduce { acc, v -> acc + v } / median.size
                    } else {
                        reduce { acc, v -> acc + v } / size
                    }
                }
            }.toFloatArray()
    }

    companion object {
        val serviceUuids = listOf(ParcelUuid(WITMOTION_SERVICE_UUID))
        val factory = @Synchronized { context: Context, device: BluetoothDevice -> WT901BLECL(context, device) }
    }

    init {
        // setOnReadListener(BATTERY_LEVEL_CHAR_UUID, ::readBatLevel)
        setOnReadListener(WITMOTION_NOTIFY_UUID, ::readSensorData)
    }

    private var mGravit = FloatArray(3)
    private var mGeomagneticField = FloatArray(3)
    private val mFilterGrav = FilterMedian(10)
    private val mFilterMag = FilterMedian(10)

    // private fun readBatLevel(uuid: UUID, value: ByteArray) {}

    private fun readRegister(register: Byte) {
        val data = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, register, 0x00)
        write(WITMOTION_SERVICE_UUID, WITMOTION_WRITE_UUID, data)
    }

    private fun ByteArray.decodeToFloat(index: Int) : Float {
        return ((this[index].toInt() and 0xFF) + (this[index+1].toInt() and 0xFF shl 8)).toShort().toFloat()
    }

    private fun readSensorData(uuid: UUID, value: ByteArray) {
        if (value[1].toInt() == 0x61) {
            val ax = value.decodeToFloat(2) / 32768.0f * 16.0f * 9.81f
            val ay = value.decodeToFloat(4) / 32768.0f * 16.0f * 9.81f
            val az = value.decodeToFloat(6) / 32768.0f * 16.0f * 9.81f
            mGravit = mFilterGrav.filter(floatArrayOf(ax, ay, az))

            val rv = Pair(FloatArray(9), FloatArray(9)).let {
                val success = SensorManager.getRotationMatrix(it.first, null, mGravit, mGeomagneticField)
                if (success) {
                    SensorManager.remapCoordinateSystem(
                        it.first,
                        coordinates.xaxis.id,
                        coordinates.yaxis.id,
                        it.second
                    )
                    getRotationVectorFromMatrix(it.second)
                } else {
                    null
                }
            }

            rv?.also { mRotation.value = it }

            readRegister(0x3A)
        } else if ((value[1].toInt() == 0x71) and (value[2].toInt() == 0x3A)) {
            val hx = value.decodeToFloat(4) / 10f
            val hy = value.decodeToFloat(6) / 10f
            val hz = value.decodeToFloat(8) / 10f
            mGeomagneticField = mFilterMag.filter(floatArrayOf(hx, hy, hz))
        }
    }

    override fun disconnect() {
        setNotification(WITMOTION_SERVICE_UUID, WITMOTION_NOTIFY_UUID, false)
        super.disconnect()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt) {
        setNotification(WITMOTION_SERVICE_UUID, WITMOTION_NOTIFY_UUID, true)
    }
}
