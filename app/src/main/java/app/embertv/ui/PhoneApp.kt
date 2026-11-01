package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.credentialsFromLink

private const val TAB_HOME = Tabs.HOME
private const val TAB_LIVE = Tabs.LIVE
private const val TAB_GUIDE = Tabs.GUIDE
private const val TAB_MOVIES = Tabs.MOVIES
private const val TAB_SERIES = Tabs.SERIES
private const val TAB_FAVOURITES = Tabs.FAVOURITES
private const val TAB_SEARCH = Tabs.SEARCH
private const val TAB_SETTINGS = Tabs.SETTINGS

private val TAB_TITLES = listOf("Home", "Live TV", "TV Guide", "Movies", "Series", "Favourites", "Search", "Settings")

/** Tabs opened from the header (with a back arrow) rather than the tab bar. */
private val OVERLAY_TABS = setOf(TAB_GUIDE, TAB_SEARCH, TAB_SETTINGS)
private val CHIP_TABS = setOf(TAB_LIVE, TAB_GUIDE, TAB_MOVIES, TAB_SERIES)

private class NavItem(val tab: Int, val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(TAB_HOME, "Home", EmberIcons.HomeFilled),
    NavItem(TAB_LIVE, "Live", EmberIcons.LiveFilled),
    NavItem(TAB_MOVIES, "Movies", EmberIcons.MoviesFilled),
    NavItem(TAB_SERIES, "Series", EmberIcons.SeriesFilled),
    NavItem(TAB_FAVOURITES, "Favourites", EmberIcons.StarFilled),
)



/** Side padding used by screens shared between TV and phone. */
@Composable
fun edgePadding(): Dp = if (LocalIsTv.current) 48.dp else 16.dp

/**
 * Phone home: content fills the screen and scrolls underneath a frosted header (large title,
 * search, settings and category chips) and a floating frosted tab bar.
 */
