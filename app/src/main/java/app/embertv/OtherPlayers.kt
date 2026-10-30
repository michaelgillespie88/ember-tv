package app.embertv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** A video app on this device that can play a stream link, such as VLC or MX Player. */
data class OtherPlayer(val packageName: String, val label: String)

/** Finding and opening other video players, for streams Streammania's own player struggles with. */
object OtherPlayers {
    /** Well-known players, listed first in this order when installed. */
    private val PREFERRED = listOf(
        "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro", "com.brouken.player",
        "is.xyz.mpv", "org.xbmc.kodi", "com.kodi.kodi",
    )

    fun installed(context: Context): List<OtherPlayer> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://example.com/stream.ts"), "video/*")
        val found = try {
            pm.queryIntentActivities(probe, 0)
        } catch (e: Exception) {
            emptyList()
        }
        return found
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                OtherPlayer(pkg, info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ PREFERRED.indexOf(it.packageName).let { i -> if (i < 0) 99 else i } }, { it.label.lowercase() }))
    }

    fun isInstalled(context: Context, pkg: String): Boolean = installed(context).any { it.packageName == pkg }

    /**
     * Opens [url] in [pkg]. [positionMs] starts videos part-way through in VLC and MX Player.
     * Returns false if that app can't be opened.
     */
    fun open(context: Context, pkg: String, url: String, title: String, positionMs: Long): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url), "video/*")
            .setPackage(pkg)
            .putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (positionMs > 0) {
            // MX Player wants an int, VLC a long.
            if (pkg.startsWith("com.mxtech.")) intent.putExtra("position", positionMs.toInt()) else intent.putExtra("position", positionMs)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
