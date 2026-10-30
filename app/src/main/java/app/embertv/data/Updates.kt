package app.embertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Checks the GitHub releases this app is built into and downloads newer APKs. */
object Updates {
    /** The newest published build, or null if it can't be reached. */
    suspend fun latest(repo: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "EmberTV")
            try {
                if (c.responseCode !in 200..299) return@withContext null
                val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = root.optString("tag_name", "")
                val code = tag.substringAfterLast('-').toIntOrNull() ?: return@withContext null
                val asset = asArray(root.opt("assets")).objects().firstOrNull { it.optString("name", "").endsWith(".apk") }
                    ?: return@withContext null
                UpdateInfo(code, "1.0.$code", asset.optString("browser_download_url", ""))
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads [info] into [dir], reporting progress from 0 to 1. Returns the APK file. */
    suspend fun download(info: UpdateInfo, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "EmberTV-${info.versionCode}.apk")
        val partial = File(dir, "download.part")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "EmberTV")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLength.toLong()
            var done = 0L
            c.inputStream.use { input ->
                partial.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
        if (!partial.renameTo(target)) throw IllegalStateException("Couldn't save the update.")
        target
    }
}
