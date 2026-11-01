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

/** The UK competitions plus the big European leagues: what "All" covers in scores and Live Sport Now. */
val POPULAR_LEAGUES: List<League> = LEAGUES.take(8) + LEAGUES.filter { it.code in setOf("esp.1", "ita.1", "ger.1", "fra.1") }

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

enum class MatchEventKind { GOAL, PENALTY, OWN_GOAL, YELLOW, RED }

/** A goal or card: the minute ("67'"), what happened, who, and for which side. */
data class MatchEvent(val minute: String, val kind: MatchEventKind, val player: String, val home: Boolean)

/** One team stat, as shown: "Possession", "58%", "42%". */
data class MatchStat(val label: String, val home: String, val away: String)

data class MatchDetail(val events: List<MatchEvent>, val stats: List<MatchStat>)

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
        return parse(root, league)
    }

    /**
     * One club's own fixture list: its next games and its recent results. A small, reliable request,
     * so Your Teams always has the next game and the last result, whatever day it is.
     */
    suspend fun teamSchedule(league: League, teamId: String): List<Match> = coroutineScope {
        val base = "https://site.api.espn.com/apis/site/v2/sports/soccer/${league.code}/teams/$teamId/schedule"
        val next = async { fetch("$base?fixture=true")?.let { parse(it, league) }.orEmpty() }
        val past = async { fetch(base)?.let { parse(it, league) }.orEmpty() }
        (next.await() + past.await()).distinctBy { it.id }.sortedBy { it.kickoff }
    }

    /** [teams]' own fixture lists together; a team whose list can't be fetched is just left out. */
    suspend fun schedules(teams: List<Team>): List<Match> = coroutineScope {
        teams.map { t ->
            async {
                val l = LEAGUES.firstOrNull { it.code == t.league }
                if (l == null) emptyList() else try {
                    teamSchedule(l, t.id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    /** Day by day from [from] to [to] days from today (for when a date range comes back empty). */
    private suspend fun dayByDay(leagues: List<League>, from: Int, to: Int): List<Match> = coroutineScope {
        (from..to).flatMap { d -> leagues.map { l -> async { matches(l, day(d)) } } }.awaitAll().flatten().distinctBy { it.id }
    }

    private fun parse(root: JSONObject, league: League): List<Match> {
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

    /**
     * Goals, cards and team stats for one match, from ESPN's match summary. Null if it can't be read.
     * Reads both the summary's key events and the scoreboard-style details, whichever is there.
     */
    suspend fun detail(match: Match): MatchDetail? {
        val root = fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/${match.league.code}/summary?event=${match.id}") ?: return null
        fun isHome(teamId: String, teamName: String): Boolean? = when {
            teamId.isNotEmpty() && teamId == match.home.id -> true
            teamId.isNotEmpty() && teamId == match.away.id -> false
            teamName.isNotEmpty() && teamName == match.home.name -> true
            teamName.isNotEmpty() && teamName == match.away.name -> false
            else -> null
        }
        fun kindOf(text: String, scoring: Boolean, yellow: Boolean, red: Boolean): MatchEventKind? {
            val t = text.lowercase()
            return when {
                t.contains("own goal") -> MatchEventKind.OWN_GOAL
                t.contains("penalty") && (scoring || t.contains("goal") || t.contains("scored")) && !t.contains("miss") && !t.contains("saved") -> MatchEventKind.PENALTY
                red || t.contains("red card") || t.contains("red-card") || t.contains("second yellow") -> MatchEventKind.RED
                yellow || t.contains("yellow") -> MatchEventKind.YELLOW
                scoring || t == "goal" || t.startsWith("goal") -> MatchEventKind.GOAL
                else -> null
            }
        }
        val events = ArrayList<MatchEvent>()
        for (e in asArray(root.opt("keyEvents")).objects()) {
            val type = e.optJSONObject("type") ?: JSONObject()
            val kind = kindOf(type.s("text").ifEmpty { type.s("type") }, e.optBoolean("scoringPlay"), false, false) ?: continue
            val team = e.optJSONObject("team") ?: JSONObject()
            val home = isHome(team.s("id"), team.s("displayName")) ?: continue
            val player = asArray(e.opt("participants")).objects().firstOrNull()?.optJSONObject("athlete")?.s("displayName").orEmpty()
            val minute = e.optJSONObject("clock")?.s("displayValue").orEmpty()
            events += MatchEvent(minute, kind, player, home)
        }
        if (events.isEmpty()) {
            val comp = root.optJSONObject("header")?.let { asArray(it.opt("competitions")).objects().firstOrNull() }
            for (d in asArray(comp?.opt("details")).objects()) {
                val type = d.optJSONObject("type") ?: JSONObject()
                val kind = kindOf(type.s("text"), d.optBoolean("scoringPlay"), d.optBoolean("yellowCard"), d.optBoolean("redCard")) ?: continue
                val team = d.optJSONObject("team") ?: JSONObject()
                val home = isHome(team.s("id"), team.s("displayName")) ?: continue
                val player = asArray(d.opt("athletesInvolved")).objects().firstOrNull()?.s("displayName").orEmpty()
                val minute = d.optJSONObject("clock")?.s("displayValue").orEmpty()
                events += MatchEvent(minute, kind, player, home)
            }
        }
        // Team stats, in a sensible order, as "home" / "away" display values.
        val wanted = listOf(
            "possessionPct" to "Possession",
            "totalShots" to "Shots",
            "shotsOnTarget" to "On target",
            "wonCorners" to "Corners",
            "foulsCommitted" to "Fouls",
            "yellowCards" to "Yellow cards",
            "redCards" to "Red cards",
            "offsides" to "Offsides",
            "saves" to "Saves",
        )
        val box = asArray(root.optJSONObject("boxscore")?.opt("teams")).objects()
        val byTeam = HashMap<Boolean, Map<String, String>>()
        for ((i, t) in box.withIndex()) {
            val team = t.optJSONObject("team") ?: JSONObject()
            val home = when (t.s("homeAway")) {
                "home" -> true
                "away" -> false
                else -> isHome(team.s("id"), team.s("displayName")) ?: (i == 0)
            }
            byTeam[home] = asArray(t.opt("statistics")).objects().associate { it.s("name") to it.s("displayValue") }
        }
        val stats = wanted.mapNotNull { (key, label) ->
            val h = byTeam[true]?.get(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val a = byTeam[false]?.get(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (key == "possessionPct") MatchStat(label, h.substringBefore('.') + "%", a.substringBefore('.') + "%") else MatchStat(label, h, a)
        }
        return MatchDetail(events.sortedBy { minuteValue(it.minute) }, stats)
    }

    /** "45'+2'" → 45.02, for ordering. */
    private fun minuteValue(m: String): Double {
        val parts = Regex("\\d+").findAll(m).map { it.value.toDouble() }.toList()
        return when (parts.size) {
            0 -> 999.0
            1 -> parts[0]
            else -> parts[0] + parts[1] / 100.0
        }
    }

    /** Matches across [leagues] from [days] ago up to today, newest first. */
    suspend fun recent(leagues: List<League>, days: Int): List<Match> = coroutineScope {
        val range = day(-days) + "-" + day(0)
        val found = leagues.map { l -> async { matches(l, range) } }.awaitAll().flatten()
        // Some feeds don't answer date ranges: ask for each day instead.
        (found.ifEmpty { dayByDay(leagues, -days, 0) }).distinctBy { it.id }.sortedByDescending { it.kickoff }
    }

    /** Matches across [leagues] from today to [days] ahead, soonest first. */
    suspend fun upcoming(leagues: List<League>, days: Int): List<Match> = coroutineScope {
        val range = day(0) + "-" + day(days)
        val found = leagues.map { l -> async { matches(l, range) } }.awaitAll().flatten()
        // Some feeds don't answer date ranges: ask for each day instead (the next week or so).
        (found.ifEmpty { dayByDay(leagues, 0, minOf(days, 8)) }).distinctBy { it.id }.sortedBy { it.kickoff }
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
            // A cup tie can be listed under two competitions; show it once.
            .distinctBy { it.id }
            .sortedWith(compareBy<Match>({ it.state != MatchState.LIVE }, { it.state == MatchState.FINISHED }, { it.kickoff }))
    }
}
