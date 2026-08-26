package com.example.voicenotes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Шиммер-плейсхолдер: мягкая волна, бегущая слева направо по строкам-заглушкам.
 * Показывается, пока идёт сжатие текста, чтобы было видно — работа идёт.
 */
@Composable
fun ShimmerText(label: String, accent: Color) {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing)
        ),
        label = "x"
    )

    val base = cs.onSurface.copy(alpha = 0.08f)
    val highlight = accent.copy(alpha = 0.25f)

    fun brush(widthFactor: Float) = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x * 600f, 0f),
        end = Offset((x + widthFactor) * 600f, 0f)
    )

    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(label, color = accent, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        // Несколько строк разной длины, как настоящий абзац.
        listOf(1f, 0.95f, 0.9f, 0.7f, 0.85f, 0.5f).forEach { w ->
            Spacer(
                Modifier
                    .fillMaxWidth(w)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(brush(1f))
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
