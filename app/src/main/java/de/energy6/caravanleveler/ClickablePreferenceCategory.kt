package de.energy6.caravanleveler

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceCategory

/** Keeps the category's standard appearance while allowing its heading to be tapped. */
class ClickablePreferenceCategory(context: Context, attrs: AttributeSet?) : PreferenceCategory(context, attrs) {
    init {
        isSelectable = true
    }

    override fun isEnabled(): Boolean = !shouldDisableDependents()
}
