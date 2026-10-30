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
    private fun pk(base: String): String = pkFor(profileId, base)

    private fun pkFor(id: String, base: String): String = if (id == MAIN_PROFILE) base else "p_${id}_$base"

    /** Football clubs this profile follows, for goal alerts and the Home card. */
    var followedTeams: List<Team>
        get() {
            val raw = sp.getString(pk("teams"), null) ?: return emptyList()
            return try {
                JSONArray(raw).objects().map { o ->
                    Team(o.optString("id"), o.optString("name"), o.optString("short"), o.optString("logo"), o.optString("league"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (t in value) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                o.put("short", t.shortName)
                o.put("logo", t.logo)
                o.put("league", t.league)
                arr.put(o)
            }
            sp.edit().putString(pk("teams"), arr.toString()).apply()
        }

    var reminders: List<Reminder>
        get() {
            val raw = sp.getString(pk("reminders"), null) ?: return emptyList()
            return try {
                JSONArray(raw).objects().map { o ->
                    Reminder(
                        streamId = o.optInt("stream"),
                        channelName = o.optString("channel"),
                        channelIcon = o.optString("icon"),
                        categoryId = o.optString("category"),
                        num = o.optInt("num"),
                        title = o.optString("title"),
                        start = o.optLong("start"),
                        end = o.optLong("end"),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (r in value) {
                val o = JSONObject()
                o.put("stream", r.streamId)
                o.put("channel", r.channelName)
                o.put("icon", r.channelIcon)
                o.put("category", r.categoryId)
                o.put("num", r.num)
                o.put("title", r.title)
                o.put("start", r.start)
                o.put("end", r.end)
                arr.put(o)
            }
            sp.edit().putString(pk("reminders"), arr.toString()).apply()
        }

    fun categoryRules(id: String): CategoryRules {
        val raw = sp.getString(pkFor(id, "category_rules"), null) ?: return CategoryRules(false, emptySet())
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("keys") ?: JSONArray()
            val keys = HashSet<String>()
            for (i in 0 until arr.length()) keys.add(arr.optString(i))
            CategoryRules(o.optBoolean("allow"), keys)
        } catch (e: Exception) {
            CategoryRules(false, emptySet())
        }
    }

    fun setCategoryRules(id: String, rules: CategoryRules) {
        val o = JSONObject()
        o.put("allow", rules.allowMode)
        val arr = JSONArray()
        for (k in rules.keys) arr.put(k)
        o.put("keys", arr)
        sp.edit().putString(pkFor(id, "category_rules"), o.toString()).apply()
    }

    var profiles: List<Profile>
        get() {
            val raw = sp.getString("profiles", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<Profile>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) {
                        out.add(Profile(id, o.optString("name"), o.optInt("color"), o.optBoolean("kids"), o.optString("pin")))
                    }
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
                o.put("kids", p.kids)
                o.put("pin", p.pinHash)
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
                key in setOf("favourites", "history", "recent_channels", "audio_lang", "subs", "reminders", "category_rules", "teams", "watched") ||
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
        get() = (sp.getString("ua", DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT)
            .let { if (it == OLD_DEFAULT_USER_AGENT) DEFAULT_USER_AGENT else it }
        set(value) {
            sp.edit().putString("ua", value).apply()
        }

    /** Package of another video app to play everything in (VLC, MX Player…); empty for the built-in player. */
    var externalPlayer: String
        get() = sp.getString("external_player", "") ?: ""
        set(value) {
            sp.edit().putString("external_player", value).apply()
        }

    /** Films and episodes this profile has watched to the end ("movie:12", "ep:34"). */
    var watched: Set<String>
        get() {
            val raw = sp.getString(pk("watched"), null) ?: return emptySet()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
            } catch (e: Exception) {
                emptySet()
            }
        }
        set(value) {
            val arr = JSONArray()
            for (k in value) arr.put(k)
            sp.edit().putString(pk("watched"), arr.toString()).apply()
        }

    /** Phones allowed to control this TV (tokens handed out when pairing). Kept on this device only. */
    var tvTokens: Set<String>
        get() = sp.getStringSet("tv_tokens", emptySet())?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet("tv_tokens", value).apply()
        }

    /** On a phone: the TVs it has paired with, as JSON (name, host, port, token). */
    var pairedTvs: String
        get() = sp.getString("paired_tvs", "[]") ?: "[]"
        set(value) {
            sp.edit().putString("paired_tvs", value).apply()
        }

    /** The user's own TMDB key, for cast photos, trailers and suggestions. Empty when not set. */
    var tmdbKey: String
        get() = sp.getString("tmdb_key", "") ?: ""
        set(value) {
            sp.edit().putString("tmdb_key", value).apply()
        }

    /** Show the Live Sport Now row on Home. */
    var sportOnHome: Boolean
        get() = sp.getBoolean("sport_on_home", true)
        set(value) {
            sp.edit().putBoolean("sport_on_home", value).apply()
        }

    /** Slimmer rows in the TV guide and channel list, so more channels fit. */
    var compactLists: Boolean
        get() = sp.getBoolean("compact_lists", true)
        set(value) {
            sp.edit().putBoolean("compact_lists", value).apply()
        }

    /** Play a small muted picture of the highlighted channel while browsing on a TV. */
    var livePreview: Boolean
        get() = sp.getBoolean("live_preview", true)
        set(value) {
            sp.edit().putBoolean("live_preview", value).apply()
        }

    /** Show each film or show once with its HD/FHD/4K copies as versions. */
    var mergeVersions: Boolean
        get() = sp.getBoolean("merge_versions", true)
        set(value) {
            sp.edit().putBoolean("merge_versions", value).apply()
        }

    /** Which version to play: "ask", "best", "FHD" or "HD". */
    var qualityChoice: String
        get() = sp.getString("quality_choice", "ask") ?: "ask"
        set(value) {
            sp.edit().putString("quality_choice", value).apply()
        }

    /** Switch the TV's refresh rate to match what's playing. */
    var matchFrameRate: Boolean
        get() = sp.getBoolean("match_frame_rate", true)
        set(value) {
            sp.edit().putBoolean("match_frame_rate", value).apply()
        }

    /** Minutes of no remote presses before the TV screensaver starts; 0 turns it off. */
    var screensaverMinutes: Int
        get() = sp.getInt("screensaver_min", 3)
        set(value) {
            sp.edit().putInt("screensaver_min", value).apply()
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
