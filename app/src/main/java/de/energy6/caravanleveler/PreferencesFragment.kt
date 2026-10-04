package de.energy6.caravanleveler

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import de.energy6.caravanleveler.sensors.SENSOR_BUILTIN_ID
import de.energy6.caravanleveler.sensors.Sensor
import java.text.DecimalFormat
import java.text.NumberFormat

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
            mViewModel.uiState.collect { data ->
                data.knownSensors.also {
                    mSensor.entryValues = it.keys.toTypedArray()
                    mSensor.entries = it.localizedSensorNames()
                }
                mBleScan.isChecked = data.bleScan
                mAutoConnect.isChecked = data.autoConnect
                mAxisX.value = data.coordinates.xaxis.name
                mAxisY.value = data.coordinates.yaxis.name
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

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
    }

    override fun onPause() {
        super.onPause()
        mViewModel.bleScan(false)
    }
}
