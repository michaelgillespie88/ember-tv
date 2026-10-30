package app.embertv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers, stored on the device only. Login and playback settings are shared;
 * favourites, history and resume points belong to the active profile ([profileId]).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ember", Context.MODE_PRIVATE)

    /** Which profile per-profile data is read from and written to. */
    var profileId: String = MAIN_PROFILE

    // The main profile uses the original key names, so data from before profiles carries over.
    private fun pk(base: String): String = if (profileId == MAIN_PROFILE) base else "p_${profileId}_$base"

    var profiles: List<Profile>
        get() {
            val raw = sp.getString("profiles", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<Profile>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) out.add(Profile(id, o.optString("name"), o.optInt("color")))
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (p in value) {
                val o = JSONObject()
                o.put("id", p.id)
                o.put("name", p.name)
                o.put("color", p.color)
                arr.put(o)
            }
            sp.edit().putString("profiles", arr.toString()).apply()
        }

    var lastProfile: String?
        get() = sp.getString("last_profile", null)
        set(value) {
            sp.edit().putString("last_profile", value).apply()
        }

    /** Forgets everything saved for one profile. */
    fun deleteProfileData(id: String) {
        val e = sp.edit()
        for (key in sp.all.keys) {
            val belongs = if (id == MAIN_PROFILE) {
                key in setOf("favourites", "history", "recent_channels", "audio_lang", "subs", "reminders", "category_rules") ||
                    key.startsWith("pos_") || key.startsWith("last_ep_")
            } else {
                key.startsWith("p_${id}_")
            }
            if (belongs) e.remove(key)
        }
        e.apply()
    }

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
        get() = decodeFavourites(sp.getString(pk("favourites"), "[]"))
        set(value) {
            sp.edit().putString(pk("favourites"), encodeFavourites(value)).apply()
        }

    /** Channels this profile watched recently, newest first. */
    var recentChannels: List<Favourite>
        get() = decodeFavourites(sp.getString(pk("recent_channels"), "[]"))
        set(value) {
            sp.edit().putString(pk("recent_channels"), encodeFavourites(value)).apply()
        }

    /** Preferred audio language code, or "" for the stream's default. */
    var audioLanguage: String
        get() = sp.getString(pk("audio_lang"), "") ?: ""
        set(value) {
            sp.edit().putString(pk("audio_lang"), value).apply()
        }

    /** "off", or a language code such as "en" to switch subtitles on in that language. */
    var subtitleMode: String
        get() = sp.getString(pk("subs"), "off") ?: "off"
        set(value) {
            sp.edit().putString(pk("subs"), value).apply()
        }

    private fun decodeFavourites(raw: String?): List<Favourite> = try {
        val arr = JSONArray(raw ?: "[]")
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

    private fun encodeFavourites(list: List<Favourite>): String {
        val arr = JSONArray()
        for (f in list) {
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
        return arr.toString()
    }

    var history: List<HistoryEntry>
        get() {
            val raw = sp.getString(pk("history"), "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<HistoryEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = ContentType.entries.firstOrNull { it.name == o.optString("type") } ?: continue
                    out.add(
                        HistoryEntry(
                            key = o.optString("key"),
                            type = type,
                            id = o.optInt("id"),
                            seriesId = o.optInt("sid"),
                            title = o.optString("title"),
                            subtitle = o.optString("sub"),
                            image = o.optString("img"),
                            seriesCover = o.optString("cover"),
                            ext = o.optString("ext"),
                            positionMs = o.optLong("pos"),
                            durationMs = o.optLong("dur"),
                            updatedAt = o.optLong("at"),
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
            for (h in value) {
                val o = JSONObject()
                o.put("key", h.key)
                o.put("type", h.type.name)
                o.put("id", h.id)
                o.put("sid", h.seriesId)
                o.put("title", h.title)
                o.put("sub", h.subtitle)
                o.put("img", h.image)
                o.put("cover", h.seriesCover)
                o.put("ext", h.ext)
                o.put("pos", h.positionMs)
                o.put("dur", h.durationMs)
                o.put("at", h.updatedAt)
                arr.put(o)
            }
            sp.edit().putString(pk("history"), arr.toString()).apply()
        }

    fun position(key: String): Long = sp.getLong(pk("pos_$key"), 0L)

    /** Keeps a resume point unless the video was barely started or is basically finished. */
    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs.toDouble() > durationMs.toDouble() * 0.95
        val e = sp.edit()
        val k = pk("pos_$key")
        if (positionMs < 60_000L || finished) e.remove(k) else e.putLong(k, positionMs)
        e.apply()
    }

    fun lastEpisode(seriesId: Int): Int? = sp.getInt(pk("last_ep_$seriesId"), -1).takeIf { it >= 0 }

    fun setLastEpisode(seriesId: Int, episodeId: Int) {
        sp.edit().putInt(pk("last_ep_$seriesId"), episodeId).apply()
    }
}
