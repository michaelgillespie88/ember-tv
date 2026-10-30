package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.embertv.data.Profile

/** Avatar colours: each is a top-left to bottom-right gradient. */
private val PALETTE = listOf(
    Color(0xFFFFB067) to Color(0xFFE0442A),
    Color(0xFFFF8A9A) to Color(0xFFD7265B),
    Color(0xFFD69CFF) to Color(0xFF7B2CBF),
    Color(0xFF8FA6FF) to Color(0xFF3546C9),
    Color(0xFF7FD3FF) to Color(0xFF0A74C9),
    Color(0xFF7FF0D8) to Color(0xFF0F8F7E),
    Color(0xFFB7F07F) to Color(0xFF3E9A2A),
    Color(0xFFFFE27F) to Color(0xFFD89A0A),
)

private fun paletteBrush(index: Int): Brush {
    val (a, b) = PALETTE[((index % PALETTE.size) + PALETTE.size) % PALETTE.size]
    return Brush.linearGradient(listOf(a, b))
}

@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(paletteBrush(profile.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.trim().firstOrNull()?.uppercase() ?: "?",
            style = Txt.Hero.copy(fontSize = (size.value * 0.44f).sp, lineHeight = (size.value * 0.5f).sp),
            color = Color.White,
        )
    }
}

/** Small avatar for the top bars; opens "Who's watching?". */
@Composable
fun ProfileButton(vm: AppViewModel, modifier: Modifier = Modifier) {
    val p = vm.profile ?: return
    if (LocalIsTv.current) {
        FocusBox(
            modifier = modifier.size(38.dp),
            focusKey = "top:profile",
            shape = CircleShape,
            focusedScale = 1.15f,
            color = Color.Transparent,
            focusedColor = Color.Transparent,
            onClick = { vm.switchProfile() },
        ) {
            ProfileAvatar(p, 38.dp)
        }
    } else {
        Box(
            modifier.size(44.dp).clip(CircleShape).clickable { vm.switchProfile() },
            contentAlignment = Alignment.Center,
        ) {
            ProfileAvatar(p, 30.dp)
        }
    }
}

// ---------------- Who's watching? ----------------

private sealed interface Editing {
    data object New : Editing
    data class Existing(val profile: Profile) : Editing
}

