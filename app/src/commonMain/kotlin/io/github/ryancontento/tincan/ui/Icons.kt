package io.github.ryancontento.tincan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hairline icons drawn as paths.
 *
 * Emoji were doing this job and rendered differently on every machine — a trash
 * can in full colour next to flat monochrome text. These scale with the window,
 * take the current content colour, and add no dependency.
 */
enum class TinIcon { SEARCH, PLUS, PENCIL, TRASH, CLOSE, CHEVRON_LEFT }

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

        when (icon) {
            TinIcon.SEARCH -> {
                val r = w * 0.30f
                drawCircle(tint, radius = r, center = Offset(w * 0.42f, w * 0.42f), style = stroke)
                drawLine(tint, Offset(w * 0.64f, w * 0.64f), Offset(w * 0.88f, w * 0.88f), stroke.width, StrokeCap.Round)
            }

            TinIcon.PLUS -> {
                drawLine(tint, Offset(w * 0.5f, w * 0.18f), Offset(w * 0.5f, w * 0.82f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, w * 0.5f), Offset(w * 0.82f, w * 0.5f), stroke.width, StrokeCap.Round)
            }

            TinIcon.PENCIL -> {
                drawLine(tint, Offset(w * 0.22f, w * 0.78f), Offset(w * 0.70f, w * 0.30f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.70f, w * 0.30f), Offset(w * 0.82f, w * 0.42f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.82f, w * 0.42f), Offset(w * 0.34f, w * 0.88f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, w * 0.88f), Offset(w * 0.18f, w * 0.90f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, w * 0.90f), Offset(w * 0.22f, w * 0.78f), stroke.width, StrokeCap.Round)
            }

            TinIcon.TRASH -> {
                drawLine(tint, Offset(w * 0.16f, w * 0.26f), Offset(w * 0.84f, w * 0.26f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.40f, w * 0.26f), Offset(w * 0.40f, w * 0.14f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.60f, w * 0.26f), Offset(w * 0.60f, w * 0.14f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.40f, w * 0.14f), Offset(w * 0.60f, w * 0.14f), stroke.width, StrokeCap.Round)
                val body = Path().apply {
                    moveTo(w * 0.26f, w * 0.26f)
                    lineTo(w * 0.32f, w * 0.86f)
                    lineTo(w * 0.68f, w * 0.86f)
                    lineTo(w * 0.74f, w * 0.26f)
                }
                drawPath(body, tint, style = stroke)
            }

            TinIcon.CLOSE -> {
                drawLine(tint, Offset(w * 0.24f, w * 0.24f), Offset(w * 0.76f, w * 0.76f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.76f, w * 0.24f), Offset(w * 0.24f, w * 0.76f), stroke.width, StrokeCap.Round)
            }

            TinIcon.CHEVRON_LEFT -> {
                drawLine(tint, Offset(w * 0.62f, w * 0.20f), Offset(w * 0.34f, w * 0.50f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, w * 0.50f), Offset(w * 0.62f, w * 0.80f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** A downward caret, for anything that opens a menu. */
@Composable
fun TinCaret(tint: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val bounds = Rect(0f, 0f, w, w)
        drawLine(
            tint,
            Offset(bounds.left + w * 0.1f, w * 0.35f),
            Offset(w * 0.5f, w * 0.72f),
            w * 0.14f,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(w * 0.5f, w * 0.72f),
            Offset(bounds.right - w * 0.1f, w * 0.35f),
            w * 0.14f,
            StrokeCap.Round,
        )
    }
}
