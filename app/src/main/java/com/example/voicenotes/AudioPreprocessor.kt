package com.example.voicenotes

/**
 * Предобработка аудио перед распознаванием (аналог обработки звука в Live Transcribe:
 * там Kiss FFT / Ooura FFT). Мы делаем практичный минимум, который реально помогает:
 * нормализация громкости и простое подавление тихого шума.
 *
 * Работает с PCM 16-bit моно (формат записи Vosk/Whisper).
 */
object AudioPreprocessor {

    /**
     * Нормализует громкость: усиливает тихую запись до комфортного уровня.
     * Тихая речь — частая причина плохого распознавания.
     * @param pcm 16-bit PCM сэмплы (ShortArray)
     */
    fun normalize(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        // находим пик
        var peak = 1
        for (s in pcm) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
        // целевой пик ~ 80% от максимума (26000 из 32767)
        val target = 26000
        if (peak >= target) return pcm  // уже громко
        val gain = target.toDouble() / peak
        // не усиливаем слишком сильно (иначе шум полезет) — максимум x4
        val g = gain.coerceAtMost(4.0)
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = (pcm[i] * g).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    /**
     * Простое шумоподавление: обнуляет очень тихие сэмплы (фоновый шум между словами).
     * Порог низкий, чтобы не резать тихую речь.
     */
    fun denoise(pcm: ShortArray, threshold: Int = 350): ShortArray {
        if (pcm.isEmpty()) return pcm
        val out = ShortArray(pcm.size)
        // скользящее окно: обнуляем сэмпл только если ВСЁ окно тихое (иначе это речь)
        val win = 400  // ~25мс при 16кГц
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + win, pcm.size)
            var maxAmp = 0
            for (j in i until end) { val a = kotlin.math.abs(pcm[j].toInt()); if (a > maxAmp) maxAmp = a }
            if (maxAmp < threshold) {
                for (j in i until end) out[j] = 0  // тихое окно = шум
            } else {
                for (j in i until end) out[j] = pcm[j]  // речь — сохраняем
            }
            i = end
        }
        return out
    }

    /** Полная предобработка: шумоподавление + нормализация. */
    fun process(pcm: ShortArray): ShortArray = normalize(denoise(pcm))
}
