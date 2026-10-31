package app.embertv.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private class TvTab(val title: String, val icon: ImageVector? = null)

// Same order as Tabs: Home first. Search and Settings are icons, as on Apple TV.
private val TABS = listOf(
    TvTab("Home"),
    TvTab("Live TV"),
    TvTab("Guide"),
    TvTab("Movies"),
    TvTab("Series"),
    TvTab("Favourites"),
    TvTab("Search", EmberIcons.Search),
    TvTab("Settings", EmberIcons.Settings),
)

@Composable
fun HomeScreen(vm: AppViewModel) {
    val tabRequesters = remember { List(TABS.size) { FocusRequester() } }
    var focusedTab by remember { mutableIntStateOf(-1) }
    val focusManager = LocalFocusManager.current

    val scope = rememberCoroutineScope()
    // Back from the content jumps up to the tab bar; back from the tab bar leaves the app.
    BackHandler(enabled = focusedTab < 0) {
        if (vm.guideFullScreen) {
            // The full-screen guide hides the tabs; show them before moving up to them.
            vm.guideFullScreen = false
            scope.launch {
                delay(60)
                runCatching { tabRequesters[vm.tab].requestFocus() }
            }
        } else {
            runCatching { tabRequesters[vm.tab].requestFocus() }
        }
    }
    // Back on the tab bar returns to Home before leaving the app.
    BackHandler(enabled = focusedTab >= 0 && vm.tab != Tabs.HOME) {
        runCatching { tabRequesters[Tabs.HOME].requestFocus() }
    }

    LaunchedEffect(Unit) {
        delay(80)
        val pending = vm.restoreFocusKey
        if (pending != null) {
            // Back from a player or detail page: the item we came from re-focuses itself.
            var waited = 0
            while (vm.restoreFocusKey == pending && waited < 1800) {
                delay(100)
                waited += 100
            }
            if (vm.restoreClaimed) return@LaunchedEffect
        }
        runCatching { tabRequesters[vm.tab].requestFocus() }
    }

    val hideBar = vm.tab == Tabs.GUIDE && vm.guideFullScreen && focusedTab < 0
    // The same soft warm glow at the top of every page, like the guide.
    Column(
        Modifier
            .fillMaxSize()
            .background(Ember.Bg)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Ember.Glow, androidx.compose.ui.graphics.Color.Transparent), endY = 620f))
    ) {
        AnimatedVisibility(visible = !hideBar, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            // Kept snug so every tab fits on a TV: when it was too wide the Settings icon got squeezed out.
            Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The tabs float in one frosted glass pill, as on Apple TV.
                Row(
                    Modifier.glassSurface(RoundedCornerShape(26.dp), Color(0x14FFFFFF)).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                TABS.forEachIndexed { i, t ->
                    TabItem(
                        tab = t,
                        selected = vm.tab == i,
                        requester = tabRequesters[i],
                        onFocusChange = { isFocused ->
                            if (isFocused) {
                                focusedTab = i
                                vm.tab = i
                            } else if (focusedTab == i) {
                                focusedTab = -1
                            }
                        },
                        onClick = { focusManager.moveFocus(FocusDirection.Down) },
                    )
                }
                }
            }
            Spacer(Modifier.width(10.dp))
            ProfileButton(vm)
            Spacer(Modifier.width(12.dp))
            Text(formatClock(LocalNow.current), style = Txt.Label, color = Ember.Dim)
        }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.tab) {
                Tabs.HOME -> HomeSection(vm)
                Tabs.LIVE -> LiveSection(vm)
                Tabs.GUIDE -> GuideSection(vm)
                Tabs.MOVIES -> MoviesSection(vm)
                Tabs.SERIES -> SeriesSection(vm)
                Tabs.FAVOURITES -> FavouritesSection(vm)
                Tabs.SEARCH -> SearchSection(vm)
                else -> SettingsSection(vm)
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: TvTab,
    selected: Boolean,
    requester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 1.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color(0x1FFFFFFF)
                    else -> Color.Transparent
                }
            )
            .focusRequester(requester)
            .onFocusChanged { state ->
                if (state.isFocused != focused) {
                    focused = state.isFocused
                    onFocusChange(state.isFocused)
                }
            }
            .tvClickable(onClick = onClick)
            .padding(horizontal = if (tab.icon != null) 10.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            focused -> Color.Black
            selected -> Ember.Text
            else -> Ember.Dim
        }
        if (tab.icon != null) {
            // Search and Settings are thin line icons: drawn bright and a little bigger so they stand out.
            Icon(tab.icon, contentDescription = tab.title, tint = if (focused) Color.Black else Ember.Text, modifier = Modifier.size(22.dp))
        } else {
            Text(tab.title, style = Txt.Label, color = color)
        }
    }
}
