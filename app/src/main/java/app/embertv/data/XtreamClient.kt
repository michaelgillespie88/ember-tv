package app.embertv.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class XtreamException(message: String) : Exception(message)

const val DEFAULT_USER_AGENT = "Streammania/1.0 (Linux; Android TV)"

/** What the default identity was called before the rename; saved settings may still hold it. */
const val OLD_DEFAULT_USER_AGENT = "EmberTV/1.0 (Linux; Android TV)"
const val VLC_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

/** Turns whatever the user typed into "http(s)://host:port" with no trailing path. */
fun normalizeServer(input: String): String {
    var s = input.trim()
    val q = s.indexOf('?')
    if (q >= 0) s = s.substring(0, q)
    if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
        s = "http://$s"
    }
    s = s.trimEnd('/')
    for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
        if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
    }
    return s.trimEnd('/')
}

/** Pulls the username and password out of a pasted link like .../get.php?username=a&password=b */
fun credentialsFromLink(input: String): Pair<String, String>? {
    val query = input.substringAfter('?', "")
    if (query.isEmpty()) return null
    val params = HashMap<String, String>()
    for (part in query.split('&')) {
        val i = part.indexOf('=')
        if (i <= 0) continue
        val value = runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull() ?: continue
        params[part.substring(0, i).lowercase()] = value
    }
    val user = params["username"] ?: return null
    val pass = params["password"] ?: return null
    return user to pass
}

internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "").trim()

internal fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i)
        if (o != null) out.add(o)
    }
    return out
}

internal fun asArray(value: Any?): JSONArray = value as? JSONArray ?: JSONArray()

internal fun firstString(value: Any?): String = when (value) {
    is JSONArray -> {
        var found = ""
        for (i in 0 until value.length()) {
            val s = value.optString(i, "").trim()
            if (s.isNotEmpty() && s != "null") {
                found = s
                break
            }
        }
        found
    }
    is String -> if (value == "null") "" else value.trim()
    else -> ""
}

private fun networkMessage(e: IOException): String = when (e) {
    is UnknownHostException -> "Couldn't find that server. Check the address."
    is SocketTimeoutException -> "The server took too long to respond."
    is ConnectException -> "Couldn't connect. Check the address and port number."
    is SSLException -> "Secure connection failed. Try the address with http:// instead of https://."
    else -> "Network problem: ${e.message ?: e.javaClass.simpleName}"
}

/** Talks to an Xtream Codes compatible panel through player_api.php. */
class XtreamClient(val account: Account, private val userAgent: String) {
    val base: String = normalizeServer(account.server)
    private val queryUser = URLEncoder.encode(account.username, "UTF-8")
    private val queryPass = URLEncoder.encode(account.password, "UTF-8")
    private val pathUser = queryUser.replace("+", "%20")
    private val pathPass = queryPass.replace("+", "%20")

