package com.quantum.qbeam.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.quantum.qbeam.ui.theme.ElectricViolet
import com.quantum.qbeam.ui.theme.EntangleMagenta
import com.quantum.qbeam.ui.theme.QuantumCyan
import kotlin.math.cos
import kotlin.math.sin

/** Animated orbiting "electrons" — ambient quantum aesthetic behind the UI. */
@Composable
fun QuantumBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orbit")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(9000, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "phase"
    )
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.32f
        val colors = listOf(QuantumCyan, ElectricViolet, EntangleMagenta)
        colors.forEachIndexed { i, color ->
            val rx = size.minDimension * (0.28f + i * 0.13f)
            val ry = rx * 0.42f
            val rot = i * 1.05f
            // orbit ellipse
            drawOval(
                color = color.copy(alpha = 0.18f),
                topLeft = Offset(cx - rx, cy - ry),
                size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
                style = Stroke(width = 2f)
            )
            // electron
            val a = (phase * (1f + i * 0.3f) + i).toDouble()
            val rotD = rot.toDouble()
            val ex = (cx + (rx * cos(a) * cos(rotD) - ry * sin(a) * sin(rotD))).toFloat()
            val ey = (cy + (rx * cos(a) * sin(rotD) + ry * sin(a) * cos(rotD))).toFloat()
            drawCircle(color = color, radius = 9f, center = Offset(ex, ey))
            drawCircle(color = color.copy(alpha = 0.25f), radius = 20f, center = Offset(ex, ey))
        }
        // nucleus
        drawCircle(color = QuantumCyan, radius = 7f, center = Offset(cx, cy))
    }
}
