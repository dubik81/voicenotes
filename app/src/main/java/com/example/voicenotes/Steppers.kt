package com.example.voicenotes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CGreen = Color(0xFF2E9E6B)
private val CAmber = Color(0xFFE0A458)
private val CGray = Color(0xFFBBBBBB)
private val CRed = Color(0xFFD8574B)

/**
 * Наглядная полоса-статус внизу кнопки:
 *  готово  — сплошная зелёная линия,
 *  считается — бегущая оранжевая (анимация),
 *  в очереди — тусклая серая,
 *  ошибка — красная.
 */
@Composable
private fun StatusBar(state: VariantProcessor.State, modifier: Modifier = Modifier) {
    when (state) {
        VariantProcessor.State.DONE ->
            Box(modifier.fillMaxWidth().height(4.dp).background(CGreen))
        VariantProcessor.State.QUEUED ->
            Box(modifier.fillMaxWidth().height(4.dp).background(CGray.copy(alpha = 0.5f)))
        VariantProcessor.State.FAILED ->
            Box(modifier.fillMaxWidth().height(4.dp).background(CRed))
        VariantProcessor.State.RUNNING -> {
            // пульсирующая оранжевая
            val tr = rememberInfiniteTransition(label = "run")
            val a by tr.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
                label = "a"
            )
            Box(modifier.fillMaxWidth().height(4.dp).background(CAmber.copy(alpha = a)))
        }
    }
}

/** Значок-галочка/иконка для наглядности рядом с названием. */
private fun mark(state: VariantProcessor.State): String = when (state) {
    VariantProcessor.State.DONE -> "✓"
    VariantProcessor.State.RUNNING -> "⏳"
    VariantProcessor.State.QUEUED -> "•"
    VariantProcessor.State.FAILED -> "⚠"
}

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
            val st = readyState(i)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (isSel) accent else cs.surfaceVariant)
                    .clickable { onSelect(i) }
            ) {
                Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                    Text(
                        (if (lvl != Level.VERBATIM) mark(st) + " " else "") + lvl.title,
                        color = if (isSel) Color.White else cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
                if (lvl != Level.VERBATIM) StatusBar(st)
                else Box(Modifier.fillMaxWidth().height(4.dp).background(CGreen))
            }
        }
    }
}

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
            val st = readyState(i)
            val bg = when {
                !enabled -> cs.surfaceVariant.copy(alpha = 0.4f)
                isSel -> CAmber
                else -> cs.surfaceVariant
            }
            val fg = when {
                !enabled -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                isSel -> Color.White
                else -> cs.onSurfaceVariant
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .then(if (enabled) Modifier.clickable { onSelect(i) } else Modifier)
            ) {
                Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        (if (enabled) mark(st) + " " else "") + when (t) {
                            Tone.FORMAL -> "Формально"
                            Tone.NEUTRAL -> "Обычно"
                            Tone.CASUAL -> "Живой"
                        },
                        color = fg, fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
                if (enabled) StatusBar(st)
                else Box(Modifier.fillMaxWidth().height(4.dp))
            }
        }
    }
}
