package app.embertv

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational

/** Picture-in-picture for phones: the video keeps playing in a small window over other apps. */
object Pip {
    fun supported(activity: Activity): Boolean =
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** [autoEnter]: on Android 12+, going home while a video plays shrinks it into a window by itself. */
    fun params(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(autoEnter)
        return b.build()
    }

    fun enter(activity: Activity): Boolean {
        if (!supported(activity)) return false
        return try {
            activity.enterPictureInPictureMode(params(autoEnter = true))
        } catch (e: IllegalStateException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
