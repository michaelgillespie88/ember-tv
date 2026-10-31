package app.embertv.data

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

    // Built once: making these afresh for every channel name froze the app when there was football on.
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^a-z0-9+]+")
    private val TAGS = Regex("\\b(uk|hd|fhd|uhd|sd|4k|hevc|h265|backup|raw|vip|50fps|60fps)\\b")
    private val SPACES = Regex("\\s+")
    private val Q_UHD = Regex("\\b(4k|uhd)\\b")
    private val Q_FHD = Regex("\\bfhd\\b")
    private val Q_HD = Regex("\\bhd\\b")

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace("&", " and ")
            .replace(NON_WORD, " ")
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

    private fun patterns(aliases: List<String>): List<Regex> = aliases.map { a -> Regex("\\b" + Regex.escape(a) + "\\b") }

    private fun mentions(text: String, patterns: List<Regex>): Boolean = patterns.any { it.containsMatchIn(text) }

    /** Groups copies of the same channel (HD, FHD, backup…) so the guide is read once. */
    internal fun baseName(name: String): String =
        normalize(name).replace(TAGS, " ").replace(SPACES, " ").trim()

    internal fun qualityRank(name: String): Int {
        val n = normalize(name)
        return when {
            Q_UHD.containsMatchIn(n) -> 0
            Q_FHD.containsMatchIn(n) -> 1
            Q_HD.containsMatchIn(n) -> 2
            else -> 3
        }
    }

    // Channel names tidied for matching, kept between searches (the channel list rarely changes).
    @Volatile private var namesFor: List<Channel>? = null
    @Volatile private var namesCache: Map<Int, String> = emptyMap()

    private suspend fun normalizedNames(channels: List<Channel>): Map<Int, String> {
        val cached = namesFor
        if (cached != null && cached.size == channels.size && cached.firstOrNull() == channels.firstOrNull() &&
            cached.lastOrNull() == channels.lastOrNull()
        ) {
            return namesCache
        }
        val map = withContext(Dispatchers.Default) { channels.associate { it.streamId to normalize(it.name) } }
        namesCache = map
        namesFor = channels
        return map
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
        val home = patterns(aliases(match.home))
        val away = patterns(aliases(match.away))
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

        // The heavy text work happens off the main thread, so the screen never freezes.
        val names = normalizedNames(channels)
        val (nameHits, candidates, toCheck) = withContext(Dispatchers.Default) {
            // 1. Channel names: event channels like "EPL 03: Arsenal v Chelsea 17:30".
            val found = ArrayList<Pair<Channel, Int>>()
            for (c in channels) {
                val n = names[c.streamId] ?: continue
                val h = mentions(n, home)
                val a = mentions(n, away)
                when {
                    h && a -> found += c to 10
                    (h || a) && c.categoryId in sportCats -> found += c to 3
                }
            }
            // 2. The guide on sports channels, broadcasters first, one copy of each.
            val broadcasters = channels.filter { c -> names[c.streamId]?.let { n -> BROADCASTERS.any { n.contains(it) } } == true }
            val inSportCats = channels.filter { it.categoryId in sportCats }
            val groups = (broadcasters + inSportCats)
                .distinctBy { it.streamId }
                .groupBy { it.epgId.ifEmpty { baseName(it.name) } }
            Triple(found, groups, groups.values.map { copies -> copies.minBy { qualityRank(it.name) } }.take(90))
        }
        for ((c, score) in nameHits) add(c, null, score)
        onProgress(0, 0, sorted())

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
                    val scored = withContext(Dispatchers.Default) {
                        guide.mapNotNull { p ->
                            if (p.end <= from || p.start >= to) return@mapNotNull null
                            val text = normalize(p.title + " " + p.description)
                            val h = mentions(text, home)
                            val a = mentions(text, away)
                            when {
                                h && a -> p to 8
                                h || a -> p to 4
                                else -> null
                            }
                        }
                    }
                    for ((p, score) in scored) {
                        // Every copy of this channel is showing it too.
                        for (copy in candidates[key].orEmpty()) add(copy, p, score)
                    }
                    checked++
                    onProgress(checked, toCheck.size, sorted())
                }
            }.forEach { it.await() }
        }
        return sorted()
    }
}
