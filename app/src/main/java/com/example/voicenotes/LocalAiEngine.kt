package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // ── v116: бюджет длины ─────────────────────────────────────────────────────
    // Разбор логов v115: 2-арг generate(prompt, cb) = generate(prompt, seqLen=128, ...).
    // 128 — это ОБЩИЙ лимит токенов (промпт + ответ). Промпт ~400 симв (~90 ток.)
    // оставлял ответу 25-50 токенов — отсюда обрывы «жилых» без «домов.»; промпт
    // 940+ симв не влезал вовсе → callback=0. Поэтому: (а) пробуем вызовы, где seqLen
    // задаётся явно; (б) режем текст на куски под реальный бюджет.
    private const val CHARS_PER_TOKEN = 3.5          // оценка для русского в Qwen (по логам ~4)
    private const val DEFAULT_SEQ_LEN = 128          // лимит 2-арг вызова (LlmModule.DEFAULT_SEQ_LEN)
    private const val WANT_SEQ_LEN = 640             // просим при явном seqLen (в рамках max_context)
    /** Какой вызов generate реально работает: "" (не известно), "4arg", "cfg", "2arg". */
    @Volatile var workingCall: String = ""
        private set
    private val brokenCalls = HashSet<String>()      // вызовы, упавшие в этой сессии — не повторяем
    @Volatile private var budgetVerified = false     // явный seqLen подтверждён реальным длинным ответом
    // Один вызов модели в один момент: параллельные запуски (быстрое переключение
    // тумблера) раньше налезали друг на друга.
    private val genMutex = Mutex()

    /** Текущий общий лимит токенов на вызов (промпт+ответ). */
    private fun seqBudget(): Int = if (workingCall == "2arg" || workingCall == "") DEFAULT_SEQ_LEN else WANT_SEQ_LEN

    /**
     * Сколько символов ТЕКСТА можно отдать модели за один вызов, чтобы ответ поместился.
     * outRatio — во сколько раз ответ длиннее входа (Чисто ≈ 1.0, суммаризация ≈ 0.5).
     */
    fun chunkChars(systemPrompt: String, outRatio: Double): Int {
        val budget = seqBudget()
        val overheadTok = 24 + (systemPrompt.length / CHARS_PER_TOKEN).toInt()   // шаблон чата + инструкция
        val freeTok = (budget - overheadTok - 6).coerceAtLeast(20)
        // вход + ответ = freeTok;  вход*(1+outRatio) = freeTok
        val inTok = (freeTok / (1.0 + outRatio)).toInt()
        return (inTok * CHARS_PER_TOKEN).toInt().coerceIn(60, 900)
    }

    suspend fun generate(context: Context, systemPrompt: String, userText: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
          genMutex.withLock {
            if (!currentCoroutineContext().isActive) return@withContext null   // обработку отменили, пока ждали очередь
            try {
                val tokF = LocalAiModelManager.tokenizerFile(context, modelId)
                Diagnostics.info("Локальный ИИ: модель=$modelId, файл=${LocalAiModelManager.modelFile(context, modelId).name}, скачана=${LocalAiModelManager.isReady(context, modelId)}, токенизатор=${tokF.name}${if (tokF.exists()) "" else " (НЕТ!)"}, бюджет=${seqBudget()} ток.")
                if (!LocalAiModelManager.isReady(context, modelId)) {
                    lastStatus = "модель не скачана"; return@withContext null
                }
                if (!tokF.exists()) {
                    // Токенизатор мог не скачаться (или лежал общий от другой модели) — пробуем добрать.
                    try { LocalAiModelManager.ensureTokenizer(context, modelId) } catch (e: Throwable) {
                        Diagnostics.error("Токенизатор не скачался: ${e.message?.take(60)}") }
                    if (!tokF.exists()) { lastStatus = "нет токенизатора"; return@withContext null }
                }
                if (moduleClass() == null) {
                    lastStatus = "класс ExecuTorch не найден"; return@withContext null
                }
                // Защита от утечки между заметками: если прошлый сброс контекста НЕ сработал,
                // принудительно выгружаем модуль — следующая загрузка будет с чистым состоянием.
                // ВСЕГДА выгружаем модель перед генерацией — каждый вызов "свежий",
                // как первый. Иначе состояние копится → callback=0 (Qwen молчит на
                // последующих вызовах). Это возвращает хорошее поведение Qwen.
                releaseCurrent()
                Diagnostics.info("Модель выгружена перед генерацией (свежий старт)")
                val mod = loadModule(context, modelId)
                if (mod == null) { lastStatus = "модель не загрузилась"; return@withContext null }
                val fullPrompt = buildPrompt(modelId, systemPrompt, userText)
                var raw = runGenerate(mod, fullPrompt)
                // Qwen иногда молчит на первом вызове (callback=0). Повтор один раз.
                if (raw.isNullOrBlank()) {
                    Diagnostics.info("Пустой ответ — повтор генерации")
                    releaseCurrent()
                    val mod2 = loadModule(context, modelId)
                    if (mod2 != null) raw = runGenerate(mod2, fullPrompt)
                }
                // Очищаем ответ от эха промпта и JSON-статистики.
                val cleaned = cleanResponse(raw, fullPrompt, systemPrompt, userText)
                lastStatus = if (cleaned.isNullOrBlank()) "генерация пустая" else "работает"
                if (!cleaned.isNullOrBlank()) Diagnostics.event("Ответ модели (${cleaned.length} симв): \"${cleaned.take(70).replace('\n', ' ')}…\"")
                cleaned
            } catch (e: Throwable) {
                lastStatus = "ошибка: ${e.message?.take(40)}"; null
            }
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
                "<|start_header_id|>", "<|end_header_id|>", "<|python_tag|>",
                "<|im_start|>", "<|im_end|>", "<|endoftext|>")) {
            t = t.replace(tok, " ")
        }
        // хвост шаблона чата, если модель его повторила («assistant\n…»)
        t = t.replace(Regex("^\\s*(system|user|assistant)\\s*\\n"), "")
        t = t.replace(Regex("\\n\\s*(system|user|assistant)\\s*$"), "")
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
            val tok = LocalAiModelManager.tokenizerFile(context, modelId).absolutePath
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
            // Сброс контекста (KV-кэш). Модель и так перезагружена перед вызовом,
            // это дополнительная страховка.
            var resetOk = false
            try { cls.getMethod("resetContext").invoke(mod); resetOk = true }
            catch (_: Throwable) {
                for (name in listOf("reset", "resetNative", "resetKVCache")) {
                    try { cls.getMethod(name).invoke(mod); resetOk = true; break }
                    catch (_: Throwable) {}
                }
            }
            lastResetOk = resetOk

            val cb = LocalAiCallback()
            val promptTok = (prompt.length / CHARS_PER_TOKEN).toInt() + 8
            Diagnostics.event("Вызываю generate, длина промпта=${prompt.length} симв (~$promptTok ток.)")

            fun unwrap(e: Throwable) = (e as? java.lang.reflect.InvocationTargetException)?.targetException?.message ?: e.message
            fun tryCall(tag: String, block: () -> Any?): Boolean {
                if (tag in brokenCalls) return false
                return try {
                    val ret = block()
                    Diagnostics.event("generate[$tag]: вернул=$ret, callback=${cb.calls}, собрано=${cb.sb.length}")
                    if (cb.sb.isNotEmpty()) {
                        // Проверка, что явный seqLen реально действует: если при большом промпте
                        // ответ крошечный — лимит всё равно 128, значит этот путь бесполезен.
                        if (tag != "2arg" && !budgetVerified) {
                            if (cb.calls + promptTok > 150) { budgetVerified = true; Diagnostics.info("Бюджет seqLen подтверждён (>128 токенов за вызов)") }
                            else if (cb.calls < 15 && promptTok > 90) {
                                Diagnostics.error("generate[$tag]: seqLen не действует (ответ ${cb.calls} ток.) → бюджет 128, путь отключён")
                                brokenCalls.add(tag); workingCall = ""; return true
                            }
                        }
                        workingCall = tag; true
                    } else { brokenCalls.add(tag); false }
                } catch (e: Throwable) {
                    Diagnostics.error("generate[$tag]: ${unwrap(e)?.take(80)} → больше не пробую в этой сессии")
                    brokenCalls.add(tag); false
                }
            }

            // 1) generate(String, int seqLen, LlmCallback, boolean echo) — явный лимит, без эха.
            //    (3-арг (String,int,cb) в этой сборке ВСЕГДА давал «Prefill failed» — это
            //    устаревший путь через prefillPrompt; его больше не трогаем.)
            val seqLen = maxOf(WANT_SEQ_LEN, promptTok + 64)
            val m4 = genMethods.firstOrNull {
                it.parameterTypes.size == 4 && it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2].simpleName == "LlmCallback" &&
                it.parameterTypes[3] == Boolean::class.javaPrimitiveType
            }
            if (m4 != null && cb.sb.isEmpty()) tryCall("4arg") { m4.invoke(mod, prompt, seqLen, cb, false) }

            // 2) generate(String, LlmGenerationConfig, LlmCallback) — конфиг с seqLen/maxNewTokens.
            if (cb.sb.isEmpty()) {
                val mCfg = genMethods.firstOrNull {
                    it.parameterTypes.size == 3 && it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1].simpleName.contains("GenerationConfig") &&
                    it.parameterTypes[2].simpleName == "LlmCallback"
                }
                if (mCfg != null) {
                    val cfg = buildGenConfig(mCfg.parameterTypes[1], seqLen)
                    if (cfg != null) tryCall("cfg") { mCfg.invoke(mod, prompt, cfg, cb) }
                }
            }

            // 3) запас: generate(String, LlmCallback) — работает всегда, но лимит 128 токенов ВСЕГО
            //    (промпт+ответ) и с эхом промпта. Куски текста под этот бюджет режет chunkChars().
            if (cb.sb.isEmpty()) {
                val m2 = genMethods.firstOrNull {
                    it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
                }
                if (m2 != null) tryCall("2arg") { m2.invoke(mod, prompt, cb) }
            }
            if (cb.sb.isEmpty()) Diagnostics.error("Генерация пуста: callback=${cb.calls} (промпт ~$promptTok ток. при бюджете ${seqBudget()})")
            cb.sb.toString().trim().ifBlank { null }
        } catch (e: Throwable) {
            Diagnostics.error("runGenerate: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            lastStatus = "ошибка генерации: ${e.message?.take(40)}"
            null
        }
    }

    // repetition_penalty > 1, temperature ~0.7, ограничение длины.
    private fun buildGenConfig(cfgCls: Class<*>, seqLen: Int): Any? {
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
                        m.name.contains("eqLen", true) && pt == Int::class.javaPrimitiveType ->
                            m.invoke(builder, seqLen)
                        (m.name.contains("axTokens", true) || m.name.contains("axNewTokens", true)) && pt == Int::class.javaPrimitiveType ->
                            m.invoke(builder, WANT_SEQ_LEN)
                        m.name.contains("opP", true) && (pt == Float::class.javaPrimitiveType || pt == Double::class.javaPrimitiveType) ->
                            m.invoke(builder, if (pt == Float::class.javaPrimitiveType) 0.9f else 0.9)
                        m.name.equals("setEcho", true) && pt == Boolean::class.javaPrimitiveType ->
                            m.invoke(builder, false)  // не повторять промпт в ответе
                    }
                } catch (_: Throwable) {}
            }
            val cfg = builderCls.getMethod("build").invoke(builder)
            Diagnostics.info("Config создан: seqLen=$seqLen, temp=0.7, echo=false")
            cfg
        } catch (e: Throwable) {
            Diagnostics.error("buildGenConfig: ${e.message?.take(80)}")
            null
        }
    }

    /**
     * ЧАНКИНГ для «Чисто»: длинный текст режем на куски ПОД БЮДЖЕТ ТОКЕНОВ модели
     * и обрабатываем по отдельности. Каждый кусок — свежая загрузка модели (generate).
     * Кусок, который модель испортила/не вернула, остаётся как был (текст не теряется).
     */
    suspend fun processLong(context: Context, systemPrompt: String, text: String,
                            modelId: String,
                            onProgress: ((done: Int, total: Int, partial: String) -> Unit)? = null,
                            chunkOk: ((chunk: String, result: String) -> Boolean)? = null): String? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            var target = chunkChars(systemPrompt, outRatio = 1.1)
            Diagnostics.info("Чанкинг Чисто: текст ${text.length} симв, куски по ~$target симв (бюджет ${seqBudget()} ток.)")
            val results = ArrayList<String>()
            var okCount = 0; var i = 0
            var remaining = text.trim()
            // Размер куска пересчитываем ПЕРЕД каждым вызовом: после первого вызова становится
            // ясно, какой бюджет реально доступен (128 или больше), и куски меняются.
            while (remaining.isNotBlank()) {
                if (!currentCoroutineContext().isActive) { Diagnostics.info("Чанкинг прерван (отмена)"); break }
                target = chunkChars(systemPrompt, outRatio = 1.1)
                val (chunk, rest) = nextChunk(remaining, target)
                remaining = rest
                val total = i + 1 + (rest.length + target - 1) / target
                i++
                val t0 = System.currentTimeMillis()
                val r = generate(context, systemPrompt, chunk, modelId)
                val good = !r.isNullOrBlank() && r.length <= chunk.length * 2 && r.length >= chunk.length / 2 &&
                    !isLoopyLocal(r) && (chunkOk?.invoke(chunk, r) ?: true)
                val piece = if (good) r!! else chunk
                if (good) okCount++
                results.add(piece)
                Diagnostics.event("Кусок $i/$total (${chunk.length} симв): ${if (good) "ОК" else "откат (оставлен как был)"} (${System.currentTimeMillis()-t0} мс)")
                onProgress?.invoke(i, total, results.joinToString(" "))
            }
            // если прервали — дописываем необработанный остаток как есть
            if (remaining.isNotBlank()) results.add(remaining)
            Diagnostics.info("Чанкинг завершён: успешно $okCount из $i за ${System.currentTimeMillis()-start} мс")
            if (okCount == 0) return@withContext null   // модель не справилась ни с одним куском → пусть решает вызывающий
            results.joinToString(" ").trim().ifBlank { null }
        }

    /**
     * СУММАРИЗАЦИЯ длинного текста (Кратко/Суть) по схеме «карта → свёртка»:
     * каждый кусок → короткий пересказ; пересказы склеиваются; если склейка всё ещё
     * не влезает в бюджет — повторяем раунд; в конце один финальный проход с itogPrompt.
     * Для короткого текста — один вызов.
     */
    suspend fun summarize(context: Context, chunkPrompt: String, finalPrompt: String, text: String,
                          modelId: String,
                          onProgress: ((done: Int, total: Int, partial: String) -> Unit)? = null): String? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val target = chunkChars(finalPrompt, outRatio = 0.6)
            if (text.length <= target) {
                return@withContext generate(context, finalPrompt, text, modelId)?.takeIf { !isLoopyLocal(it) }
            }
            var current = text
            var round = 0
            var totalCalls = 0
            while (current.length > target && round < 4) {
                round++
                val chunks = splitIntoChunks(current, chunkChars(chunkPrompt, outRatio = 0.5))
                Diagnostics.info("Суммаризация, раунд $round: ${chunks.size} кусков (текст ${current.length} симв)")
                val parts = ArrayList<String>()
                for ((i, ch) in chunks.withIndex()) {
                    if (!currentCoroutineContext().isActive) { Diagnostics.info("Суммаризация прервана (отмена)"); return@withContext null }
                    val r = generate(context, chunkPrompt, ch, modelId)
                    totalCalls++
                    val good = !r.isNullOrBlank() && !isLoopyLocal(r) && r.length < ch.length * 1.2
                    // кусок, который модель не смогла пересказать, берём укороченным правилами
                    parts.add(if (good) r!!.trim() else TextCondenser.condense(ch, Level.BRIEF))
                    onProgress?.invoke(i + 1, chunks.size, parts.joinToString(" "))
                }
                val next = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                if (next.isBlank() || next.length >= current.length) {
                    // не сжимается — выходим с тем, что есть
                    Diagnostics.info("Суммаризация: раунд не сжал текст, стоп")
                    current = next.ifBlank { current }; break
                }
                current = next
            }
            if (!currentCoroutineContext().isActive) return@withContext null
            // финальный проход: из склейки пересказов делаем итог
            val fin = if (current.length <= target) generate(context, finalPrompt, current, modelId)
                      else generate(context, finalPrompt, current.take(target), modelId)
            totalCalls++
            Diagnostics.info("Суммаризация завершена: $totalCalls вызовов, ${System.currentTimeMillis()-start} мс")
            val res = fin?.takeIf { !isLoopyLocal(it) } ?: current
            res.trim().ifBlank { null }
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

    /** Отрезает от текста первый кусок ~target символов по границе предложения/слова. */
    private fun nextChunk(text: String, target: Int): Pair<String, String> {
        val t = text.trim()
        if (t.length <= target * 1.3) return t to ""
        // граница предложения в окне [target/2, target*1.3]
        val hi = (target * 1.3).toInt().coerceAtMost(t.length - 1)
        val lo = target / 2
        var cut = -1
        for (j in hi downTo lo) { if (t[j] == '.' || t[j] == '!' || t[j] == '?') { cut = j + 1; break } }
        if (cut < 0) { // по слову
            val sp = t.lastIndexOf(' ', target)
            cut = if (sp > lo) sp else target
        }
        return t.substring(0, cut).trim() to t.substring(cut).trim()
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

    // Chat-формат ЗАВИСИТ ОТ МОДЕЛИ. v115 и раньше Qwen кормили форматом Llama —
    // Qwen эти токены не знает, видит их как мусорный текст, теряет структуру
    // «инструкция → текст → ответ» и вдобавок тратит на них бюджет токенов.
    //  • Qwen 2.5  — ChatML: <|im_start|>role … <|im_end|>
    //  • Llama 3.2 — <|begin_of_text|><|start_header_id|>role<|end_header_id|> … <|eot_id|>
    private fun buildPrompt(modelId: String, system: String, user: String): String =
        if (modelId.startsWith("qwen"))
            "<|im_start|>system\n$system<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n" +
            "<|im_start|>assistant\n"
        else
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
        val tok = LocalAiModelManager.tokenizerFile(context, modelId)
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
        val fp = buildPrompt(modelId, sys, usr)
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
