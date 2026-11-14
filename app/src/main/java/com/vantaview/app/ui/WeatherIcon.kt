// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vantaview.app.data.weather.WeatherRepository
import kotlin.math.cos
import kotlin.math.sin

private val Sun = Color(0xFFFFC81A)
private val Cloud = Color(0xFFC3C9D8)
private val Rain = Color(0xFF14E0E0)

/** A small drawn weather picture for a WMO weather code (no emoji, works in light and dark). */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier) {
    val kind = WeatherRepository.kind(code)
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        fun sun(cx: Float, cy: Float, r: Float) {
            if (isDay) {
                drawCircle(Sun, r, Offset(cx, cy), style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(Sun, Offset(cx + cos(a).toFloat() * r * 1.45f, cy + sin(a).toFloat() * r * 1.45f),
                        Offset(cx + cos(a).toFloat() * r * 1.85f, cy + sin(a).toFloat() * r * 1.85f), stroke.width, StrokeCap.Round)
                }
            } else {
                // Moon at night: a crescent arc.
                drawArc(
                    Cloud, startAngle = 70f, sweepAngle = 250f, useCenter = false,
                    topLeft = Offset(cx - r * 1.2f, cy - r * 1.2f), size = androidx.compose.ui.geometry.Size(r * 2.4f, r * 2.4f),
                    style = stroke,
                )
            }
        }
        fun DrawScope.cloud(dx: Float, dy: Float, s: Float, c: Color = Cloud) {
            val p = Path().apply {
                moveTo(dx + 0.22f * s, dy + 0.78f * s)
                lineTo(dx + 0.80f * s, dy + 0.78f * s)
                cubicTo(dx + 0.98f * s, dy + 0.78f * s, dx + 0.98f * s, dy + 0.50f * s, dx + 0.80f * s, dy + 0.50f * s)
                cubicTo(dx + 0.78f * s, dy + 0.28f * s, dx + 0.50f * s, dy + 0.22f * s, dx + 0.42f * s, dy + 0.42f * s)
                cubicTo(dx + 0.30f * s, dy + 0.36f * s, dx + 0.16f * s, dy + 0.46f * s, dx + 0.20f * s, dy + 0.58f * s)
                cubicTo(dx + 0.04f * s, dy + 0.60f * s, dx + 0.04f * s, dy + 0.78f * s, dx + 0.22f * s, dy + 0.78f * s)
                close()
            }
            drawPath(p, c, style = stroke)
        }
        when (kind) {
            "sun" -> sun(w / 2, w / 2, w * 0.2f)
            "partly" -> { sun(w * 0.36f, w * 0.34f, w * 0.13f); cloud(w * 0.12f, w * 0.12f, w * 0.86f) }
            "fog" -> for (i in 0..2) drawLine(Cloud, Offset(w * 0.15f, w * (0.35f + i * 0.17f)), Offset(w * 0.85f, w * (0.35f + i * 0.17f)), stroke.width, StrokeCap.Round)
            "rain" -> {
                cloud(0f, -w * 0.12f, w)
                for (i in 0..2) drawLine(Rain, Offset(w * (0.32f + i * 0.18f), w * 0.72f), Offset(w * (0.27f + i * 0.18f), w * 0.9f), stroke.width, StrokeCap.Round)
            }
            "snow" -> {
                cloud(0f, -w * 0.12f, w)
                for (i in 0..2) drawCircle(Color.White, w * 0.045f, Offset(w * (0.3f + i * 0.2f), w * 0.82f))
            }
            "storm" -> {
                cloud(0f, -w * 0.12f, w)
                val b = Path().apply { moveTo(w * 0.52f, w * 0.62f); lineTo(w * 0.42f, w * 0.8f); lineTo(w * 0.54f, w * 0.8f); lineTo(w * 0.46f, w * 0.97f) }
                drawPath(b, Sun, style = stroke)
            }
            else -> cloud(0f, 0f, w)
        }
    }
}
