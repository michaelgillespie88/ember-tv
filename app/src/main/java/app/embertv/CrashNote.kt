package app.embertv

import android.content.Context

/**
 * If the app ever closes because of a fault, this writes down why, so the next launch can show it
 * (and it can be screenshotted and passed on). Nothing is sent anywhere.
 */
object CrashNote {
    private const val KEY = "last_crash"
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString().lines()
                // The error itself, then the app's own lines, which say where it happened.
                val ours = trace.filter { it.contains("app.embertv") }.take(6)
                val text = (listOf(trace.firstOrNull().orEmpty()) + ours).joinToString("\n").take(1_500)
                context.getSharedPreferences("ember", Context.MODE_PRIVATE).edit()
                    .putString(KEY, "${BuildConfig.VERSION_NAME}\n$text")
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** What went wrong last time, once; null if nothing did. */
    fun take(context: Context): String? {
        val sp = context.getSharedPreferences("ember", Context.MODE_PRIVATE)
        val note = sp.getString(KEY, null) ?: return null
        sp.edit().remove(KEY).apply()
        return note
    }
}
