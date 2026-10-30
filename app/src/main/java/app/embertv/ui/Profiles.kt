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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.window.DialogProperties
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

/** A PIN to ask for before [onSuccess]. [check] decides whether the digits are right. */
private class PinAsk(val title: String, val subtitle: String?, val check: (String) -> Boolean, val onSuccess: () -> Unit)

@Composable
fun ProfilePicker(vm: AppViewModel) {
    val isTv = LocalIsTv.current
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var pinAsk by remember { mutableStateOf<PinAsk?>(null) }
    // Profiles whose PIN has been typed since this screen opened.
    val unlocked = remember { mutableStateListOf<String>() }
    var grownUp by remember { mutableStateOf(false) }

    fun isOpen(p: Profile): Boolean =
        !p.locked || p.id in unlocked || (vm.pickerCancelable && p.id == vm.profile?.id)

    /** Runs [action] for the profile's owner, asking for its PIN first if it has one. */
    fun withPin(p: Profile, action: () -> Unit) {
        if (isOpen(p)) {
            action()
        } else {
            pinAsk = PinAsk("Enter ${p.name}'s PIN", null, { vm.checkPin(p, it) }) {
                unlocked.add(p.id)
                action()
            }
        }
    }

    /**
     * Adding and editing profiles is for grown-ups: once a grown-up profile has a PIN, it's needed
     * here unless you came from a grown-up profile.
     */
    fun asGrownUp(action: () -> Unit) {
        val trusted = grownUp || !vm.parentPinSet || (vm.pickerCancelable && vm.profile?.kids == false)
        if (trusted) {
            action()
        } else {
            pinAsk = PinAsk("Grown-ups only", "Enter the PIN of a grown-up's profile", { pin ->
                val owner = vm.parentForPin(pin)
                if (owner != null) unlocked.add(owner.id)
                owner != null
            }) {
                grownUp = true
                action()
            }
        }
    }

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
                                onClick = { asGrownUp { editing = Editing.New } },
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
                                locked = p.locked,
                                kids = p.kids,
                                onClick = {
                                    if (editMode) {
                                        withPin(p) { editing = Editing.Existing(p) }
                                    } else {
                                        withPin(p) { vm.chooseProfile(p) }
                                    }
                                },
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
            ActionButton(if (editMode) "Done" else "Edit profiles", primary = false) {
                if (editMode) editMode = false else asGrownUp { editMode = true }
            }
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
            initial = vm.profiles.firstOrNull { it.id == e.profile.id } ?: e.profile,
            onDone = { editing = null },
        )
    }

    pinAsk?.let { ask ->
        PinDialog(title = ask.title, subtitle = ask.subtitle, onDismiss = { pinAsk = null }) { pin ->
            val ok = ask.check(pin)
            if (ok) {
                pinAsk = null
                ask.onSuccess()
            }
            ok
        }
    }
}

@Composable
private fun ProfileTile(
    name: String,
    avatarSize: Dp,
    focusKey: String,
    autoFocus: Boolean = false,
    current: Boolean = false,
    locked: Boolean = false,
    kids: Boolean = false,
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
        val color = if (focused || current || !LocalIsTv.current) Ember.Text else Ember.Dim
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = Txt.Label.copy(fontWeight = if (focused || current) FontWeight.SemiBold else FontWeight.Medium),
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (locked) {
                Spacer(Modifier.width(5.dp))
                Icon(EmberIcons.Lock, contentDescription = "PIN locked", tint = Ember.Dim, modifier = Modifier.size(14.dp))
            }
        }
        if (kids) {
            Spacer(Modifier.height(4.dp))
            Text("KIDS", style = Txt.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = Ember.Accent)
        }
    }
}

/** A row in the profile editor: a title and hint on the left, the current value on the right. */
@Composable
private fun OptionRow(title: String, value: String, hint: String, focusKey: String, onClick: () -> Unit) {
    FocusBox(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        color = Ember.Card,
        focusedColor = Color.White,
        onClick = onClick,
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Txt.Label, color = if (focused) Color.Black else Ember.Text)
                Text(hint, style = Txt.Caption, color = if (focused) Color(0xFF55555C) else Ember.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text(value, style = Txt.Label, color = if (focused) Color(0xFF333338) else Ember.Accent)
        }
    }
    Spacer(Modifier.height(8.dp))
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
    var kids by remember { mutableStateOf(initial?.kids ?: false) }
    // null: keep the current PIN; "": remove it; four digits: the new PIN.
    var pin by remember { mutableStateOf<String?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var choosingCategories by remember { mutableStateOf(false) }
    val newPin = pin
    val locked = if (newPin == null) initial?.locked == true else newPin.isNotEmpty()
    val preview = Profile(initial?.id ?: "new", name.ifBlank { "?" }, color)

    fun save() {
        if (initial == null) {
            vm.addProfile(name, color, kids, pin?.takeIf { it.isNotEmpty() })
        } else {
            vm.updateProfile(initial.copy(name = name, color = color, kids = kids), pin)
        }
        onDone()
    }

    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogBackdropBlur()
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(if (isTv) 560.dp else 520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(dialogPanelColor())
                .verticalScroll(rememberScrollState())
                .padding(if (isTv) 26.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isTv) {
                // Wide and short, because TV screens are only about 540dp tall.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(preview, 64.dp)
                    Spacer(Modifier.width(18.dp))
                    Text(if (initial == null) "New profile" else "Edit profile", style = Txt.Title, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Text(if (initial == null) "New profile" else "Edit profile", style = Txt.Title)
                Spacer(Modifier.height(18.dp))
                ProfileAvatar(preview, 84.dp)
                Spacer(Modifier.height(18.dp))
            }
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
            Spacer(Modifier.height(18.dp))
            OptionRow(
                "Kids profile",
                if (kids) "On" else "Off",
                "Only shows the categories you choose",
                focusKey = "profile:kids",
            ) { kids = !kids }
            OptionRow(
                "PIN lock",
                if (locked) "On" else "Off",
                "Ask for a 4-digit PIN to open this profile",
                focusKey = "profile:pin",
            ) { settingPin = true }
            if (kids && initial?.kids == true) {
                OptionRow("Categories", "Choose", "What this profile can watch", focusKey = "profile:cats") {
                    choosingCategories = true
                }
            } else if (kids) {
                Text(
                    "Child-friendly categories are picked for you when you save. You can change them afterwards.",
                    style = Txt.Caption,
                    color = Ember.Dim,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                )
            }
            if (kids && !vm.parentPinSet) {
                Text(
                    "Tip: give the grown-ups' profiles a PIN so kids can't switch into them.",
                    style = Txt.Caption,
                    color = Ember.Accent,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
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
    }

    if (settingPin) {
        SetPinDialog(
            onDismiss = { settingPin = false },
            onTurnOff = if (locked) ({ pin = ""; settingPin = false }) else null,
        ) {
            pin = it
            settingPin = false
        }
    }

    if (choosingCategories && initial != null) {
        CategoryManager(vm, initial) { choosingCategories = false }
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
