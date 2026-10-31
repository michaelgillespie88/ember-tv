package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.embertv.data.Category
import app.embertv.data.CategoryRules
import app.embertv.data.ContentType
import app.embertv.data.Profile
import app.embertv.data.categoryRuleKey
import app.embertv.data.looksKidsFriendly

private val TYPES = listOf(ContentType.LIVE to "Live TV", ContentType.MOVIE to "Movies", ContentType.SERIES to "Series")

/**
 * Full-screen list of the provider's categories with a tick for each one [profile] can see.
 * Normal profiles untick what they want hidden; kids profiles only see what's ticked.
 * Changes are saved when it closes.
 */
@Composable
fun CategoryManager(vm: AppViewModel, profile: Profile, onDone: () -> Unit) {
    val repo = vm.repo ?: return
    val isTv = LocalIsTv.current
    val allowMode = profile.kids
    val initial = remember(profile.id) { vm.categoryRules(profile).keys }
    var keys by remember(profile.id) { mutableStateOf(initial) }
    var type by remember { mutableStateOf(ContentType.LIVE) }

    fun close() {
        if (keys != initial) vm.setCategoryRules(profile, CategoryRules(allowMode, keys))
        onDone()
    }

    fun visible(t: ContentType, id: String): Boolean {
        val k = categoryRuleKey(t, id)
        return if (allowMode) k in keys else k !in keys
    }

    fun setVisible(t: ContentType, ids: Collection<String>, show: Boolean) {
        val ks = ids.map { categoryRuleKey(t, it) }
        // Allow lists hold what's shown; hide lists hold what's hidden.
        keys = if (allowMode == show) keys + ks else keys - ks.toSet()
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF1E1410), Ember.Bg), radius = 1600f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val load by rememberLoad(repo, type) {
                when (type) {
                    ContentType.LIVE -> repo.rawLiveCategories()
                    ContentType.MOVIE -> repo.rawMovieCategories()
                    ContentType.SERIES -> repo.rawSeriesCategories()
                }
            }
            val cats: List<Category> = (load as? LoadResult.Ok)?.value ?: emptyList()
            val shown = cats.count { visible(type, it.id) }

            Column(Modifier.fillMaxSize().padding(horizontal = if (isTv) 48.dp else 16.dp, vertical = if (isTv) 28.dp else 12.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isTv) {
                        IconCircleButton(EmberIcons.Back, "Done", glass = true, onClick = { close() })
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (allowMode) "${profile.name}'s categories" else "Categories", style = if (isTv) Txt.Title else Txt.Headline)
                        Text(
                            if (allowMode) {
                                "${profile.name} only sees ticked categories. New ones from your provider stay hidden until you tick them."
                            } else {
                                "Untick a category to hide it everywhere on ${profile.name}'s profile, including search."
                            },
                            style = Txt.Caption,
                            color = Ember.Dim,
                        )
                    }
                    if (isTv) {
                        Spacer(Modifier.width(20.dp))
                        TvButton("Done", icon = "✓", onClick = { close() })
                    }
                }
                Spacer(Modifier.height(if (isTv) 18.dp else 14.dp))

                // Live / Movies / Series, then bulk actions
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(TYPES, key = { it.first.name }) { (t, label) ->
                        Chip(label, selected = t == type, focusKey = "cats:type:${t.name}", autoFocus = isTv && t == ContentType.LIVE) { type = t }
                    }
                    item(key = "gap") { Spacer(Modifier.width(if (isTv) 24.dp else 8.dp)) }
                    item(key = "all") {
                        Chip(if (allowMode) "Allow all" else "Show all", selected = false, focusKey = "cats:all") {
                            setVisible(type, cats.map { it.id }, true)
                        }
                    }
                    item(key = "none") {
                        Chip(if (allowMode) "Allow none" else "Hide all", selected = false, focusKey = "cats:none") {
                            setVisible(type, cats.map { it.id }, false)
                        }
                    }
                    if (allowMode) {
                        item(key = "suggested") {
                            Chip("Suggested", selected = false, focusKey = "cats:suggested") {
                                setVisible(type, cats.map { it.id }, false)
                                setVisible(type, cats.filter { looksKidsFriendly(it.name) }.map { it.id }, true)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (load is LoadResult.Ok) "$shown of ${cats.size} ${if (allowMode) "allowed" else "shown"}" else " ",
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
                Spacer(Modifier.height(10.dp))

                when (val r = load) {
                    is LoadResult.Loading -> LoadingView(Modifier.weight(1f))
                    is LoadResult.Err -> ErrorView(r.message, Modifier.weight(1f)) { vm.reload() }
                    is LoadResult.Ok -> if (cats.isEmpty()) {
                        EmptyView("Your provider has no categories here.", Modifier.weight(1f))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (isTv) 3 else 1),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 8.dp),
                        ) {
                            items(cats, key = { it.id }) { c ->
                                val on = visible(type, c.id)
                                CategoryToggle(c.name, on, "cats:${type.name}:${c.id}") { setVisible(type, listOf(c.id), !on) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, focusKey: String, autoFocus: Boolean = false, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.height(38.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(19.dp),
        focusedScale = 1.06f,
        color = if (selected) Color(0x40FFFFFF) else Color(0x14FFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = Txt.Label.copy(fontSize = 14.sp),
                color = when {
                    focused -> Color.Black
                    selected -> Ember.Text
                    else -> Ember.Dim
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CategoryToggle(name: String, on: Boolean, focusKey: String, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = Txt.Label,
                color = when {
                    focused -> Color.Black
                    on -> Ember.Text
                    else -> Ember.Faint
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (on) Ember.Text else Color.Transparent)
                    .then(if (on) Modifier else Modifier.border(1.5.dp, if (focused) Color(0xFF88888F) else Ember.Faint, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(EmberIcons.Check, contentDescription = "Shown", tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}
