package app.embertv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val isTv = isTelevision()
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

    /** Google TV, Android TV and Fire TV all count as TVs; everything else gets the touch layout. */
    private fun isTelevision(): Boolean {
        val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
