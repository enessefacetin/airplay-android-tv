package dev.matinzd.airplaytv

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.TextView

/** Shared look for the 10-foot UI: dark surfaces, large type, a clear focus state. */
object TvUi {
    const val BG = 0xFF0E1015.toInt()
    const val SURFACE = 0xFF1B1F27.toInt()
    const val SURFACE_FOCUSED = 0xFF2B3240.toInt()
    const val ACCENT = 0xFF5B9BFF.toInt()
    const val TEXT = 0xFFF2F4F8.toInt()
    const val TEXT_DIM = 0xFFA3ABB9.toInt()
    const val WARNING = 0xFFFFB74D.toInt()

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, sizeSp: Float, color: Int = TEXT, bold: Boolean = false) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun rounded(context: Context, color: Int, radiusDp: Int = 16, strokeColor: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(context, 3), strokeColor)
        }

    /**
     * Makes a view a remote-friendly card: focusable and highlighted on focus. Small
     * cards also grow a little; full-width rows don't (they would be clipped).
     */
    fun card(view: View, grow: Boolean = true) = view.apply {
        isFocusable = true
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(context, SURFACE_FOCUSED, strokeColor = ACCENT))
            addState(intArrayOf(), rounded(context, SURFACE))
        }
        val pad = dp(context, 24)
        setPadding(pad, dp(context, 18), pad, dp(context, 18))
        // Grow from the left edge so the card stays aligned with the text above it.
        if (grow) addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = 0f; v.pivotY = v.height / 2f }
        if (grow) setOnFocusChangeListener { v, focused ->
            v.animate().scaleX(if (focused) 1.05f else 1f).scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
        }
    }
}
