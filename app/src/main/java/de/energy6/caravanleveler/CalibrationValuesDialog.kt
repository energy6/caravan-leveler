package de.energy6.caravanleveler

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.energy6.caravanleveler.databinding.DialogCalibrationValuesBinding

class CalibrationValuesDialog : DialogFragment() {
    private val viewModel: PreferencesViewModel by viewModels({ requireParentFragment() })
    private lateinit var binding: DialogCalibrationValuesBinding
    private val target: CalibrationEditorTarget
        get() = requireArguments().getString("vehicleKey")?.let {
            CalibrationEditorTarget.Vehicle(requireNotNull(VehicleCalibrationKey.fromStorageKey(it)))
        } ?: CalibrationEditorTarget.Device

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogCalibrationValuesBinding.inflate(layoutInflater)
        val values = requireArguments().getFloatArray("values") ?: floatArrayOf(0f, 0f, 0f, 1f)
        listOf(binding.valueX, binding.valueY, binding.valueZ, binding.valueW).zip(values.toList())
            .forEach { (field, value) -> field.setText(value.toString()) }
        binding.calibrationExplanation.setText(
            if (target == CalibrationEditorTarget.Device) R.string.pro_device_explanation
            else R.string.pro_vehicle_explanation
        )
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(requireArguments().getString("title"))
            .setView(binding.root)
            .setPositiveButton(R.string.save_calibration, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset_calibration) { _, _ -> viewModel.resetCalibration(target) }
            .create()
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = listOf(binding.valueX, binding.valueY, binding.valueZ, binding.valueW)
                .map { it.text.toString() }
            if (viewModel.saveCalibration(target, values)) dismiss()
            else binding.calibrationInputError.isVisible = true
        }
    }

    companion object {
        fun create(target: CalibrationEditorTarget, title: String, value: CalibrationValue?) =
            CalibrationValuesDialog().apply {
                arguments = Bundle().apply {
                    putString("title", title)
                    if (target is CalibrationEditorTarget.Vehicle) putString("vehicleKey", target.key.storageKey)
                    value?.let { putFloatArray("values", floatArrayOf(it.x, it.y, it.z, it.w)) }
                }
            }
    }
}
