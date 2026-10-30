package app.embertv

import android.app.Activity
import android.view.Display
import kotlin.math.abs

/**
 * Auto frame rate: switches the TV's refresh rate to suit what's playing, so UK channels (25/50 fps)
 * play at 50Hz and films at 24Hz instead of juddering at 60Hz. Resolution is never changed.
 */
object FrameRate {
    /** Snaps a measured or declared frame rate to the standard it really is, or null if it's unclear. */
    fun standard(fps: Float, declared: Boolean): Float? = when {
        // Measurements can't tell 23.976 from 24 or 29.97 from 30, so assume the common one unless declared.
        fps in 23f..24.5f -> if (declared && abs(fps - 24f) < 0.01f) 24f else 23.976f
        fps in 24.5f..25.6f -> 25f
        fps in 29f..30.6f -> if (declared && abs(fps - 30f) < 0.01f) 30f else 29.97f
        fps in 48.5f..51f -> 50f
        fps in 58f..61f -> if (declared && abs(fps - 60f) < 0.01f) 60f else 59.94f
        else -> null
    }

    /** Refresh rates that show [fps] smoothly, best first. */
    private fun targets(fps: Float): List<Float> = when (fps) {
        23.976f -> listOf(23.976f, 24f, 59.94f)
        24f -> listOf(24f, 23.976f, 60f)
        25f -> listOf(50f, 25f)
        29.97f -> listOf(59.94f, 29.97f)
        30f -> listOf(60f, 30f)
        50f -> listOf(50f)
        59.94f -> listOf(59.94f, 60f)
        60f -> listOf(60f, 59.94f)
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display = activity.windowManager.defaultDisplay

    /** A display mode at the current resolution whose refresh rate suits [fps], or null if the TV has none. */
    fun modeFor(activity: Activity, fps: Float): Display.Mode? {
        val d = display(activity)
        val current = d.mode
        val sameSize = d.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        for (t in targets(fps)) {
            sameSize.firstOrNull { abs(it.refreshRate - t) < 0.01f }?.let { return it }
        }
        return null
    }

    fun currentModeId(activity: Activity): Int = display(activity).mode.modeId

    fun currentRefresh(activity: Activity): Float = display(activity).mode.refreshRate

    /** Asks for display mode [modeId]; 0 hands the choice back to the system. */
    fun request(activity: Activity, modeId: Int) {
        val window = activity.window
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != modeId) {
            attrs.preferredDisplayModeId = modeId
            window.attributes = attrs
        }
    }

    /** "50Hz", "59.94Hz", "23.98Hz". */
    fun label(hz: Float): String {
        val r = Math.round(hz * 100) / 100f
        val text = if (abs(r - Math.round(r)) < 0.01f) Math.round(r).toString() else if (abs(r - 23.98f) < 0.01f) "23.98" else r.toString()
        return text + "Hz"
    }
}
