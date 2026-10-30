package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.Category
import app.embertv.data.credentialsFromLink

// Tab numbers are shared with the TV layout (see HomeScreen).
private const val TAB_LIVE = 0
private const val TAB_GUIDE = 1
private const val TAB_MOVIES = 2
private const val TAB_SERIES = 3
private const val TAB_FAVOURITES = 4
private const val TAB_SEARCH = 5
private const val TAB_SETTINGS = 6

private class NavItem(val tab: Int, val label: String, val icon: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(TAB_LIVE, "Live", EmberIcons.Live),
    NavItem(TAB_GUIDE, "Guide", EmberIcons.Guide),
    NavItem(TAB_MOVIES, "Movies", EmberIcons.Movies),
    NavItem(TAB_SERIES, "Series", EmberIcons.Series),
    NavItem(TAB_FAVOURITES, "Favourites", EmberIcons.Star),
)

/** Side padding used by screens shared between TV and phone. */
@Composable
fun edgePadding(): Dp = if (LocalIsTv.current) 48.dp else 16.dp

@Composable
fun PhoneHome(vm: AppViewModel) {
    var returnTab by remember { mutableIntStateOf(TAB_LIVE) }
    val tab = vm.tab

    BackHandler(enabled = tab != TAB_LIVE) {
        vm.tab = if (tab == TAB_SEARCH || tab == TAB_SETTINGS) returnTab else TAB_LIVE
    }

    fun openOverlayTab(target: Int) {
        if (tab != TAB_SEARCH && tab != TAB_SETTINGS) returnTab = tab
        vm.tab = target
    }

    val mainTab = tab <= TAB_FAVOURITES
    Column(Modifier.fillMaxSize().background(Ember.Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mainTab) {
                Spacer(Modifier.width(8.dp))
                Wordmark()
            } else {
                IconCircleButton(EmberIcons.Back, "Back") { vm.tab = returnTab }
                Spacer(Modifier.width(4.dp))
                Text(if (tab == TAB_SEARCH) "Search" else "Settings", style = Txt.Headline)
            }
            Spacer(Modifier.weight(1f))
            if (tab != TAB_SEARCH) IconCircleButton(EmberIcons.Search, "Search") { openOverlayTab(TAB_SEARCH) }
            if (tab != TAB_SETTINGS) IconCircleButton(EmberIcons.Settings, "Settings") { openOverlayTab(TAB_SETTINGS) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (mainTab) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        ) {
            when (tab) {
                TAB_LIVE -> PhoneLiveTab(vm)
                TAB_GUIDE -> PhoneGuideTab(vm)
                TAB_MOVIES -> PhoneMoviesTab(vm)
                TAB_SERIES -> PhoneSeriesTab(vm)
                TAB_FAVOURITES -> FavouritesSection(vm)
                TAB_SEARCH -> SearchSection(vm)
                else -> SettingsSection(vm)
            }
        }

        if (mainTab) {
            NavigationBar(containerColor = Ember.Panel, tonalElevation = 0.dp) {
                for (item in NAV_ITEMS) {
                    NavigationBarItem(
                        selected = tab == item.tab,
                        onClick = { vm.tab = item.tab },
                        icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        label = { Text(item.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ember.Text,
                            selectedTextColor = Ember.Text,
                            indicatorColor = Ember.Accent.copy(alpha = 0.28f),
                            unselectedIconColor = Ember.Dim,
                            unselectedTextColor = Ember.Dim,
                        ),
                    )
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
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
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
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(if (primary) Color.White else Color(0x26FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Txt.Label, color = fg, maxLines = 1)
    }
}

@Composable
fun CategoryChips(
    categories: List<Category>,
    selectedId: String,
    state: LazyListState,
    onSelect: (Category) -> Unit,
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it.id }) { c ->
            val selected = c.id == selectedId
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Color.White else Ember.Card)
                    .clickable { onSelect(c) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.name,
                    style = Txt.Label.copy(fontSize = 14.sp),
                    color = if (selected) Color.Black else Ember.Dim,
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
            .background(Brush.radialGradient(listOf(Color(0xFF2A1408), Ember.Bg), radius = 1200f))
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
                Text(error, style = Txt.Body, color = Color(0xFFFF8A80), modifier = Modifier.padding(bottom = 14.dp))
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
            textStyle = TextStyle(color = Ember.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(Ember.Accent),
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
                        .clip(shape)
                        .background(if (focused) Ember.CardHi else Ember.Card)
                        .then(if (focused) Modifier.border(1.5.dp, Ember.Accent, shape) else Modifier)
                        .padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = TextStyle(color = Ember.Faint, fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                .clickable { reveal = !reveal }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            },
        )
    }
}
