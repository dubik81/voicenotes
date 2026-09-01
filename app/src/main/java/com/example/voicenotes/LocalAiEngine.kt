package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальный ИИ на устройстве через ExecuTorch (LLaMA/Gemma).
 * Обрабатывает ТЕКСТ офлайн: чистка, пунктуация, сжатие, сборка.
 *
 * API (проверено по документации ExecuTorch 1.x):
 *   класс: org.pytorch.executorch.extension.llm.LlmModule
 *   конструктор: LlmModule(modelPath, tokenizerPath, temperature)
 *   методы: load(), generate(prompt, seqLen, callback)
 *   callback: org.pytorch.executorch.extension.llm.LlmCallback { onResult(String); onStats(String) }
 *
 * Всё через рефлексию + try/catch: если API отличается — возвращаем null,
 * вызывающий код откатывается на облако, приложение НЕ падает.
 */
object LocalAiEngine {

    // Последний статус для диагностики (виден пользователю).
    @Volatile var lastStatus: String = "не запускался"
        private set

    // Возможные пути к классу (новый и старый) — пробуем по очереди.
    private val MODULE_CLASSES = listOf(
        "org.pytorch.executorch.extension.llm.LlmModule",
        "org.pytorch.executorch.LlmModule"
    )
    private val CALLBACK_CLASSES = listOf(
        "org.pytorch.executorch.extension.llm.LlmCallback",
        "org.pytorch.executorch.LlmCallback"
    )

    @Volatile private var module: Any? = null
    @Volatile private var loadedId: String? = null

    suspend fun generate(context: Context, systemPrompt: String, userText: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!LocalAiModelManager.isReady(context, modelId)) {
                    lastStatus = "модель не скачана"; return@withContext null
                }
                if (moduleClass() == null) {
                    lastStatus = "класс ExecuTorch не найден"; return@withContext null
                }
                val mod = loadModule(context, modelId)
                if (mod == null) { lastStatus = "модель не загрузилась"; return@withContext null }
                val res = runGenerate(mod, buildPrompt(systemPrompt, userText))
                lastStatus = if (res.isNullOrBlank()) "генерация пустая" else "работает"
                res
            } catch (e: Throwable) {
                lastStatus = "ошибка: ${e.message?.take(40)}"; null
            }
        }

    private fun moduleClass(): Class<*>? {
        for (name in MODULE_CLASSES) {
            try { return Class.forName(name) } catch (_: Throwable) {}
        }
        return null
    }
    private fun callbackClass(): Class<*>? {
        for (name in CALLBACK_CLASSES) {
            try { return Class.forName(name) } catch (_: Throwable) {}
        }
        return null
    }

    private fun loadModule(context: Context, modelId: String): Any? {
        return try {
            if (module != null && loadedId == modelId) return module
            releaseCurrent()
            val cls = moduleClass() ?: return null
            val path = LocalAiModelManager.modelFile(context, modelId).absolutePath
            val tok = LocalAiModelManager.tokenizerFile(context).absolutePath
            // конструктор (modelPath, tokenizerPath, temperature)
            val m = try {
                cls.getConstructor(String::class.java, String::class.java, Float::class.javaPrimitiveType)
                    .newInstance(path, tok, 0.3f)
            } catch (_: Throwable) {
                try {
                    // (modelType:Int, modelPath, tokenizerPath, temperature)
                    cls.getConstructor(Int::class.javaPrimitiveType, String::class.java,
                        String::class.java, Float::class.javaPrimitiveType)
                        .newInstance(1, path, tok, 0.3f)
                } catch (_: Throwable) {
                    cls.getConstructor(String::class.java, String::class.java).newInstance(path, tok)
                }
            }
            try { cls.getMethod("load").invoke(m) } catch (_: Throwable) {}
            module = m; loadedId = modelId
            m
        } catch (e: Throwable) { null }
    }

    private fun runGenerate(mod: Any, prompt: String): String? {
        return try {
            val cbCls = callbackClass() ?: return null
            val sb = StringBuilder()
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                cbCls.classLoader, arrayOf(cbCls)
            ) { _, method, args ->
                if (method.name == "onResult" && args != null && args.isNotEmpty()) {
                    sb.append(args[0] as? String ?: "")
                }
                null
            }
            val cls = mod.javaClass
            // generate(prompt, seqLen, callback) — основной вариант
            val gen = cls.methods.firstOrNull {
                it.name == "generate" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java
            } ?: cls.methods.firstOrNull { it.name == "generate" }
            when (gen?.parameterTypes?.size) {
                3 -> gen.invoke(mod, prompt, 512, handler)
                4 -> gen.invoke(mod, prompt, 512, handler, false)
                2 -> gen.invoke(mod, prompt, handler)
                else -> return null
            }
            sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) { null }
    }

    private fun buildPrompt(system: String, user: String): String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n$system<|eot_id|>" +
        "<|start_header_id|>user<|end_header_id|>\n$user<|eot_id|>" +
        "<|start_header_id|>assistant<|end_header_id|>\n"

    private fun releaseCurrent() {
        try { module?.let { m -> m.javaClass.getMethod("resetNative").invoke(m) } } catch (_: Throwable) {}
        module = null; loadedId = null
    }
}
