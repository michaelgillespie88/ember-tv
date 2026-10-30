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

/** The leagues (not cups) teams can be followed from. */
val TEAM_LEAGUES = LEAGUES.filter { it.code in setOf("eng.1", "eng.2", "eng.3", "eng.4", "sco.1", "esp.1", "ita.1", "ger.1", "fra.1") }

/** Cups a team from [leagueCode] may also play in. */
fun cupsFor(leagueCode: String): List<League> {
    val codes = mutableListOf("uefa.champions", "uefa.europa", "uefa.europa.conf")
    if (leagueCode.startsWith("eng.")) codes += listOf("eng.fa", "eng.league_cup")
    return LEAGUES.filter { it.code in codes }
}

enum class MatchState { UPCOMING, LIVE, FINISHED }

data class TeamScore(val name: String, val shortName: String, val logo: String, val score: String, val id: String = "")

/** A club someone follows. [league] is the code of its domestic league. */
data class Team(val id: String, val name: String, val shortName: String, val logo: String, val league: String)

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

    private fun day(offsetDays: Int): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() + offsetDays * 86_400_000L))

    /** Today's matches in [league]. */
    suspend fun matches(league: League): List<Match> = matches(league, day(0))

    /** Matches in [league] on [dates]: one day "20261030" or a range "20261030-20261120". */
    suspend fun matches(league: League, dates: String): List<Match> {
        val root = fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/${league.code}/scoreboard?dates=$dates&limit=300")
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
                val score = when (val raw = c.opt("score")) {
                    is JSONObject -> raw.s("displayValue").ifEmpty { raw.s("value") }
                    null, JSONObject.NULL -> ""
                    else -> raw.toString()
                }
                return TeamScore(
                    name = team.s("displayName").ifEmpty { team.s("name") },
                    shortName = team.s("shortDisplayName").ifEmpty { team.s("abbreviation") }.ifEmpty { team.s("displayName") },
                    logo = logo,
                    score = score,
                    id = team.s("id").ifEmpty { c.s("id") },
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

    /** Matches across [leagues] from today to [days] ahead, soonest first. */
    suspend fun upcoming(leagues: List<League>, days: Int): List<Match> = coroutineScope {
        val range = day(0) + "-" + day(days)
        leagues.map { l -> async { matches(l, range) } }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.kickoff }
    }

    private val teamCache = HashMap<String, List<Team>>()

    /** Every club in [league], A to Z. */
    suspend fun teams(league: League): List<Team> {
        teamCache[league.code]?.let { return it }
        val root = fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/${league.code}/teams") ?: return emptyList()
        // Teams sit at sports[].leagues[].teams[].team; look for them anywhere in case the shape shifts.
        val found = LinkedHashMap<String, Team>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    val t = v.optJSONObject("team")
                    if (t != null && t.s("id").isNotEmpty() && t.s("displayName").isNotEmpty()) {
                        val logo = t.s("logo").ifEmpty { asArray(t.opt("logos")).objects().firstOrNull()?.s("href") ?: "" }
                        found.getOrPut(t.s("id")) {
                            Team(t.s("id"), t.s("displayName"), t.s("shortDisplayName").ifEmpty { t.s("displayName") }, logo, league.code)
                        }
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(root)
        return found.values.sortedBy { it.name }.also { if (it.isNotEmpty()) teamCache[league.code] = it }
    }

    /** Today's matches across [leagues], live ones first. */
    suspend fun today(leagues: List<League>): List<Match> = coroutineScope {
        leagues.map { l -> async { matches(l) } }.awaitAll().flatten()
            .sortedWith(compareBy<Match>({ it.state != MatchState.LIVE }, { it.state == MatchState.FINISHED }, { it.kickoff }))
    }
}
