package app.embertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Someone in the cast, with their photo from TMDB. */
data class CastMember(val name: String, val role: String, val photo: String)

/** A title TMDB recommends. */
data class Suggestion(val title: String, val year: String, val poster: String)

/** Extra details from The Movie Database: cast photos, a trailer and recommendations. */
data class Extras(val cast: List<CastMember>, val trailer: String, val suggestions: List<Suggestion>)

/**
 * The Movie Database (themoviedb.org), with the user's own free key. Accepts either the short
 * "API key" or the long "API Read Access Token". Results are kept for the session.
 */
class Tmdb(private val key: String) {
    private val cache = HashMap<String, Extras?>()

    private suspend fun get(path: String, params: String = ""): JSONObject? = withContext(Dispatchers.IO) {
        val bearer = key.startsWith("eyJ")
        val auth = if (bearer) "" else "&api_key=" + URLEncoder.encode(key, "UTF-8")
        try {
            val c = URL("https://api.themoviedb.org/3$path?language=en-GB$params$auth").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            try {
                if (c.responseCode !in 200..299) return@withContext null
                JSONTokener(c.inputStream.bufferedReader().use { it.readText() }).nextValue() as? JSONObject
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True if the key works. */
    suspend fun check(): Boolean = get("/configuration") != null

    private suspend fun find(kind: String, title: String, year: String): Int? {
        val q = "&query=" + URLEncoder.encode(Versions.cleanTitle(title).replace(Regex("\\(\\d{4}\\)"), "").trim(), "UTF-8") +
            (if (year.length == 4) (if (kind == "movie") "&year=$year" else "&first_air_date_year=$year") else "")
        val root = get("/search/$kind", q) ?: return null
        return asArray(root.opt("results")).objects().firstOrNull()?.optInt("id")?.takeIf { it > 0 }
    }

    /** Cast, trailer and suggestions for a film ("movie") or show ("tv"). */
    suspend fun extras(kind: String, tmdbId: Int, title: String, year: String): Extras? {
        val cacheKey = "$kind:$tmdbId:$title"
        if (cache.containsKey(cacheKey)) return cache[cacheKey]
        val id = tmdbId.takeIf { it > 0 } ?: find(kind, title, year)
        val result = if (id == null) null else {
            val credits = if (kind == "tv") "aggregate_credits" else "credits"
            val root = get("/$kind/$id", "&append_to_response=$credits,videos,recommendations")
            if (root == null) null else {
                val castJson = root.optJSONObject(credits) ?: root.optJSONObject("credits")
                val cast = asArray(castJson?.opt("cast")).objects().take(15).map { o ->
                    val role = o.optString("character").ifEmpty {
                        asArray(o.opt("roles")).objects().firstOrNull()?.optString("character").orEmpty()
                    }
                    val path = o.optString("profile_path")
                    CastMember(o.optString("name"), role, if (path.isNotBlank() && path != "null") "https://image.tmdb.org/t/p/w185$path" else "")
                }
                val videos = asArray(root.optJSONObject("videos")?.opt("results")).objects()
                    .filter { it.optString("site") == "YouTube" }
                val trailer = (videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                    ?: videos.firstOrNull { it.optString("type") == "Trailer" } ?: videos.firstOrNull())?.optString("key").orEmpty()
                val suggestions = asArray(root.optJSONObject("recommendations")?.opt("results")).objects().take(20).map { o ->
                    val date = o.optString("release_date").ifEmpty { o.optString("first_air_date") }
                    val path = o.optString("poster_path")
                    Suggestion(
                        o.optString("title").ifEmpty { o.optString("name") },
                        date.take(4),
                        if (path.isNotBlank() && path != "null") "https://image.tmdb.org/t/p/w342$path" else "",
                    )
                }
                Extras(cast, trailer, suggestions)
            }
        }
        cache[cacheKey] = result
        return result
    }
}