@Composable
fun PhoneHome(vm: AppViewModel) {
    var returnTab by remember { mutableIntStateOf(TAB_HOME) }
    val tab = vm.tab
    val mainTab = tab !in OVERLAY_TABS
    val hasChips = tab in CHIP_TABS
    val isHome = tab == TAB_HOME

    BackHandler(enabled = tab != TAB_HOME) {
        vm.tab = if (tab in OVERLAY_TABS) returnTab else TAB_HOME
    }

    fun openOverlayTab(target: Int) {
        if (tab !in OVERLAY_TABS) returnTab = tab
        vm.tab = target
    }

    // Sideways: a slimmer header and tab bar, so the guide and rows get the room.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val HEADER_ROW = if (landscape) 50.dp else 62.dp
    val CHIPS_ROW = if (landscape) 46.dp else 52.dp
    val TAB_BAR = if (landscape) 54.dp else 62.dp
    // The Screen direction setting.
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    androidx.compose.runtime.LaunchedEffect(activity, vm.screenDirection) {
        activity?.requestedOrientation = appOrientation(vm.screenDirection)
    }

    val backdrop = rememberBackdrop()
    val chips = remember { mutableStateOf<ChipsSpec?>(null) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = PaddingValues(
        // Home's featured artwork runs up under the header, as in Apple's TV app; other pages start under their title.
        top = if (isHome) 0.dp else statusTop + HEADER_ROW + (if (hasChips) CHIPS_ROW else 0.dp),
        bottom = navBottom + (if (mainTab) TAB_BAR + 20.dp else 12.dp),
    )

    Box(Modifier.fillMaxSize().background(Ember.Bg)) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .background(Ember.Bg)
                .background(Brush.verticalGradient(listOf(Ember.Glow, Color.Transparent), endY = 900f))
        ) {
            CompositionLocalProvider(LocalContentPadding provides contentPadding, LocalHeaderChips provides chips) {
                when (tab) {
                    TAB_HOME -> PhoneHomeTab(vm)
                    TAB_LIVE -> PhoneLiveTab(vm)
                    TAB_GUIDE -> PhoneGuideTab(vm)
                    TAB_MOVIES -> PhoneMoviesTab(vm)
                    TAB_SERIES -> PhoneSeriesTab(vm)
                    TAB_FAVOURITES -> FavouritesSection(vm)
                    TAB_SEARCH -> SearchSection(vm)
                    else -> SettingsSection(vm)
                }
            }
        }

        // Frosted header
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .frosted(backdrop, RectangleShape, tint = if (isHome) Color(0x26000000) else Color(0xB30B0B0E), edge = false)
        ) {
            Spacer(Modifier.height(statusTop))
            Row(
                Modifier.fillMaxWidth().height(HEADER_ROW).padding(start = if (mainTab) 20.dp else 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!mainTab) {
                    IconCircleButton(EmberIcons.Back, "Back", size = 38.dp, background = Color(0x1AFFFFFF), glass = true) { vm.tab = returnTab }
                    Spacer(Modifier.width(2.dp))
                }
                // A big, bold page title, as in Apple's TV app; Home shows the Streammania wordmark, as on the TV.
                if (isHome) Box(Modifier.weight(1f)) { Wordmark(large = true) } else Text(
                    TAB_TITLES[tab.coerceIn(0, TAB_TITLES.lastIndex)],
                    style = Txt.LargeTitle.copy(fontSize = (if (landscape) 24f else if (mainTab) 32f else 26f).times(Txt.scale).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isHome || vm.castTarget != null) {
                    IconCircleButton(
                        if (vm.castTarget != null) EmberIcons.Remote else EmberIcons.Tv,
                        if (vm.castTarget != null) "Playing on ${vm.castTarget?.tv?.name}" else "Play on TV",
                        size = 38.dp,
                        background = Color(0x1AFFFFFF),
                        glass = true,
                        tint = if (vm.castTarget != null) Ember.Accent else Ember.Text,
                    ) { vm.showCastSheet = true }
                }
                if (tab == TAB_LIVE) IconCircleButton(EmberIcons.Guide, "TV guide", size = 38.dp, background = Color(0x1AFFFFFF), glass = true) { openOverlayTab(TAB_GUIDE) }
                if (tab != TAB_SEARCH) IconCircleButton(EmberIcons.Search, "Search", size = 38.dp, background = Color(0x1AFFFFFF), glass = true) { openOverlayTab(TAB_SEARCH) }
                if (tab != TAB_SETTINGS) IconCircleButton(EmberIcons.Settings, "Settings", size = 38.dp, background = Color(0x1AFFFFFF), glass = true) { openOverlayTab(TAB_SETTINGS) }
                ProfileButton(vm)
            }
            if (hasChips) {
                Box(Modifier.fillMaxWidth().height(CHIPS_ROW)) {
                    val spec = chips.value
                    if (spec != null) {
                        CategoryChips(spec)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.6.dp).background(Color(0x1FFFFFFF)))
        }

        // Floating frosted tab bar
        if (mainTab) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 14.dp, end = 14.dp, bottom = if (landscape) 6.dp else 8.dp)
                    .then(if (landscape) Modifier.width(460.dp) else Modifier.fillMaxWidth())
                    .height(TAB_BAR)
                    .frosted(backdrop, RoundedCornerShape(32.dp), tint = Color(0xC716161B))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (item in NAV_ITEMS) {
                    val selected = tab == item.tab
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .then(if (selected) Modifier.background(Color(0x2EFFFFFF)) else Modifier)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { vm.tab = item.tab },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // iOS grey for the tabs you're not on.
                        val color = if (selected) Ember.Text else Color(0xFF8E8E93)
                        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(25.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = color,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------- Small touch components ----------------

@Composable
fun IconCircleButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = Color.Transparent,
    tint: Color = Ember.Text,
    glass: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .then(if (glass) Modifier.glassSurface(CircleShape, background) else Modifier.clip(CircleShape).background(background))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = if (primary) Color.Black else Ember.Text
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .height(48.dp)
            .then(if (primary) Modifier.clip(shape).background(Color.White) else Modifier.glassSurface(shape))
            .pressable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Txt.Label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Category chips shown in the frosted header. */
@Composable
private fun CategoryChips(spec: ChipsSpec) {
    LazyRow(
        state = spec.state,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(spec.categories, key = { it.id }) { c ->
            val selected = c.id == spec.selectedId
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .height(36.dp)
                    .then(if (selected) Modifier.clip(shape).background(Color.White) else Modifier.glassSurface(shape, Color(0x14FFFFFF)))
                    .pressable { spec.onSelect(c) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.name,
                    style = Txt.Label.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (selected) Color.Black else Ember.Text.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------- Sign in ----------------

@Composable
fun PhoneLoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Ember.GlowStrong, Ember.Bg), radius = 1200f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Wordmark(large = true)
            Spacer(Modifier.height(28.dp))
            Text("Sign in to your IPTV service", style = Txt.Title)
            Spacer(Modifier.height(10.dp))
            Text(
                "Enter the Xtream Codes details from your provider. They're saved on this phone only.",
                style = Txt.Body,
                color = Ember.Dim,
            )
            Spacer(Modifier.height(28.dp))
            PhoneTextField(
                label = "Server address",
                value = server,
                onValueChange = { text ->
                    server = text
                    val creds = credentialsFromLink(text)
                    if (creds != null) {
                        if (user.isEmpty()) user = creds.first
                        if (pass.isEmpty()) pass = creds.second
                    }
                },
                placeholder = "http://example.com:8080",
                keyboardType = KeyboardType.Uri,
            )
            PhoneTextField(label = "Username", value = user, onValueChange = { user = it }, keyboardType = KeyboardType.Email)
            PhoneTextField(
                label = "Password",
                value = pass,
                onValueChange = { pass = it },
                password = true,
                imeAction = ImeAction.Done,
                onDone = { vm.login(server, user, pass) },
            )
            val error = vm.loginError
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .glassSurface(RoundedCornerShape(14.dp), Color(0x24FF453A))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(EmberIcons.Alert, contentDescription = null, tint = Ember.Text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(error, style = Txt.Label, color = Ember.Text)
                }
            }
            Spacer(Modifier.height(6.dp))
            PillButton(
                text = if (vm.loggingIn) "Signing in…" else "Sign in",
                primary = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.login(server, user, pass) },
            )
            Spacer(Modifier.height(20.dp))
            Text("Version ${app.embertv.BuildConfig.VERSION_NAME}", style = Txt.Caption, color = Ember.Faint)
        }
    }
}

@Composable
private fun PhoneTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(label, style = Txt.Caption, color = if (focused) Ember.Text else Ember.Dim)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Txt.Body.copy(color = Ember.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(Ember.Text),
            visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .glassSurface(shape, if (focused) Color(0x29FFFFFF) else Color(0x14FFFFFF))
                        .then(if (focused) Modifier.border(1.5.dp, Color(0x80FFFFFF), shape) else Modifier)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = Txt.Body.copy(color = Ember.Faint, fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                    if (password) {
                        Text(
                            if (reveal) "Hide" else "Show",
                            style = Txt.Caption,
                            color = Ember.Dim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .pressable { reveal = !reveal }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
        )
    }
}