@Composable
fun ProfilePicker(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }

    BackHandler(enabled = editMode || vm.pickerCancelable) {
        if (editMode) editMode = false else vm.closeProfilePicker()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1E1410), Ember.Bg), radius = 1600f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (isTv) 56.dp else 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Wordmark(large = !isTv)
            Spacer(Modifier.height(if (isTv) 36.dp else 40.dp))
            Text(
                if (editMode) "Edit profiles" else "Who's watching?",
                style = if (isTv) Txt.Hero else Txt.LargeTitle,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (isTv) 40.dp else 32.dp))

            val avatarSize = if (isTv) 128.dp else 84.dp
            val perRow = if (isTv) 5 else 3
            val tiles: List<Profile?> = vm.profiles.toList() + (if (vm.profiles.size < 8) listOf(null) else emptyList())
            for (row in tiles.chunked(perRow)) {
                Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 28.dp else 14.dp)) {
                    for (p in row) {
                        if (p == null) {
                            ProfileTile(
                                name = "Add profile",
                                avatarSize = avatarSize,
                                focusKey = "profile:add",
                                onClick = { editing = Editing.New },
                            ) {
                                Box(
                                    Modifier.fillMaxSize().glassSurface(CircleShape, Color(0x14FFFFFF)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("+", style = Txt.Hero.copy(fontSize = (avatarSize.value * 0.4f).sp), color = Ember.Dim)
                                }
                            }
                        } else {
                            ProfileTile(
                                name = p.name,
                                avatarSize = avatarSize,
                                focusKey = "profile:${p.id}",
                                autoFocus = isTv && !editMode && p.id == vm.profile?.id,
                                current = p.id == vm.profile?.id && vm.pickerCancelable,
                                onClick = { if (editMode) editing = Editing.Existing(p) else vm.chooseProfile(p) },
                            ) {
                                ProfileAvatar(p, avatarSize)
                                if (editMode) {
                                    Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                                        Text("Edit", style = Txt.Headline, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(if (isTv) 28.dp else 22.dp))
            }

            Spacer(Modifier.height(12.dp))
            ActionButton(if (editMode) "Done" else "Edit profiles", primary = false) { editMode = !editMode }
        }
    }

    when (val e = editing) {
        null -> Unit
        Editing.New -> ProfileEditor(
            vm = vm,
            initial = null,
            onDone = { editing = null },
        )
        is Editing.Existing -> ProfileEditor(
            vm = vm,
            initial = e.profile,
            onDone = { editing = null },
        )
    }
}

@Composable
private fun ProfileTile(
    name: String,
    avatarSize: Dp,
    focusKey: String,
    autoFocus: Boolean = false,
    current: Boolean = false,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(avatarSize + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusBox(
            modifier = Modifier.size(avatarSize),
            focusKey = focusKey,
            shape = CircleShape,
            focusedScale = 1.1f,
            color = Color.Transparent,
            focusedColor = Color.Transparent,
            autoFocus = autoFocus,
            onFocusChange = { focused = it },
            onClick = onClick,
        ) {
            avatar()
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name,
            style = Txt.Label.copy(fontWeight = if (focused || current) FontWeight.SemiBold else FontWeight.Medium),
            color = if (focused || current || !LocalIsTv.current) Ember.Text else Ember.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A TV button on TVs, a pill button on phones. */
@Composable
private fun ActionButton(text: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (LocalIsTv.current) {
        TvButton(text, modifier = modifier, onClick = onClick)
    } else {
        PillButton(text, modifier = modifier, primary = primary, onClick = onClick)
    }
}

// ---------------- Add / edit ----------------

@Composable
private fun ProfileEditor(vm: AppViewModel, initial: Profile?, onDone: () -> Unit) {
    val isTv = LocalIsTv.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableIntStateOf(initial?.color ?: (vm.profiles.size % PALETTE.size)) }
    var typing by remember { mutableStateOf(initial == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val preview = Profile(initial?.id ?: "new", name.ifBlank { "?" }, color)

    fun save() {
        if (initial == null) {
            val p = vm.addProfile(name, color)
            vm.notify("Added ${p.name}")
        } else {
            vm.updateProfile(initial.copy(name = name, color = color))
        }
        onDone()
    }

    Dialog(onDismissRequest = onDone) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 30.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (initial == null) "New profile" else "Edit profile", style = Txt.Title)
            Spacer(Modifier.height(18.dp))
            ProfileAvatar(preview, if (isTv) 96.dp else 84.dp)
            Spacer(Modifier.height(18.dp))
            EntryRow(
                label = "Name",
                value = name,
                placeholder = "Type a name",
                focusKey = "profile:name",
                onClick = { typing = true },
            )
            Text("Colour", style = Txt.Caption, color = Ember.Dim, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(if (isTv) 12.dp else 6.dp)) {
                PALETTE.indices.forEach { i ->
                    val selected = i == color
                    FocusBox(
                        modifier = Modifier.size(if (isTv) 40.dp else 28.dp),
                        focusKey = "profile:color:$i",
                        shape = CircleShape,
                        focusedScale = 1.15f,
                        color = Color.Transparent,
                        focusedColor = Color.Transparent,
                        onClick = { color = i },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(paletteBrush(i))
                                .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (isTv) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvButton("Save", icon = "✓", onClick = { save() })
                    if (initial != null && vm.profiles.size > 1) TvButton("Delete", onClick = { confirmDelete = true })
                    TvButton("Cancel", onClick = onDone)
                }
            } else {
                PillButton("Save", primary = true, modifier = Modifier.fillMaxWidth()) { save() }
                if (initial != null && vm.profiles.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    PillButton("Delete profile", modifier = Modifier.fillMaxWidth()) { confirmDelete = true }
                }
                Spacer(Modifier.height(10.dp))
                PillButton("Cancel", modifier = Modifier.fillMaxWidth(), onClick = onDone)
            }
        }
    }

    if (typing) {
        TextEntryDialog(
            title = "Profile name",
            value = name,
            onValueChange = { name = it.take(20) },
            placeholder = "Type a name",
            onDone = { typing = false },
        )
    }

    if (confirmDelete && initial != null) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            DialogBackdropBlur()
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(dialogPanelColor())
                    .padding(if (isTv) 30.dp else 22.dp)
            ) {
                Text("Delete ${initial.name}?", style = Txt.Title)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Their favourites, Continue Watching and resume points will be removed from this device.",
                    style = Txt.Body,
                    color = Ember.Dim,
                )
                Spacer(Modifier.height(22.dp))
                if (isTv) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvButton("Cancel", autoFocus = true, onClick = { confirmDelete = false })
                        TvButton("Delete", onClick = {
                            confirmDelete = false
                            vm.deleteProfile(initial)
                            onDone()
                        })
                    }
                } else {
                    PillButton("Delete", primary = true, modifier = Modifier.fillMaxWidth()) {
                        confirmDelete = false
                        vm.deleteProfile(initial)
                        onDone()
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton("Cancel", modifier = Modifier.fillMaxWidth()) { confirmDelete = false }
                }
            }
        }
    }
}
