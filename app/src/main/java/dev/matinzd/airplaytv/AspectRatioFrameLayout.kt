package dev.matinzd.airplaytv

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout

/** Letterboxes its child to the video's aspect ratio inside the parent's bounds. */
class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
    var aspectRatio = 16f / 9f
        set(value) {
            if (value > 0 && value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        var width = maxWidth
        var height = (maxWidth / aspectRatio).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (maxHeight * aspectRatio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    companion object {
        fun centered() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
    }
}
