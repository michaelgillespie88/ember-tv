package app.embertv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A programme found by searching the guide. */
data class ProgrammeHit(val channel: Channel, val programme: Programme)

/**
 * Searches programme titles in the guide ("When's EastEnders on?"). Providers only hand out the
 * guide one channel at a time, so this reads one copy of each channel (favourites and recently
 * watched first), a few at once, and reports what it has found as it goes. Guides already read
 * are cached, so later searches are quick.
 */
object ProgrammeSearch {
    private const val MAX_CHANNELS = 220

    suspend fun search(
        repo: Repository,
        query: String,
        now: Long,
        first: List<Int>,
        onProgress: (checked: Int, total: Int, hits: List<ProgrammeHit>) -> Unit,
    ): List<ProgrammeHit> {
        val q = MatchFinder.normalize(query)
        if (q.length < 3) return emptyList()
        val channels = try {
            repo.allChannels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val rank = first.withIndex().associate { (i, id) -> id to i }
        // One copy of each channel: the guide is the same for HD, FHD and backups.
        val toCheck = channels
            .filter { it.epgId.isNotEmpty() || it.streamId in rank }
            .groupBy { it.epgId.ifEmpty { MatchFinder.baseName(it.name) } }
            .values
            .map { copies -> copies.minWith(compareBy({ rank[it.streamId] ?: Int.MAX_VALUE }, { MatchFinder.qualityRank(it.name) })) }
            .sortedWith(compareBy({ rank[it.streamId] ?: Int.MAX_VALUE }, { if (it.num > 0) it.num else Int.MAX_VALUE }, { it.name }))
            .take(MAX_CHANNELS)

        val from = now - 7 * 86_400L
        val to = now + 8 * 86_400L
        val found = ArrayList<ProgrammeHit>()
        val lock = Any()
        var checked = 0
        val gate = Semaphore(8)
        coroutineScope {
            toCheck.map { c ->
                async {
                    gate.withPermit {
                        val guide = try {
                            repo.fullEpg(c.streamId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }
                        val matches = guide.filter { p ->
                            p.end > from && p.start < to && MatchFinder.normalize(p.title).contains(q)
                        }
                        val snapshot = synchronized(lock) {
                            checked++
                            matches.mapTo(found) { ProgrammeHit(c, it) }
                            sort(found, now)
                        }
                        onProgress(checked, toCheck.size, snapshot)
                    }
                }
            }.awaitAll()
        }
        return synchronized(lock) { sort(found, now) }
    }

    /** On now first, then what's coming soonest, then the most recent on catch-up. */
    private fun sort(hits: List<ProgrammeHit>, now: Long): List<ProgrammeHit> =
        hits.distinctBy { "${it.channel.epgId.ifEmpty { it.channel.streamId.toString() }}:${it.programme.start}" }
            .sortedWith(
                compareBy<ProgrammeHit>(
                    {
                        when {
                            it.programme.isLive(now) -> 0
                            it.programme.start > now -> 1
                            else -> 2
                        }
                    },
                    { if (it.programme.start > now) it.programme.start else -it.programme.start },
                )
            )
            .take(60)
}
