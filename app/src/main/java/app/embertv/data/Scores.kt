package app.embertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A football competition on ESPN's public scoreboard. */
data class League(val code: String, val name: String)

val LEAGUES = listOf(
    League("eng.1", "Premier League"),
    League("eng.2", "Championship"),
    League("uefa.champions", "Champions League"),
    League("uefa.europa", "Europa League"),
    League("uefa.europa.conf", "Conference League"),
    League("sco.1", "Scottish Premiership"),
    League("eng.fa", "FA Cup"),
    League("eng.league_cup", "EFL Cup"),
    League("eng.3", "League One"),
    League("eng.4", "League Two"),
    League("esp.1", "La Liga"),
    League("ita.1", "Serie A"),
    League("ger.1", "Bundesliga"),
    League("fra.1", "Ligue 1"),
)

enum class MatchState { UPCOMING, LIVE, FINISHED }

data class TeamScore(val name: String, val shortName: String, val logo: String, val score: String)

data class Match(
    val id: String,
    val league: League,
    val home: TeamScore,
    val away: TeamScore,
    val state: MatchState,
    /** "67'", "HT", "FT", or the local kick-off time. */
    val status: String,
    val kickoff: Long,
)

private fun JSONObject.s(key: String): String = if (isNull(key)) "" else optString(key, "")

private val isoFormats = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")

private fun parseIsoUtc(text: String): Long {
    for (pattern in isoFormats) {
        try {
            val f = SimpleDateFormat(pattern, Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.parse(text)?.time ?: continue
        } catch (e: Exception) {
            // try the next pattern
        }
    }
    return 0L
}

/** Live scores from ESPN's free scoreboard feed (no key needed). */
object ScoresClient {
    private suspend fun fetch(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            try {
                if (c.responseCode !in 200..299) return@withContext null
                val text = c.inputStream.bufferedReader().use { it.readText() }
                JSONTokener(text).nextValue() as? JSONObject
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun matches(league: League): List<Match> {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val root = fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/${league.code}/scoreboard?dates=$day")
            ?: return emptyList()
        val clockFormat = SimpleDateFormat("HH:mm", Locale.UK)
        return asArray(root.opt("events")).objects().mapNotNull { ev ->
            val comp = asArray(ev.opt("competitions")).objects().firstOrNull() ?: return@mapNotNull null
            val competitors = asArray(comp.opt("competitors")).objects()
            fun side(which: String): TeamScore? {
                val c = competitors.firstOrNull { it.s("homeAway") == which } ?: return null
                val team = c.optJSONObject("team") ?: JSONObject()
                val logo = team.s("logo").ifEmpty {
                    asArray(team.opt("logos")).objects().firstOrNull()?.s("href") ?: ""
                }
                return TeamScore(
                    name = team.s("displayName").ifEmpty { team.s("name") },
                    shortName = team.s("shortDisplayName").ifEmpty { team.s("abbreviation") }.ifEmpty { team.s("displayName") },
                    logo = logo,
                    score = c.s("score"),
                )
            }
            val home = side("home") ?: return@mapNotNull null
            val away = side("away") ?: return@mapNotNull null
            val status = comp.optJSONObject("status") ?: ev.optJSONObject("status") ?: JSONObject()
            val type = status.optJSONObject("type") ?: JSONObject()
            val state = when (type.s("state")) {
                "in" -> MatchState.LIVE
                "post" -> MatchState.FINISHED
                else -> MatchState.UPCOMING
            }
            val kickoff = parseIsoUtc(ev.s("date").ifEmpty { comp.s("date") })
            val shortDetail = type.s("shortDetail")
            val label = when (state) {
                MatchState.UPCOMING -> if (kickoff > 0) clockFormat.format(Date(kickoff)) else shortDetail
                MatchState.LIVE -> when {
                    type.s("description").contains("half", ignoreCase = true) && shortDetail.isNotBlank() -> shortDetail
                    status.s("displayClock").isNotBlank() -> status.s("displayClock")
                    else -> shortDetail.ifBlank { "Live" }
                }
                MatchState.FINISHED -> shortDetail.ifBlank { "FT" }
            }
            Match(ev.s("id"), league, home, away, state, label, kickoff)
        }
    }

    /** Today's matches across [leagues], live ones first. */
    suspend fun today(leagues: List<League>): List<Match> = coroutineScope {
        leagues.map { l -> async { matches(l) } }.awaitAll().flatten()
            .sortedWith(compareBy<Match>({ it.state != MatchState.LIVE }, { it.state == MatchState.FINISHED }, { it.kickoff }))
    }
}
