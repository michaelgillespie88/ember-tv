package app.embertv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code for [text], drawn black on a white rounded card. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.09f)).background(Color.White).padding(size * 0.07f)) {
        if (matrix != null) {
            Canvas(Modifier.fillMaxSize()) {
                val n = matrix.width
                val cell = this.size.width / n
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        if (matrix.get(x, y)) {
                            // Slight overlap avoids hairline gaps between modules.
                            drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
                        }
                    }
                }
            }
        }
    }
}
