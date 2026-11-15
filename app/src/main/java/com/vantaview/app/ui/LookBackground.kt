// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vantaview.app.data.Look
import kotlin.math.PI
import kotlin.math.sin

/** The background a look draws behind every page. VANTA has none (pages are solid). */
@Composable
fun LookBackground(look: Look, modifier: Modifier = Modifier.fillMaxSize()) {
    when (look) {
        Look.VANTA -> Unit
        Look.GLASS -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF081650), Color(0xFF16399E), Color(0xFF2F63D8))))
            // Soft light from the top right, like a lit pane of glass.
            drawRect(Brush.radialGradient(listOf(Color(0x405FA8FF), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.maxDimension * 0.7f))
            drawRect(Brush.radialGradient(listOf(Color(0x30000020), Color.Transparent), center = Offset(0f, size.height), radius = size.maxDimension * 0.6f))
        }
        Look.BROADCAST -> Canvas(modifier) {
            drawRect(Brush.linearGradient(listOf(Color(0xFF1A4FC4), Color(0xFF2F6CE2), Color(0xFF4C88F2)), start = Offset.Zero, end = Offset(size.width, size.height)))
        }
        Look.CLASSIC -> Canvas(modifier) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1450), Color(0xFF13207A), Color(0xFF1C2E9A))))
        }
        Look.WAVE -> {
            // One slow loop a minute: cheap enough for a Fire TV Stick.
            val t by rememberInfiniteTransition(label = "wave").animateFloat(
                0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
            )
            Canvas(modifier) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF041E40), Color(0xFF0A3B73), Color(0xFF0F4F8E))))
                wave(t, 0.58f, 0.08f, 1.0f, Color(0x2E9FD4FF))
                wave(t + 0.33f, 0.66f, 0.06f, 1.4f, Color(0x269FD4FF))
                wave(t + 0.66f, 0.74f, 0.05f, 0.8f, Color(0x1FCFEAFF))
                // A few still specks of light.
                val dots = listOf(0.12f to 0.2f, 0.3f to 0.65f, 0.48f to 0.12f, 0.62f to 0.85f, 0.77f to 0.35f, 0.9f to 0.7f, 0.2f to 0.9f)
                dots.forEach { (x, y) -> drawCircle(Color(0x66FFFFFF), radius = 2.5f, center = Offset(size.width * x, size.height * y)) }
            }
        }
    }
}

/** A filled band whose top edge is a sine wave, drifting sideways with [t]. */
private fun DrawScope.wave(t: Float, at: Float, height: Float, cycles: Float, color: Color) {
    val w = size.width
    val h = size.height
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val x = w * i / steps
        val y = h * at + h * height * sin((i.toFloat() / steps * cycles + t) * 2 * PI).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.lineTo(w, h); path.lineTo(0f, h); path.close()
    drawPath(path, color)
}
