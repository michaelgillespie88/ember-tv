package app.embertv.ui

import androidx.compose.ui.unit.Dp
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.embertv.data.Category
import app.embertv.data.nowSeconds
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

val LocalAppVm = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }
val LocalNow = compositionLocalOf { nowSeconds() }

/** True on TVs and Fire TV; false on phones and tablets, which get the touch layout. */
val LocalIsTv = staticCompositionLocalOf { true }

/** "Hold OK on" with a remote, "Press and hold" on a touch screen. */
@Composable
fun holdPrompt(): String = if (LocalIsTv.current) "Hold OK on" else "Press and hold"

// ---------- Loading helper ----------

sealed interface LoadResult<out T> {
    data object Loading : LoadResult<Nothing>
    data class Ok<T>(val value: T) : LoadResult<T>
    data class Err(val message: String) : LoadResult<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): State<LoadResult<T>> =
    produceState<LoadResult<T>>(LoadResult.Loading, *keys) {
        value = LoadResult.Loading
        value = try {
            LoadResult.Ok(
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Providers sometimes refuse a request for a moment: try once more before giving up.
                    delay(2_000)
                    block()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Err(e.message ?: "Something went wrong.")
        } catch (e: OutOfMemoryError) {
            // A provider list too big for this device: say so rather than closing the app.
            LoadResult.Err("That list is too big for this device to load all at once.")
        }
    }

// ---------- Remote-friendly clicking ----------

private class PressState {
    var down = false
    var longFired = false
}

/** OK/Enter clicks, holding OK long-presses; on touch screens, tap and press-and-hold do the same. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val press = remember { PressState() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val interaction = remember { MutableInteractionSource() }
    Modifier
        .onPreviewKeyEvent { ev ->
            val code = ev.nativeKeyEvent.keyCode
            val isSelect = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                code == AndroidKeyEvent.KEYCODE_ENTER ||
                code == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isSelect) {
                false
            } else if (ev.type == KeyEventType.KeyDown) {
                if (ev.nativeKeyEvent.repeatCount == 0) {
                    press.down = true
                    press.longFired = false
                } else {
                    val handler = longClick
                    if (press.down && !press.longFired && handler != null) {
                        press.longFired = true
                        handler()
                    }
                }
                true
            } else if (ev.type == KeyEventType.KeyUp) {
                val fire = press.down && !press.longFired
                press.down = false
                press.longFired = false
                if (fire) click()
                true
            } else {
                false
            }
        }
        .combinedClickable(
            interactionSource = interaction,
            // TVs show focus with the white outline; phones get the usual touch ripple.
            indication = if (LocalIsTv.current) null else LocalIndication.current,
            onLongClick = if (onLongClick != null) {
                { longClick?.invoke() }
            } else {
                null
            },
            onClick = { click() },
        )
}

// ---------- Focusable surface ----------

@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.06f,
    color: Color = Ember.Card,
    focusedColor: Color = Ember.CardHi,
    autoFocus: Boolean = false,
    requester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val vm = LocalAppVm.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val ownRequester = remember { FocusRequester() }
    val requester = requester ?: ownRequester

    // Phones have no focus highlight to return to, so only TVs restore it.
    val shouldRestore = LocalIsTv.current && focusKey != null && vm.restoreFocusKey == focusKey
    if (shouldRestore) {
        LaunchedEffect(Unit) {
            delay(60)
            runCatching { requester.requestFocus() }
            vm.restoreClaimed = true
            vm.restoreFocusKey = null
        }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            delay(80)
            val pending = vm.restoreFocusKey
            if (pending != null) {
                // Coming back from another screen: let the remembered item take focus first.
                var waited = 0
                while (vm.restoreFocusKey == pending && waited < 1800) {
                    delay(100)
                    waited += 100
                }
                if (vm.restoreClaimed) return@LaunchedEffect
            }
            runCatching { requester.requestFocus() }
        }
    }

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.border(2.5.dp, Ember.Focus, shape) else Modifier)
            .clip(shape)
            .background(if (focused) focusedColor else color)
            // Tinted (see-through) surfaces get Apple TV's glass edge: a soft top highlight and a hairline rim.
            .then(
                if (!focused && color.alpha > 0f && color.alpha < 0.5f) {
                    Modifier
                        .background(Brush.verticalGradient(listOf(Color(0x12FFFFFF), Color.Transparent)))
                        .border(0.6.dp, Color(0x24FFFFFF), shape)
                } else {
                    Modifier
                }
            )
            .focusRequester(requester)
            .onFocusChanged { state ->
                if (state.isFocused != focused) {
                    focused = state.isFocused
                    onFocusChange?.invoke(state.isFocused)
                }
            }
            .tvClickable(
                onClick = {
                    if (focusKey != null) vm.lastFocusKey = focusKey
                    onClick()
                },
                onLongClick = onLongClick,
            ),
    ) {
        content(focused)
    }
}

// ---------- Images ----------

@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (url.isBlank()) return
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).crossfade(180).build()
    }
    AsyncImage(model = request, contentDescription = null, modifier = modifier, contentScale = contentScale)
}

@Composable
fun Placeholder(name: String, modifier: Modifier = Modifier, fontSizeSp: Int = 22) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Ember.CardHi, Ember.Card))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = Ember.Faint,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ChannelLogo(url: String, name: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
        if (url.isBlank()) {
            Text(initials(name), style = Txt.Caption, color = Ember.Dim)
        } else {
            NetImage(url, Modifier.fillMaxSize().padding(4.dp), ContentScale.Fit)
        }
    }
}

// ---------- Cards ----------

@Composable
fun PosterCard(
    title: String,
    image: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    favourite: Boolean = false,
    /** Shown on the poster's corner, e.g. "4K · FHD". */
    badge: String? = null,
    /** A tick in the corner for films already watched. */
    watched: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            focusKey = focusKey,
            focusedScale = 1.08f,
            onFocusChange = {
                focused = it
                if (it) onFocused?.invoke()
            },
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            Placeholder(title, Modifier.fillMaxSize())
            NetImage(image, Modifier.fillMaxSize())
            if (favourite) FavBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (badge != null) PosterBadge(badge, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (watched) WatchedTick(Modifier.align(Alignment.TopStart).padding(7.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = Txt.Label,
            color = if (focused) Ember.Text else Ember.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(subtitle, style = Txt.Caption, color = Ember.Faint, maxLines = 1)
        }
    }
}

@Composable
fun ChannelCard(
    name: String,
    logo: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(210.dp)) {
        FocusBox(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            focusKey = focusKey,
            focusedScale = 1.08f,
            onFocusChange = { focused = it },
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                if (logo.isBlank()) {
                    Text(initials(name), style = Txt.Title, color = Ember.Dim)
                } else {
                    NetImage(logo, Modifier.fillMaxSize(), ContentScale.Fit)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            name,
            style = Txt.Label,
            color = if (focused) Ember.Text else Ember.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FavBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).clip(CircleShape).background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Text("★", color = Ember.Accent, fontSize = 14.sp)
    }
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    focusKey: String? = null,
    autoFocus: Boolean = false,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    FocusBox(
        modifier = modifier.height(50.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(25.dp),
        focusedScale = 1.06f,
        color = Color(0x26FFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
        requester = requester,
        onClick = onClick,
    ) { focused ->
        Row(
            Modifier.fillMaxHeight().padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Text(icon, fontSize = 15.sp, color = if (focused) Color.Black else Ember.Text)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = Txt.Label.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = if (focused) Color.Black else Ember.Text)
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Ember.Accent) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Txt.Headline, color = Ember.Text, modifier = modifier.padding(bottom = 14.dp))
}

// ---------- Category rail ----------

@Composable
fun CategoryRail(
    categories: List<Category>,
    selectedId: String?,
    state: LazyListState,
    keyPrefix: String,
    onFocusCategory: (Category) -> Unit,
    onSelectCategory: (Category) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 232.dp,
    rowHeight: Dp = 46.dp,
    /** Apple TV sidebar look: a softly lit pill for the chosen group instead of a dot. */
    sidebar: Boolean = true,
) {
    LazyColumn(
        modifier = modifier.width(width).fillMaxHeight(),
        state = state,
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(categories, key = { it.id }) { c ->
            val selected = c.id == selectedId
            FocusBox(
                modifier = Modifier.fillMaxWidth().height(rowHeight),
                focusKey = "$keyPrefix:cat:${c.id}",
                shape = RoundedCornerShape(if (sidebar) 12.dp else 10.dp),
                focusedScale = if (sidebar) 1.05f else 1.03f,
                color = if (selected) (if (sidebar) Color(0x29FFFFFF) else Ember.Card) else Color.Transparent,
                focusedColor = Color.White,
                onFocusChange = { if (it) onFocusCategory(c) },
                onClick = { onSelectCategory(c) },
            ) { focused ->
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected && !sidebar) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (focused) Ember.AccentDeep else Ember.Accent)
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        c.name,
                        style = (if (rowHeight < 44.dp) Txt.Label.copy(fontSize = 14.sp) else Txt.Label)
                            .let { if (sidebar && (selected || focused)) it.copy(fontWeight = FontWeight.SemiBold) else it },
                        color = when {
                            focused -> Color.Black
                            selected -> Ember.Text
                            else -> Ember.Dim
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Selecting a category on focus would fire a request for every row scrolled past, so wait a moment. */
@Composable
fun rememberCategorySelector(section: SectionState): (String, Boolean) -> Unit {
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(300)
        section.categoryId = p
    }
    return remember(section) {
        { id: String, immediate: Boolean ->
            if (immediate) {
                pending = null
                section.categoryId = id
            } else {
                pending = id
            }
            Unit
        }
    }
}

// ---------- States ----------

@Composable
fun LoadingView(modifier: Modifier = Modifier, text: String? = null) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(38.dp), color = Ember.Text, strokeWidth = 3.dp)
            if (text != null) {
                Spacer(Modifier.height(16.dp))
                Text(text, style = Txt.Body, color = Ember.Dim)
            }
        }
    }
}

@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Txt.Body, color = Ember.Dim, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorView(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load this", style = Txt.Headline, color = Ember.Text)
            Spacer(Modifier.height(8.dp))
            Text(message, style = Txt.Body, color = Ember.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            TvButton("Try again", icon = "↻", onClick = onRetry)
        }
    }
}
