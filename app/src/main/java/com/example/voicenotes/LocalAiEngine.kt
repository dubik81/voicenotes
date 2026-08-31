package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальный ИИ на устройстве через ExecuTorch (LLaMA/Gemma).
 * Обрабатывает ТЕКСТ офлайн: чистка, пунктуация, сжатие, сборка.
 *
 * ВАЖНО (стабильность эксплуатации): вся работа обёрнута в try/catch.
 * При любой ошибке возвращает null — вызывающий код откатывается на
 * запасной путь (правила/облако), приложение НЕ падает.
 */
object LocalAiEngine {

    @Volatile private var module: Any? = null
    @Volatile private var loadedId: String? = null

    /**
     * Генерация ответа локальной моделью. Возвращает текст или null при любой проблеме.
     * systemPrompt + userText объединяются в промпт для инструктивной модели.
     */
    suspend fun generate(context: Context, systemPrompt: String, userText: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!LocalAiModelManager.isReady(context, modelId)) return@withContext null
                val mod = loadModule(context, modelId) ?: return@withContext null
                val prompt = buildPrompt(systemPrompt, userText)
                runGenerate(mod, prompt)
            } catch (e: Throwable) {
                null  // любая ошибка → откат на запасной путь
            }
        }

    private fun loadModule(context: Context, modelId: String): Any? {
        return try {
            if (module != null && loadedId == modelId) return module
            releaseCurrent()
            val path = LocalAiModelManager.modelFile(context, modelId).absolutePath
            // ExecuTorch LlmModule грузит .pte-модель. Через рефлексию, чтобы не падала
            // сборка, если точное имя класса иное — тогда просто вернём null.
            val cls = Class.forName("org.pytorch.executorch.LlmModule")
            val ctor = cls.getConstructor(String::class.java)
            val m = ctor.newInstance(path)
            // load()
            try { cls.getMethod("load").invoke(m) } catch (_: Throwable) {}
            module = m; loadedId = modelId
            m
        } catch (e: Throwable) { null }
    }

    private fun runGenerate(mod: Any, prompt: String): String? {
        return try {
            val cls = mod.javaClass
            val sb = StringBuilder()
            // Callback-интерфейс LlmCallback: onResult(String), onStats(...)
            val cbCls = Class.forName("org.pytorch.executorch.LlmCallback")
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                cbCls.classLoader, arrayOf(cbCls)
            ) { _, method, args ->
                if (method.name == "onResult" && args != null && args.isNotEmpty()) {
                    sb.append(args[0] as? String ?: "")
                }
                null
            }
            // generate(prompt, seqLen, callback, echo)
            val gen = cls.methods.firstOrNull { it.name == "generate" }
            when (gen?.parameterTypes?.size) {
                4 -> gen.invoke(mod, prompt, 512, handler, false)
                3 -> gen.invoke(mod, prompt, 512, handler)
                2 -> gen.invoke(mod, prompt, handler)
                else -> return null
            }
            sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) { null }
    }

    private fun buildPrompt(system: String, user: String): String =
        "<|system|>\n$system\n<|user|>\n$user\n<|assistant|>\n"

    private fun releaseCurrent() {
        try {
            module?.let { m -> m.javaClass.getMethod("resetNative").invoke(m) }
        } catch (_: Throwable) {}
        module = null; loadedId = null
    }
}
