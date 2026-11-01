package app.embertv.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import app.embertv.data.Category

// ---------------- Frosted glass ----------------
//
// The content behind the glass is recorded into a graphics layer (backdropSource). Each glass
// surface (frosted) redraws the part of that layer it sits over, blurred, then lays a tint and a
// soft highlight on top. Real blur needs Android 12+; older phones get a stronger plain tint.

private val blurSupported = Build.VERSION.SDK_INT >= 31

class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var frame by mutableIntStateOf(0)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks the content that glass surfaces should show through. Glass must sit outside this. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        val layer = backdrop.layer
        if (!blurSupported || layer.isReleased) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        // Let the glass know the content changed (scrolled, loaded…) so it redraws too.
        backdrop.frame++
    }

/**
 * Frosted glass: blurred content from [backdrop], a [tint] and a faint top highlight.
 * With no backdrop (or before Android 12) it falls back to a solid translucent fill.
 */
fun Modifier.frosted(
    backdrop: Backdrop?,
    shape: Shape,
    tint: Color = Color(0x8C101014),
    blurRadius: Dp = 28.dp,
    edge: Boolean = true,
): Modifier = composed {
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    val canBlur = backdrop != null && blurSupported
    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (canBlur && backdrop != null) {
                @Suppress("UNUSED_VARIABLE") val tick = backdrop.frame
                val source = backdrop.layer
                if (!source.isReleased && !blurLayer.isReleased) {
                    val radius = blurRadius.toPx()
                    blurLayer.renderEffect = BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Clamp)
                    blurLayer.record {
                        drawRect(Ember.Bg)
                        translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) {
                            drawLayer(source)
                        }
                    }
                    drawLayer(blurLayer)
                }
                drawRect(tint)
            } else {
                drawRect(tint.copy(alpha = (tint.alpha + 0.35f).coerceAtMost(0.94f)))
            }
            // A soft sheen along the top edge, like light catching glass.
            drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent), endY = size.height * 0.6f))
        }
        .then(if (edge) Modifier.border(0.6.dp, Color(0x29FFFFFF), shape) else Modifier)
}

/**
 * The glass look for controls that sit on top of content (buttons, chips, player controls):
 * translucent fill, hairline edge and sheen. No blur, so it works anywhere, including over video.
 */
fun Modifier.glassSurface(shape: Shape, fill: Color = Color(0x1FFFFFFF)): Modifier = this
    .clip(shape)
    .background(fill)
    .background(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color.Transparent)))
    .border(0.6.dp, Color(0x33FFFFFF), shape)

// ---------------- Dialogs ----------------

/**
 * Panel colour for pop-ups. Phones get a frosted sheet (the screen behind is blurred). TVs get no
 * panel at all: like tvOS, the screen dims right down and the pop-up floats in the middle.
 */
@Composable
fun dialogPanelColor(): Color = if (LocalIsTv.current) Color.Transparent else Color(0xF21C1C21)

/**
 * Sets up the window behind a pop-up. Call inside a Dialog.
 * TVs: the screen dims almost to black, as on Apple TV. Phones (Android 12+): it blurs.
 */
@Composable
fun DialogBackdropBlur() {
    val isTv = LocalIsTv.current
    if (!isTv && !blurSupported) return
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        if (isTv) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.93f)
            return@SideEffect
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val attrs = window.attributes
        attrs.blurBehindRadius = 60
        window.attributes = attrs
        window.setDimAmount(0.3f)
    }
}

// ---------------- Apple TV–style pop-up pieces ----------------

/** A centred heading for a pop-up: small coloured [eyebrow], big [title], dimmer [subtitle]. */
@Composable
fun SheetTitle(title: String, eyebrow: String? = null, subtitle: String? = null, subtitleLines: Int = 4) {
    val isTv = LocalIsTv.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (eyebrow != null) {
            Text(eyebrow, style = Txt.Label, color = Ember.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
        }
        Text(
            title,
            style = if (isTv) Txt.Hero.copy(fontSize = 32.sp, lineHeight = 38.sp) else Txt.Title,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = Txt.Body,
                color = Ember.Dim,
                textAlign = TextAlign.Center,
                maxLines = subtitleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A choice in a pop-up, Apple TV style: a wide rounded bar that turns white and lifts when focused.
 * With no [leading] or [subtitle] the text is centred, like tvOS alert buttons.
 * On phones, [primary] makes it the white button.
 */
@Composable
fun SheetOption(
    title: String,
    focusKey: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    autoFocus: Boolean = false,
    primary: Boolean = false,
    leading: (@Composable (focused: Boolean) -> Unit)? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val white = !isTv && primary
    FocusBox(
        modifier = modifier.fillMaxWidth().heightIn(min = if (isTv) 58.dp else 52.dp),
        focusKey = focusKey,
        shape = RoundedCornerShape(if (isTv) 16.dp else 14.dp),
        focusedScale = 1.05f,
        // Solid on TVs: without blur, see-through buttons would show the screen behind.
        color = if (white) Color.White else if (isTv) Color(0xFF2A2A31) else Color(0x1FFFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        val dark = focused || white
        val centred = leading == null && subtitle == null && trailing == null
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (centred) Arrangement.Center else Arrangement.Start,
        ) {
            if (leading != null) {
                leading(dark)
                Spacer(Modifier.width(14.dp))
            }
            Column(if (centred) Modifier else Modifier.weight(1f), horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
                Text(
                    title,
                    style = Txt.Label.copy(fontSize = if (isTv) 17.sp else 16.sp, fontWeight = FontWeight.SemiBold),
                    color = if (dark) Color.Black else Ember.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = Txt.Caption,
                        color = if (dark) Color(0xFF55555C) else Ember.Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing(dark)
            }
        }
    }
}

// ---------------- Phone layout plumbing ----------------

/** Space taken by the glass header and tab bar, so lists can scroll underneath them. */
val LocalContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

/** Category chips a tab wants shown in the glass header. */
class ChipsSpec(
    val categories: List<Category>,
    val selectedId: String,
    val state: LazyListState,
    val onSelect: (Category) -> Unit,
)

val LocalHeaderChips = compositionLocalOf<MutableState<ChipsSpec?>?> { null }

/** A small grey capitals label over a group of choices, like tvOS settings panels. */
@Composable
fun SheetSection(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp),
        color = Ember.Dim,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}
