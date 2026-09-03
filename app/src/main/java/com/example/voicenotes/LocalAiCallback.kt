package com.example.voicenotes

/**
 * НАСТОЯЩАЯ реализация LlmCallback (не динамический Proxy).
 * Нативный JNI-код ExecuTorch не умеет вызывать Java-Proxy — ему нужен
 * реальный класс. Именно поэтому раньше callback не срабатывал (callback=0).
 *
 * Собирает токены ответа в StringBuilder.
 */
class LocalAiCallback : org.pytorch.executorch.extension.llm.LlmCallback {
    val sb = StringBuilder()
    var calls = 0

    override fun onResult(result: String) {
        calls++
        sb.append(result)
    }

    override fun onStats(stats: String) {
        // статистика генерации — в текст не добавляем
    }

    fun text(): String = sb.toString()
}
