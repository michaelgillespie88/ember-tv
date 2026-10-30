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
        CrashNote.install(applicationContext)
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

    override fun onStart() {
        super.onStart()
        if (isTv) {
            vm.remoteKeys = { key -> pressFromPhone(key) }
            vm.startTvLink(tvName())
        }
    }

    override fun onStop() {
        super.onStop()
        if (isTv) {
            vm.stopTvLink()
            vm.remoteKeys = null
        }
    }

    /** A button pressed on a phone's remote, as if it came from the TV remote. */
    private fun pressFromPhone(key: String) {
        if (key == "home") {
            while (vm.stack.size > 1) vm.pop()
            return
        }
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "chup" -> KeyEvent.KEYCODE_CHANNEL_UP
            "chdown" -> KeyEvent.KEYCODE_CHANNEL_DOWN
            "menu" -> KeyEvent.KEYCODE_MENU
            else -> return
        }
        // BACK has to go through the back dispatcher for Compose to see it.
        if (code == KeyEvent.KEYCODE_BACK) {
            if (vm.userActivity(wake = true)) return
            onBackPressedDispatcher.onBackPressed()
            return
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun tvName(): String {
        val named = runCatching { android.provider.Settings.Global.getString(contentResolver, "device_name") }.getOrNull()
        return named?.takeIf { it.isNotBlank() } ?: Build.MODEL.ifBlank { "Streammania TV" }
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
