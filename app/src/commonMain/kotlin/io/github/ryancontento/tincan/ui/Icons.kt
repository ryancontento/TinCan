package io.github.ryancontento.tincan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Hairline path icons: emoji rendered differently on every machine; these take the content colour. */
enum class TinIcon { SEARCH, PLUS, PENCIL, TRASH, CLOSE, CHEVRON_LEFT, PIN }

@Composable
fun TinIconGlyph(
    icon: TinIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)

        // Coordinates are fractions of the icon's width.
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(w * x1, w * y1), Offset(w * x2, w * y2), stroke.width, StrokeCap.Round)

        when (icon) {
            TinIcon.SEARCH -> {
                drawCircle(tint, radius = w * 0.30f, center = Offset(w * 0.42f, w * 0.42f), style = stroke)
                line(0.64f, 0.64f, 0.88f, 0.88f)
            }

            TinIcon.PLUS -> {
                line(0.5f, 0.18f, 0.5f, 0.82f)
                line(0.18f, 0.5f, 0.82f, 0.5f)
            }

            TinIcon.PENCIL -> {
                line(0.22f, 0.78f, 0.70f, 0.30f)
                line(0.70f, 0.30f, 0.82f, 0.42f)
                line(0.82f, 0.42f, 0.34f, 0.88f)
                line(0.34f, 0.88f, 0.18f, 0.90f)
                line(0.18f, 0.90f, 0.22f, 0.78f)
            }

            TinIcon.TRASH -> {
                line(0.16f, 0.26f, 0.84f, 0.26f)
                line(0.40f, 0.26f, 0.40f, 0.14f)
                line(0.60f, 0.26f, 0.60f, 0.14f)
                line(0.40f, 0.14f, 0.60f, 0.14f)
                val body = Path().apply {
                    moveTo(w * 0.26f, w * 0.26f)
                    lineTo(w * 0.32f, w * 0.86f)
                    lineTo(w * 0.68f, w * 0.86f)
                    lineTo(w * 0.74f, w * 0.26f)
                }
                drawPath(body, tint, style = stroke)
            }

            TinIcon.CLOSE -> {
                line(0.24f, 0.24f, 0.76f, 0.76f)
                line(0.76f, 0.24f, 0.24f, 0.76f)
            }

            TinIcon.CHEVRON_LEFT -> {
                line(0.62f, 0.20f, 0.34f, 0.50f)
                line(0.34f, 0.50f, 0.62f, 0.80f)
            }

            TinIcon.PIN -> {
                // Flat head, flared collar, then the needle.
                val head = Path().apply {
                    moveTo(w * 0.30f, w * 0.14f)
                    lineTo(w * 0.70f, w * 0.14f)
                    moveTo(w * 0.37f, w * 0.14f)
                    lineTo(w * 0.37f, w * 0.44f)
                    lineTo(w * 0.24f, w * 0.58f)
                    lineTo(w * 0.76f, w * 0.58f)
                    lineTo(w * 0.63f, w * 0.44f)
                    lineTo(w * 0.63f, w * 0.14f)
                }
                drawPath(head, tint, style = stroke)
                line(0.5f, 0.58f, 0.5f, 0.90f)
            }
        }
    }
}

/** A downward caret, for anything that opens a menu. */
@Composable
fun TinCaret(tint: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        drawLine(tint, Offset(w * 0.1f, w * 0.35f), Offset(w * 0.5f, w * 0.72f), w * 0.14f, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.9f, w * 0.35f), w * 0.14f, StrokeCap.Round)
    }
}
