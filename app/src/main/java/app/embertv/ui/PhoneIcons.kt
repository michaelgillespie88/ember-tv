package app.embertv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Small line icons drawn in code, so the app needs no icon library. Icon() tints them. */
object EmberIcons {
    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    private fun solid(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.White), pathBuilder = block)
            .build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.star() {
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) 9.5f else 4.2f
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = 12f + radius * cos(angle).toFloat()
            val y = 12.6f + radius * sin(angle).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    val Live = outline("live") {
        moveTo(3f, 7f); lineTo(21f, 7f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(8f, 3f); lineTo(12f, 7f); lineTo(16f, 3f)
    }

    val Guide = outline("guide") {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 18f); lineTo(21f, 18f)
        moveTo(8f, 4f); lineTo(8f, 20f)
    }

    val Movies = outline("movies") {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(8f, 4f); lineTo(8f, 20f)
        moveTo(16f, 4f); lineTo(16f, 20f)
        moveTo(4f, 9f); lineTo(8f, 9f)
        moveTo(4f, 15f); lineTo(8f, 15f)
        moveTo(16f, 9f); lineTo(20f, 9f)
        moveTo(16f, 15f); lineTo(20f, 15f)
    }

    val Series = outline("series") {
        moveTo(3f, 15f); lineTo(3f, 4f); lineTo(17f, 4f)
        moveTo(7f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(7f, 19f); close()
    }

    val Star = outline("star") { star() }
    val StarFilled = solid("star-filled") { star() }

    val Search = outline("search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.5f, 15.5f); lineTo(20.5f, 20.5f)
    }

    val Settings = outline("settings") {
        moveTo(4f, 7f); lineTo(10f, 7f)
        moveTo(16f, 7f); lineTo(20f, 7f)
        circle(13f, 7f, 2.5f)
        moveTo(4f, 17f); lineTo(6f, 17f)
        moveTo(12f, 17f); lineTo(20f, 17f)
        circle(9f, 17f, 2.5f)
    }

    val Back = outline("back") {
        moveTo(20f, 12f); lineTo(5f, 12f)
        moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f)
    }

    val ChannelList = outline("channel-list") {
        moveTo(9f, 6f); lineTo(21f, 6f)
        moveTo(9f, 12f); lineTo(21f, 12f)
        moveTo(9f, 18f); lineTo(21f, 18f)
        moveTo(4f, 6f); lineTo(4.5f, 6f)
        moveTo(4f, 12f); lineTo(4.5f, 12f)
        moveTo(4f, 18f); lineTo(4.5f, 18f)
    }

    val ChevronUp = outline("up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ChevronDown = outline("down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }

    val Play = solid("play") { moveTo(7f, 4.5f); lineTo(20f, 12f); lineTo(7f, 19.5f); close() }

    val Pause = solid("pause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }

    val Next = solid("next") {
        moveTo(5f, 5f); lineTo(15f, 12f); lineTo(5f, 19f); close()
        moveTo(16f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(16f, 19f); close()
    }

    val Subtitles = outline("subtitles") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(7f, 15f); lineTo(11f, 15f)
        moveTo(13f, 15f); lineTo(17f, 15f)
        moveTo(7f, 11f); lineTo(9f, 11f)
        moveTo(11f, 11f); lineTo(17f, 11f)
    }

    val Restart = outline("restart") {
        moveTo(4f, 12f)
        arcTo(8f, 8f, 0f, true, true, 6.5f, 17.8f)
        moveTo(4f, 7f); lineTo(4f, 12f); lineTo(9f, 12f)
    }
}
