package app.embertv.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embertv.data.Account
import app.embertv.data.AccountInfo
import app.embertv.data.Category
import app.embertv.data.CategoryRules
import app.embertv.data.Channel
import app.embertv.data.categoryRuleKey
import app.embertv.data.looksKidsFriendly
import app.embertv.data.pinHash
import app.embertv.data.ContentType
import app.embertv.data.DEFAULT_USER_AGENT
import app.embertv.data.Episode
import app.embertv.data.Quality
import app.embertv.data.Versions
import app.embertv.data.Favourite
import app.embertv.data.HistoryEntry
import app.embertv.data.MAIN_PROFILE
import app.embertv.data.Profile
import app.embertv.data.Programme
import app.embertv.data.Reminder
import app.embertv.data.Match
import app.embertv.data.MatchHit
import app.embertv.data.MatchState
import app.embertv.data.ScoresClient
import app.embertv.data.Team
import app.embertv.data.Tmdb
import app.embertv.CrashNote
import app.embertv.data.toChannel
import app.embertv.data.FoundTv
import app.embertv.data.TvFinder
import app.embertv.data.TvLinkClient
import app.embertv.data.TvLinkServer
import app.embertv.data.cupsFor
import app.embertv.data.nowSeconds
import app.embertv.data.UpdateInfo
import app.embertv.data.Updates
import app.embertv.data.Movie
import app.embertv.data.Prefs
import app.embertv.data.Repository
import app.embertv.data.Series
import app.embertv.data.XtreamClient
import app.embertv.data.favouriteKey
import app.embertv.data.normalizeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class MovieDetail(val movie: Movie) : Screen
    data class SeriesDetail(val series: Series) : Screen
    data class PlayLive(val channels: List<Channel>, val index: Int) : Screen
    data class PlayVod(val items: List<VodItem>, val index: Int, val startMs: Long) : Screen
    data class Catchup(val channel: Channel) : Screen
    /** Two or four channels at once; [pool] is what the empty pictures can be filled from. */
    data class MultiView(val channels: List<Channel>, val pool: List<Channel>) : Screen
}

const val CATCHUP_PREFIX = "catchup:"

/** A title with several qualities, waiting for one to be picked. */
sealed interface VersionChoice {
    val title: String
    val qualities: List<app.embertv.data.Quality>
    val names: List<String>

    data class ForMovie(override val title: String, val versions: List<Movie>, val startAt: Long) : VersionChoice {
        override val qualities get() = versions.map { it.quality }
        override val names get() = versions.map { it.name }
    }

    data class ForEpisode(
        override val title: String,
        val versions: List<Series>,
        val from: Series,
        val episodes: List<Episode>,
        val index: Int,
        val fromStart: Boolean,
    ) : VersionChoice {
        override val qualities get() = versions.map { it.quality }
        override val names get() = versions.map { it.name }
    }
}

/** Something happening in a followed team's match. [good] is true when it's our team who scored. */
data class TeamAlert(val kind: Kind, val match: app.embertv.data.Match, val good: Boolean?, val id: Long = System.nanoTime()) {
    enum class Kind { KICK_OFF, GOAL, FULL_TIME }
}

data class VodItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val seriesId: Int? = null,
    val episodeId: Int? = null,
    // For Continue Watching
    val contentId: Int = 0,
    val image: String = "",
    val seriesCover: String = "",
    val ext: String = "",
)

/** Tab numbers, shared by the TV and phone layouts. Home is first, so the app opens on it. */
object Tabs {
    const val HOME = 0
    const val LIVE = 1
    const val GUIDE = 2
    const val MOVIES = 3
    const val SERIES = 4
    const val FAVOURITES = 5
    const val SEARCH = 6
    const val SETTINGS = 7
}

data class Notice(val text: String, val id: Long = System.nanoTime())

/** UI state for one browsable section, kept here so it survives opening a player or detail page. */
class SectionState {
    var categoryId by mutableStateOf<String?>(null)
    val rail = LazyListState()
    private val lists = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()

    fun list(category: String): LazyListState = lists.getOrPut(category) { LazyListState() }
    fun grid(category: String): LazyGridState = grids.getOrPut(category) { LazyGridState() }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    var account by mutableStateOf<Account?>(null)
        private set
    var repo by mutableStateOf<Repository?>(null)
        private set
    var accountInfo by mutableStateOf<AccountInfo?>(null)
        private set
    var loggingIn by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set

    // Navigation: a simple stack, plus the item to re-focus when we come back.
    val stack = mutableStateListOf<Screen>(Screen.Home)
    private val returnKeys = ArrayList<String?>()
    var lastFocusKey: String? = null
    var restoreFocusKey by mutableStateOf<String?>(null)
    var restoreClaimed = false

    var tab by mutableIntStateOf(Tabs.HOME)
    var home = SectionState()
        private set
    var live = SectionState()
        private set
    var guide = SectionState()
        private set
    /** True while the TV guide's grid has the highlight, so the tab bar tucks away. */
    var guideFullScreen by mutableStateOf(false)
    var movies = SectionState()
        private set
    var series = SectionState()
        private set
    var searchQuery by mutableStateOf("")

    var reloadToken by mutableIntStateOf(0)
        private set
    var notice by mutableStateOf<Notice?>(null)
        private set

    var streamFormat by mutableStateOf(prefs.streamFormat)
        private set
    var userAgent by mutableStateOf(prefs.userAgent)
        private set
    var invertZap by mutableStateOf(prefs.invertZap)
        private set

    var matchFrameRate by mutableStateOf(prefs.matchFrameRate)
        private set

    // ---- Browsing on a TV ----

    var compactLists by mutableStateOf(prefs.compactLists)
        private set
    var livePreview by mutableStateOf(prefs.livePreview)
        private set

    fun toggleCompactLists() {
        compactLists = !compactLists
        prefs.compactLists = compactLists
    }

    fun toggleLivePreview() {
        livePreview = !livePreview
        prefs.livePreview = livePreview
    }

    // ---- Watched ----

    /** Films and episodes this profile has finished. */
    var watchedKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    fun isWatched(key: String): Boolean = key in watchedKeys

    /** Watched in any of its qualities. */
    fun movieWatched(m: Movie): Boolean = (versionsOf(m).map { it.streamId } + m.streamId).any { "movie:$it" in watchedKeys }

    private fun markWatched(key: String) {
        if (key in watchedKeys || key.startsWith(CATCHUP_PREFIX)) return
        var next = LinkedHashSet(watchedKeys).apply { add(key) }.toSet()
        if (next.size > 3000) next = next.drop(next.size - 3000).toSet()
        watchedKeys = next
        prefs.watched = next
    }

    /** Called when a film or episode plays to its end. */
    fun markFinished(item: VodItem) {
        if (item.key.startsWith(CATCHUP_PREFIX)) return
        markWatched(item.key)
        removeHistory(item.key, notifyUser = false)
        if (item.seriesId != null) queueNextEpisode(item)
    }

