package de.energy6.caravanleveler.sensors

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.*

typealias OnReadListener = ((uuid: UUID, value: ByteArray) -> Unit)

val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

abstract class BleSensor(private var context: Context,
                         private var device : BluetoothDevice) : AbstractSensor()
{
    companion object
    {
        private val available = mapOf(WT901BLECL.serviceUuids to WT901BLECL.factory)
        private val instances = mutableMapOf<BluetoothDevice, BleSensor>()

        val scanFilers by lazy { available.keys.map { it.map { uuid ->
            ScanFilter.Builder().setServiceUuid(uuid).build()
        }}.flatten()}

        @Synchronized
        fun create(context: Context, device: BluetoothDevice, serviceUuids: List<ParcelUuid>) : BleSensor? {
            if (device !in instances) {
                available[serviceUuids]?.invoke(context, device)?.also {
                    instances[device] = it
                }
            }
            return instances[device]
        }
    }

    private var mGattConnection : BluetoothGatt? = null

    override val id : String
        get() = device.address

    override val isBuiltin: Boolean
        get() = false

    override var coordinates = Sensor.Coordinates()

    private val mOnReadListener = hashMapOf<UUID, OnReadListener>()
    protected fun setOnReadListener(uuid: UUID, listener: OnReadListener?) {
        if (listener != null) {
            mOnReadListener[uuid] = listener
        } else {
            mOnReadListener.remove(uuid)
        }
    }

    private fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    private fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    private fun BluetoothGattCharacteristic.hasNotification(): Boolean =
        isIndicatable() or isNotifiable()

    private fun BluetoothGattCharacteristic.isIndicatable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)

    private fun BluetoothGattCharacteristic.isNotifiable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }

    @SuppressLint("MissingPermission")
    fun write(serviceUuid: UUID, charUuid: UUID, payload: ByteArray) {
        mGattConnection?.getService(serviceUuid)?.getCharacteristic(charUuid)?.run {
            val writeType = when {
                isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                isWritableWithoutResponse() -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> error("Characteristic $uuid cannot be written to")
            }
            mGattConnection?.writeCharacteristic(this, payload, writeType)
        }
    }

    @SuppressLint("MissingPermission")
    protected fun setNotification(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        mGattConnection?.getService(serviceUuid)?.getCharacteristic(charUuid)?.apply {
            val payload = when {
                (enable and isIndicatable()) -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                (enable and isNotifiable()) -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                (!enable and hasNotification()) -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                else -> error("Characteristic $uuid  not support notification")
            }

            getDescriptor(CCC_DESCRIPTOR_UUID)?.also { cccd ->
                if (mGattConnection?.setCharacteristicNotification(this, enable) == true) {
                    mGattConnection?.writeDescriptor(cccd, payload)
                } else {
                    // Log.e("BleSensor", "setNotification failed for $charUuid!")
                }
            }
        }
    }

    private inner class GattCallback : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onConnected(gatt)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onDisconnected(gatt)
                }
            } else {
                if (mConnected.value == Sensor.ConnectionState.CONNECTED) {
                    gatt.disconnect()
                } else {
                    onDisconnected(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onServicesDiscovered(gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            with(characteristic) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> mOnReadListener[uuid]?.invoke(uuid, value)
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                        // Log.e("BleSensor", "Read not permitted for $uuid!")
                    }
                    else -> {
                        // Log.e("BleSensor", "Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            with(characteristic) {
                mOnReadListener[uuid]?.invoke(uuid, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (mConnected.value == Sensor.ConnectionState.DISCONNECTED) {
            mConnected.value = Sensor.ConnectionState.CONNECTING
            val callback = GattCallback()
            if (Build.VERSION.SDK_INT >= 37) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setAutoConnectEnabled(false)
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .build()
                device.connectGatt(settings, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    null
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val gatt = mGattConnection
        if (mConnected.value == Sensor.ConnectionState.CONNECTED) {
            mConnected.value = Sensor.ConnectionState.DISCONNECTING
            gatt?.disconnect()
        }
    }


    @SuppressLint("MissingPermission")
    protected open fun onConnected(gatt: BluetoothGatt) {
        mGattConnection = gatt
        mConnected.value = Sensor.ConnectionState.CONNECTED
        gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    protected open fun onDisconnected(gatt: BluetoothGatt) {
        gatt.close()
        mConnected.value = Sensor.ConnectionState.DISCONNECTED
        mGattConnection = null
    }

    protected open fun onServicesDiscovered(gatt: BluetoothGatt) {
    }

}
