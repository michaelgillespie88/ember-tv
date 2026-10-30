package app.embertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * A text box that behaves like any other button on a TV: arrow keys move past it, and OK opens
 * [TextEntryDialog]. Editable text fields are never in the normal focus path, because TV remotes
 * that act as keyboards get trapped inside them.
 */
@Composable
fun EntryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    masked: Boolean = false,
    autoFocus: Boolean = false,
    requester: FocusRequester? = null,
    focusKey: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = Txt.Caption, color = if (focused) Ember.Text else Ember.Dim)
            Spacer(Modifier.height(8.dp))
        }
        FocusBox(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            focusKey = focusKey,
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.03f,
            color = Ember.Card,
            focusedColor = Ember.CardHi,
            autoFocus = autoFocus,
            requester = requester,
            onFocusChange = { focused = it },
            onClick = onClick,
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                val shown = if (masked) "•".repeat(value.length) else value
                Text(
                    shown.ifEmpty { placeholder },
                    style = TextStyle(fontSize = 18.sp),
                    color = if (value.isEmpty()) Ember.Faint else Ember.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Pop-up editor with the keyboard open. Pressing the keyboard's Done / Next / Go key, Enter, or
 * Back all finish editing and keep what was typed, so there is always a way out.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextEntryDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
) {
    val finish by rememberUpdatedState(onDone)
    var finished by remember { mutableStateOf(false) }
    val close = {
        if (!finished) {
            finished = true
            finish()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val requester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { requester.requestFocus() }
            delay(100)
            keyboard?.show()
        }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ember.Panel)
                    .padding(28.dp)
            ) {
                Text(title, style = Txt.Headline)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Ember.Text, fontSize = 20.sp),
                    cursorBrush = SolidColor(Ember.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                    keyboardActions = KeyboardActions(onAny = { close() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .onPreviewKeyEvent { ev ->
                            when (ev.nativeKeyEvent.keyCode) {
                                // Enter, Tab or an arrow press (once the keyboard is hidden) all mean "done".
                                AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                                AndroidKeyEvent.KEYCODE_TAB,
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                    if (ev.type == KeyEventType.KeyUp) close()
                                    true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_CENTER -> {
                                    if (ev.type == KeyEventType.KeyUp) keyboard?.show()
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(shape)
                                .background(Ember.CardHi)
                                .border(2.dp, Ember.Focus, shape)
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, style = TextStyle(color = Ember.Faint, fontSize = 20.sp))
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "When you've finished, press Next or Done on the keyboard, or press Back.",
                    style = Txt.Caption,
                    color = Ember.Faint,
                )
            }
        }
    }
}
