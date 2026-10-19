package de.energy6.caravanleveler

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.Sensor
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PreferencesFragment : PreferenceFragmentCompat() {

    private val mViewModel: PreferencesViewModel by viewModels()
    private val mSensor by lazy { preferenceManager.findPreference<DropDownPreference>("sensor")!! }
    private val mBleScan by lazy { preferenceManager.findPreference<SwitchPreference>("ble_scan")!! }
    private val mAutoConnect by lazy { preferenceManager.findPreference<SwitchPreference>("auto_connect")!! }
    private val mAxisX by lazy { preferenceManager.findPreference<DropDownPreference>("sensor_axis_x")!! }
    private val mAxisY by lazy { preferenceManager.findPreference<DropDownPreference>("sensor_axis_y")!! }
    private val mWidth by lazy { preferenceManager.findPreference<EditNumberPreference>("width") !! }
    private val mLength by lazy { preferenceManager.findPreference<EditNumberPreference>("length")!! }
    private val deviceCalibration by lazy { findPreference<Preference>("pro_device_calibration")!! }
    private val vehicleCalibration by lazy { findPreference<Preference>("pro_vehicle_calibration")!! }
    private val snapshotCapture by lazy { findPreference<Preference>("pro_snapshot_capture")!! }
    private val snapshotExport by lazy { findPreference<Preference>("pro_snapshot_export")!! }
    private val snapshotClear by lazy { findPreference<Preference>("pro_snapshot_clear")!! }
    private val proToggle by lazy { findPreference<Preference>("pro_toggle")!! }
    private val proOptions by lazy {
        listOf(deviceCalibration, vehicleCalibration, snapshotCapture, snapshotExport, snapshotClear)
    }
    private var proExpanded = false
    private val exportSnapshots = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(mViewModel::exportSnapshots)
    }

    private val mFormatter by lazy {
        (NumberFormat.getNumberInstance() as DecimalFormat).apply {
            isDecimalSeparatorAlwaysShown = true
            isGroupingUsed = false
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    }

    private fun Map<String, String>.localizedSensorNames() = map { (id, name) ->
        if (id == SENSOR_BUILTIN_ID) getString(R.string.builtin) else name
    }.toTypedArray()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { mViewModel.uiState.collect { data ->
                data.knownSensors.also {
                    mSensor.entryValues = it.keys.toTypedArray()
                    mSensor.entries = it.localizedSensorNames()
                }
                mBleScan.isChecked = data.bleScan
                mAutoConnect.isChecked = data.autoConnect
                mAxisX.value = data.coordinates.xaxis.name
                mAxisY.value = data.coordinates.yaxis.name
                deviceCalibration.summary = calibrationSummary(data.deviceCalibration)
                val builtin = data.selectedSensorId == SENSOR_BUILTIN_ID
                vehicleCalibration.isEnabled = !builtin
                vehicleCalibration.title = getString(R.string.pro_sensor_calibration_title,
                    if (builtin) getString(R.string.builtin) else data.knownSensors[data.selectedSensorId] ?: data.selectedSensorId)
                vehicleCalibration.summary = if (builtin) getString(R.string.pro_builtin_selected) else
                    getString(R.string.pro_sensor_calibration_summary,
                        getString(data.coordinates.xaxis.label), getString(data.coordinates.yaxis.label),
                        calibrationSummary(data.vehicleCalibration))
                snapshotCapture.summary = getString(R.string.snapshot_capture_summary, data.snapshotCount)
                snapshotCapture.isEnabled = !data.snapshotBusy
                snapshotExport.isEnabled = !data.snapshotBusy && data.snapshotCount > 0
                snapshotClear.isEnabled = !data.snapshotBusy && data.snapshotCount > 0
            } }
            launch { mViewModel.messages.collect { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            } }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setProExpanded(savedInstanceState?.getBoolean("pro_expanded") ?: false)
        proToggle.setOnPreferenceClickListener {
            setProExpanded(!proExpanded)
            true
        }

        mSensor.apply {
            mViewModel.uiState.value.knownSensors.also {
                entryValues = it.keys.toTypedArray()
                entries = it.localizedSensorNames()
            }
            setSummaryProvider { entry }
            setOnPreferenceClickListener { mViewModel.bleScan(false) }
            setOnPreferenceChangeListener { _, value ->
                mViewModel.addKnownSensor(
                    value as String,
                    entries[findIndexOfValue(value)].toString()
                )
                true
            }
        }

        mBleScan.apply {
            setOnPreferenceChangeListener { _, value -> mViewModel.bleScan(value as Boolean) }
        }

        fun parallel(lhs: String, rhs: String) : Boolean {
            return enumValueOf<Sensor.Axis>(lhs).parallelTo(enumValueOf(rhs))
        }

        fun acceptAxis(candidate: String, other: String): Boolean {
            if (!parallel(candidate, other)) return true

            Toast.makeText(requireContext(), R.string.sensor_axes_parallel, Toast.LENGTH_LONG).show()
            return false
        }

        mAxisX.apply {
            entryValues = Sensor.Axis.entries.map { it.name }.toTypedArray()
            entries = Sensor.Axis.entries.map { getString(it.label) }.toTypedArray()
            setSummaryProvider { resources.getString(R.string.sensor_axis_x_summary, entry) }
            setOnPreferenceChangeListener { _, value ->
                acceptAxis(value as String, mAxisY.value)
            }
        }

        mAxisY.apply {
            entryValues = Sensor.Axis.entries.map { it.name }.toTypedArray()
            entries = Sensor.Axis.entries.map { getString(it.label) }.toTypedArray()
            setSummaryProvider { resources.getString(R.string.sensor_axis_y_summary, entry) }
            setOnPreferenceChangeListener { _, value ->
                acceptAxis(value as String, mAxisX.value)
            }
        }

        fun EditNumberPreference.configureDimension(summary: Int, invalidMessage: Int) {
            setFormatter(mFormatter)
            setSummaryProvider { resources.getString(summary, text) }
            setOnPreferenceChangeListener { _, value ->
                if (mFormatter.parsePositiveNumber(value as String) != null) return@setOnPreferenceChangeListener true

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.invalid_number)
                    .setMessage(invalidMessage)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                false
            }
        }

        mWidth.configureDimension(R.string.caravan_width_summary, R.string.caravan_width_invalid)
        mLength.configureDimension(R.string.caravan_length_summary, R.string.caravan_length_invalid)

        deviceCalibration.icon = deviceCalibration.icon?.mutate()?.apply {
            setTint(requireContext().getColor(R.color.app_icon_primary))
        }
        deviceCalibration.setOnPreferenceClickListener {
            showCalibrationEditor(CalibrationEditorTarget.Device, getString(R.string.pro_device_calibration),
                mViewModel.uiState.value.deviceCalibration)
            true
        }
        vehicleCalibration.setOnPreferenceClickListener {
            val state = mViewModel.uiState.value
            if (state.selectedSensorId != SENSOR_BUILTIN_ID) {
                showCalibrationEditor(CalibrationEditorTarget.Vehicle(
                    VehicleCalibrationKey(state.selectedSensorId, state.coordinates)),
                    vehicleCalibration.title.toString(), state.vehicleCalibration)
            }
            true
        }
        snapshotCapture.setOnPreferenceClickListener { mViewModel.captureSnapshot(); true }
        snapshotExport.setOnPreferenceClickListener {
            exportSnapshots.launch("caravan-leveler-${Instant.now().toString().replace(':', '-')}.json")
            true
        }
        snapshotClear.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.snapshot_clear)
                .setMessage(R.string.snapshot_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.snapshot_clear) { _, _ -> mViewModel.clearSnapshots() }.show()
            true
        }
    }

    private fun setProExpanded(expanded: Boolean) {
        proExpanded = expanded
        proOptions.forEach { it.isVisible = expanded }
        proToggle.setIcon(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        proToggle.setSummary(if (expanded) R.string.pro_hide else R.string.pro_show)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pro_expanded", proExpanded)
    }

    private fun calibrationSummary(value: CalibrationValue?): String = value?.let {
        String.format(Locale.ROOT, "x=%.7f · y=%.7f\nz=%.7f · w=%.7f", it.x, it.y, it.z, it.w)
    } ?: getString(R.string.pro_calibration_absent)

    private fun showCalibrationEditor(target: CalibrationEditorTarget, title: String, value: CalibrationValue?) {
        if (childFragmentManager.findFragmentByTag("calibrationValues") == null) {
            CalibrationValuesDialog.create(target, title, value).show(childFragmentManager, "calibrationValues")
        }
    }

    override fun onResume() {
        super.onResume()
        mViewModel.monitorDevice(true)
    }

    override fun onPause() {
        mViewModel.monitorDevice(false)
        super.onPause()
        mViewModel.bleScan(false)
    }
}
