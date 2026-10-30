package app.embertv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.embertv.ui.AppViewModel
import app.embertv.ui.EmberApp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var isTv = false
    /** The key that woke the screensaver, so its release doesn't press anything either. */
    private var swallowUp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        isTv = isTelevision()
        if (!isTv) {
            // Phones and tablets: draw behind transparent system bars; screens pad themselves.
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)
        if (!isTv) window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContent { EmberApp(vm, isTv) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Any button counts as activity; the first one only wakes the screensaver.
            if (vm.userActivity(wake = !volume)) {
                swallowUp = event.keyCode
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Pressing Home during a video on a phone shrinks it into picture-in-picture (Android 8 to 11; 12+ does it itself). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTv && vm.playerOpen && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Pip.enter(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip = isInPictureInPictureMode
    }

    /** Google TV, Android TV and Fire TV all count as TVs; everything else gets the touch layout. */
    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