    private fun open(start: String): HttpURLConnection {
        var url = URL(start)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json, */*")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw XtreamException("The server redirected without saying where.")
                url = URL(url, location)
            } else {
                return c
            }
        }
        throw XtreamException("The server redirected too many times.")
    }

    private suspend fun call(action: String? = null, extra: String = ""): Any? = withContext(Dispatchers.IO) {
        val sb = StringBuilder(base).append("/player_api.php?username=").append(queryUser).append("&password=").append(queryPass)
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        val conn = try {
            open(sb.toString())
        } catch (e: IOException) {
            throw XtreamException(networkMessage(e))
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                throw XtreamException("The server refused this login (HTTP $code). Check your username and password.")
            }
            if (code == 404) throw XtreamException("No Xtream server found at that address (HTTP 404).")
            if (code !in 200..299) throw XtreamException("The server returned an error (HTTP $code).")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (text.isEmpty()) null else JSONTokener(text).nextValue()
        } catch (e: XtreamException) {
            throw e
        } catch (e: JSONException) {
            throw XtreamException("The server's reply wasn't valid Xtream data.")
        } catch (e: IOException) {
            throw XtreamException(networkMessage(e))
        } finally {
            conn.disconnect()
        }
    }

    suspend fun authenticate(): AccountInfo {
        val notXtream = "That address didn't respond like an Xtream Codes server."
        val root = call() as? JSONObject ?: throw XtreamException(notXtream)
        val user = root.optJSONObject("user_info") ?: throw XtreamException(notXtream)
        if (user.optInt("auth", 1) == 0) throw XtreamException("Wrong username or password.")
        val status = user.str("status").ifEmpty { "Active" }
        when (status.lowercase()) {
            "expired" -> throw XtreamException("This subscription has expired.")
            "banned" -> throw XtreamException("This account has been banned by the provider.")
            "disabled" -> throw XtreamException("This account is disabled.")
        }
        val formats = ArrayList<String>()
        val f = user.optJSONArray("allowed_output_formats")
        if (f != null) for (i in 0 until f.length()) formats.add(f.optString(i, ""))
        val server = root.optJSONObject("server_info")
        return AccountInfo(
            status = status,
            expiry = user.str("exp_date").toLongOrNull(),
            maxConnections = user.str("max_connections"),
            activeConnections = user.str("active_cons"),
            formats = formats,
            serverTimezone = server?.str("timezone") ?: "",
        )
    }

    suspend fun liveCategories(): List<Category> = categories("get_live_categories")
    suspend fun movieCategories(): List<Category> = categories("get_vod_categories")
    suspend fun seriesCategories(): List<Category> = categories("get_series_categories")

    private suspend fun categories(action: String): List<Category> =
        asArray(call(action)).objects().mapNotNull { o ->
            val id = o.str("category_id")
            if (id.isEmpty()) null else Category(id, o.str("category_name").ifEmpty { "Untitled" })
        }.distinctBy { it.id }

    private fun categoryParam(categoryId: String?): String =
        if (categoryId == null) "" else "&category_id=" + URLEncoder.encode(categoryId, "UTF-8")

    suspend fun channels(categoryId: String? = null): List<Channel> =
        asArray(call("get_live_streams", categoryParam(categoryId))).objects().mapNotNull { o ->
            val id = o.str("stream_id").toIntOrNull() ?: return@mapNotNull null
            Channel(
                streamId = id,
                num = o.str("num").toIntOrNull() ?: 0,
                name = o.str("name").ifEmpty { "Channel $id" },
                icon = o.str("stream_icon"),
                categoryId = o.str("category_id"),
                epgId = o.str("epg_channel_id"),
                hasArchive = o.str("tv_archive") == "1",
                archiveDays = o.str("tv_archive_duration").toIntOrNull() ?: 0,
            )
        }.distinctBy { it.streamId }

    suspend fun movies(categoryId: String? = null): List<Movie> =
        asArray(call("get_vod_streams", categoryParam(categoryId))).objects().mapNotNull { o ->
            val id = o.str("stream_id").toIntOrNull() ?: return@mapNotNull null
            Movie(
                streamId = id,
                name = o.str("name").ifEmpty { "Movie $id" },
                poster = o.str("stream_icon"),
                categoryId = o.str("category_id"),
                rating = o.str("rating"),
                ext = o.str("container_extension").ifEmpty { "mp4" },
                added = o.str("added").toLongOrNull() ?: 0L,
            )
        }.distinctBy { it.streamId }

    suspend fun series(categoryId: String? = null): List<Series> =
        asArray(call("get_series", categoryParam(categoryId))).objects().mapNotNull { o ->
            val id = o.str("series_id").toIntOrNull() ?: return@mapNotNull null
            Series(
                seriesId = id,
                name = o.str("name").ifEmpty { "Series $id" },
                cover = o.str("cover"),
                categoryId = o.str("category_id"),
                plot = o.str("plot"),
                genre = o.str("genre"),
                rating = o.str("rating"),
                year = o.str("releaseDate").ifEmpty { o.str("release_date") }.take(4),
            )
        }.distinctBy { it.seriesId }

    suspend fun movieInfo(id: Int): MovieInfo {
        val root = call("get_vod_info", "&vod_id=$id") as? JSONObject
            ?: throw XtreamException("No details for this movie.")
        val info = root.optJSONObject("info") ?: JSONObject()
        return MovieInfo(
            plot = info.str("plot").ifEmpty { info.str("description") },
            genre = info.str("genre"),
            cast = info.str("cast").ifEmpty { info.str("actors") },
            director = info.str("director"),
            year = info.str("releasedate").ifEmpty { info.str("release_date") }.take(4),
            duration = info.str("duration"),
            rating = info.str("rating"),
            backdrop = firstString(info.opt("backdrop_path")),
            poster = info.str("movie_image").ifEmpty { info.str("cover_big") },
        )
    }

    suspend fun seriesInfo(id: Int): SeriesInfo {
        val root = call("get_series_info", "&series_id=$id") as? JSONObject
            ?: throw XtreamException("No details for this series.")
        val info = root.optJSONObject("info") ?: JSONObject()
        val bySeason = HashMap<Int, MutableList<Episode>>()

        fun add(o: JSONObject, seasonHint: Int) {
            val epId = o.str("id").toIntOrNull() ?: return
            val season = o.str("season").toIntOrNull() ?: seasonHint
            val epInfo = o.optJSONObject("info") ?: JSONObject()
            bySeason.getOrPut(season) { ArrayList() }.add(
                Episode(
                    id = epId,
                    season = season,
                    number = o.str("episode_num").toIntOrNull() ?: 0,
                    title = o.str("title").ifEmpty { "Episode" },
                    ext = o.str("container_extension").ifEmpty { "mp4" },
                    plot = epInfo.str("plot"),
                    image = epInfo.str("movie_image"),
                    duration = epInfo.str("duration"),
                )
            )
        }

        val episodes = root.opt("episodes")
        if (episodes is JSONObject) {
            val keys = episodes.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                for (o in asArray(episodes.opt(k)).objects()) add(o, k.toIntOrNull() ?: 0)
            }
        } else if (episodes is JSONArray) {
            for (i in 0 until episodes.length()) {
                val item = episodes.opt(i)
                if (item is JSONArray) {
                    for (o in item.objects()) add(o, 0)
                } else if (item is JSONObject) {
                    add(item, 0)
                }
            }
        }

        val seasons = bySeason.keys.sorted()
        val sorted = LinkedHashMap<Int, List<Episode>>()
        for (s in seasons) sorted[s] = bySeason.getValue(s).distinctBy { it.id }.sortedBy { it.number }
        return SeriesInfo(
            plot = info.str("plot"),
            genre = info.str("genre"),
            cast = info.str("cast"),
            year = info.str("releaseDate").ifEmpty { info.str("release_date") }.take(4),
            rating = info.str("rating"),
            backdrop = firstString(info.opt("backdrop_path")),
            cover = info.str("cover"),
            seasons = seasons,
            episodes = sorted,
        )
    }

    /** What's on now and next. */
    suspend fun shortEpg(streamId: Int, limit: Int): List<Programme> {
        val root = call("get_short_epg", "&stream_id=$streamId&limit=$limit") as? JSONObject ?: return emptyList()
        return listings(root)
    }

    /** Everything the provider has for a channel: usually the past week (for catch-up) and the days ahead. */
    suspend fun fullEpg(streamId: Int): List<Programme> {
        val root = call("get_simple_data_table", "&stream_id=$streamId") as? JSONObject ?: return emptyList()
        return listings(root)
    }

    private fun listings(root: JSONObject): List<Programme> =
        asArray(root.opt("epg_listings")).objects().mapNotNull { o ->
            val start = o.str("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val end = o.str("stop_timestamp").toLongOrNull()
                ?: o.str("end_timestamp").toLongOrNull()
                ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Programme(
                title = decode(o.str("title")).ifEmpty { "Untitled" },
                description = decode(o.str("description")),
                start = start,
                end = end,
                archive = when (o.str("has_archive")) {
                    "1" -> true
                    "0" -> false
                    else -> null
                },
                serverStart = o.str("start"),
            )
        }.distinctBy { it.start }.sortedBy { it.start }

    /** Xtream sends guide text base64-encoded; a few panels send plain text instead. */
    private fun decode(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val decoded = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            val looksBroken = decoded.any { it == '�' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }
            if (looksBroken) s else decoded
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    fun liveUrl(streamId: Int, format: String): String =
        "$base/live/$pathUser/$pathPass/$streamId." + (if (format == "m3u8") "m3u8" else "ts")

    fun movieUrl(streamId: Int, ext: String): String = "$base/movie/$pathUser/$pathPass/$streamId.$ext"

    fun episodeUrl(episodeId: Int, ext: String): String = "$base/series/$pathUser/$pathPass/$episodeId.$ext"

    /**
     * Catch-up for a programme that has started. The start time goes in the provider's own clock,
     * taken from the guide when it gives one, otherwise worked out from [serverTimezone].
     */
    fun catchupUrl(streamId: Int, p: Programme, serverTimezone: String): String {
        val fromGuide = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(p.serverStart)
        val start = if (fromGuide != null) {
            val (date, h, m) = fromGuide.destructured
            "$date:$h-$m"
        } else {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            if (serverTimezone.isNotBlank()) f.timeZone = java.util.TimeZone.getTimeZone(serverTimezone)
            f.format(java.util.Date(p.start * 1000))
        }
        val minutes = ((p.end - p.start + 59) / 60).coerceAtLeast(1)
        return "$base/timeshift/$pathUser/$pathPass/$minutes/$start/$streamId.ts"
    }
}
