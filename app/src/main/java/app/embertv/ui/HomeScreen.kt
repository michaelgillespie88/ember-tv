package app.embertv.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val TABS = listOf("Live TV", "Guide", "Movies", "Series", "Favourites", "Search", "Settings")

@Composable
fun HomeScreen(vm: AppViewModel) {
    val tabRequesters = remember { List(TABS.size) { FocusRequester() } }
    var focusedTab by remember { mutableIntStateOf(-1) }
    val focusManager = LocalFocusManager.current

    // Back from the content jumps up to the tab bar; back from the tab bar leaves the app.
    BackHandler(enabled = focusedTab < 0) {
        runCatching { tabRequesters[vm.tab].requestFocus() }
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

    Column(Modifier.fillMaxSize().background(Ember.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark()
            Spacer(Modifier.width(24.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TABS.forEachIndexed { i, title ->
                    TabItem(
                        title = title,
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
            Spacer(Modifier.width(24.dp))
            Text(formatClock(LocalNow.current), style = Txt.Label, color = Ember.Dim)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.tab) {
                0 -> LiveSection(vm)
                1 -> GuideSection(vm)
                2 -> MoviesSection(vm)
                3 -> SeriesSection(vm)
                4 -> FavouritesSection(vm)
                5 -> SearchSection(vm)
                else -> SettingsSection(vm)
            }
        }
    }
}

@Composable
private fun TabItem(
    title: String,
    selected: Boolean,
    requester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(horizontal = 2.dp)
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
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = Txt.Label,
            color = when {
                focused -> Color.Black
                selected -> Ember.Text
                else -> Ember.Dim
            },
        )
    }
}
