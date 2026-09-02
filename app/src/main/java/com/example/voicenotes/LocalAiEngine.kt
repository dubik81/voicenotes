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
                val fullPrompt = buildPrompt(systemPrompt, userText)
                val raw = runGenerate(mod, fullPrompt)
                // Очищаем ответ от эха промпта и JSON-статистики.
                val cleaned = cleanResponse(raw, fullPrompt, systemPrompt, userText)
                lastStatus = if (cleaned.isNullOrBlank()) "генерация пустая" else "работает"
                cleaned
            } catch (e: Throwable) {
                lastStatus = "ошибка: ${e.message?.take(40)}"; null
            }
        }

    /** Чистит ответ модели: убирает эхо промпта и хвост со статистикой (JSON). */
    private fun cleanResponse(raw: String?, fullPrompt: String, system: String, user: String): String? {
        if (raw.isNullOrBlank()) return null
        var t = raw
        // убрать эхо полного промпта / системного / пользовательского текста в начале
        for (p in listOf(fullPrompt, system, user)) {
            if (p.isNotBlank() && t.startsWith(p)) t = t.substring(p.length)
            // либо промпт встречается внутри — берём то, что после него
            val idx = t.indexOf(p)
            if (p.isNotBlank() && idx in 0..50) t = t.substring(idx + p.length)
        }
        // отрезать JSON-статистику (начинается с { "prompt_tokens" ...)
        val jsonIdx = t.indexOf("{\"prompt_tokens")
        if (jsonIdx >= 0) t = t.substring(0, jsonIdx)
        // отрезать любой хвостовой JSON-объект статистики
        val braceIdx = t.indexOf("{\"")
        if (braceIdx >= 0 && t.substring(braceIdx).contains("_ms\"")) t = t.substring(0, braceIdx)
        return t.trim().ifBlank { null }
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
            // Логируем доступные конструкторы.
            Diagnostics.info("Конструкторы LlmModule: ${cls.constructors.joinToString { c -> "(${c.parameterTypes.joinToString{p->p.simpleName}})" }}")
            val path = LocalAiModelManager.modelFile(context, modelId).absolutePath
            val tok = LocalAiModelManager.tokenizerFile(context).absolutePath
            val m = try {
                cls.getConstructor(String::class.java, String::class.java, Float::class.javaPrimitiveType)
                    .newInstance(path, tok, 0.3f).also { Diagnostics.info("Конструктор: (model,tok,temp)") }
            } catch (_: Throwable) {
                try {
                    cls.getConstructor(Int::class.javaPrimitiveType, String::class.java,
                        String::class.java, Float::class.javaPrimitiveType)
                        .newInstance(1, path, tok, 0.3f).also { Diagnostics.info("Конструктор: (int,model,tok,temp)") }
                } catch (_: Throwable) {
                    cls.getConstructor(String::class.java, String::class.java).newInstance(path, tok)
                        .also { Diagnostics.info("Конструктор: (model,tok)") }
                }
            }
            val loadRet = try {
                val r = cls.getMethod("load").invoke(m)
                Diagnostics.event("load() вернул: $r")
                r
            } catch (e: Throwable) { Diagnostics.error("load() исключение: ${e.message?.take(60)}"); null }
            module = m; loadedId = modelId
            m
        } catch (e: Throwable) {
            Diagnostics.error("loadModule исключение: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            null
        }
    }

    private fun runGenerate(mod: Any, prompt: String): String? {
        return try {
            val cls = mod.javaClass
            // Логируем ВСЕ методы generate с сигнатурами.
            val genMethods = cls.methods.filter { it.name == "generate" }
            Diagnostics.info("Методы generate (${genMethods.size}):")
            genMethods.forEach { m ->
                Diagnostics.info("  generate(${m.parameterTypes.joinToString { it.simpleName }}) → ${m.returnType.simpleName}")
            }
            val cbCls = callbackClass()
            if (cbCls == null) { Diagnostics.error("Callback-класс не найден"); return null }
            // Логируем методы callback-интерфейса.
            Diagnostics.info("Методы Callback: ${cbCls.methods.joinToString { "${it.name}(${it.parameterTypes.joinToString{p->p.simpleName}})" }}")

            val sb = StringBuilder()
            var callbackCalls = 0
            val calledMethods = mutableSetOf<String>()
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                cbCls.classLoader, arrayOf(cbCls)
            ) { _, method, args ->
                callbackCalls++
                calledMethods.add(method.name)
                // ТОЛЬКО onResult даёт текст ответа. onStats — это JSON-статистика,
                // её в текст брать нельзя (иначе prompt_tokens... попадёт в заметку).
                if (method.name == "onResult" && args != null && args.isNotEmpty()) {
                    (args[0] as? String)?.let { sb.append(it) }
                }
                if (method.returnType == Boolean::class.javaPrimitiveType ||
                    method.returnType == java.lang.Boolean.TYPE) false else null
            }

            Diagnostics.event("Вызываю generate, длина промпта=${prompt.length}")
            // Пробуем сигнатуры по очереди, пока callback не начнёт отдавать текст.
            // Порядок: сначала простые (без config), потом с config.
            val attempts = listOf<Pair<String, () -> Any?>>(
                "(String, LlmCallback)" to {
                    genMethods.firstOrNull { it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java }
                        ?.invoke(mod, prompt, handler)
                },
                "(String, int, LlmCallback, boolean)" to {
                    genMethods.firstOrNull { it.parameterTypes.size == 4 &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType }
                        ?.invoke(mod, prompt, 256, handler, false)
                },
                "(String, LlmGenerationConfig, LlmCallback)" to {
                    val gm = genMethods.firstOrNull { it.parameterTypes.size == 3 &&
                        it.parameterTypes[1].simpleName == "LlmGenerationConfig" }
                    val cfg = gm?.let { buildGenConfig(it.parameterTypes[1]) }
                    if (gm != null && cfg != null) gm.invoke(mod, prompt, cfg, handler) else null
                }
            )
            var ret: Any? = null
            for ((sig, call) in attempts) {
                if (sb.isNotEmpty()) break  // уже получили текст
                sb.clear(); callbackCalls = 0; calledMethods.clear()
                try {
                    Diagnostics.info("Пробую generate $sig")
                    ret = call()
                    Diagnostics.event("$sig → вернул=$ret, callback=$callbackCalls, собрано=${sb.length}")
                    if (sb.isNotEmpty()) { Diagnostics.event("РАБОТАЕТ: $sig"); break }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    Diagnostics.error("$sig нативная ошибка: ${e.targetException?.javaClass?.simpleName}: ${e.targetException?.message?.take(120)}")
                } catch (e: Throwable) {
                    Diagnostics.error("$sig ошибка: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                }
            }

            sb.toString().trim().ifBlank {
                Diagnostics.error("Генерация пуста: callback=$callbackCalls, методы=[${calledMethods.joinToString()}]")
                null
            }
        } catch (e: Throwable) {
            Diagnostics.error("runGenerate исключение: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            lastStatus = "ошибка генерации: ${e.message?.take(40)}"
            null
        }
    }

    /**
     * Самопроверка: прогоняет простой тест и возвращает подробный отчёт,
     * что именно работает или где сломалось. Для диагностики на устройстве.
     */
    suspend fun selfTest(context: Context, modelId: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("Проверка локального ИИ:\n")
        // 1. модель скачана?
        val ready = LocalAiModelManager.isReady(context, modelId)
        sb.append("1. Модель скачана: ${if (ready) "да" else "НЕТ"}\n")
        if (!ready) { sb.append("→ Скачайте модель."); return@withContext sb.toString() }
        // 2. токенизатор есть?
        val tok = LocalAiModelManager.tokenizerFile(context)
        sb.append("2. Токенизатор: ${if (tok.exists() && tok.length() > 1000) "есть (${tok.length()} б)" else "НЕТ или пустой"}\n")
        // 3. класс движка найден?
        val cls = moduleClass()
        sb.append("3. Класс ExecuTorch: ${if (cls != null) "найден (${cls.name})" else "НЕ НАЙДЕН"}\n")
        if (cls == null) { sb.append("→ Библиотека ExecuTorch не подключилась."); return@withContext sb.toString() }
        val cb = callbackClass()
        sb.append("4. Класс Callback: ${if (cb != null) "найден" else "НЕ НАЙДЕН"}\n")
        // 5. модель грузится?
        val t0 = System.currentTimeMillis()
        val mod = loadModule(context, modelId)
        sb.append("5. Загрузка модели: ${if (mod != null) "успех (${System.currentTimeMillis()-t0} мс)" else "ПРОВАЛ"}\n")
        if (mod == null) { sb.append("→ Модель не загрузилась (проверьте формат .pte и нативные библиотеки)."); return@withContext sb.toString() }
        // 6. генерация?
        val t1 = System.currentTimeMillis()
        val out = runGenerate(mod, buildPrompt("Ответь одним словом.", "Скажи: привет"))
        val genOk = !out.isNullOrBlank()
        val genTime = System.currentTimeMillis() - t1
        if (genOk) {
            sb.append("6. Генерация: РАБОТАЕТ ($genTime мс)\n")
            sb.append("   ответ: ").append(out!!.take(60)).append("\n")
        } else {
            sb.append("6. Генерация: пустой результат\n")
        }
        sb.append("\nИтог: ").append(if (genOk) "OK Локальный ИИ работает!" else "Модель грузится, но не генерирует.")
        lastStatus = if (genOk) "работает" else "генерация пустая"
        Diagnostics.info("САМОПРОВЕРКА локального ИИ:\n${sb}")
        sb.toString()
    }

    // Создаёт LlmGenerationConfig. У ExecuTorch обычно Builder-паттерн.
    private fun buildGenConfig(cfgCls: Class<*>): Any? {
        return try {
            // Логируем конструкторы и вложенные классы конфига.
            Diagnostics.info("Config конструкторы: ${cfgCls.constructors.joinToString { c -> "(${c.parameterTypes.joinToString{p->p.simpleName}})" }}")
            val builderCls = cfgCls.classes.firstOrNull { it.simpleName == "Builder" }
            if (builderCls != null) {
                Diagnostics.info("Config.Builder найден")
                val builder = builderCls.getConstructor().newInstance()
                // пробуем задать seqLen и temperature, если есть сеттеры
                builderCls.methods.forEach { m ->
                    try {
                        when {
                            m.name.contains("eqLen", true) && m.parameterTypes.size == 1 ->
                                m.invoke(builder, 256)
                            m.name.contains("emperature", true) && m.parameterTypes.size == 1 ->
                                m.invoke(builder, 0.3f)
                        }
                    } catch (_: Throwable) {}
                }
                val build = builderCls.getMethod("build")
                return build.invoke(builder)
            }
            // без Builder — пробуем пустой конструктор
            cfgCls.getConstructor().newInstance()
        } catch (e: Throwable) {
            Diagnostics.error("buildGenConfig: ${e.message?.take(60)}")
            null
        }
    }

    // Простой промпт БЕЗ спецтокенов — токенизатор Llama сам добавит служебное.
    // Спецтокены в тексте могут ломать генерацию (пустой результат).
    private fun buildPrompt(system: String, user: String): String =
        "$system\n\n$user"

    private fun releaseCurrent() {
        try { module?.let { m -> m.javaClass.getMethod("resetNative").invoke(m) } } catch (_: Throwable) {}
        module = null; loadedId = null
    }
}
