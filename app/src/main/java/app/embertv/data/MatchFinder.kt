package app.embertv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer

/** A channel that looks like it's showing the match, with how sure we are ([score], higher is better). */
data class MatchHit(val channel: Channel, val programme: Programme?, val score: Int)

/**
 * Finds which of the provider's channels is showing a football match: first by channel names
 * (providers often name event channels after the match), then by the guide on sports channels.
 */
object MatchFinder {
    private val NICKNAMES = mapOf(
        "manchester united" to listOf("man utd", "man united", "manchester utd", "man u"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("tottenham", "spurs"),
        "wolverhampton wanderers" to listOf("wolves"),
        "brighton and hove albion" to listOf("brighton"),
        "nottingham forest" to listOf("nottm forest", "nottingham", "forest"),
        "west ham united" to listOf("west ham"),
        "newcastle united" to listOf("newcastle"),
        "leeds united" to listOf("leeds"),
        "sheffield united" to listOf("sheff utd", "sheffield utd", "sheffield united"),
        "sheffield wednesday" to listOf("sheff wed", "sheffield wed"),
        "west bromwich albion" to listOf("west brom", "wba"),
        "queens park rangers" to listOf("qpr"),
        "afc bournemouth" to listOf("bournemouth"),
        "crystal palace" to listOf("palace"),
        "aston villa" to listOf("villa"),
        "leicester city" to listOf("leicester"),
        "ipswich town" to listOf("ipswich"),
        "paris saint germain" to listOf("psg", "paris sg", "paris"),
        "bayern munich" to listOf("bayern", "fc bayern"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "internazionale" to listOf("inter", "inter milan"),
        "ac milan" to listOf("milan"),
        "atletico madrid" to listOf("atletico", "atleti"),
        "heart of midlothian" to listOf("hearts"),
        "hibernian" to listOf("hibs"),
    )

    // First words too common to identify a club by themselves.
    private val COMMON = setOf(
        "manchester", "west", "sheffield", "bristol", "birmingham", "nottingham", "real", "athletic", "sporting",
        "club", "united", "city", "town", "county", "rovers", "wanderers", "albion", "queens", "afc", "fc", "the",
        "st", "saint", "inter", "royal", "racing", "borussia", "dynamo",
    )

    private val SPORT_CATEGORY = Regex("sport|football|soccer|\\bepl\\b|ppv|event|premier|league|\\bmatch|\\bdazn|\\bbein|\\bespn")
    private val BROADCASTERS = listOf(
        "sky sports", "sky sp", "tnt sport", "bt sport", "premier sports", "amazon prime", "prime video", "dazn", "viaplay",
        "bein", "espn", "eurosport", "laliga", "canal+", "supersport", "bbc one", "bbc 1", "bbc two", "bbc 2", "itv1", "itv 1",
        "itv4", "itv 4", "channel 4", "s4c", "stv", "bbc scotland", "main event", "premier league", "football",
    )

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9+]+"), " ")
            .trim()

    /** Names a team might go by in a channel name or guide listing. */
    fun aliases(team: TeamScore): List<String> {
        val full = normalize(team.name)
        val out = linkedSetOf(full, normalize(team.shortName))
        out += full.removePrefix("afc ").removePrefix("fc ").removeSuffix(" fc").removeSuffix(" afc")
        NICKNAMES[full]?.let { out += it }
        val first = full.split(' ').firstOrNull { it !in COMMON && it.length >= 4 }
        if (first != null && full.split(' ').size > 1) out += first
        return out.filter { it.length >= 3 }
    }

    private fun mentions(text: String, aliases: List<String>): Boolean =
        aliases.any { a -> Regex("\\b" + Regex.escape(a) + "\\b").containsMatchIn(text) }

    /** Groups copies of the same channel (HD, FHD, backup…) so the guide is read once. */
    private fun baseName(name: String): String =
        normalize(name).replace(Regex("\\b(uk|hd|fhd|uhd|sd|4k|hevc|h265|backup|raw|vip|50fps|60fps)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun qualityRank(name: String): Int {
        val n = normalize(name)
        return when {
            Regex("\\b(4k|uhd)\\b").containsMatchIn(n) -> 0
            Regex("\\bfhd\\b").containsMatchIn(n) -> 1
            Regex("\\bhd\\b").containsMatchIn(n) -> 2
            else -> 3
        }
    }

    /**
     * Searches for [match]. [onProgress] reports results as the guide is checked (checked, total, hits).
     * Call from the main thread; network work happens inside the repository.
     */
    suspend fun find(
        repo: Repository,
        match: Match,
        now: Long,
        onProgress: (Int, Int, List<MatchHit>) -> Unit,
    ): List<MatchHit> {
        val home = aliases(match.home)
        val away = aliases(match.away)
        val channels = try {
            repo.allChannels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val sportCats = try {
            repo.liveCategories().filter { SPORT_CATEGORY.containsMatchIn(normalize(it.name)) }.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }

        val hits = HashMap<Int, MatchHit>()
        fun add(c: Channel, p: Programme?, score: Int) {
            val old = hits[c.streamId]
            if (old == null || old.score < score) hits[c.streamId] = MatchHit(c, p, score)
        }
        fun sorted(): List<MatchHit> =
            hits.values.sortedWith(compareBy<MatchHit>({ -it.score }, { qualityRank(it.channel.name) }, { it.channel.num }, { it.channel.name }))

        // 1. Channel names: event channels like "EPL 03: Arsenal v Chelsea 17:30".
        for (c in channels) {
            val n = normalize(c.name)
            val h = mentions(n, home)
            val a = mentions(n, away)
            when {
                h && a -> add(c, null, 10)
                (h || a) && c.categoryId in sportCats -> add(c, null, 3)
            }
        }
        onProgress(0, 0, sorted())

        // 2. The guide on sports channels, broadcasters first, one copy of each.
        val broadcasters = channels.filter { c -> normalize(c.name).let { n -> BROADCASTERS.any { n.contains(it) } } }
        val inSportCats = channels.filter { it.categoryId in sportCats }
        val candidates = (broadcasters + inSportCats)
            .distinctBy { it.streamId }
            .groupBy { it.epgId.ifEmpty { baseName(it.name) } }
        val toCheck = candidates.values.map { copies -> copies.minBy { qualityRank(it.name) } }.take(90)

        val live = match.state == MatchState.LIVE
        val from = if (live) now else match.kickoff / 1000 - 15 * 60
        val to = if (live) now + 60 else match.kickoff / 1000 + 105 * 60
        val soon = match.kickoff / 1000 - now < 18 * 3600

        var checked = 0
        coroutineScope {
            toCheck.map { c ->
                async {
                    val guide = try {
                        if (soon || live) repo.epg(c.streamId) else repo.fullEpg(c.streamId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val key = c.epgId.ifEmpty { baseName(c.name) }
                    for (p in guide) {
                        if (p.end <= from || p.start >= to) continue
                        val text = normalize(p.title + " " + p.description)
                        val h = mentions(text, home)
                        val a = mentions(text, away)
                        val score = when {
                            h && a -> 8
                            h || a -> 4
                            else -> 0
                        }
                        if (score > 0) {
                            // Every copy of this channel is showing it too.
                            for (copy in candidates[key].orEmpty()) add(copy, p, score)
                        }
                    }
                    checked++
                    onProgress(checked, toCheck.size, sorted())
                }
            }.forEach { it.await() }
        }
        return sorted()
    }
}
