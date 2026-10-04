package de.energy6.caravanleveler

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import androidx.preference.EditTextPreference
import java.text.NumberFormat

class EditNumberPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.editTextPreferenceStyle,
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    private var value: Number? = null
    private var formatter: NumberFormat = NumberFormat.getNumberInstance()

    init {
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.setSelectAllOnFocus(true)
        }
    }

    override fun setText(text: String?) {
        if (text == null) {
            value = null
            super.setText(null)
            return
        }

        val parsed = formatter.parsePositiveNumber(text) ?: return
        value = parsed
        super.setText(parsed.toDouble().toString())
    }

    override fun getText(): String? = value?.let(formatter::format)

    override fun onSetInitialValue(defaultValue: Any?) {
        val persistedValue = getPersistedString(defaultValue as? String)
        value = persistedValue?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        super.setText(value?.toDouble()?.toString())
    }

    fun setFormatter(formatter: NumberFormat) {
        this.formatter = formatter
    }
}
