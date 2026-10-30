package app.embertv.ui

import android.app.Application
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
import app.embertv.data.Channel
import app.embertv.data.ContentType
import app.embertv.data.DEFAULT_USER_AGENT
import app.embertv.data.Episode
import app.embertv.data.Favourite
import app.embertv.data.HistoryEntry
import app.embertv.data.MAIN_PROFILE
import app.embertv.data.Profile
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

    val favourites = mutableStateListOf<Favourite>()
    val favKeys: Set<String> by derivedStateOf<Set<String>> { favourites.mapTo(HashSet()) { it.key } }

    /** Part-watched movies and episodes, most recent first. */
    val history = mutableStateListOf<HistoryEntry>()

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

    init {
        profiles.addAll(prefs.profiles)
        if (profiles.isEmpty()) {
            profiles.add(Profile(MAIN_PROFILE, "Me", 0))
            prefs.profiles = profiles.toList()
        }
        val last = prefs.lastProfile
        activate(profiles.firstOrNull { it.id == last } ?: profiles.first())
        pickingProfile = profiles.size > 1
        val saved = prefs.account
        if (saved != null) startSession(saved, null)
    }

    // ---- Session ----

    private fun startSession(acc: Account, info: AccountInfo?) {
        account = acc
        repo = Repository(XtreamClient(acc, userAgent))
        accountInfo = info
        if (info == null) refreshAccountInfo()
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
        home = SectionState()
        live = SectionState()
        guide = SectionState()
        movies = SectionState()
        series = SectionState()
        searchQuery = ""
        tab = Tabs.HOME
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

    fun addProfile(name: String, color: Int): Profile {
        val id = "p" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val p = Profile(id, name.trim().ifEmpty { "Profile ${profiles.size + 1}" }, color)
        profiles.add(p)
        prefs.profiles = profiles.toList()
        return p
    }

    fun updateProfile(p: Profile) {
        val i = profiles.indexOfFirst { it.id == p.id }
        if (i < 0) return
        val fixed = p.copy(name = p.name.trim().ifEmpty { profiles[i].name })
        profiles[i] = fixed
        prefs.profiles = profiles.toList()
        if (profile?.id == fixed.id) profile = fixed
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
        reloadToken++
    }

    // ---- Navigation ----

    fun push(screen: Screen) {
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

    fun toggleInvertZap() {
        invertZap = !invertZap
        prefs.invertZap = invertZap
    }

    // ---- Playback ----

    fun liveUrl(c: Channel, alternate: Boolean): String {
        val format = if (alternate) (if (streamFormat == "m3u8") "ts" else "m3u8") else streamFormat
        return repo?.client?.liveUrl(c.streamId, format) ?: ""
    }

    fun playMovie(m: Movie, fromStart: Boolean) {
        val r = repo ?: return
        val key = "movie:${m.streamId}"
        val start = if (fromStart) 0L else prefs.position(key)
        val art = r.peekMovieInfo(m.streamId)?.backdrop?.takeIf { it.isNotBlank() } ?: m.poster
        val item = VodItem(
            key = key,
            title = m.name,
            subtitle = "",
            url = r.client.movieUrl(m.streamId, m.ext),
            contentId = m.streamId,
            image = art,
            seriesCover = m.poster,
            ext = m.ext,
        )
        push(Screen.PlayVod(listOf(item), 0, start))
    }

    fun playEpisodes(s: Series, episodes: List<Episode>, index: Int, fromStart: Boolean) {
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
        val start = if (fromStart) 0L else prefs.position(items[index].key)
        push(Screen.PlayVod(items, index, start))
    }

    fun saveProgress(item: VodItem, positionMs: Long, durationMs: Long) {
        prefs.savePosition(item.key, positionMs, durationMs)
        val sid = item.seriesId
        val eid = item.episodeId
        if (sid != null && eid != null) prefs.setLastEpisode(sid, eid)
        updateHistory(item, positionMs, durationMs)
    }

    // ---- Continue Watching ----

    private fun updateHistory(item: VodItem, positionMs: Long, durationMs: Long) {
        if (item.contentId == 0) return
        val finished = durationMs > 0 && positionMs.toDouble() > durationMs.toDouble() * 0.95
        val isEpisode = item.seriesId != null
        if (finished) {
            removeHistory(item.key, notifyUser = false)
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
            playMovie(Movie(entry.id, entry.title, entry.seriesCover, "", "", entry.ext.ifBlank { "mp4" }, 0L), fromStart = false)
            return
        }
        val series = Series(entry.seriesId, entry.title, entry.seriesCover, "", "", "", "", "")
        viewModelScope.launch {
            try {
                val info = r.seriesInfo(entry.seriesId)
                val season = info.episodes.values.firstOrNull { eps -> eps.any { it.id == entry.id } }
                if (season != null) {
                    playEpisodes(series, season, season.indexOfFirst { it.id == entry.id }, fromStart = false)
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
}
