package app.embertv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers, stored on the TV only. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ember", Context.MODE_PRIVATE)

    var account: Account?
        get() {
            val server = sp.getString("server", null) ?: return null
            return Account(server, sp.getString("user", "") ?: "", sp.getString("pass", "") ?: "")
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove("server")
                e.remove("user")
                e.remove("pass")
            } else {
                e.putString("server", value.server)
                e.putString("user", value.username)
                e.putString("pass", value.password)
            }
            e.apply()
        }

    var streamFormat: String
        get() = sp.getString("format", "ts") ?: "ts"
        set(value) {
            sp.edit().putString("format", value).apply()
        }

    var userAgent: String
        get() = sp.getString("ua", DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) {
            sp.edit().putString("ua", value).apply()
        }

    var invertZap: Boolean
        get() = sp.getBoolean("invert_zap", false)
        set(value) {
            sp.edit().putBoolean("invert_zap", value).apply()
        }

    var favourites: List<Favourite>
        get() {
            val raw = sp.getString("favourites", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<Favourite>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = ContentType.entries.firstOrNull { it.name == o.optString("type") } ?: continue
                    out.add(
                        Favourite(
                            type = type,
                            id = o.optInt("id"),
                            name = o.optString("name"),
                            image = o.optString("image"),
                            categoryId = o.optString("cat"),
                            ext = o.optString("ext"),
                            num = o.optInt("num"),
                        )
                    )
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (f in value) {
                val o = JSONObject()
                o.put("type", f.type.name)
                o.put("id", f.id)
                o.put("name", f.name)
                o.put("image", f.image)
                o.put("cat", f.categoryId)
                o.put("ext", f.ext)
                o.put("num", f.num)
                arr.put(o)
            }
            sp.edit().putString("favourites", arr.toString()).apply()
        }

    fun position(key: String): Long = sp.getLong("pos_$key", 0L)

    /** Keeps a resume point unless the video was barely started or is basically finished. */
    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs.toDouble() > durationMs.toDouble() * 0.95
        val e = sp.edit()
        if (positionMs < 60_000L || finished) e.remove("pos_$key") else e.putLong("pos_$key", positionMs)
        e.apply()
    }

    fun lastEpisode(seriesId: Int): Int? = sp.getInt("last_ep_$seriesId", -1).takeIf { it >= 0 }

    fun setLastEpisode(seriesId: Int, episodeId: Int) {
        sp.edit().putInt("last_ep_$seriesId", episodeId).apply()
    }
}