    /** Puts the episode after [item] at the front of Up Next on Home, like Apple TV. */
    private fun queueNextEpisode(item: VodItem) {
        val sid = item.seriesId ?: return
        val eid = item.episodeId ?: return
        val r = repo ?: return
        viewModelScope.launch {
            val info = r.peekSeriesInfo(sid) ?: try {
                r.seriesInfo(sid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            val all = info.episodes.values.flatten()
            val current = all.firstOrNull { it.id == eid } ?: return@launch
            val next = info.episodes[current.season].orEmpty().sortedBy { it.number }.firstOrNull { it.number > current.number }
                ?: info.seasons.filter { it > current.season }.sorted()
                    .firstNotNullOfOrNull { s -> info.episodes[s]?.minByOrNull { it.number } }
                ?: return@launch
            val key = "ep:${next.id}"
            if (key in watchedKeys) return@launch
            // Already further along in that one? Leave Up Next as it is.
            if (history.any { it.key == key && it.positionMs > 0 }) return@launch
            val entry = HistoryEntry(
                key = key,
                type = ContentType.SERIES,
                id = next.id,
                seriesId = sid,
                title = item.title,
                subtitle = "Season ${next.season} · Episode ${next.number}  ${next.title}",
                image = next.image.ifBlank { info.backdrop.ifBlank { item.seriesCover } },
                seriesCover = item.seriesCover,
                ext = next.ext,
                positionMs = 0L,
                durationMs = 0L,
                updatedAt = System.currentTimeMillis(),
            )
            history.removeAll { it.type == ContentType.SERIES && it.seriesId == sid }
            history.add(0, entry)
            while (history.size > 30) history.removeAt(history.lastIndex)
            prefs.history = history.toList()
        }
    }

    // ---- HD / FHD / 4K versions ----

    var mergeVersions by mutableStateOf(prefs.mergeVersions)
        private set
    /** "ask", "best", "FHD" or "HD". */
    var qualityChoice by mutableStateOf(prefs.qualityChoice)
        private set
    /** Waiting for someone to pick a quality. */
    var choosingVersion by mutableStateOf<VersionChoice?>(null)
    // Every version found for a title, by "m:<id>" or "s:<id>" of the one shown.
    private val foundVersions = androidx.compose.runtime.mutableStateMapOf<String, List<Any>>()

    fun toggleMergeVersions() {
        mergeVersions = !mergeVersions
        prefs.mergeVersions = mergeVersions
        repo?.mergeVersions = mergeVersions
        foundVersions.clear()
        resetSections()
        reloadToken++
    }

    fun cycleQualityChoice() {
        val steps = listOf("ask", "best", "FHD", "HD")
        qualityChoice = steps[(steps.indexOf(qualityChoice) + 1) % steps.size]
        prefs.qualityChoice = qualityChoice
    }

    @Suppress("UNCHECKED_CAST")
    fun versionsOf(m: Movie): List<Movie> =
        (foundVersions["m:${m.streamId}"] as? List<Movie>)?.takeIf { it.size >= m.versions.size } ?: m.versions

    @Suppress("UNCHECKED_CAST")
    fun versionsOf(s: Series): List<Series> =
        (foundVersions["s:${s.seriesId}"] as? List<Series>)?.takeIf { it.size >= s.versions.size } ?: s.versions

    /** Looks for other qualities of a title (detail screens call this when they open). */
    /** Films whose other qualities have been looked for already. */
    private val checkedMovies = HashSet<Int>()

    suspend fun loadVersions(m: Movie) {
        val r = repo ?: return
        if (!mergeVersions) return
        val found = try {
            r.movieVersions(m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } catch (e: OutOfMemoryError) {
            return
        }
        checkedMovies += m.streamId
        if (found.size > 1) foundVersions["m:${m.streamId}"] = found
    }

    suspend fun loadVersions(s: Series) {
        val r = repo ?: return
        if (!mergeVersions) return
        val found = try {
            r.seriesVersions(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        } catch (e: OutOfMemoryError) {
            return
        }
        if (found.size > 1) foundVersions["s:${s.seriesId}"] = found
    }

    // ---- Changing quality while watching ----

    /** A quality the film or show playing also comes in. [versionId] is the film's or show's id. */
    class QualityChoice(val quality: Quality, val label: String, val versionId: Int, val ext: String)

    @Suppress("UNCHECKED_CAST")
    private fun knownMovieVersions(id: Int): List<Movie> {
        foundVersions.values.firstOrNull { l -> l.any { (it as? Movie)?.streamId == id } }?.let { return it.filterIsInstance<Movie>() }
        val m = repo?.loadedMovies()?.firstOrNull { it.streamId == id || it.versions.any { v -> v.streamId == id } } ?: return emptyList()
        return versionsOf(m).ifEmpty { listOf(m) }
    }

    private fun knownSeriesVersions(id: Int): List<Series> {
        foundVersions.values.firstOrNull { l -> l.any { (it as? Series)?.seriesId == id } }?.let { return it.filterIsInstance<Series>() }
        val s = repo?.loadedSeries()?.firstOrNull { it.seriesId == id || it.versions.any { v -> v.seriesId == id } } ?: return emptyList()
        return versionsOf(s).ifEmpty { listOf(s) }
    }

    /** The other qualities of what's playing, best first; empty when there's only one. */
    suspend fun qualityChoicesFor(item: VodItem): List<QualityChoice> {
        if (!mergeVersions || item.key.startsWith(CATCHUP_PREFIX)) return emptyList()
        val sid = item.seriesId
        if (sid == null) {
            val id = item.contentId.takeIf { it > 0 } ?: return emptyList()
            var list = knownMovieVersions(id)
            if (list.size <= 1) {
                val m = repo?.loadedMovies()?.firstOrNull { it.streamId == id } ?: return emptyList()
                kotlinx.coroutines.withTimeoutOrNull(VERSION_CHECK_MS) { loadVersions(m) }
                list = knownMovieVersions(id)
            }
            if (list.size <= 1) return emptyList()
            return list.map { QualityChoice(it.quality, (listOf(it.quality.title) + Versions.extras(it.name)).joinToString(" · "), it.streamId, it.ext) }
        }
        var list = knownSeriesVersions(sid)
        if (list.size <= 1) {
            val s = repo?.loadedSeries()?.firstOrNull { it.seriesId == sid } ?: return emptyList()
            kotlinx.coroutines.withTimeoutOrNull(VERSION_CHECK_MS) { loadVersions(s) }
            list = knownSeriesVersions(sid)
        }
        if (list.size <= 1) return emptyList()
        return list.map { QualityChoice(it.quality, (listOf(it.quality.title) + Versions.extras(it.name)).joinToString(" · "), it.seriesId, "") }
    }

    /**
     * Where each item from [from] onwards plays in quality [c]: the film's other copy, or for a show
     * the same season and episode in the other copy. Null if it can't be worked out.
     */
    suspend fun urlsFor(c: QualityChoice, items: List<VodItem>, from: Int): Map<Int, String>? {
        val r = repo ?: return null
        val item = items.getOrNull(from) ?: return null
        val sid = item.seriesId ?: return mapOf(from to r.client.movieUrl(c.versionId, c.ext.ifBlank { item.ext }))
        return try {
            val here = r.peekSeriesInfo(sid) ?: r.seriesInfo(sid)
            val there = r.peekSeriesInfo(c.versionId) ?: r.seriesInfo(c.versionId)
            val herePlaces = here.episodes.values.flatten().associate { it.id to (it.season to it.number) }
            val thereEps = there.episodes.values.flatten().associateBy { it.season to it.number }
            val out = HashMap<Int, String>()
            for (i in from until items.size) {
                val place = herePlaces[items[i].episodeId ?: continue] ?: continue
                val ep = thereEps[place] ?: continue
                out[i] = r.client.episodeUrl(ep.id, ep.ext)
            }
            out.takeIf { from in it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Where to resume a film: the furthest point reached in any of its versions. */
    fun moviePosition(m: Movie): Long =
        (versionsOf(m).map { it.streamId } + m.streamId).distinct().maxOf { prefs.position("movie:$it") }

    /** The version to play without asking, or null to ask. [versions] are best first. */
    private fun autoVersion(qualities: List<Quality>): Int? = when (qualityChoice) {
        "best" -> 0
        "FHD", "HD" -> {
            val want = if (qualityChoice == "FHD") Quality.FHD.rank else Quality.HD.rank
            qualities.indexOfFirst { it.rank == want }.takeIf { it >= 0 }
                ?: qualities.indexOfFirst { it.rank in 1 until want }.takeIf { it >= 0 }
                ?: 0
        }
        else -> null
    }

    // ---- Other video players (VLC, MX Player…) ----

    /** Package of the app that plays everything, or "" for Streammania's own player. */
    var externalPlayer by mutableStateOf(prefs.externalPlayer)
        private set
    /** Something to open in another player once one is picked. */
    var pickingOtherPlayer by mutableStateOf<Screen?>(null)

    fun chooseExternalPlayer(pkg: String) {
        externalPlayer = pkg
        prefs.externalPlayer = pkg
        notify(if (pkg.isEmpty()) "Playing in Streammania" else "Playing in ${externalPlayerName()}")
    }

    fun externalPlayerName(): String {
        if (externalPlayer.isEmpty()) return "Streammania"
        return app.embertv.OtherPlayers.installed(getApplication()).firstOrNull { it.packageName == externalPlayer }?.label
            ?: "Streammania (other player not found)"
    }

    /** From inside a player: plays [screen] in the chosen app, or asks which one. */
    fun openElsewhere(screen: Screen) {
        val pkg = externalPlayer
        if (pkg.isNotEmpty() && app.embertv.OtherPlayers.isInstalled(getApplication(), pkg)) launchElsewhere(screen, pkg) else pickingOtherPlayer = screen
    }

    fun launchElsewhere(screen: Screen, pkg: String) {
        pickingOtherPlayer = null
        // Close Streammania's player first so the provider connection is free for the other app.
        val top = stack.last()
        val closing = top is Screen.PlayLive || top is Screen.PlayVod
        if (closing) pop()
        viewModelScope.launch {
            if (closing) kotlinx.coroutines.delay(500)
            if (!openExternally(screen, pkg)) notify("Couldn't open that player")
        }
    }

    /** Hands a channel or video to another app. Returns false if it couldn't be opened. */
    private fun openExternally(screen: Screen, pkg: String): Boolean {
        val ctx = getApplication<Application>()
        return when (screen) {
            is Screen.PlayLive -> {
                val c = screen.channels.getOrNull(screen.index) ?: return false
                val ok = app.embertv.OtherPlayers.open(ctx, pkg, liveUrl(c, false), c.name, 0L)
                if (ok) recordChannel(c)
                ok
            }
            is Screen.PlayVod -> {
                val item = screen.items.getOrNull(screen.index) ?: return false
                val title = listOf(item.title, item.subtitle.substringBefore("  ")).filter { it.isNotBlank() }.joinToString(" · ")
                app.embertv.OtherPlayers.open(ctx, pkg, item.url, title, screen.startMs)
            }
            else -> false
        }
    }

    fun toggleMatchFrameRate() {
        matchFrameRate = !matchFrameRate
        prefs.matchFrameRate = matchFrameRate
    }

    // Picture-in-picture (phones) and screensaver (TVs)
    var inPip by mutableStateOf(false)
    var screensaverMinutes by mutableIntStateOf(prefs.screensaverMinutes)
        private set
    var screensaver by mutableStateOf(false)
        private set
    private var lastActivity = android.os.SystemClock.uptimeMillis()

    /** True while a video or channel is playing. */
    val playerOpen: Boolean get() = stack.last().let { it is Screen.PlayLive || it is Screen.PlayVod || it is Screen.MultiView }

    val favourites = mutableStateListOf<Favourite>()
    val favKeys: Set<String> by derivedStateOf<Set<String>> { favourites.mapTo(HashSet()) { it.key } }

    /** Part-watched movies and episodes, most recent first. */
    val history = mutableStateListOf<HistoryEntry>()

    /** Channels watched recently on this profile, newest first. */
    val recentChannels = mutableStateListOf<Favourite>()

    /** Football clubs this profile follows. */
    val followedTeams = mutableStateListOf<Team>()
    /** Live, today's and upcoming matches for [followedTeams], most urgent first. */
    var teamMatches by mutableStateOf<List<Match>>(emptyList())
        private set
    /** A goal, kick-off or full time to announce. */
    var teamAlert by mutableStateOf<TeamAlert?>(null)
        private set
    /** The match "Where's it on?" is searching for. */
    var findingMatch by mutableStateOf<Match?>(null)
    private var teamWatcher: kotlinx.coroutines.Job? = null

    /** Programmes this profile wants a nudge about, soonest first. */
    val reminders = mutableStateListOf<Reminder>()
    /** A reminder whose programme is starting, shown as a pop-up. */
    var dueReminder by mutableStateOf<Reminder?>(null)
        private set

    var audioLanguage by mutableStateOf("")
        private set
    var subtitleMode by mutableStateOf("off")
        private set

    /** When the sleep timer stops playback (epoch ms), or null when it's off. */
    var sleepAt by mutableStateOf<Long?>(null)
        private set

    // Updates
    var update by mutableStateOf<UpdateInfo?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    private var checkedForUpdates = false

    // ---- Profiles ----

    val profiles = mutableStateListOf<Profile>()
    var profile by mutableStateOf<Profile?>(null)
        private set
    /** True while the "Who's watching?" screen is showing. */
    var pickingProfile by mutableStateOf(false)
        private set
    /** False on the screen shown at start-up, where Back leaves the app. */
    var pickerCancelable by mutableStateOf(false)
        private set

    // ---- Session ----

    private fun startSession(acc: Account, info: AccountInfo?) {
        account = acc
        repo = Repository(XtreamClient(acc, userAgent)).also { it.mergeVersions = mergeVersions }
        vodCheckedAt = System.currentTimeMillis()
        applyCategoryFilter()
        // Start on Home's rows straight away, whichever page is shown first.
        ensureHome()
        accountInfo = info
        if (info == null) refreshAccountInfo()
        if (!checkedForUpdates) {
            checkedForUpdates = true
            checkForUpdates(manual = false)
        }
    }

    fun refreshAccountInfo() {
        val r = repo ?: return
        viewModelScope.launch {
            try {
                accountInfo = r.client.authenticate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep using the saved login; Settings shows the account as unknown.
            }
        }
    }

    fun login(server: String, username: String, password: String) {
        if (loggingIn) return
        if (server.isBlank() || username.isBlank()) {
            loginError = "Enter the server address and username."
            return
        }
        loginError = null
        loggingIn = true
        viewModelScope.launch {
            try {
                val acc = Account(normalizeServer(server), username.trim(), password.trim())
                val info = XtreamClient(acc, userAgent).authenticate()
                prefs.account = acc
                startSession(acc, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError = e.message ?: "Sign-in failed."
            } finally {
                loggingIn = false
            }
        }
    }

    // ---- Sign in from a phone ----

    /** The address a phone can open to type the login, while the TV sign-in screen is up. */
    var phoneLoginUrl by mutableStateOf<String?>(null)
        private set
    /** The server and username a phone last sent, so the TV's boxes can show them. */
    var phoneLoginFill by mutableStateOf<Triple<String, String, String>?>(null)
        private set
    private var phoneLogin: app.embertv.data.PhoneLoginServer? = null

    fun startPhoneLogin() {
        if (phoneLogin != null) return
        val server = app.embertv.data.PhoneLoginServer { srv, user, pass -> signInFromPhone(srv, user, pass) }
        phoneLogin = server
        viewModelScope.launch {
            server.run { url -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (phoneLogin === server) phoneLoginUrl = url } }
        }
    }

    fun stopPhoneLogin() {
        phoneLogin?.stop()
        phoneLogin = null
        phoneLoginUrl = null
    }

    /** Runs the sign-in on the TV and waits for the answer, so the phone can say how it went. */
    private suspend fun signInFromPhone(server: String, user: String, pass: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (repo != null) return@withContext null
            phoneLoginFill = Triple(server, user, pass)
            login(server, user, pass)
            var waited = 0
            while ((loggingIn || loginError == null && repo == null) && waited < 30_000) {
                kotlinx.coroutines.delay(200)
                waited += 200
            }
            when {
                repo != null -> null
                else -> loginError ?: "The TV couldn't sign in. Check the details and try again."
            }
        }

    fun signOut() {
        prefs.account = null
        account = null
        repo = null
        accountInfo = null
        resetBrowsing()
    }

    private fun resetBrowsing() {
        stack.clear()
        stack.add(Screen.Home)
        returnKeys.clear()
        restoreFocusKey = null
        resetSections()
        searchQuery = ""
        tab = Tabs.HOME
    }

    private fun resetSections() {
        home = SectionState()
        live = SectionState()
        guide = SectionState()
        movies = SectionState()
        series = SectionState()
    }

    // ---- Profiles ----

    private fun activate(p: Profile) {
        prefs.profileId = p.id
        prefs.lastProfile = p.id
        profile = p
        favourites.clear()
        favourites.addAll(prefs.favourites)
        history.clear()
        history.addAll(prefs.history)
        recentChannels.clear()
        recentChannels.addAll(prefs.recentChannels)
        reminders.clear()
        reminders.addAll(prefs.reminders.filter { it.end > nowSeconds() }.sortedBy { it.start })
        dueReminder = null
        watchedKeys = prefs.watched
        followedTeams.clear()
        followedTeams.addAll(prefs.followedTeams)
        sportLeagues = prefs.sportLeagues
        sportOthers = prefs.sportOthers
        startTeamWatcher()
        audioLanguage = prefs.audioLanguage
        subtitleMode = prefs.subtitleMode
        seriesSeen = prefs.seriesSeen
        lastWatched = prefs.lastWatched
        homeOrder = prefs.homeOrder
        homeHidden = prefs.homeHidden
        Ember.backdrop = BACKDROPS.firstOrNull { it.id == prefs.backdrop } ?: BACKDROPS[0]
        Txt.scale = prefs.textScale.coerceIn(1f, 1.25f)
        HintsSetting.show = prefs.showHints
        autoplayNext = prefs.autoplayNext
        homeExtras = null
        applyCategoryFilter()
        reloadToken++
    }

    fun chooseProfile(p: Profile) {
        if (profile?.id != p.id) {
            activate(p)
            resetBrowsing()
        }
        pickingProfile = false
    }

    fun switchProfile() {
        pickerCancelable = true
        pickingProfile = true
    }

    fun closeProfilePicker() {
        if (pickerCancelable) pickingProfile = false
    }

    /** [pin]: four digits to lock the profile, or null for no PIN. */
    fun addProfile(name: String, color: Int, kids: Boolean = false, pin: String? = null): Profile {
        val id = "p" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val p = Profile(
            id = id,
            name = name.trim().ifEmpty { "Profile ${profiles.size + 1}" },
            color = color,
            kids = kids,
            pinHash = if (pin.isNullOrEmpty()) "" else pinHash(id, pin),
        )
        profiles.add(p)
        prefs.profiles = profiles.toList()
        if (kids) seedKidsCategories(p) else notify("Added ${p.name}")
        return p
    }

    /** [pin]: null keeps the current PIN, "" removes it, four digits sets a new one. */
    fun updateProfile(p: Profile, pin: String? = null) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i < 0) return
        val old = profiles[i]
        var fixed = p.copy(name = p.name.trim().ifEmpty { old.name })
        if (pin != null) fixed = fixed.copy(pinHash = if (pin.isEmpty()) "" else pinHash(fixed.id, pin))
        profiles[i] = fixed
        prefs.profiles = profiles.toList()
        if (profile?.id == fixed.id) profile = fixed
        if (old.kids != fixed.kids) {
            if (fixed.kids) seedKidsCategories(fixed) else setCategoryRules(fixed, CategoryRules(false, emptySet()))
        }
    }

    // ---- PINs ----

    fun checkPin(p: Profile, pin: String): Boolean = p.locked && p.pinHash == pinHash(p.id, pin)

    /** True when at least one grown-up profile is locked, so kids can't get into it or edit profiles. */
    val parentPinSet: Boolean get() = profiles.any { !it.kids && it.locked }

    /** The grown-up profile whose PIN this is, if any. */
    fun parentForPin(pin: String): Profile? = profiles.firstOrNull { !it.kids && checkPin(it, pin) }

    // ---- Categories ----

    /** The categories [p] sees. Rules saved for the other kind of profile don't apply. */
    fun categoryRules(p: Profile): CategoryRules {
        val saved = prefs.categoryRules(p.id)
        return if (saved.allowMode == p.kids) saved else CategoryRules(p.kids, emptySet())
    }

    fun setCategoryRules(p: Profile, rules: CategoryRules) {
        prefs.setCategoryRules(p.id, rules)
        if (profile?.id == p.id) {
            applyCategoryFilter()
            resetSections()
            reloadToken++
        }
    }

    private fun applyCategoryFilter() {
        val r = repo ?: return
        val p = profile ?: return
        hiddenChannels = prefs.hiddenChannels
        r.hiddenChannels = hiddenChannels
        val rules = categoryRules(p)
        r.filter = if (!rules.allowMode && rules.keys.isEmpty()) {
            { _, _ -> true }
        } else {
            { type, id -> rules.visible(type, id) }
        }
    }

    /** Starts a kids profile off with the categories whose names sound child-friendly. */
    private fun seedKidsCategories(p: Profile) {
        val r = repo
        if (r == null) {
            setCategoryRules(p, CategoryRules(true, emptySet()))
            return
        }
        viewModelScope.launch {
            val keys = HashSet<String>()
            suspend fun add(type: ContentType, load: suspend () -> List<Category>) {
                try {
                    for (c in load()) if (looksKidsFriendly(c.name)) keys.add(categoryRuleKey(type, c.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Leave that type empty; it can be filled in by hand.
                }
            }
            add(ContentType.LIVE) { r.rawLiveCategories() }
            add(ContentType.MOVIE) { r.rawMovieCategories() }
            add(ContentType.SERIES) { r.rawSeriesCategories() }
            setCategoryRules(p, CategoryRules(true, keys))
            notify(
                if (keys.isEmpty()) "${p.name} is a kids profile. Choose their categories in Edit profiles."
                else "${p.name} can see ${keys.size} kids categories. Change them in Edit profiles."
            )
        }
    }

    /** Removes a profile and everything saved for it. The last profile can't be deleted. */
    fun deleteProfile(p: Profile) {
        if (profiles.size <= 1) return
        prefs.deleteProfileData(p.id)
        profiles.removeAll { it.id == p.id }
        prefs.profiles = profiles.toList()
        if (profile?.id == p.id) {
            activate(profiles.first())
            resetBrowsing()
        }
        notify("Deleted ${p.name}")
    }

    fun reload() {
        repo?.clearCaches()
        vodCheckedAt = System.currentTimeMillis()
        reloadToken++
    }

    // ---- Play on TV (the TV side) ----

    /** A phone asking to pair, with the code shown on the TV. */
    var tvPairing by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** Remote buttons from a phone are pressed through this (set by the activity). */
    var remoteKeys: ((String) -> Unit)? = null
    private var tvLink: TvLinkServer? = null
    private var tvLinkJob: kotlinx.coroutines.Job? = null

    fun startTvLink(name: String) {
        if (tvLink != null) return
        val server = TvLinkServer(name, object : TvLinkServer.Handler {
            override suspend fun hello(): org.json.JSONObject = org.json.JSONObject().put("name", name).put("app", "Streammania")

            override suspend fun pairRequested(phone: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val code = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
                tvPairing = phone to code
                userActivity(wake = true)
                Unit
            }

            override suspend fun pairConfirmed(code: String, extras: org.json.JSONObject): String? =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val p = tvPairing ?: return@withContext null
                    if (code != p.second) return@withContext null
                    tvPairing = null
                    val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
                    val token = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                    prefs.tvTokens = (prefs.tvTokens + token).toList().takeLast(12).toSet()
                    pairedPhoneCount = prefs.tvTokens.size
                    // A phone with film details turned on shares its key so the TV gets them too.
                    val key = extras.optString("tmdbKey")
                    if (key.isNotBlank() && tmdbKey.isBlank()) saveTmdbKey(key)
                    notify("${p.first} can now control this TV")
                    token
                }

            override fun authorised(token: String): Boolean = token.isNotEmpty() && token in prefs.tvTokens

            override suspend fun play(spec: org.json.JSONObject): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (repo == null) return@withContext "The TV isn't signed in yet"
                if (pickingProfile) return@withContext "Pick a profile on the TV first"
                val screen = screenFromSpec(spec) ?: return@withContext "The TV couldn't read that"
                userActivity(wake = true)
                while (playerOpen) pop()
                push(screen)
                null
            }

            override suspend fun key(key: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                remoteKeys?.invoke(key)
                Unit
            }
        })
        tvLink = server
        tvLinkJob = viewModelScope.launch {
            runCatching {
                server.run(getApplication()) { address ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (tvLink === server) tvLinkAddress = address }
                }
            }
        }
    }

    fun stopTvLink() {
        tvLink?.stop()
        tvLink = null
        tvLinkJob?.cancel()
        tvLinkJob = null
        tvPairing = null
        tvLinkAddress = null
    }

    /** Where phones can reach this TV, for typing in by hand. */
    var tvLinkAddress by mutableStateOf<String?>(null)
        private set
    var pairedPhoneCount by mutableIntStateOf(prefs.tvTokens.size)
        private set

    fun forgetPairedPhones() {
        if (prefs.tvTokens.isEmpty()) return
        prefs.tvTokens = emptySet()
        pairedPhoneCount = 0
        notify("Phones unpaired. They'll need a new code")
    }

    fun cancelTvPairing() {
        tvPairing = null
    }

    // ---- Play on TV (the phone side) ----

    /** TVs this phone has paired with; the token proves it to the TV. */
    data class PairedTv(val tv: FoundTv, val token: String)

    val pairedTvs = mutableStateListOf<PairedTv>().also { list ->
        runCatching {
            val arr = org.json.JSONArray(prefs.pairedTvs)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(PairedTv(FoundTv(o.getString("name"), o.getString("host"), o.getInt("port")), o.getString("token")))
            }
        }
    }
    val foundTvs = mutableStateListOf<FoundTv>()
    /** Where videos play: null means this phone. */
    var castTarget by mutableStateOf<PairedTv?>(null)
        private set
    var showCastSheet by mutableStateOf(false)
    var showRemote by mutableStateOf(false)
    /** A TV waiting for its code to be typed in. */
    var pairingWith by mutableStateOf<FoundTv?>(null)
        private set
    private var finder: TvFinder? = null

    private fun savePairedTvs() {
        val arr = org.json.JSONArray()
        for (p in pairedTvs) {
            arr.put(org.json.JSONObject().put("name", p.tv.name).put("host", p.tv.host).put("port", p.tv.port).put("token", p.token))
        }
        prefs.pairedTvs = arr.toString()
    }

    fun startFindingTvs() {
        if (finder != null) return
        finder = TvFinder(getApplication()) { list ->
            foundTvs.clear()
            foundTvs.addAll(list)
            // A TV that moved to a new address on the network keeps its pairing.
            var changed = false
            for (tv in list) {
                val i = pairedTvs.indexOfFirst { it.tv.name == tv.name }
                if (i >= 0 && pairedTvs[i].tv != tv) {
                    pairedTvs[i] = pairedTvs[i].copy(tv = tv)
                    if (castTarget?.tv?.name == tv.name) castTarget = pairedTvs[i]
                    changed = true
                }
            }
            if (changed) savePairedTvs()
        }.also { it.start() }
    }

    fun stopFindingTvs() {
        finder?.stop()
        finder = null
    }

    fun isPaired(tv: FoundTv): Boolean = pairedTvs.any { it.tv.name == tv.name }

    fun chooseTv(tv: FoundTv?) {
        if (tv == null) {
            castTarget = null
            notify("Playing on this phone")
            return
        }
        val paired = pairedTvs.firstOrNull { it.tv.name == tv.name }
        if (paired != null) {
            castTarget = paired
            notify("Playing on ${tv.name}")
            return
        }
        viewModelScope.launch {
            val error = TvLinkClient.requestPairing(tv, phoneName())
            if (error != null) notify(error) else pairingWith = tv
        }
    }

    fun addTvByAddress(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "8771").toIntOrNull() ?: 8771
        viewModelScope.launch {
            val probe = FoundTv(host, host, port)
            val hello = TvLinkClient.hello(probe)
            if (hello == null) {
                notify("No Streammania TV at $t")
                return@launch
            }
            chooseTv(FoundTv(hello.optString("name").ifBlank { host }, host, port))
        }
    }

    fun cancelPairing() {
        pairingWith = null
    }

    fun enterPairingCode(code: String) {
        val tv = pairingWith ?: return
        pairingWith = null
        viewModelScope.launch {
            val extras = org.json.JSONObject()
            if (tmdbKey.isNotBlank()) extras.put("tmdbKey", tmdbKey)
            val token = TvLinkClient.confirmPairing(tv, code, extras)
            if (token == null) {
                notify("That code didn't match. Try again from Play on TV.")
                return@launch
            }
            pairedTvs.removeAll { it.tv.name == tv.name }
            val p = PairedTv(tv, token)
            pairedTvs.add(p)
            savePairedTvs()
            castTarget = p
            notify("Paired. Playing on ${tv.name}")
        }
    }

    fun forgetTv(p: PairedTv) {
        pairedTvs.removeAll { it.tv.name == p.tv.name }
        savePairedTvs()
        if (castTarget?.tv?.name == p.tv.name) castTarget = null
    }

    fun pressRemote(key: String) {
        val target = castTarget ?: return
        viewModelScope.launch {
            if (!TvLinkClient.key(target.tv, target.token, key)) notify("Couldn't reach ${target.tv.name}")
        }
    }

    private fun phoneName(): String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").take(40)

    private fun sendToTv(screen: Screen, target: PairedTv) {
        val spec = specFor(screen) ?: return
        notify("Sending to ${target.tv.name}…")
        viewModelScope.launch {
            val error = TvLinkClient.play(target.tv, target.token, spec)
            when {
                error == null -> {
                    notify("Playing on ${target.tv.name}")
                    showRemote = true
                }
                // TV off or out of range: play it here, and stop sending to it until it's picked again.
                error.startsWith("Couldn't reach") -> {
                    notify("Couldn't reach ${target.tv.name}, so playing on this phone")
                    castTarget = null
                    pushHere(screen)
                }
                else -> notify(error)
            }
        }
    }

    private fun specFor(screen: Screen): org.json.JSONObject? {
        val o = org.json.JSONObject()
        when (screen) {
            is Screen.PlayLive -> {
                // Only the channels near the one chosen, so channel up/down still works on the TV.
                val from = (screen.index - 50).coerceAtLeast(0)
                val to = (screen.index + 50).coerceAtMost(screen.channels.size)
                val arr = org.json.JSONArray()
                for (c in screen.channels.subList(from, to)) {
                    arr.put(
                        org.json.JSONObject().put("id", c.streamId).put("num", c.num).put("name", c.name).put("icon", c.icon)
                            .put("cat", c.categoryId).put("epg", c.epgId).put("archive", c.hasArchive).put("days", c.archiveDays)
                    )
                }
                o.put("kind", "live").put("index", screen.index - from).put("channels", arr)
            }
            is Screen.PlayVod -> {
                val arr = org.json.JSONArray()
                for (v in screen.items) {
                    arr.put(
                        org.json.JSONObject().put("key", v.key).put("title", v.title).put("subtitle", v.subtitle).put("url", v.url)
                            .put("seriesId", v.seriesId ?: -1).put("episodeId", v.episodeId ?: -1).put("contentId", v.contentId)
                            .put("image", v.image).put("cover", v.seriesCover).put("ext", v.ext)
                    )
                }
                o.put("kind", "vod").put("index", screen.index).put("startMs", screen.startMs).put("items", arr)
            }
            else -> return null
        }
        return o
    }

    private fun screenFromSpec(o: org.json.JSONObject): Screen? = runCatching {
        when (o.getString("kind")) {
            "live" -> {
                val arr = o.getJSONArray("channels")
                val chans = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    Channel(
                        c.getInt("id"), c.optInt("num"), c.optString("name"), c.optString("icon"), c.optString("cat"),
                        c.optString("epg"), c.optBoolean("archive"), c.optInt("days"),
                    )
                }
                if (chans.isEmpty()) null else Screen.PlayLive(chans, o.optInt("index").coerceIn(0, chans.lastIndex))
            }
            "vod" -> {
                val arr = o.getJSONArray("items")
                val items = (0 until arr.length()).map { i ->
                    val v = arr.getJSONObject(i)
                    VodItem(
                        v.getString("key"), v.optString("title"), v.optString("subtitle"), v.getString("url"),
                        v.optInt("seriesId", -1).takeIf { it >= 0 }, v.optInt("episodeId", -1).takeIf { it >= 0 },
                        v.optInt("contentId"), v.optString("image"), v.optString("cover"), v.optString("ext"),
                    )
                }
                if (items.isEmpty()) null else Screen.PlayVod(items, o.optInt("index").coerceIn(0, items.lastIndex), o.optLong("startMs"))
            }
            else -> null
        }
    }.getOrNull()

    /** Plays a channel with the rest of its group, so channel up/down works as usual. */
    fun playChannel(c: Channel) {
        val r = repo ?: return
        viewModelScope.launch {
            val list = try {
                r.channels(c.categoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val i = list.indexOfFirst { it.streamId == c.streamId }
            if (i >= 0) push(Screen.PlayLive(list, i)) else push(Screen.PlayLive(listOf(c), 0))
        }
    }

    // ---- Home ----

    /** Home's rows: loaded in the background and kept, so other pages don't interrupt it. */
    var homeFeed by mutableStateOf<LoadResult<HomeFeed>>(LoadResult.Loading)
        private set
    private var homeJob: kotlinx.coroutines.Job? = null
    private var homeFor: Pair<Repository, Int>? = null

    /** Loads Home if it hasn't been for this sign-in and refresh yet (or the last try failed). */
    // ---- Home: New Episodes, Because You Watched, and the row order ----

    /** When each series was last watched (epoch ms). */
    private var seriesSeen: Map<Int, Long> = emptyMap()

    /** The last film ("m:<id>") or series ("s:<id>") watched. */
    var lastWatched by mutableStateOf("")
        private set

    /** Home's extra rows, worked out in the background. */
    class HomeExtras(val newEpisodes: List<Series>, val becauseOf: String?, val because: List<Any>)

    var homeExtras by mutableStateOf<HomeExtras?>(null)
        private set
    private var homeExtrasFor: Any? = null
    private var homeExtrasJob: kotlinx.coroutines.Job? = null

    /** Marks a series as just watched, so it leaves New Episodes until something newer arrives. */
    fun noteSeriesSeen(sid: Int) {
        val now = System.currentTimeMillis()
        val was = seriesSeen[sid]
        val extras = homeExtras
        if (extras != null && extras.newEpisodes.any { it.seriesId == sid || it.versions.any { v -> v.seriesId == sid } }) {
            homeExtras = HomeExtras(
                extras.newEpisodes.filterNot { it.seriesId == sid || it.versions.any { v -> v.seriesId == sid } },
                extras.becauseOf,
                extras.because,
            )
        }
        // Saved at most once a minute while watching.
        if (was != null && now - was < 60_000L) return
        var next = seriesSeen + (sid to now)
        if (next.size > 400) next = next.entries.sortedByDescending { it.value }.take(400).associate { it.key to it.value }
        seriesSeen = next
        prefs.seriesSeen = next
    }

    /** Works out New Episodes and Because You Watched, once per change of lists or of what was watched. */
    fun ensureHomeExtras() {
        val r = repo ?: return
        val key = listOf(r, reloadToken, vodVersion, lastWatched, profile?.id)
        if (homeExtrasFor == key) return
        homeExtrasFor = key
        homeExtrasJob?.cancel()
        homeExtrasJob = viewModelScope.launch {
            val fresh = newEpisodesFor(r)
            val (becauseOf, because) = becauseYouWatched(r)
            if (repo === r) homeExtras = HomeExtras(fresh, becauseOf, because)
        }
    }

    private suspend fun newEpisodesFor(r: Repository): List<Series> {
        // Series watched before this was added count from their Up Next entry.
        val seen = HashMap(seriesSeen)
        for (h in history) if (h.type == ContentType.SERIES && h.seriesId > 0 && h.seriesId !in seen) seen[h.seriesId] = h.updatedAt
        if (seen.isEmpty()) return emptyList()
        val all = try {
            r.allSeries()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        } catch (e: OutOfMemoryError) {
            return emptyList()
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.mapNotNull { s ->
                val copies = listOf(s) + s.versions
                val watchedAt = copies.mapNotNull { seen[it.seriesId] }.maxOrNull() ?: return@mapNotNull null
                val updatedMs = copies.maxOf { it.updated } * 1000L
                // A minute's grace, as the provider's clock and ours never quite agree.
                if (updatedMs > watchedAt + 60_000L) s to updatedMs else null
            }.sortedByDescending { it.second }.take(20).map { it.first }
        }
    }

    private suspend fun becauseYouWatched(r: Repository): Pair<String?, List<Any>> {
        val last = lastWatched
        val id = last.substringAfter(':').toIntOrNull() ?: return null to emptyList()
        return try {
            if (last.startsWith("m:")) {
                // Only from lists already loaded: Home shouldn't download the whole library for this.
                val movie = r.loadedMovies().firstOrNull { m -> m.streamId == id || m.versions.any { it.streamId == id } }
                    ?: return null to emptyList()
                val extras = try { movieExtras(movie, r.peekMovieInfo(movie.streamId)) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                val picks = moreLikeThis(movie, extras).filter { it.streamId != movie.streamId && !movieWatched(it) }.take(20)
                Versions.cleanTitle(movie.name) to picks
            } else {
                val show = r.loadedSeries().firstOrNull { s -> s.seriesId == id || s.versions.any { it.seriesId == id } }
                    ?: return null to emptyList()
                val extras = try { seriesExtras(show, r.peekSeriesInfo(show.seriesId)) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                val picks = moreLikeThis(show, extras).filter { it.seriesId != show.seriesId }.take(20)
                Versions.cleanTitle(show.name) to picks
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to emptyList()
        } catch (e: OutOfMemoryError) {
            null to emptyList()
        }
    }

    /** Home's rows in this profile's chosen order. */
    var homeOrder by mutableStateOf<List<String>>(emptyList())
        private set

    /** Home rows this profile has turned off. */
    var homeHidden by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Every row, in the profile's order: saved ones first, then any not yet placed, in their usual spot. */
    fun homeRowOrder(): List<String> {
        val known = HOME_ROWS.map { it.first }
        val saved = homeOrder.filter { it in known }
        if (saved.isEmpty()) return known
        val out = saved.toMutableList()
        for ((i, id) in known.withIndex()) if (id !in out) out.add(i.coerceAtMost(out.size), id)
        return out
    }

    fun moveHomeRow(id: String, by: Int) {
        val order = homeRowOrder().toMutableList()
        val from = order.indexOf(id)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        order.removeAt(from)
        order.add(to, id)
        homeOrder = order
        prefs.homeOrder = order
    }

    fun toggleHomeRow(id: String) {
        val next = if (id in homeHidden) homeHidden - id else homeHidden + id
        homeHidden = next
        prefs.homeHidden = next
    }

    /** Steps through Normal, Large and Extra large text. */
    fun cycleTextSize() {
        val i = TEXT_SIZES.indexOfFirst { kotlin.math.abs(it.second - Txt.scale) < 0.01f }
        val next = TEXT_SIZES[(i + 1) % TEXT_SIZES.size].second
        Txt.scale = next
        prefs.textScale = next
    }

    val textSizeLabel: String
        get() = TEXT_SIZES.firstOrNull { kotlin.math.abs(it.second - Txt.scale) < 0.01f }?.first ?: "Normal"

    fun toggleHints() {
        HintsSetting.show = !HintsSetting.show
        prefs.showHints = HintsSetting.show
    }

    /** Whether the next episode starts by itself after a short countdown. */
    var autoplayNext by mutableStateOf(true)
        private set

    fun toggleAutoplayNext() {
        autoplayNext = !autoplayNext
        prefs.autoplayNext = autoplayNext
    }

    /** Changes this profile's background colours. */
    fun chooseBackdrop(b: AppBackground) {
        Ember.backdrop = b
        prefs.backdrop = b.id
    }

    fun resetHomeRows() {
        homeOrder = emptyList()
        homeHidden = emptySet()
        prefs.homeOrder = emptyList()
        prefs.homeHidden = emptySet()
    }

    // ---- Keeping films and series up to date ----

    /** Goes up each time the film and series lists are updated in the background; screens re-read quietly. */
    var vodVersion by mutableIntStateOf(0)
        private set

    /** Whether the app is on screen. Background updates only run while it is. */
    var appVisible = true

    private var vodCheckedAt = System.currentTimeMillis()
    private var vodUpdating = false

    /**
     * Fetches the latest films and series every few hours while the app is open, and when it comes
     * back after a while (Fire TV keeps it running for days), so new additions turn up by themselves.
     */
    fun updateFilmsAndSeriesIfDue(force: Boolean = false) {
        val r = repo ?: return
        if (vodUpdating) return
        val now = System.currentTimeMillis()
        if (!force && now - vodCheckedAt < VOD_UPDATE_EVERY_MS) return
        vodUpdating = true
        viewModelScope.launch {
            try {
                val fresh = r.refreshFilmsAndSeries()
                if (repo !== r) return@launch
                vodCheckedAt = System.currentTimeMillis()
                vodVersion++
                val films = fresh.newMovies.coerceAtLeast(0)
                val shows = fresh.newSeries.coerceAtLeast(0)
                refreshHomeQuietly()
                if (films + shows > 0) {
                    // Not in the middle of something: a player or a detail page.
                    if (stack.lastOrNull() is Screen.Home) {
                        val parts = listOfNotNull(
                            if (films > 0) "$films new ${if (films == 1) "film" else "films"}" else null,
                            if (shows > 0) "$shows new series" else null,
                        )
                        notify(parts.joinToString(" and ") + " added")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Try again in half an hour rather than waiting the full gap.
                vodCheckedAt = System.currentTimeMillis() - VOD_UPDATE_EVERY_MS + 30 * 60_000L
            } catch (e: OutOfMemoryError) {
                vodCheckedAt = System.currentTimeMillis()
            } finally {
                vodUpdating = false
            }
        }
    }

    /** Re-builds Home in the background and swaps it in, keeping what's on screen meanwhile. */
    private fun refreshHomeQuietly() {
        val r = repo ?: return
        if (homeFeed !is LoadResult.Ok) return
        viewModelScope.launch {
            try {
                val feed = loadHomeFeed(r)
                if (repo === r) homeFeed = LoadResult.Ok(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the Home that's showing.
            } catch (e: OutOfMemoryError) {
            }
        }
    }

    fun ensureHome() {
        val r = repo ?: return
        val key = r to reloadToken
        if (homeFor == key && homeFeed !is LoadResult.Err) return
        homeFor = key
        homeJob?.cancel()
        homeFeed = LoadResult.Loading
        homeJob = viewModelScope.launch {
            homeFeed = try {
                LoadResult.Ok(loadHomeFeed(r))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadResult.Err(e.message ?: "Something went wrong.")
            } catch (e: OutOfMemoryError) {
                LoadResult.Err("That list is too big for this device to load all at once.")
            }
        }
    }

    // ---- Navigation ----

    fun push(screen: Screen) {
        // With another player chosen in Settings, channels and videos open there instead.
        // With a TV chosen from the phone, it plays there instead.
        val target = castTarget
        if (target != null && (screen is Screen.PlayLive || screen is Screen.PlayVod)) {
            sendToTv(screen, target)
            return
        }
        pushHere(screen)
    }

    /** Opens [screen] on this device (or in the chosen other player). */
    private fun pushHere(screen: Screen) {
        val pkg = externalPlayer
        if (pkg.isNotEmpty() && (screen is Screen.PlayLive || screen is Screen.PlayVod)) {
            if (openExternally(screen, pkg)) return
            notify("Couldn't open ${externalPlayerName()}, so playing here")
        }
        returnKeys.add(lastFocusKey)
        stack.add(screen)
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.lastIndex)
        restoreClaimed = false
        restoreFocusKey = if (returnKeys.isNotEmpty()) returnKeys.removeAt(returnKeys.lastIndex) else null
    }

    fun notify(text: String) {
        notice = Notice(text)
    }

    fun clearNotice(n: Notice) {
        if (notice == n) notice = null
    }

    // ---- Favourites ----

    /** Channels hidden on this profile. */
    var hiddenChannels by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** Hides or shows channels; the lists, guide and channel up/down update straight away. */
    fun setChannelsHidden(ids: Collection<Int>, hidden: Boolean) {
        val next = if (hidden) hiddenChannels + ids else hiddenChannels - ids.toSet()
        if (next == hiddenChannels) return
        hiddenChannels = next
        prefs.hiddenChannels = next
        repo?.hiddenChannels = next
    }

    /** Called when leaving the channel editor, so open lists pick up hidden channels. */
    fun channelsEdited() {
        reloadToken++
    }

    /** Moves a favourite channel up (-1) or down (+1) among the favourite channels. */
    fun moveFavouriteChannel(key: String, by: Int): Boolean {
        val liveIdx = favourites.indices.filter { favourites[it].type == ContentType.LIVE }
        val pos = liveIdx.indexOfFirst { favourites[it].key == key }
        val target = pos + by
        if (pos < 0 || target !in liveIdx.indices) return false
        val a = liveIdx[pos]
        val b = liveIdx[target]
        val item = favourites[a]
        favourites[a] = favourites[b]
        favourites[b] = item
        prefs.favourites = favourites.toList()
        return true
    }

    fun removeFavourite(key: String) {
        if (favourites.removeAll { it.key == key }) prefs.favourites = favourites.toList()
    }

    fun isFavourite(type: ContentType, id: Int): Boolean = favKeys.contains(favouriteKey(type, id))

    fun toggleFavourite(f: Favourite) {
        val i = favourites.indexOfFirst { it.key == f.key }
        if (i >= 0) {
            favourites.removeAt(i)
            notify("Removed from favourites")
        } else {
            favourites.add(0, f)
            notify("Added to favourites")
        }
        prefs.favourites = favourites.toList()
    }

    fun favouriteOf(c: Channel) = Favourite(ContentType.LIVE, c.streamId, c.name, c.icon, c.categoryId, "", c.num)
    fun favouriteOf(m: Movie) = Favourite(ContentType.MOVIE, m.streamId, m.name, m.poster, m.categoryId, m.ext)
    fun favouriteOf(s: Series) = Favourite(ContentType.SERIES, s.seriesId, s.name, s.cover, s.categoryId)

    // ---- Settings ----

    fun toggleStreamFormat() {
        val next = if (streamFormat == "m3u8") "ts" else "m3u8"
        streamFormat = next
        prefs.streamFormat = next
    }

    fun toggleUserAgent() {
        val next = if (userAgent == DEFAULT_USER_AGENT) app.embertv.data.VLC_USER_AGENT else DEFAULT_USER_AGENT
        userAgent = next
        prefs.userAgent = next
        val acc = account ?: return
        startSession(acc, accountInfo)
        reloadToken++
    }

    /** Pause and rewind live TV, using the provider's catch-up on channels that have it. */
    var timeshiftOn by mutableStateOf(prefs.timeshiftMinutes > 0)
        private set

    fun toggleTimeshift() {
        timeshiftOn = !timeshiftOn
        prefs.timeshiftMinutes = if (timeshiftOn) 30 else 0
    }

    /** The provider's rewind for [c], from [startSec] for [minutes]; null when not signed in. */
    fun timeshiftUrl(c: Channel, startSec: Long, minutes: Int): String? {
        val r = repo ?: return null
        val hint = (r.cachedEpg(c.streamId).orEmpty() + r.cachedFullEpg(c.streamId).orEmpty()).firstOrNull { it.serverStart.isNotBlank() }
        return r.client.timeshiftUrl(c.streamId, startSec, minutes, accountInfo?.serverTimezone ?: "", hint)
    }

    /** Why the app closed last time, if it crashed; shown once. */
    var crashNote by mutableStateOf<String?>(CrashNote.take(getApplication()))

    /** Picture zoom for live TV and films: 0 fit, 1 zoom, 2 stretch. Remembered. */
    var pictureMode by mutableStateOf(prefs.pictureMode.coerceIn(0, 2))
        private set

    val pictureModeLabel: String
        get() = PICTURE_MODES[pictureMode]

    fun setPicture(mode: Int, announce: Boolean = true) {
        val m = mode.coerceIn(0, 2)
        if (m == pictureMode) return
        pictureMode = m
        prefs.pictureMode = m
        if (announce) notify("Picture: ${PICTURE_MODES[m]}")
    }

    fun cyclePicture(announce: Boolean = true) = setPicture((pictureMode + 1) % 3, announce)

    var startOn by mutableStateOf(prefs.startOn)
        private set
    private var startupApplied = false

    fun cycleStartOn() {
        val steps = listOf("home", "last", "guide")
        val next = steps[(steps.indexOf(startOn) + 1) % steps.size]
        startOn = next
        prefs.startOn = next
    }

    fun startOnLabel(): String = when (startOn) {
        "last" -> "Last channel"
        "guide" -> "TV guide"
        else -> "Home"
    }

    /** Once per launch, after signing in and choosing a profile: go where the app should open. */
    fun applyStartup() {
        if (startupApplied || repo == null || pickingProfile) return
        startupApplied = true
        when (startOn) {
            "last" -> {
                val last = recentChannels.firstOrNull()
                if (last != null && stack.size == 1) playChannel(last.toChannel())
            }
            "guide" -> tab = Tabs.GUIDE
        }
    }

    fun cycleScreensaver() {
        val steps = listOf(3, 5, 10, 0)
        val next = steps[(steps.indexOf(screensaverMinutes) + 1) % steps.size]
        screensaverMinutes = next
        prefs.screensaverMinutes = next
    }

    /** Records a button press. Returns true if it woke the screensaver (and should go no further). */
    fun userActivity(wake: Boolean = true): Boolean {
        lastActivity = android.os.SystemClock.uptimeMillis()
        if (screensaver && wake) {
            screensaver = false
            return true
        }
        return false
    }

    /** Starts the screensaver after a while without button presses, except during playback and pop-ups. */
    fun checkIdle() {
        if (screensaver || screensaverMinutes == 0) return
        val busy = repo == null || pickingProfile || playerOpen || dueReminder != null || update != null
        if (busy) {
            lastActivity = android.os.SystemClock.uptimeMillis()
            return
        }
        if (android.os.SystemClock.uptimeMillis() - lastActivity >= screensaverMinutes * 60_000L) screensaver = true
    }

    fun toggleInvertZap() {
        invertZap = !invertZap
        prefs.invertZap = invertZap
    }

    // ---- Watching ----

    fun recordChannel(c: Channel) {
        val f = favouriteOf(c)
        recentChannels.removeAll { it.id == f.id }
        recentChannels.add(0, f)
        while (recentChannels.size > 15) recentChannels.removeAt(recentChannels.lastIndex)
        prefs.recentChannels = recentChannels.toList()
    }

    fun cycleAudioLanguage() {
        val next = if (audioLanguage.isEmpty()) "en" else ""
        audioLanguage = next
        prefs.audioLanguage = next
    }

    fun cycleSubtitles() {
        val next = if (subtitleMode == "off") "en" else "off"
        subtitleMode = next
        prefs.subtitleMode = next
    }

    private val sleepSteps = listOf(0, 15, 30, 60, 90, 120)

    /** Off → 15 → 30 → 60 → 90 → 120 minutes → off. */
    fun cycleSleepTimer() {
        val remaining = sleepAt?.let { ((it - System.currentTimeMillis()) / 60_000L).toInt() } ?: 0
        val next = sleepSteps.firstOrNull { it > remaining + 1 } ?: 0
        sleepAt = if (next == 0) null else System.currentTimeMillis() + next * 60_000L
        notify(if (next == 0) "Sleep timer off" else "Sleep timer: $next minutes")
    }

    fun sleepMinutesLeft(now: Long = System.currentTimeMillis()): Int? =
        sleepAt?.let { ((it - now + 59_999L) / 60_000L).toInt().coerceAtLeast(0) }

    /** Called when the sleep timer runs out: stop anything that's playing. */
    fun sleepNow() {
        sleepAt = null
        var stopped = false
        while (stack.size > 1 && (stack.last() is Screen.PlayLive || stack.last() is Screen.PlayVod)) {
            pop()
            stopped = true
        }
        if (stopped) notify("Sleep timer: playback stopped")
    }

    // ---- Updates ----

    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            val latest = Updates.latest(app.embertv.BuildConfig.UPDATE_REPO)
            when {
                latest == null -> if (manual) notify("Couldn't check for updates")
                latest.versionCode > app.embertv.BuildConfig.VERSION_CODE -> update = latest
                manual -> notify("You're up to date (version ${app.embertv.BuildConfig.VERSION_NAME})")
            }
        }
    }

    fun dismissUpdate() {
        update = null
        updateProgress = null
    }

    fun installUpdate() {
        val info = update ?: return
        if (updateProgress != null) return
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                notify("Allow Streammania to install apps, then choose Install again")
            } catch (e: ActivityNotFoundException) {
                notify("Allow Streammania under Settings → Install unknown apps, then try again")
            }
            return
        }
        updateProgress = 0f
        viewModelScope.launch {
            try {
                val file = Updates.download(info, File(context.cacheDir, "updates")) { p ->
                    viewModelScope.launch { updateProgress = p }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                update = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.message ?: "The update couldn't be downloaded")
            } finally {
                updateProgress = null
            }
        }
    }

    // ---- Playback ----

    fun liveUrl(c: Channel, alternate: Boolean): String {
        val format = if (alternate) (if (streamFormat == "m3u8") "ts" else "m3u8") else streamFormat
        return repo?.client?.liveUrl(c.streamId, format) ?: ""
    }

    /** Plays a film, asking which quality first when there's more than one (unless set in Settings). */
    fun playMovie(m: Movie, fromStart: Boolean) {
        // Still looking for other qualities: finish that first, so the choice is always offered.
        if (mergeVersions && m.streamId !in checkedMovies) {
            // Give the check a few seconds at most: on a big library the full film list can take a
            // while, and Play should never just sit there. It plays with what's known so far.
            notify("Checking for other qualities…")
            checkedMovies += m.streamId
            // The check carries on in the background if it runs out of time, ready for next time.
            val check = viewModelScope.launch { loadVersions(m) }
            viewModelScope.launch {
                kotlinx.coroutines.withTimeoutOrNull(VERSION_CHECK_MS) { check.join() }
                playMovie(m, fromStart)
            }
            return
        }
        val versions = versionsOf(m)
        val startAt = if (fromStart) 0L else moviePosition(m)
        if (versions.size > 1) {
            val auto = autoVersion(versions.map { it.quality })
            if (auto == null) {
                choosingVersion = VersionChoice.ForMovie(Versions.cleanTitle(m.name), versions, startAt)
            } else {
                playMovieVersion(versions[auto], startAt)
            }
            return
        }
        playMovieVersion(m, startAt)
    }

    fun playMovieVersion(m: Movie, startAt: Long) {
        choosingVersion = null
        val r = repo ?: return
        val key = "movie:${m.streamId}"
        val start = startAt
        val art = r.peekMovieInfo(m.streamId)?.backdrop?.takeIf { it.isNotBlank() } ?: m.poster
        val item = VodItem(
            key = key,
            title = if (mergeVersions) Versions.cleanTitle(m.name) else m.name,
            subtitle = "",
            url = r.client.movieUrl(m.streamId, m.ext),
            contentId = m.streamId,
            image = art,
            seriesCover = m.poster,
            ext = m.ext,
        )
        push(Screen.PlayVod(listOf(item), 0, start))
    }

    /** Plays episodes, asking which quality first when the show comes in more than one. */
    fun playEpisodes(s: Series, episodes: List<Episode>, index: Int, fromStart: Boolean) {
        if (index !in episodes.indices) return
        val versions = versionsOf(s)
        if (versions.size > 1) {
            val auto = autoVersion(versions.map { it.quality })
            if (auto == null) {
                choosingVersion = VersionChoice.ForEpisode(Versions.cleanTitle(s.name), versions, s, episodes, index, fromStart)
            } else {
                playEpisodeVersion(versions[auto], s, episodes, index, fromStart)
            }
            return
        }
        playEpisodesIn(s, episodes, index, fromStart, null)
    }

    /** Plays the same episode from another version of the show, matched by season and episode number. */
    fun playEpisodeVersion(target: Series, from: Series, episodes: List<Episode>, index: Int, fromStart: Boolean) {
        choosingVersion = null
        val r = repo ?: return
        if (target.seriesId == from.seriesId) {
            playEpisodesIn(from, episodes, index, fromStart, null)
            return
        }
        val ep = episodes[index]
        viewModelScope.launch {
            val info = try {
                r.seriesInfo(target.seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val season = info?.episodes?.get(ep.season).orEmpty()
            val i = season.indexOfFirst { it.number == ep.number }
            if (i >= 0) {
                playEpisodesIn(target.copy(name = from.name), season, i, fromStart, "ep:${ep.id}")
            } else {
                notify("That episode isn't in ${target.quality.badge.ifEmpty { "that version" }}, so playing this one")
                playEpisodesIn(from, episodes, index, fromStart, null)
            }
        }
    }

    /** [fallbackKey]: resume from there if this exact episode has no saved position (same episode, other quality). */
    private fun playEpisodesIn(s: Series, episodes: List<Episode>, index: Int, fromStart: Boolean, fallbackKey: String?) {
        val r = repo ?: return
        if (index !in episodes.indices) return
        val items = episodes.map { e ->
            VodItem(
                key = "ep:${e.id}",
                title = s.name,
                subtitle = "Season ${e.season} · Episode ${e.number}  ${e.title}",
                url = r.client.episodeUrl(e.id, e.ext),
                seriesId = s.seriesId,
                episodeId = e.id,
                contentId = e.id,
                image = e.image.ifBlank { r.peekSeriesInfo(s.seriesId)?.backdrop?.takeIf { it.isNotBlank() } ?: s.cover },
                seriesCover = s.cover,
                ext = e.ext,
            )
        }
        val start = when {
            fromStart -> 0L
            else -> prefs.position(items[index].key).takeIf { it > 0 } ?: fallbackKey?.let { prefs.position(it) } ?: 0L
        }
        push(Screen.PlayVod(items, index, start))
    }

    fun saveProgress(item: VodItem, positionMs: Long, durationMs: Long) {
        if (item.key.startsWith(CATCHUP_PREFIX)) return
        prefs.savePosition(item.key, positionMs, durationMs)
        val sid = item.seriesId
        val eid = item.episodeId
        if (sid != null && eid != null) prefs.setLastEpisode(sid, eid)
        updateHistory(item, positionMs, durationMs)
        // For New Episodes and Because You Watched on Home.
        if (sid != null) noteSeriesSeen(sid)
        val watched = if (sid != null) "s:$sid" else if (item.contentId > 0) "m:${item.contentId}" else null
        if (watched != null && watched != lastWatched) {
            lastWatched = watched
            prefs.lastWatched = watched
        }
    }

    // ---- Catch-up ----

    /** Keeps the live screen's entry in step with zapping, so returning to it resumes the same channel. */
    fun noteLiveIndex(i: Int) {
        val top = stack.lastOrNull() as? Screen.PlayLive ?: return
        if (top.index != i) stack[stack.lastIndex] = top.copy(index = i)
    }

    /** Plays [p] on [channel] from the provider's recordings, from its start. */
    fun playCatchup(channel: Channel, p: Programme) {
        val r = repo ?: return
        val url = r.client.catchupUrl(channel.streamId, p, accountInfo?.serverTimezone ?: "")
        val item = VodItem(
            key = "$CATCHUP_PREFIX${channel.streamId}:${p.start}",
            title = p.title,
            subtitle = "${channel.name}  ·  ${formatDay(p.start)} ${formatClock(p.start)}",
            url = url,
            image = channel.icon,
        )
        push(Screen.PlayVod(listOf(item), 0, 0L))
    }

    // ---- Richer detail pages ----

    var tmdbKey by mutableStateOf(prefs.tmdbKey)
        private set
    private var tmdb: Tmdb? = prefs.tmdbKey.takeIf { it.isNotBlank() }?.let { Tmdb(it) }

    /** Saves a TMDB key after checking it works. Empty removes it. */
    fun saveTmdbKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) {
            tmdbKey = ""
            prefs.tmdbKey = ""
            tmdb = null
            notify("Film details from TMDB turned off")
            return
        }
        viewModelScope.launch {
            val t = app.embertv.data.Tmdb(key)
            if (t.check()) {
                tmdbKey = key
                prefs.tmdbKey = key
                tmdb = t
                notify("TMDB connected: cast photos and suggestions are on")
            } else {
                notify("That TMDB key didn't work. Check it and try again")
            }
        }
    }

    suspend fun movieExtras(m: Movie, info: app.embertv.data.MovieInfo?): app.embertv.data.Extras? =
        tmdb?.extras("movie", info?.tmdbId ?: 0, m.name, info?.year ?: "")

    suspend fun seriesExtras(s: Series, info: app.embertv.data.SeriesInfo?): app.embertv.data.Extras? =
        tmdb?.extras("tv", info?.tmdbId ?: 0, s.name, info?.year ?: s.year)

    /**
     * TMDB's suggestions that you can actually watch, then more from the same category. Runs off the
     * main thread and only works out title keys for as many films as it needs: the whole library can
     * be tens of thousands of titles, and doing them all on screen froze the app.
     */
    suspend fun moreLikeThis(m: Movie, extras: app.embertv.data.Extras?): List<Movie> {
        val r = repo ?: return emptyList()
        val sameCategory = try {
            r.movies(m.categoryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val pool = if (extras?.suggestions.isNullOrEmpty()) emptyList() else r.loadedMovies()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            pickMore(
                ownName = m.name,
                suggestions = extras?.suggestions.orEmpty(),
                pool = pool,
                fallback = sameCategory.shuffled(java.util.Random(m.streamId.toLong())),
                name = { it.name },
                year = { Versions.year(it.name) },
            )
        }
    }

    suspend fun moreLikeThis(s: Series, extras: app.embertv.data.Extras?): List<Series> {
        val r = repo ?: return emptyList()
        val sameCategory = try {
            r.series(s.categoryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val pool = if (extras?.suggestions.isNullOrEmpty()) emptyList() else r.loadedSeries()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            pickMore(
                ownName = s.name,
                suggestions = extras?.suggestions.orEmpty(),
                pool = pool,
                fallback = sameCategory.shuffled(java.util.Random(s.seriesId.toLong())),
                name = { it.name },
                year = { null },
            )
        }
    }

    private fun <T> pickMore(
        ownName: String,
        suggestions: List<app.embertv.data.Suggestion>,
        pool: List<T>,
        fallback: List<T>,
        name: (T) -> String,
        year: (T) -> String?,
    ): List<T> {
        val own = Versions.key(ownName)
        val out = ArrayList<T>()
        val seen = HashSet<String>().apply { add(own) }
        if (suggestions.isNotEmpty() && pool.isNotEmpty()) {
            // Match suggestions by a cheap first word before the full title key.
            val wanted = suggestions.associateBy { Versions.key(it.title) }
            val firstWords = wanted.keys.mapNotNull { it.split(' ').firstOrNull()?.takeIf { w -> w.length > 1 } }.toSet()
            val found = HashMap<String, T>()
            for (item in pool) {
                val n = name(item)
                val first = n.lowercase().split(' ', ':', '-', '|').firstOrNull { it.length > 1 } ?: continue
                if (firstWords.none { n.contains(it, ignoreCase = true) } && first !in firstWords) continue
                val k = Versions.key(n)
                val sg = wanted[k] ?: continue
                val y = year(item)
                if (k !in found && (sg.year.isEmpty() || y == null || y == sg.year)) found[k] = item
            }
            for (k in wanted.keys) {
                val item = found[k] ?: continue
                if (seen.add(k)) out += item
                if (out.size >= 16) return out
            }
        }
        for (item in fallback) {
            if (seen.add(Versions.key(name(item)))) out += item
            if (out.size >= 16) break
        }
        return out
    }

    /** Opens a YouTube trailer in the YouTube app (or the browser if it isn't installed). */
    fun playTrailer(youtubeId: String) {
        val ctx = getApplication<Application>()
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(web)
            } catch (e2: ActivityNotFoundException) {
                notify("Install the YouTube app to watch trailers")
            }
        }
    }

    // ---- Live Sport Now and the score bug ----

    /** A match on now, and the channels found showing it (best first). */
    /** A match for the Home row; [searching] while its channels are still being looked for. */
    data class LiveSport(val match: Match, val hits: List<MatchHit>, val searching: Boolean = false)

    /** Your leagues, shown first in Live Sport Now. */
    var sportLeagues by mutableStateOf(prefs.sportLeagues)
        private set
    var sportOthers by mutableStateOf(prefs.sportOthers)
        private set

    fun setSportLeague(code: String, on: Boolean) = chooseSportLeagues(if (on) sportLeagues + code else sportLeagues - code)

    fun chooseSportLeagues(codes: Set<String>) {
        if (codes == sportLeagues) return
        sportLeagues = codes
        prefs.sportLeagues = codes
        restartLiveSport()
    }

    fun toggleSportOthers() {
        sportOthers = !sportOthers
        prefs.sportOthers = sportOthers
        restartLiveSport()
    }

    private fun restartLiveSport() {
        if (sportJob != null) watchLiveSport(true)
    }

    var sportOnHome by mutableStateOf(prefs.sportOnHome)
        private set
    /** Football on now (or about to start), for the Home row. */
    var liveSport by mutableStateOf<List<LiveSport>>(emptyList())
        private set
    private var sportJob: kotlinx.coroutines.Job? = null
    // Channel searches by match id, with when they were done.
    private val sportHits = HashMap<String, Pair<Long, List<MatchHit>>>()

    fun toggleSportOnHome() {
        sportOnHome = !sportOnHome
        prefs.sportOnHome = sportOnHome
        if (!sportOnHome) liveSport = emptyList()
    }

    /** Home calls this while it's showing: refreshes live matches every minute and finds their channels. */
    fun watchLiveSport(active: Boolean) {
        sportJob?.cancel()
        sportJob = null
        if (!active || !sportOnHome || profile?.kids == true) return
        val r = repo ?: return
        sportJob = viewModelScope.launch {
            // Let Home's own rows start first; the sport search can wait a moment.
            var waited = 0
            while (homeFeed is LoadResult.Loading && waited < 6_000) {
                kotlinx.coroutines.delay(500)
                waited += 500
            }
            while (true) {
                val nowMs = System.currentTimeMillis()
                val mine = sportLeagues
                // Your leagues, plus the usual ones too unless you've said only yours.
                val leagues = (app.embertv.data.LEAGUES.filter { it.code in mine } +
                    (if (sportOthers) app.embertv.data.POPULAR_LEAGUES else emptyList())).distinctBy { it.code }
                val matches = try {
                    ScoresClient.today(leagues)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }.filter {
                    it.state == MatchState.LIVE || (it.state == MatchState.UPCOMING && it.kickoff - nowMs in 0..15 * 60_000L)
                }.sortedWith(compareBy({ if (it.league.code in mine) 0 else 1 }, { if (it.state == MatchState.LIVE) 0 else 1 }, { it.kickoff }))
                    .take(8)
                // Every card straight away, with channels filled in as each search finishes.
                val out = ArrayList<LiveSport>()
                val start = matches.map { m ->
                    val cached = sportHits[m.id]
                    if (cached != null && nowMs - cached.first < 10 * 60_000L) LiveSport(m, cached.second) else LiveSport(m, emptyList(), searching = true)
                }
                liveSport = sortSport(start)
                for (m in matches) {
                    val cached = sportHits[m.id]
                    val hits = if (cached != null && nowMs - cached.first < 10 * 60_000L) cached.second else {
                        val found = try {
                            // Home: channel names, plus the guides of just a handful of channels.
                            app.embertv.data.MatchFinder.find(r, m, nowSeconds(), guideBudget = 10) { _, _, _ -> }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList()
                        }.filter { it.score >= 8 }.take(30)
                        sportHits[m.id] = nowMs to found
                        found
                    }
                    out += LiveSport(m, hits)
                    // Show what's ready so far rather than waiting for every search.
                    liveSport = sortSport(out + start.filter { s -> out.none { it.match.id == s.match.id } })
                }
                liveSport = sortSport(out)
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    // Your leagues first, then what's live, then soonest; the order doesn't jump about as channels are found.
    private fun sortSport(list: List<LiveSport>): List<LiveSport> =
        list.sortedWith(
            compareBy(
                { if (it.match.league.code in sportLeagues) 0 else 1 },
                { if (it.match.state == MatchState.LIVE) 0 else 1 },
                { it.match.kickoff },
            )
        )

    /** A match whose score sits in the corner of the screen. */
    var pinnedMatch by mutableStateOf<Match?>(null)
        private set
    /** When the pinned score last changed (ms), to flash it. */
    var pinnedChangedAt by mutableStateOf(0L)
        private set
    private var pinJob: kotlinx.coroutines.Job? = null

    fun pinMatch(m: Match) {
        pinJob?.cancel()
        pinnedMatch = m
        pinnedChangedAt = 0L
        notify("${m.home.shortName} v ${m.away.shortName} pinned to the corner")
        pinJob = viewModelScope.launch {
            var finishedAt = 0L
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val fresh = try {
                    ScoresClient.matches(m.league).firstOrNull { it.id == m.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue
                val before = pinnedMatch ?: break
                if (fresh.home.score != before.home.score || fresh.away.score != before.away.score) {
                    pinnedChangedAt = System.currentTimeMillis()
                }
                pinnedMatch = fresh
                if (fresh.state == MatchState.FINISHED) {
                    if (finishedAt == 0L) finishedAt = System.currentTimeMillis()
                    // Leave the final score up for a few minutes.
                    if (System.currentTimeMillis() - finishedAt > 4 * 60_000L) break
                }
            }
            pinnedMatch = null
        }
    }

    fun unpinMatch() {
        pinJob?.cancel()
        pinJob = null
        pinnedMatch = null
    }

    // ---- Followed teams ----

    fun isFollowing(team: Team): Boolean = followedTeams.any { it.id == team.id }

    fun toggleFollow(team: Team) {
        val i = followedTeams.indexOfFirst { it.id == team.id }
        if (i >= 0) followedTeams.removeAt(i) else followedTeams.add(team)
        prefs.followedTeams = followedTeams.toList()
        startTeamWatcher()
    }

    fun dismissTeamAlert(alert: TeamAlert) {
        if (teamAlert == alert) teamAlert = null
    }

    /**
     * While the app is open, keeps an eye on the followed teams: their fixtures for the next three
     * weeks (for Home), and today's matches every 45 seconds while one is on, announcing kick-offs,
     * goals and full time.
     */
    private fun startTeamWatcher() {
        teamWatcher?.cancel()
        teamMatches = emptyList()
        if (followedTeams.isEmpty()) return
        teamWatcher = viewModelScope.launch {
            val seen = HashMap<String, Match>()
            var fixtures = emptyList<Match>()
            var fixturesAt = 0L
            while (true) {
                val ids = followedTeams.map { it.id }.toSet()
                fun ours(m: Match) = m.home.id in ids || m.away.id in ids
                val leagues = followedTeams.flatMap { t ->
                    listOfNotNull(app.embertv.data.LEAGUES.firstOrNull { it.code == t.league }) + cupsFor(t.league)
                }.distinctBy { it.code }
                val nowMs = System.currentTimeMillis()
                if (nowMs - fixturesAt > 30 * 60_000L || fixtures.isEmpty() && nowMs - fixturesAt > 5 * 60_000L) {
                    fixtures = try {
                        ScoresClient.upcoming(leagues, 21).filter { ours(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fixtures
                    }
                    fixturesAt = nowMs
                }
                // Today's scoreboards, only for competitions where one of ours plays today
                // (all of their competitions if the fixture list couldn't be fetched).
                val todayLeagues = if (fixtures.isEmpty()) leagues else fixtures
                    .filter { it.state == MatchState.LIVE || startOfDayMs(it.kickoff) == startOfDayMs(nowMs) }
                    .map { it.league }
                    .distinctBy { it.code }
                val today = if (todayLeagues.isEmpty()) emptyList() else try {
                    ScoresClient.today(todayLeagues).filter { ours(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                for (m in today) {
                    val before = seen[m.id]
                    if (before != null) announce(before, m, ids)
                    seen[m.id] = m
                }
                val merged = (today + fixtures.filter { f -> today.none { it.id == f.id } })
                    .filter { it.state != MatchState.FINISHED || nowMs - it.kickoff < 4 * 3600_000L }
                    .sortedWith(compareBy<Match>({ if (it.state == MatchState.LIVE) 0 else 1 }, { it.kickoff }))
                teamMatches = merged
                val busy = today.any {
                    it.state == MatchState.LIVE || (it.state == MatchState.UPCOMING && it.kickoff - nowMs < 20 * 60_000L)
                }
                kotlinx.coroutines.delay(if (busy) 45_000L else 5 * 60_000L)
            }
        }
    }

    private fun startOfDayMs(ms: Long): Long = startOfDay(ms / 1000)

    private fun announce(before: Match, now: Match, ids: Set<String>) {
        val hs = now.home.score.toIntOrNull() ?: 0
        val as_ = now.away.score.toIntOrNull() ?: 0
        val hb = before.home.score.toIntOrNull() ?: 0
        val ab = before.away.score.toIntOrNull() ?: 0
        val alert = when {
            before.state == MatchState.UPCOMING && now.state == MatchState.LIVE -> TeamAlert(TeamAlert.Kind.KICK_OFF, now, null)
            hs > hb -> TeamAlert(TeamAlert.Kind.GOAL, now, if (now.home.id in ids) true else if (now.away.id in ids) false else null)
            as_ > ab -> TeamAlert(TeamAlert.Kind.GOAL, now, if (now.away.id in ids) true else if (now.home.id in ids) false else null)
            before.state == MatchState.LIVE && now.state == MatchState.FINISHED -> TeamAlert(TeamAlert.Kind.FULL_TIME, now, null)
            else -> null
        }
        if (alert != null) teamAlert = alert
    }

    /** Plays one of the channels "Where's it on?" found, with the others a channel-change away. */
    fun playFound(hits: List<MatchHit>, index: Int) {
        findingMatch = null
        val channels = hits.map { it.channel }
        if (index !in channels.indices) return
        val top = stack.last()
        if (top is Screen.PlayLive || top is Screen.PlayVod) pop()
        push(Screen.PlayLive(channels, index))
    }

    // ---- Reminders ----

    fun hasReminder(streamId: Int, start: Long): Boolean = reminders.any { it.streamId == streamId && it.start == start }

    fun toggleReminder(channel: Channel, p: Programme) {
        val existing = reminders.indexOfFirst { it.streamId == channel.streamId && it.start == p.start }
        if (existing >= 0) {
            reminders.removeAt(existing)
            notify("Reminder removed")
        } else {
            reminders.add(
                Reminder(channel.streamId, channel.name, channel.icon, channel.categoryId, channel.num, p.title, p.start, p.end)
            )
            reminders.sortBy { it.start }
            notify("We'll remind you at ${formatClock(p.start)} while Streammania is open")
        }
        prefs.reminders = reminders.toList()
    }

    /** Called every few seconds: pops up the next reminder whose programme is about to start. */
    fun checkReminders(now: Long) {
        if (dueReminder != null || repo == null || pickingProfile) return
        val expired = reminders.filter { it.end <= now }
        val due = reminders.firstOrNull { it.start - 60 <= now && it.end > now }
        if (expired.isEmpty() && due == null) return
        reminders.removeAll(expired.toSet())
        if (due != null) {
            reminders.remove(due)
            dueReminder = due
        }
        prefs.reminders = reminders.toList()
    }

    fun dismissReminder() {
        dueReminder = null
    }

    /** Switches to the reminder's channel, within its category so up/down still change channel. */
    fun watchReminder(rem: Reminder) {
        dueReminder = null
        val r = repo ?: return
        viewModelScope.launch {
            val list = try {
                r.channels(rem.categoryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            val top = stack.last()
            if (top is Screen.PlayLive || top is Screen.PlayVod) pop()
            val i = list.indexOfFirst { it.streamId == rem.streamId }
            if (i >= 0) push(Screen.PlayLive(list, i)) else push(Screen.PlayLive(listOf(rem.toChannel()), 0))
        }
    }

    // ---- Continue Watching ----

    private fun updateHistory(item: VodItem, positionMs: Long, durationMs: Long) {
        if (item.contentId == 0) return
        val finished = durationMs > 0 && positionMs.toDouble() > durationMs.toDouble() * 0.95
        val isEpisode = item.seriesId != null
        if (finished) {
            markFinished(item)
            return
        }
        if (positionMs < 60_000L) return
        val entry = HistoryEntry(
            key = item.key,
            type = if (isEpisode) ContentType.SERIES else ContentType.MOVIE,
            id = item.contentId,
            seriesId = item.seriesId ?: 0,
            title = item.title,
            subtitle = item.subtitle,
            image = item.image,
            seriesCover = item.seriesCover,
            ext = item.ext,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
        )
        // One entry per movie, and one per series (the latest episode).
        history.removeAll { it.key == entry.key || (isEpisode && it.seriesId == entry.seriesId && it.type == ContentType.SERIES) }
        history.add(0, entry)
        while (history.size > 30) history.removeAt(history.lastIndex)
        prefs.history = history.toList()
    }

    fun removeHistory(key: String, notifyUser: Boolean = true) {
        if (history.removeAll { it.key == key }) {
            prefs.history = history.toList()
            if (notifyUser) notify("Removed from Continue Watching")
        }
    }

    /** Carries on from where you stopped. Episodes reload the season so the next one plays after. */
    fun resume(entry: HistoryEntry) {
        val r = repo ?: return
        if (entry.type == ContentType.MOVIE) {
            // Carry on in the same version that was being watched.
            playMovieVersion(Movie(entry.id, entry.title, entry.seriesCover, "", "", entry.ext.ifBlank { "mp4" }, 0L), prefs.position(entry.key))
            return
        }
        val series = Series(entry.seriesId, entry.title, entry.seriesCover, "", "", "", "", "")
        viewModelScope.launch {
            try {
                val info = r.seriesInfo(entry.seriesId)
                val season = info.episodes.values.firstOrNull { eps -> eps.any { it.id == entry.id } }
                if (season != null) {
                    playEpisodesIn(series, season, season.indexOfFirst { it.id == entry.id }, fromStart = false, fallbackKey = null)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through and play just this episode.
            }
            val item = VodItem(
                key = entry.key,
                title = entry.title,
                subtitle = entry.subtitle,
                url = r.client.episodeUrl(entry.id, entry.ext.ifBlank { "mp4" }),
                seriesId = entry.seriesId,
                episodeId = entry.id,
                contentId = entry.id,
                image = entry.image,
                seriesCover = entry.seriesCover,
                ext = entry.ext,
            )
            push(Screen.PlayVod(listOf(item), 0, prefs.position(entry.key)))
        }
    }

    // Runs last, so everything above is set up before the saved profile and sign-in are restored.
    init {
        profiles.addAll(prefs.profiles)
        if (profiles.isEmpty()) {
            profiles.add(Profile(MAIN_PROFILE, "Me", 0))
            prefs.profiles = profiles.toList()
        }
        val last = prefs.lastProfile
        val start = profiles.firstOrNull { it.id == last } ?: profiles.first()
        activate(start)
        pickingProfile = profiles.size > 1 || start.locked
        val saved = prefs.account
        if (saved != null) startSession(saved, null)
        // Check for new films and series now and then while the app is on screen.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15 * 60_000L)
                if (appVisible) updateFilmsAndSeriesIfDue()
            }
        }
    }
}

/** Names for the picture zoom modes, in order. */
val PICTURE_MODES = listOf("Fit", "Zoom", "Stretch")

/** How often films and series are checked for new additions. */
private const val VOD_UPDATE_EVERY_MS = 3 * 60 * 60_000L

/** Home's rows that can be moved or turned off, with their names, in their usual order. */
val HOME_ROWS = listOf(
    "sport" to "Live Sport Now",
    "teams" to "Your Teams",
    "continue" to "Up Next",
    "new-episodes" to "New Episodes",
    "recently-added" to "Recently Added",
    "because" to "Because You Watched",
    "recent-channels" to "Recently Watched channels",
    "fav-channels" to "Your Channels",
    "mylist" to "My List",
    "live" to "Live channels",
    "movies" to "Film categories",
    "series" to "Series categories",
)

/** The longest Play waits to find a film's other qualities before playing anyway. */
private const val VERSION_CHECK_MS = 4_000L
