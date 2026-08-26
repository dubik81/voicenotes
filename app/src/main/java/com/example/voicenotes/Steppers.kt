package com.example.voicenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Маленький индикатор готовности варианта в углу кнопки. */
@Composable
private fun ReadyBadge(state: VariantProcessor.State, modifier: Modifier = Modifier) {
    val (symbol, color) = when (state) {
        VariantProcessor.State.DONE -> "✓" to Color(0xFF2E9E6B)
        VariantProcessor.State.RUNNING -> "◐" to Color(0xFFE0A458)
        VariantProcessor.State.QUEUED -> "…" to Color(0xFF9AA0AD)
        VariantProcessor.State.FAILED -> "!" to Color(0xFFD8574B)
    }
    Box(
        modifier
            .size(14.dp)
            .clip(RoundedCornerShape(topStart = 6.dp))
            .background(Color.White.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ступени сжатия с индикацией готовности в углу каждой. */
@Composable
fun LevelStepper(
    selected: Int,
    accent: Color,
    readyState: (Int) -> VariantProcessor.State,
    onSelect: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Level.entries.forEachIndexed { i, lvl ->
            val isSel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) accent else cs.surfaceVariant)
                    .clickable { onSelect(i) }
            ) {
                Text(
                    lvl.title,
                    color = if (isSel) Color.White else cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 2.dp)
                )
                // индикатор только для не-дословных (у дословного всегда есть текст)
                if (lvl != Level.VERBATIM) {
                    ReadyBadge(readyState(i), Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}

/** Ступени тона. Неактивны (серые), если enabled = false. */
@Composable
fun ToneStepper(
    selected: Int,
    enabled: Boolean,
    readyState: (Int) -> VariantProcessor.State,
    onSelect: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tone.entries.forEachIndexed { i, t ->
            val isSel = i == selected && enabled
            val bg = when {
                !enabled -> cs.surfaceVariant.copy(alpha = 0.4f)
                isSel -> Palette.Amber
                else -> cs.surfaceVariant
            }
            val fg = when {
                !enabled -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                isSel -> Color.White
                else -> cs.onSurfaceVariant
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(if (enabled) Modifier.clickable { onSelect(i) } else Modifier)
            ) {
                Text(
                    when (t) {
                        Tone.FORMAL -> "Формально"
                        Tone.NEUTRAL -> "Обычно"
                        Tone.CASUAL -> "Живой"
                    },
                    color = fg,
                    fontSize = 10.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 2.dp)
                )
                if (enabled) {
                    ReadyBadge(readyState(i), Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}
