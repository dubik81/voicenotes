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

/** Ступени сжатия: Дословно / Чисто / Сжато / Кратко / Суть. */
@Composable
fun LevelStepper(selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Level.entries.forEachIndexed { i, lvl ->
                val isSel = i == selected
                val segColor = if (isSel) accent else cs.surfaceVariant
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(segColor)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        lvl.title,
                        color = if (isSel) Color.White else cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** Ступени тона: Формально / Нейтрально / Разговорно / +эмодзи. */
@Composable
fun ToneStepper(selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Tone.entries.forEachIndexed { i, t ->
            val isSel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) Palette.Amber else cs.surfaceVariant)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (t) {
                        Tone.FORMAL -> "Формально"
                        Tone.NEUTRAL -> "Нейтрально"
                        Tone.CASUAL -> "Разговорно"
                        Tone.EMOJI -> "+ эмодзи"
                    },
                    color = if (isSel) Color.White else cs.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
