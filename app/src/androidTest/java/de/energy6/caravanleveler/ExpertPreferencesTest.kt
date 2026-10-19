package de.energy6.caravanleveler

import android.Manifest
import android.net.Uri
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.energy6.caravanleveler.databinding.DialogCalibrationValuesBinding
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExpertPreferencesTest {
    private lateinit var preferences: PreferencesFragment
    private lateinit var model: PreferencesViewModel
    @Test
    fun expertDialogValidatesEditsResetsAndRecordsExportableSnapshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val previousCalibration = CalibrationRepository(context).data.value
        val snapshotFile = File(context.filesDir, "sensor-snapshots.json")
        val previousSnapshots = snapshotFile.takeIf { it.exists() }?.readBytes()
        val exported = File(context.cacheDir, "expert-snapshot-test.json")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val nav = activity.supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
                    nav.navController.navigate(NavGraphDirections.actionGlobalSettingsFragment())
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val nav = activity.supportFragmentManager.findFragmentById(R.id.fragment) as NavHostFragment
                    preferences = nav.childFragmentManager.primaryNavigationFragment as PreferencesFragment
                    model = ViewModelProvider(preferences)[PreferencesViewModel::class.java]
                    val options = listOf("pro_device_calibration", "pro_vehicle_calibration",
                        "pro_snapshot_capture", "pro_snapshot_export", "pro_snapshot_clear")
                        .map { preferences.findPreference<androidx.preference.Preference>(it)!! }
                    val toggle = preferences.findPreference<androidx.preference.Preference>("pro_toggle")!!
                    assertTrue(toggle is androidx.preference.PreferenceCategory)
                    assertTrue(toggle.isEnabled && toggle.isSelectable)
                    assertTrue("Expert settings are collapsed by default", options.none { it.isVisible })
                    toggle.performClick()
                    assertTrue(options.all { it.isVisible })
                    toggle.performClick()
                    assertTrue(options.none { it.isVisible })
                    toggle.performClick()
                    preferences.findPreference<androidx.preference.Preference>("pro_device_calibration")!!.performClick()
                    preferences.childFragmentManager.executePendingTransactions()
                    val dialog = preferences.childFragmentManager.findFragmentByTag("calibrationValues") as CalibrationValuesDialog
                    val binding = DialogCalibrationValuesBinding.bind((dialog.requireDialog() as AlertDialog)
                        .findViewById(R.id.calibrationValues)!!)
                    listOf(binding.valueX, binding.valueY, binding.valueZ, binding.valueW).forEach { it.setText("0") }
                    (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(binding.calibrationInputError.isShown)
                    binding.valueX.setText("0,1")
                    binding.valueW.setText("1")
                    (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                instrumentation.waitForIdleSync()
                assertNotNull(CalibrationRepository(context).data.value.device)
                scenario.onActivity {
                    preferences.findPreference<androidx.preference.Preference>("pro_device_calibration")!!.performClick()
                    preferences.childFragmentManager.executePendingTransactions()
                    val dialog = preferences.childFragmentManager.findFragmentByTag("calibrationValues") as CalibrationValuesDialog
                    (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                }
                instrumentation.waitForIdleSync()
                waitUntil { CalibrationRepository(context).data.value.device == null }
                assertNull(CalibrationRepository(context).data.value.device)
                val previousCount = model.uiState.value.snapshotCount
                scenario.onActivity { model.captureSnapshot() }
                waitUntil { !model.uiState.value.snapshotBusy && model.uiState.value.snapshotCount == previousCount + 1 }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue(preferences.findPreference<androidx.preference.Preference>("pro_snapshot_export")!!.isEnabled)
                    model.exportSnapshots(Uri.fromFile(exported))
                }
                waitUntil { exported.exists() && !model.uiState.value.snapshotBusy }
                assertTrue(JSONObject(exported.readText()).getJSONArray("snapshots").length() > 0)
                scenario.onActivity {
                    preferences.listView.scrollToPosition(preferences.listView.adapter!!.itemCount - 1)
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(300, 3_000)
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    File(context.cacheDir, "expert-preferences.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
                scenario.onActivity {
                    preferences.findPreference<androidx.preference.Preference>("pro_device_calibration")!!.performClick()
                    preferences.childFragmentManager.executePendingTransactions()
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(300, 3_000)
                instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                    File(context.cacheDir, "expert-calibration.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                }
            } finally {
                if (::model.isInitialized && scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) {
                    scenario.onActivity {
                        model.resetCalibration(CalibrationEditorTarget.Device)
                        previousCalibration.device?.let { value -> model.saveCalibration(CalibrationEditorTarget.Device,
                            listOf(value.x, value.y, value.z, value.w).map(Float::toString)) }
                        previousCalibration.vehicles.forEach { (key, value) ->
                            model.saveCalibration(CalibrationEditorTarget.Vehicle(key),
                                listOf(value.x, value.y, value.z, value.w).map(Float::toString))
                        }
                    }
                } else {
                    CalibrationRepository(context).apply {
                        resetDevice()
                        previousCalibration.device?.let { saveDevice(it.toQuaternion()) }
                        previousCalibration.vehicles.forEach { (key, value) ->
                            saveVehicle(key.sensorId, key.coordinates, value.toQuaternion())
                        }
                    }
                }
                if (previousSnapshots == null) snapshotFile.delete() else snapshotFile.writeBytes(previousSnapshots)
                exported.delete()
            }
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for snapshot operation", condition())
    }
}
