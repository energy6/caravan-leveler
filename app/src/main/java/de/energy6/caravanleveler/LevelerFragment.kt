package de.energy6.caravanleveler

import android.animation.ObjectAnimator
import java.text.DecimalFormat
import java.text.NumberFormat
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

import de.energy6.caravanleveler.databinding.DialogCalibrationBinding
import de.energy6.caravanleveler.databinding.LevelerBinding
import kotlin.math.abs

@AndroidEntryPoint
class LevelerFragment : Fragment() {
    private val mViewModel: LevelerViewModel by viewModels()
    private lateinit var mBinding : LevelerBinding
    private var mCalibrationDialog: AlertDialog? = null
    private var mCalibrationDialogBinding: DialogCalibrationBinding? = null
    private var mCalibrationTarget: CalibrationTarget? = null
    private val mFormatter by lazy {
        (NumberFormat.getNumberInstance() as DecimalFormat).apply {
            isDecimalSeparatorAlwaysShown = true
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            mViewModel.uiState.collect { state ->
                mBinding.btnCalibration.isVisible = state.calibrationButtonVisible
                renderCalibrationDialog(state.calibrationDialog)
            }
        }
        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            mViewModel.calibrationEvents.collect { event ->
                val message = when (event) {
                    is CalibrationEvent.Succeeded -> when (event.target) {
                        CalibrationTarget.DEVICE -> R.string.device_calibration_succeeded
                        CalibrationTarget.VEHICLE -> R.string.vehicle_calibration_succeeded
                    }
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            mViewModel.cameraState.collect {
                mBinding.btnViewpane.apply {
                    ObjectAnimator
                        .ofFloat(this, "rotation", rotation.mod(360f), it.rotation)
                        .setDuration(abs(it.rotation - rotation.mod(360f)).toLong() * 7)
                        .start()
                }
            }
        }
        launchRepeatOnLifecycle(Lifecycle.State.STARTED) {
            mViewModel.caravanState.collect {
                with(mFormatter) {
                    mBinding.txtAxis.text =
                        getString(R.string.axis_correction, format(-it.axis), format(it.axis))
                    mBinding.txtStabilizer.text =
                        getString(R.string.stabilizer_correction, format(it.stabilizer))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mBinding = LevelerBinding.inflate(inflater, container, false)

        mBinding.sceneView.apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val cameraState by mViewModel.cameraState.collectAsStateWithLifecycle()
                val caravanState by mViewModel.caravanState.collectAsStateWithLifecycle()
                val uiState by mViewModel.uiState.collectAsStateWithLifecycle()
                LevelerScene(
                    cameraState = cameraState,
                    caravanState = caravanState,
                    showCompass = uiState.showCompass
                )
            }
        }

        mBinding.btnViewpane.setOnClickListener {
            mViewModel.rotateCamera()
        }
        mBinding.btnCalibration.setOnClickListener {
            mViewModel.openCalibration()
        }

        return mBinding.root
    }

    override fun onStart() {
        super.onStart()
        mViewModel.setCalibrationMonitoring(true)
    }

    override fun onStop() {
        if (!requireActivity().isChangingConfigurations) {
            mViewModel.setCalibrationMonitoring(false)
        }
        super.onStop()
    }

    override fun onDestroyView() {
        dismissCalibrationDialog()
        super.onDestroyView()
    }

    private fun renderCalibrationDialog(state: CalibrationDialogState?) {
        if (state == null) {
            dismissCalibrationDialog()
            return
        }

        if (mCalibrationDialog == null || mCalibrationTarget != state.target) {
            dismissCalibrationDialog()
            mCalibrationTarget = state.target
            val binding = DialogCalibrationBinding.inflate(layoutInflater)
            mCalibrationDialogBinding = binding
            binding.txtCalibrationInstructions.setText(
                when (state.target) {
                    CalibrationTarget.DEVICE -> R.string.device_calibration_instructions
                    CalibrationTarget.VEHICLE -> R.string.vehicle_calibration_instructions
                }
            )
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(
                    when (state.target) {
                        CalibrationTarget.DEVICE -> R.string.device_calibration_title
                        CalibrationTarget.VEHICLE -> R.string.vehicle_calibration_title
                    }
                )
                .setView(binding.root)
                .setPositiveButton(R.string.calibrate, null)
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    mViewModel.cancelCalibration()
                }
                .create()
            dialog.setOnCancelListener { mViewModel.cancelCalibration() }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    mViewModel.startCalibration()
                }
                updateCalibrationDialog(state)
            }
            mCalibrationDialog = dialog
            dialog.show()
        }
        updateCalibrationDialog(state)
    }

    private fun updateCalibrationDialog(state: CalibrationDialogState) {
        val binding = mCalibrationDialogBinding ?: return
        val dialog = mCalibrationDialog ?: return
        val isDevice = state.target == CalibrationTarget.DEVICE
        val showsRotation = isDevice && state.position > 1 && state.phase in setOf(
            CalibrationDialogPhase.TURNING,
            CalibrationDialogPhase.STABILIZING,
            CalibrationDialogPhase.CAPTURING
        )

        binding.txtCalibrationStep.isVisible = isDevice
        if (isDevice) {
            binding.txtCalibrationStep.text = if (state.position == 5) {
                getString(R.string.calibration_control_position)
            } else {
                getString(R.string.calibration_position, state.position, state.positionCount)
            }
        }
        binding.txtCalibrationStatus.isVisible = isDevice &&
            state.phase !in setOf(
                CalibrationDialogPhase.RETRYABLE_ERROR,
                CalibrationDialogPhase.RESTART_REQUIRED,
                CalibrationDialogPhase.VALIDATION_FAILED
            )
        if (binding.txtCalibrationStatus.isVisible) {
            binding.txtCalibrationStatus.setText(
                when (state.phase) {
                    CalibrationDialogPhase.READY -> R.string.calibration_ready
                    CalibrationDialogPhase.TURNING -> R.string.calibration_turn_phone
                    CalibrationDialogPhase.STABILIZING -> R.string.calibration_hold_still
                    CalibrationDialogPhase.CAPTURING -> R.string.calibration_recording_position
                    CalibrationDialogPhase.VALIDATING -> R.string.calibration_validating
                    CalibrationDialogPhase.RETRYABLE_ERROR,
                    CalibrationDialogPhase.RESTART_REQUIRED,
                    CalibrationDialogPhase.VALIDATION_FAILED -> R.string.calibration_ready
                }
            )
        }
        binding.calibrationRotationGuide.isVisible = showsRotation
        binding.txtCalibrationAngle.isVisible = showsRotation
        if (showsRotation) {
            binding.calibrationRotationGuide.render(
                angleDegrees = state.relativeTurnDegrees,
                live = state.isLiveRotation,
                reached = state.turnTargetReached,
                closureTurn = state.position == 5
            )
            val targetDescription = getString(
                if (state.turnTargetReached) {
                    R.string.calibration_target_reached
                } else {
                    R.string.calibration_target_not_reached
                }
            )
            binding.calibrationRotationGuide.contentDescription = getString(
                R.string.calibration_rotation_description,
                state.relativeTurnDegrees,
                targetDescription
            )
            binding.txtCalibrationAngle.text = getString(
                R.string.calibration_rotation_angle,
                state.relativeTurnDegrees,
                state.totalTurnDegrees
            )
        }
        binding.progressCalibrationStability.isVisible =
            isDevice && state.phase == CalibrationDialogPhase.STABILIZING
        binding.progressCalibrationStability.progress =
            (state.stabilityProgress * 100f).toInt().coerceIn(0, 100)
        binding.progressCalibration.isVisible = state.phase in setOf(
            CalibrationDialogPhase.CAPTURING,
            CalibrationDialogPhase.VALIDATING
        )
        binding.txtCalibrationError.isVisible = state.error != null
        state.error?.let { error ->
            binding.txtCalibrationError.text = when (error) {
                CalibrationError.UNSTABLE -> getString(R.string.calibration_unstable)
                CalibrationError.SENSOR_UNAVAILABLE ->
                    getString(R.string.calibration_sensor_unavailable)
                CalibrationError.VEHICLE_DISCONNECTED ->
                    getString(R.string.calibration_vehicle_disconnected)
                CalibrationError.INVALID_ROTATION ->
                    getString(R.string.calibration_invalid_rotation)
                CalibrationError.INCONSISTENT_POSITIONS ->
                    getString(R.string.calibration_inconsistent_positions)
                CalibrationError.VALIDATION_FAILED -> getString(
                    R.string.calibration_validation_failed,
                    state.validationErrorDegrees ?: 0f
                )
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isEnabled = !state.isRunning
            setText(
                when (state.phase) {
                    CalibrationDialogPhase.RESTART_REQUIRED,
                    CalibrationDialogPhase.VALIDATION_FAILED -> R.string.restart_calibration
                    CalibrationDialogPhase.RETRYABLE_ERROR -> R.string.retry
                    else -> R.string.calibrate
                }
            )
        }
    }

    private fun dismissCalibrationDialog() {
        mCalibrationDialog?.setOnCancelListener(null)
        mCalibrationDialog?.dismiss()
        mCalibrationDialog = null
        mCalibrationDialogBinding = null
        mCalibrationTarget = null
    }

}
