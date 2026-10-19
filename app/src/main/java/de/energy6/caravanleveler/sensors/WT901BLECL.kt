package de.energy6.caravanleveler.sensors

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.os.ParcelUuid
import java.util.*

private val WITMOTION_SERVICE_UUID  = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
private val WITMOTION_NOTIFY_UUID     = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
private val WITMOTION_WRITE_UUID     = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")
// private val BATTERY_SERVICE_UUID    = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

class WT901BLECL(context: Context, device: BluetoothDevice) : BleSensor(context, device) {

    companion object {
        val serviceUuids = listOf(ParcelUuid(WITMOTION_SERVICE_UUID))
        val factory = @Synchronized { context: Context, device: BluetoothDevice -> WT901BLECL(context, device) }
    }

    init {
        // setOnReadListener(BATTERY_LEVEL_CHAR_UUID, ::readBatLevel)
        setOnReadListener(WITMOTION_NOTIFY_UUID, ::readSensorData)
    }

    private val decoder = WT901BLECLDecoder()

    // private fun readBatLevel(uuid: UUID, value: ByteArray) {}

    private fun readRegister(register: Byte) {
        val data = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 0x27, register, 0x00)
        write(WITMOTION_SERVICE_UUID, WITMOTION_WRITE_UUID, data)
    }

    private fun readSensorData(uuid: UUID, value: ByteArray) {
        decoder.decode(value, coordinates)?.also { mRotation.value = it }
        if (value[1].toInt() == 0x61) readRegister(0x3A)
    }

    override fun disconnect() {
        setNotification(WITMOTION_SERVICE_UUID, WITMOTION_NOTIFY_UUID, false)
        super.disconnect()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt) {
        setNotification(WITMOTION_SERVICE_UUID, WITMOTION_NOTIFY_UUID, true)
    }
}
