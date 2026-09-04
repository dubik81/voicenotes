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
    @Volatile private var lastResetOk: Boolean = true

    suspend fun generate(context: Context, systemPrompt: String, userText: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                if (!LocalAiModelManager.isReady(context, modelId)) {
                    lastStatus = "модель не скачана"; return@withContext null
                }
                if (moduleClass() == null) {
                    lastStatus = "класс ExecuTorch не найден"; return@withContext null
                }
                // Защита от утечки между заметками: если прошлый сброс контекста НЕ сработал,
                // принудительно выгружаем модуль — следующая загрузка будет с чистым состоянием.
                if (!lastResetOk) { releaseCurrent(); Diagnostics.info("Модель выгружена для чистого старта") }
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

    /** Чистит ответ модели: убирает эхо промпта, спецтокены и хвост со статистикой. */
    private fun cleanResponse(raw: String?, fullPrompt: String, system: String, user: String): String? {
        if (raw.isNullOrBlank()) return null
        var t: String = raw
        // убрать эхо промпта в начале
        for (p in listOf(fullPrompt, system, user)) {
            if (p.isNotBlank() && t.startsWith(p)) t = t.substring(p.length)
            val idx = t.indexOf(p)
            if (p.isNotBlank() && idx in 0..50) t = t.substring(idx + p.length)
        }
        // убрать служебные токены Llama (<|eot_id|>, <|end_of_text|>, заголовки)
        for (tok in listOf("<|eot_id|>", "<|end_of_text|>", "<|begin_of_text|>",
                "<|start_header_id|>", "<|end_header_id|>", "assistant", "<|python_tag|>")) {
            t = t.replace(tok, " ")
        }
        // отрезать JSON-статистику (в любом регистре)
        var searchFrom = 0
        while (true) {
            val brace = t.indexOf("{\"", searchFrom)
            if (brace < 0) break
            val tail = t.substring(brace).lowercase()
            if (tail.contains("_ms\"") || tail.contains("_tokens\"") || tail.contains("token_ms")) {
                t = t.substring(0, brace); break
            }
            searchFrom = brace + 2
        }
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
            val genMethods = cls.methods.filter { it.name == "generate" }
            // КЛЮЧЕВОЕ против утечки между заметками: сброс контекста (KV-кэш).
            // Пробуем resetContext; если недоступен — модуль будет перезагружен
            // принудительно в generate() через forceReload (см. вызывающий код).
            var resetOk = false
            try { cls.getMethod("resetContext").invoke(mod); resetOk = true; Diagnostics.info("resetContext OK") }
            catch (_: Throwable) {
                // пробуем альтернативные имена
                for (name in listOf("reset", "resetNative", "resetKVCache")) {
                    try { cls.getMethod(name).invoke(mod); resetOk = true; Diagnostics.info("$name OK"); break }
                    catch (_: Throwable) {}
                }
            }
            if (!resetOk) Diagnostics.info("сброс контекста недоступен — будет перезагрузка модели")
            lastResetOk = resetOk

            val cb = LocalAiCallback()
            Diagnostics.event("Вызываю generate, длина промпта=${prompt.length}")
            // Рабочая сигнатура (по логу): generate(String, LlmCallback). Трёхаргументная
            // (String,int,cb) падает на префилле — не используем.
            val m2 = genMethods.firstOrNull {
                it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
            }
            if (m2 != null) {
                try {
                    m2.invoke(mod, prompt, cb)
                    Diagnostics.event("generate(String,cb): callback=${cb.calls}, собрано=${cb.sb.length}")
                } catch (e: Throwable) {
                    val msg = (e as? java.lang.reflect.InvocationTargetException)?.targetException?.message ?: e.message
                    Diagnostics.error("generate(String,cb): ${msg?.take(80)}")
                }
            }
            // запас: (String, int, LlmCallback) если двухаргументной нет
            if (cb.sb.isEmpty()) {
                genMethods.firstOrNull {
                    it.parameterTypes.size == 3 && it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2].simpleName == "LlmCallback"
                }?.let { m ->
                    try { m.invoke(mod, prompt, 256, cb)
                        Diagnostics.event("generate(String,int,cb): callback=${cb.calls}, собрано=${cb.sb.length}")
                    } catch (e: Throwable) {
                        val msg = (e as? java.lang.reflect.InvocationTargetException)?.targetException?.message ?: e.message
                        Diagnostics.error("generate(String,int,cb): ${msg?.take(80)}")
                    }
                }
            }
            if (cb.sb.isEmpty()) Diagnostics.error("Генерация пуста: callback=${cb.calls}")
            cb.sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) {
            Diagnostics.error("runGenerate: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            lastStatus = "ошибка генерации: ${e.message?.take(40)}"
            null
        }
    }

    // repetition_penalty > 1, temperature ~0.7, ограничение длины.
    private fun buildGenConfig(cfgCls: Class<*>): Any? {
        return try {
            val builderCls = cfgCls.classes.firstOrNull { it.simpleName == "Builder" }
                ?: return cfgCls.getConstructor().newInstance()
            val builder = builderCls.getConstructor().newInstance()
            // Перебираем сеттеры и задаём нужные параметры по имени.
            builderCls.methods.forEach { m ->
                if (m.parameterTypes.size != 1) return@forEach
                val pt = m.parameterTypes[0]
                try {
                    when {
                        m.name.contains("epetition", true) && (pt == Float::class.javaPrimitiveType || pt == Double::class.javaPrimitiveType) ->
                            m.invoke(builder, if (pt == Float::class.javaPrimitiveType) 1.3f else 1.3)
                        m.name.contains("emperature", true) && (pt == Float::class.javaPrimitiveType || pt == Double::class.javaPrimitiveType) ->
                            m.invoke(builder, if (pt == Float::class.javaPrimitiveType) 0.7f else 0.7)
                        (m.name.contains("eqLen", true) || m.name.contains("axTokens", true) || m.name.contains("axNewTokens", true)) && pt == Int::class.javaPrimitiveType ->
                            m.invoke(builder, 300)
                        m.name.contains("opP", true) && (pt == Float::class.javaPrimitiveType || pt == Double::class.javaPrimitiveType) ->
                            m.invoke(builder, if (pt == Float::class.javaPrimitiveType) 0.9f else 0.9)
                        m.name.equals("setEcho", true) && pt == Boolean::class.javaPrimitiveType ->
                            m.invoke(builder, false)  // не повторять промпт в ответе
                    }
                } catch (_: Throwable) {}
            }
            val cfg = builderCls.getMethod("build").invoke(builder)
            Diagnostics.info("Config создан с repetition_penalty=1.3, temp=0.7")
            cfg
        } catch (e: Throwable) {
            Diagnostics.error("buildGenConfig: ${e.message?.take(80)}")
            null
        }
    }

    /**
     * ЧАНКИНГ для «Чисто»: длинный текст режем на куски и обрабатываем по отдельности —
     * локальная модель НЕ падает на префилле (Prefill failed на длинном). Лимит времени.
     */
    suspend fun processLong(context: Context, systemPrompt: String, text: String,
                            modelId: String,
                            onProgress: ((done: Int, total: Int, partial: String) -> Unit)? = null): String? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            if (text.length <= 120) {
                forceReload()
                val r = generate(context, systemPrompt, text, modelId)
                onProgress?.invoke(1, 1, r ?: text)
                return@withContext r ?: text
            }
            val chunks = splitIntoChunks(text, 100)
            Diagnostics.info("Чанкинг: ${chunks.size} кусков (полная изоляция)")
            val results = ArrayList<String>()
            for ((i, chunk) in chunks.withIndex()) {
                // ПОЛНАЯ ИЗОЛЯЦИЯ: выгружаем модель, грузим заново для ОДНОГО куска.
                // Модель обрабатывает кусок как первый и единственный запрос.
                forceReload()
                val t0 = System.currentTimeMillis()
                val r = generate(context, systemPrompt, chunk, modelId)
                val good = !r.isNullOrBlank() && r.length <= chunk.length * 2 && !isLoopyLocal(r)
                val piece = if (good) r!! else chunk
                results.add(piece)
                Diagnostics.event("Кусок ${i+1}/${chunks.size}: ${if (good) "ОК" else "откат"} (${System.currentTimeMillis()-t0} мс)")
                onProgress?.invoke(i + 1, chunks.size, results.joinToString(" "))
            }
            Diagnostics.info("Чанкинг завершён: ${chunks.size} кусков за ${System.currentTimeMillis()-start} мс")
            results.joinToString(" ").trim().ifBlank { null }
        }

    /** Принудительная выгрузка модели — следующая генерация с чистого состояния. */
    fun forceReload() { releaseCurrent() }

    // Детект зацикливания (фраза повторяется).
    private fun isLoopyLocal(text: String): Boolean {
        val w = text.split(Regex("\\s+")).filter { it.length > 1 }
        if (w.size < 8) return false
        val tri = HashMap<String, Int>()
        for (i in 0..w.size - 3) {
            val k = "${w[i]} ${w[i+1]} ${w[i+2]}".lowercase()
            val c = (tri[k] ?: 0) + 1; tri[k] = c
            if (c >= 3) return true
        }
        return false
    }

    private fun splitIntoChunks(text: String, target: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val chunks = ArrayList<String>()
        var cur = StringBuilder()
        for (s in sentences) {
            if (s.length > target * 1.5) {
                if (cur.isNotEmpty()) { chunks.add(cur.toString().trim()); cur = StringBuilder() }
                val words = s.split(" ")
                val wb = StringBuilder()
                for (w in words) {
                    if (wb.isNotEmpty() && wb.length + w.length > target) {
                        chunks.add(wb.toString().trim()); wb.clear()
                    }
                    wb.append(w).append(" ")
                }
                if (wb.isNotEmpty()) cur = StringBuilder(wb)
            } else if (cur.isNotEmpty() && cur.length + s.length > target) {
                chunks.add(cur.toString().trim()); cur = StringBuilder(s).append(" ")
            } else cur.append(s).append(" ")
        }
        if (cur.toString().isNotBlank()) chunks.add(cur.toString().trim())
        // объединяем мелкие куски (<40 симв) с предыдущим
        val merged = ArrayList<String>()
        for (c in chunks) {
            if (merged.isNotEmpty() && c.length < 40) merged[merged.size - 1] = merged.last() + " " + c
            else merged.add(c)
        }
        return merged.filter { it.isNotBlank() }
    }

    // Правильный chat-формат Llama 3.2 (instruct). Без этих токенов модель
    // не понимает структуру диалога и зацикливается.
    private fun buildPrompt(system: String, user: String): String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
        "$system<|eot_id|><|start_header_id|>user<|end_header_id|>\n" +
        "$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n"

    private fun releaseCurrent() {
        try { module?.let { m -> m.javaClass.getMethod("resetNative").invoke(m) } } catch (_: Throwable) {}
        module = null; loadedId = null
    }

    /** Самопроверка: прогоняет тест по шагам, возвращает отчёт. Результат — в общий лог. */
    suspend fun selfTest(context: Context, modelId: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("Проверка локального ИИ:\n")
        val ready = LocalAiModelManager.isReady(context, modelId)
        sb.append("1. Модель скачана: ${if (ready) "да" else "НЕТ"}\n")
        if (!ready) { sb.append("→ Скачайте модель."); Diagnostics.info("САМОПРОВЕРКА:\n$sb"); return@withContext sb.toString() }
        val tok = LocalAiModelManager.tokenizerFile(context)
        sb.append("2. Токенизатор: ${if (tok.exists() && tok.length() > 1000) "есть (${tok.length()} б)" else "НЕТ"}\n")
        val cls = moduleClass()
        sb.append("3. Класс ExecuTorch: ${if (cls != null) "найден" else "НЕ НАЙДЕН"}\n")
        if (cls == null) { sb.append("→ Библиотека не подключилась."); Diagnostics.info("САМОПРОВЕРКА:\n$sb"); return@withContext sb.toString() }
        val t0 = System.currentTimeMillis()
        val mod = loadModule(context, modelId)
        sb.append("4. Загрузка модели: ${if (mod != null) "успех (${System.currentTimeMillis()-t0} мс)" else "ПРОВАЛ"}\n")
        if (mod == null) { sb.append("→ Модель не загрузилась."); Diagnostics.info("САМОПРОВЕРКА:\n$sb"); return@withContext sb.toString() }
        val t1 = System.currentTimeMillis()
        val sys = "Ответь одним словом."; val usr = "Скажи: привет"
        val fp = buildPrompt(sys, usr)
        val out = cleanResponse(runGenerate(mod, fp), fp, sys, usr)
        val genOk = !out.isNullOrBlank()
        if (genOk) {
            sb.append("5. Генерация: РАБОТАЕТ (${System.currentTimeMillis()-t1} мс)\n")
            sb.append("   ответ: ").append(out!!.take(60)).append("\n")
        } else sb.append("5. Генерация: пустой результат\n")
        sb.append("\nИтог: ").append(if (genOk) "OK Локальный ИИ работает!" else "Модель грузится, но не генерирует.")
        lastStatus = if (genOk) "работает" else "генерация пустая"
        Diagnostics.info("САМОПРОВЕРКА:\n$sb")
        sb.toString()
    }
}
