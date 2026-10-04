package de.energy6.caravanleveler

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import de.energy6.caravanleveler.sensors.BleSensor
import de.energy6.caravanleveler.sensors.BuiltinSensor
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_NAME
import de.energy6.caravanleveler.sensors.Sensor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

data class SensorDevices(
    val devices: Map<String, String> = mapOf(SENSOR_BUILTIN_ID to SENSOR_BUILTIN_NAME)
)

class SensorManager(private val mContext: Context) {

    private var requestPermissions : (() -> Unit)? = null

    private val mDevices = MutableStateFlow(SensorDevices())
    val devices = mDevices.asStateFlow()

    private val mBluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = mContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val mBleScanner
        get() = mBluetoothAdapter.bluetoothLeScanner
    private val mScanSettings by lazy {
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    private val isScanPermissionGranted
        get() = mContext.hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    private val isConnectPermissionGranted
        get() = mContext.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    @SuppressLint("MissingPermission")
    private fun checkPermission(): Boolean {
        if (!isScanPermissionGranted ||
            !isConnectPermissionGranted)
        {
            requestPermissions?.invoke()
            return false
        }
        if (!mBluetoothAdapter.isEnabled) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            mContext.startActivity(intent)
            return false
        }
        return true
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            with(result.device) {
                if (address !in mDevices.value.devices) {
                    mDevices.update {
                        it.copy(
                            devices = it.devices.toMutableMap().apply { put(address, name ?: address) }
                        )
                    }
                }
            }
        }
    }

    fun setOnRequestPermissionsListener(function: () -> Unit) {
        requestPermissions = function
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() : Boolean {
        if (checkPermission()) {
            mBleScanner?.startScan(BleSensor.scanFilers, mScanSettings, scanCallback)
                ?: return false
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (isScanPermissionGranted) {
            mBleScanner?.stopScan(scanCallback)
        }
    }

    fun getBuiltinSensor() : Sensor = BuiltinSensor(mContext)

    suspend fun getSensor(id: String) : Sensor? = when {
        (id == SENSOR_BUILTIN_ID) -> getBuiltinSensor()
        (BluetoothAdapter.checkBluetoothAddress(id)) -> createSensorForAddress(id)
        else -> null
    }

    @SuppressLint("MissingPermission")
    private suspend fun createSensorForAddress(address: String) : Sensor? {
        if (!checkPermission()) return null

        val scanner = mBleScanner ?: return null
        val device = withTimeoutOrNull(10.seconds) {
            suspendCancellableCoroutine<Pair<BluetoothDevice, List<ParcelUuid>?>?> { continuation ->
                val filter = ScanFilter.Builder().setDeviceAddress(address).build()
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
                val callback = object : ScanCallback() {
                    @SuppressLint("MissingPermission")
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        scanner.stopScan(this)
                        if (continuation.isActive) {
                            continuation.resume(Pair(result.device, result.scanRecord?.serviceUuids))
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    if (isScanPermissionGranted) {
                        scanner.stopScan(callback)
                    }
                }
                scanner.startScan(listOf(filter), settings, callback)
            }
        }
        return device?.second?.let { BleSensor.create(mContext, device.first, it) }
    }
}
