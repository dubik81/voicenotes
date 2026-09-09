package com.example.voicenotes

import android.content.Context
import java.io.File
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

    // ── v118: изоляция заметок вместо перезагрузки перед каждым вызовом ────────
    // До v118 модель выгружалась ПЕРЕД КАЖДОЙ генерацией (v100) — это лечило «Qwen
    // молчит на повторах», но настоящей причиной был сломанный токенизатор (модель не
    // читала кириллицу). Цена: на лекции 40 перезагрузок × 3 с = 120 с и сильный нагрев
    // (каждый раз читается файл модели на 3 ГБ).
    // Теперь выгружаем только там, где это реально защищает:
    //   • смена заметки — гарантия против утечки текста между заметками (задача №4, v75);
    //   • resetContext не сработал;
    //   • пустой ответ — один повтор с чистого состояния;
    //   • сторож поймал утечку.
    // Заметка, для которой модель генерировала ПОСЛЕДНИЙ раз. Сравнение идёт внутри
    // genMutex по id, переданному вместе с запросом: фон может обрабатывать две заметки
    // вперемешку, и глобальный «текущий id» тут был бы ненадёжен.
    @Volatile private var lastGenNote: Long = Long.MIN_VALUE
    // Текст ПРЕДЫДУЩЕЙ заметки — эталон для сторожа утечек (см. leakFragment).
    @Volatile private var prevNoteText: String = ""
    private val noteTexts = HashMap<Long, String>()
    /** Сколько раз сторож поймал утечку за сессию (видно в снимке окружения). */
    @Volatile var leaksCaught: Int = 0
        private set
    // Счётчики для контроля скорости по чёрному ящику: время генерации прямо
    // пропорционально числу выданных символов, а перезагрузки — это по 3 с каждая.
    @Volatile var reloads: Int = 0
        private set
    @Volatile var generations: Int = 0
        private set
    @Volatile var charsGenerated: Long = 0
        private set
    @Volatile var genMillis: Long = 0
        private set

    /**
     * Запоминает исходный текст заметки — эталон для сторожа утечек. Сама перезагрузка
     * модели при смене заметки происходит в generate() по переданному noteId.
     */
    @Synchronized
    fun beginNote(noteId: Long, sourceText: String) {
        if (sourceText.isNotBlank()) noteTexts[noteId] = sourceText
        // не даём словарю расти бесконечно — эталон нужен только для соседних заметок
        if (noteTexts.size > 8) {
            val keep = noteTexts.entries.take(4).associate { it.key to it.value }
            noteTexts.clear(); noteTexts.putAll(keep)
        }
    }

    /**
     * СТОРОЖ УТЕЧЕК. Если resetContext подведёт, модель может подмешать текст прошлой
     * заметки. Признак: цепочка из 4+ слов подряд, которая ЕСТЬ в прошлой заметке и
     * ОТСУТСТВУЕТ в текущем источнике. Случайное совпадение четырёх слов подряд
     * практически невозможно, поэтому ложных срабатываний нет.
     */
    private fun leakFragment(result: String, source: String): String? {
        if (prevNoteText.length < 40) return null
        fun words(s: String) = s.lowercase().split(Regex("[^а-яёa-z0-9]+")).filter { it.isNotBlank() }
        val prev = words(prevNoteText); if (prev.size < 4) return null
        val prevSet = HashSet<String>()
        for (i in 0..prev.size - 4) prevSet.add(prev.subList(i, i + 4).joinToString(" "))
        val src = words(source)
        val srcSet = HashSet<String>()
        for (i in 0..maxOf(0, src.size - 4)) if (src.size >= 4) srcSet.add(src.subList(i, i + 4).joinToString(" "))
        val res = words(result)
        for (i in 0..res.size - 4) {
            val key = res.subList(i, i + 4).joinToString(" ")
            if (key in prevSet && key !in srcSet) return key
        }
        return null
    }

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
    // Отдельный замок на пробу режима токенизатора (её могут запустить одновременно
    // обработка и самопроверка — раньше модель гонялась дважды).
    private val probeMutex = Mutex()

    // ── v117: режим промпта/токенизатора: "llama" (формат Llama 3, рабочий в v98–v102),
    //    "chatml" (родной Qwen), "tiktoken" (словарь конвертирован, ChatML через псевдонимы).
    // Определяется пробами один раз и запоминается в настройках; самопроверка сбрасывает.
    @Volatile var tokMode: String = ""
        private set
    private const val PREF = "localai_prefs"
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun loadTokMode(context: Context, modelId: String): String {
        if (tokMode.isBlank()) tokMode = prefs(context).getString("tokmode_$modelId", "") ?: ""
        return tokMode
    }
    private fun saveTokMode(context: Context, modelId: String, mode: String) {
        tokMode = mode; prefs(context).edit().putString("tokmode_$modelId", mode).apply()
    }
    fun resetTokMode(context: Context, modelId: String) = saveTokMode(context, modelId, "")

    /** Путь к токенизатору с учётом режима. */
    private fun tokenizerPath(context: Context, modelId: String): String =
        if (tokMode == "tiktoken" && modelId.startsWith("qwen") && LocalAiModelManager.tiktokenFile(context, modelId).exists())
            LocalAiModelManager.tiktokenFile(context, modelId).absolutePath
        else LocalAiModelManager.tokenizerFile(context, modelId).absolutePath

    /**
     * Ответ похож на отказ ассистента («Извините, я не могу…») — это не результат.
     * v118: раньше искали извинение ГДЕ УГОДНО в первых 120 символах — и забраковали
     * хороший пересказ лекции, где слово «извините» произнёс сам лектор («…на этом конец
     * лекции, извините, что…»). Теперь отказ = извинение В НАЧАЛЕ ответа И рядом отказная
     * формула («не могу», "cannot"). Одно без другого отказом не считается.
     */
    fun isRefusal(t: String): Boolean {
        val l = t.trim().lowercase().take(160)
        val opensWithApology = listOf("извините", "простите", "к сожалению", "прошу прощения",
            "i apologize", "i'm sorry", "i am sorry", "sorry,", "as an ai").any { l.startsWith(it) }
        val opensWithRefusal = listOf("я не могу", "не могу помочь", "не могу понять", "не могу воспроизвести",
            "i cannot", "i can't", "i'm not able", "i am not able").any { l.startsWith(it) }
        if (opensWithRefusal) return true
        if (!opensWithApology) return false
        return listOf("не мог", "не в состоянии", "cannot", "can't", "not able", "unable")
            .any { l.contains(it) }
    }

    /**
     * ПРОБА РЕЖИМА ПРОМПТА (один раз, результат запоминается; самопроверка повторяет).
     * Факт из истории проекта: Qwen давала отличный русский результат в v98–v102, когда
     * промпт строился в формате Llama (тем же токенизатором). После перевода на ChatML (v116)
     * модель стала отвечать отказами «не могу понять ваш текст». Поэтому порядок проб:
     *   1) "llama"   — формат Llama 3 (ИЗВЕСТНО РАБОЧИЙ для этой сборки) — если русский читается, стоп;
     *   2) "chatml"  — родной формат Qwen;
     *   3) "tiktoken"— словарь конвертирован в tiktoken + ChatML через псевдонимы.
     * Проба = «Повтори это слово: яблоко» → в ответе должно быть «яблоко».
     */
    private suspend fun ensureTokMode(context: Context, modelId: String) {
        if (!modelId.startsWith("qwen")) { if (tokMode.isBlank()) tokMode = "llama"; return }
        if (loadTokMode(context, modelId).isNotBlank()) return
        // v118: пробу запускали и generate, и самопроверка — они шли ПАРАЛЛЕЛЬНО и гоняли
        // модель дважды (в логе v117 сдвоенные строки «Токенизатор для загрузки»). Один
        // замок на пробу: второй вызов ждёт и уходит по готовому результату.
        probeMutex.withLock {
            if (loadTokMode(context, modelId).isNotBlank()) return
            probeModes(context, modelId)
        }
    }

    private suspend fun probeModes(context: Context, modelId: String) {
        Diagnostics.info("ПРОБА РЕЖИМА ПРОМПТА (один раз): llama → chatml → tiktoken; тест «яблоко»")
        for (mode in listOf("llama", "chatml", "tiktoken")) {
            try {
                if (mode == "tiktoken") {
                    val tt = LocalAiModelManager.tiktokenFile(context, modelId)
                    if (!tt.exists()) {
                        val t0 = System.currentTimeMillis()
                        val r = LocalAiModelManager.convertJsonToTiktoken(LocalAiModelManager.tokenizerFile(context, modelId), tt)
                        Diagnostics.info("tiktoken создан: $r, ${tt.length()} б за ${System.currentTimeMillis()-t0} мс")
                    }
                }
                tokMode = mode; releaseCurrent()
                val ru = probe(context, modelId, "Ответь одним словом.", "Повтори это слово: яблоко", "яблоко")
                Diagnostics.info("Проба [$mode]: русский=${if (ru.first) "ЧИТАЕТ" else "нет"} («${ru.second}»)")
                if (ru.first) { saveTokMode(context, modelId, mode); releaseCurrent(); return }
            } catch (e: Throwable) {
                Diagnostics.error("Проба [$mode] упала: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
        releaseCurrent()
        saveTokMode(context, modelId, "llama")
        Diagnostics.error("Ни один режим не прошёл пробу «яблоко» — остаюсь на llama (рабочий в v98–v102)")
    }

    private fun probe(context: Context, modelId: String, sys: String, usr: String, expect: String): Pair<Boolean, String> {
        return try {
            releaseCurrent()
            val mod = loadModule(context, modelId) ?: return false to "модель не загрузилась"
            val fp = buildPrompt(modelId, sys, usr)
            val out = cleanResponse(runGenerate(mod, fp), fp, sys, usr) ?: return false to "пусто"
            (out.lowercase().contains(expect)) to out.take(60).replace('\n', ' ')
        } catch (e: Throwable) { false to "ошибка: ${e.message?.take(40)}" }
    }

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

    suspend fun generate(context: Context, systemPrompt: String, userText: String, modelId: String,
                         maxNewTok: Int = 0, noteId: Long = Long.MIN_VALUE): String? =
        withContext(Dispatchers.IO) {
          genMutex.withLock {
            if (!currentCoroutineContext().isActive) return@withContext null   // обработку отменили, пока ждали очередь
            try {
                val tokF = LocalAiModelManager.tokenizerFile(context, modelId)
                Diagnostics.info("Локальный ИИ: модель=$modelId, файл=${LocalAiModelManager.modelFile(context, modelId).name}, скачана=${LocalAiModelManager.isReady(context, modelId)}, токенизатор=${tokF.name}${if (tokF.exists()) "" else " (НЕТ!)"}, бюджет=${seqBudget()} ток., токенизатор-режим=${loadTokMode(context, modelId).ifBlank { "не определён" }}")
                if (!LocalAiModelManager.isReady(context, modelId)) {
                    lastStatus = "модель не скачана"; return@withContext null
                }
                if (!tokF.exists()) {
                    // Токенизатор мог не скачаться (или лежал общий от другой модели) — пробуем добрать.
                    try { LocalAiModelManager.ensureTokenizer(context, modelId) } catch (e: Throwable) {
                        Diagnostics.error("Токенизатор не скачался: ${e.message?.take(60)}") }
                    if (!tokF.exists()) { lastStatus = "нет токенизатора"; return@withContext null }
                }
                ensureTokMode(context, modelId)
                if (moduleClass() == null) {
                    lastStatus = "класс ExecuTorch не найден"; return@withContext null
                }
                // Перезагрузка модели ТОЛЬКО когда она защищает (см. блок про изоляцию
                // заметок наверху файла). Внутри одной заметки модель остаётся в памяти,
                // контекст чистится resetContext в runGenerate.
                // Смена заметки определяется ЗДЕСЬ, под общим замком, по id самого запроса —
                // тогда чередование фоновых обработок двух заметок не может обмануть проверку.
                if (noteId != lastGenNote) {
                    if (lastGenNote != Long.MIN_VALUE) {
                        prevNoteText = noteTexts[lastGenNote].orEmpty()
                        releaseCurrent()
                        Diagnostics.info("Перезагрузка модели: смена заметки (было #$lastGenNote, стало #$noteId)")
                    }
                    lastGenNote = noteId
                } else if (!lastResetOk && module != null) {
                    releaseCurrent()
                    Diagnostics.info("Перезагрузка модели: прошлый resetContext не сработал")
                } else if (module != null) {
                    Diagnostics.info("Модель уже в памяти, сброс контекста (без перезагрузки)")
                }
                val mod = loadModule(context, modelId)
                if (mod == null) { lastStatus = "модель не загрузилась"; return@withContext null }
                val fullPrompt = buildPrompt(modelId, systemPrompt, userText)
                // Сколько токенов ответа просить. Раньше всегда вход×1.4 — для суммаризации
                // это втрое больше нужного, а время генерации прямо пропорционально длине
                // ответа. Теперь уровень передаёт свой лимит (maxNewTok).
                val wantOut = if (maxNewTok > 0) maxNewTok else ((userText.length / CHARS_PER_TOKEN) * 1.4).toInt() + 24
                val tGen0 = System.currentTimeMillis()
                var raw = runGenerate(mod, fullPrompt, wantOut)
                // Qwen иногда молчит на первом вызове (callback=0). Повтор один раз.
                if (raw.isNullOrBlank()) {
                    Diagnostics.info("Пустой ответ — перезагрузка и повтор генерации")
                    releaseCurrent()
                    val mod2 = loadModule(context, modelId)
                    if (mod2 != null) raw = runGenerate(mod2, fullPrompt, wantOut)
                }
                // Очищаем ответ от эха промпта и JSON-статистики.
                var cleaned = cleanResponse(raw, fullPrompt, systemPrompt, userText)
                generations++
                genMillis += System.currentTimeMillis() - tGen0
                charsGenerated += (cleaned?.length ?: 0).toLong()
                lastStatus = if (cleaned.isNullOrBlank()) "генерация пустая" else "работает"
                if (!cleaned.isNullOrBlank()) Diagnostics.event("Ответ модели (${cleaned.length} симв): \"${cleaned.take(70).replace('\n', ' ')}…\"")
                // СТОРОЖ УТЕЧЕК: текст из прошлой заметки в ответе → перезагрузка и один повтор.
                if (!cleaned.isNullOrBlank()) {
                    val leak = leakFragment(cleaned!!, userText)
                    if (leak != null) {
                        leaksCaught++
                        Diagnostics.error("УТЕЧКА: в ответе фрагмент прошлой заметки «$leak» → перезагрузка, повтор")
                        releaseCurrent()
                        val mod3 = loadModule(context, modelId)
                        cleaned = if (mod3 != null)
                            cleanResponse(runGenerate(mod3, fullPrompt, wantOut), fullPrompt, systemPrompt, userText)
                        else null
                        if (cleaned != null && leakFragment(cleaned!!, userText) != null) {
                            Diagnostics.error("УТЕЧКА повторилась → результат отброшен")
                            lastStatus = "утечка контекста"
                            return@withContext null
                        }
                    }
                }
                if (!cleaned.isNullOrBlank() && isRefusal(cleaned!!)) {
                    Diagnostics.error("Ответ модели — ОТКАЗ («${cleaned!!.take(40)}…») → считаем провалом")
                    lastStatus = "модель отказалась"
                    return@withContext null
                }
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
        // Ответ заканчивается на первом служебном маркере конца/новой реплики: с большим
        // seqLen модель может «продолжить диалог» за себя — этот хвост отрезаем.
        for (mark in listOf("<|eot_id|>", "<|end_of_text|>", "<|im_end|>", "<|endoftext|>",
                "<|start_header_id|>", "<|im_start|>", "<|reserved_special_token_0|>")) {
            val i = t.indexOf(mark)
            if (i > 0) t = t.substring(0, i)
        }
        // убрать служебные токены Llama (<|eot_id|>, <|end_of_text|>, заголовки)
        for (tok in listOf("<|eot_id|>", "<|end_of_text|>", "<|begin_of_text|>",
                "<|start_header_id|>", "<|end_header_id|>", "<|python_tag|>",
                "<|im_start|>", "<|im_end|>", "<|endoftext|>",
                "<|reserved_special_token_0|>", "<|reserved_special_token_1|>")) {
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
            reloads++          // реальная загрузка модели с диска (~3 с на файл 3 ГБ)
            val cls = moduleClass() ?: return null
            // Логируем доступные конструкторы.
            Diagnostics.info("Конструкторы LlmModule: ${cls.constructors.joinToString { c -> "(${c.parameterTypes.joinToString{p->p.simpleName}})" }}")
            val path = LocalAiModelManager.modelFile(context, modelId).absolutePath
            val tok = tokenizerPath(context, modelId)
            Diagnostics.info("Токенизатор для загрузки: ${File(tok).name} (режим ${tokMode.ifBlank { "llama" }})")
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

    private fun runGenerate(mod: Any, prompt: String, wantOut: Int = 0): String? {
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
                        if (tag != "2arg" && !budgetVerified && cb.calls + promptTok > 150) {
                            budgetVerified = true; Diagnostics.info("Бюджет seqLen подтверждён (>128 токенов за вызов)")
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
            // Явный лимит: промпт + нужный ответ (+запас). Не просим лишнего: в формате Llama
            // у Qwen нет своего стоп-токена, и лишний бюджет уходит на «продолжение диалога».
            val seqLen = if (wantOut > 0) (promptTok + wantOut + 16).coerceIn(promptTok + 48, 1024)
                         else maxOf(WANT_SEQ_LEN, promptTok + 64)
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
                            modelId: String, noteId: Long = Long.MIN_VALUE,
                            onProgress: ((done: Int, total: Int, partial: String) -> Unit)? = null,
                            chunkOk: ((chunk: String, result: String) -> Boolean)? = null,
                            // v118: правка результата куска ДО проверки — сюда подключена
                            // пословная защита от порчи слов (см. VariantProcessor.restoreWords).
                            chunkFix: ((chunk: String, result: String) -> String)? = null): String? =
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
                // «Чисто» = восстановление того же текста: ответ примерно равен входу.
                // Раньше просили вход×1.4 — лишние токены модель тратила на «продолжение
                // разговора за себя», а время генерации прямо пропорционально их числу.
                val wantOut = ((chunk.length * 1.15) / CHARS_PER_TOKEN).toInt() + 16
                val raw = generate(context, systemPrompt, chunk, modelId, maxNewTok = wantOut, noteId = noteId)
                // Пословная защита: несозвучные замены откатываются к исходным словам.
                val r = if (!raw.isNullOrBlank() && chunkFix != null) chunkFix(chunk, raw) else raw
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
     * СЖАТИЕ ТЕКСТА В ЗАДАННУЮ ДОЛЮ (v118) — для «Кратко» (≈50%) и «Суть» (≈25%).
     *
     * Почему не прежняя «карта → свёртка»: она сжимала КАЖДЫЙ кусок до 1-2 предложений
     * (то есть раз в десять), потом склеивала и повторяла раундами. На многотемной
     * лекции соседние куски сваривались в одну фразу — отсюда «в онлайн-лекциях
     * пользователи формируют текст заголовками и пунками». Плюс 4 раунда генерации.
     *
     * Здесь сжатие ЛОКАЛЬНОЕ: каждый кусок ужимается на своём месте в ту же долю,
     * порядок сохраняется, темы не перемешиваются, раундов нет. Один проход по тексту.
     * Кусок, который модель не осилила, ужимается правилами — текст не теряется.
     *
     * @param ratio доля от исходной длины (0.5 = вдвое короче)
     */
    suspend fun condense(context: Context, prompt: String, text: String, modelId: String, ratio: Double,
                         noteId: Long = Long.MIN_VALUE,
                         onProgress: ((done: Int, total: Int, partial: String) -> Unit)? = null): String? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val target = chunkChars(prompt, outRatio = ratio)
            val chunks = if (text.length <= target) listOf(text.trim()) else splitIntoChunks(text, target)
            Diagnostics.info("Сжатие до ${(ratio * 100).toInt()}%: текст ${text.length} симв, ${chunks.size} кусков по ~$target")
            val parts = ArrayList<String>()
            var okCount = 0
            for ((i, ch) in chunks.withIndex()) {
                if (!currentCoroutineContext().isActive) { Diagnostics.info("Сжатие прервано (отмена)"); break }
                val t0 = System.currentTimeMillis()
                // Просим ровно столько токенов, сколько нужно на долю ratio (+запас 20%).
                val wantOut = ((ch.length * ratio * 1.2) / CHARS_PER_TOKEN).toInt().coerceAtLeast(24)
                val r = generate(context, prompt, ch, modelId, maxNewTok = wantOut, noteId = noteId)
                // Годится, если модель реально сжала (не длиннее входа) и не зациклилась.
                val good = !r.isNullOrBlank() && !isLoopyLocal(r) && r.length <= ch.length &&
                    r.length >= minOf(20, ch.length / 4)
                parts.add(if (good) r!!.trim() else TextCondenser.condense(ch, Level.BRIEF))
                if (good) okCount++
                Diagnostics.event("Сжатие, кусок ${i + 1}/${chunks.size} (${ch.length}→${parts.last().length} симв): " +
                    "${if (good) "модель" else "правила"} (${System.currentTimeMillis() - t0} мс)")
                onProgress?.invoke(i + 1, chunks.size, parts.joinToString(" "))
            }
            val res = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            Diagnostics.info("Сжатие завершено: моделью $okCount из ${chunks.size} кусков, " +
                "${text.length}→${res.length} симв за ${System.currentTimeMillis() - start} мс")
            if (okCount == 0) return@withContext null
            res.ifBlank { null }
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
    // В режиме tiktoken служебные токены Qwen доступны под именами Llama 3 (ID по порядку:
    // <|endoftext|>=<|begin_of_text|>, <|im_start|>=<|end_of_text|>, <|im_end|>=<|reserved_special_token_0|>).
    private fun buildPrompt(modelId: String, system: String, user: String): String =
        if (modelId.startsWith("qwen") && tokMode == "tiktoken")
            "<|end_of_text|>system\n$system<|reserved_special_token_0|>\n" +
            "<|end_of_text|>user\n$user<|reserved_special_token_0|>\n" +
            "<|end_of_text|>assistant\n"
        else if (modelId.startsWith("qwen") && tokMode == "chatml")
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
        resetTokMode(context, modelId)
        ensureTokMode(context, modelId)
        sb.append("3а. Режим промпта по пробе «яблоко»: ${tokMode} (подробности в ЧЯ «Проба [...]»)\n")
        releaseCurrent()
        val mod = loadModule(context, modelId)
        sb.append("4. Загрузка модели: ${if (mod != null) "успех (${System.currentTimeMillis()-t0} мс)" else "ПРОВАЛ"}\n")
        if (mod == null) { sb.append("→ Модель не загрузилась."); Diagnostics.info("САМОПРОВЕРКА:\n$sb"); return@withContext sb.toString() }
        val t1 = System.currentTimeMillis()
        val sys = "Ответь одним словом."; val usr = "Скажи: привет"
        val fp = buildPrompt(modelId, sys, usr)
        val out = cleanResponse(runGenerate(mod, fp), fp, sys, usr)
        // v118: раньше ЛЮБОЙ непустой ответ засчитывался как «РАБОТАЕТ» — и самопроверка
        // писала «OK Локальный ИИ работает!», когда модель отвечала «I apologize, but I'm
        // not able to understand…», то есть не читала русский вообще. Теперь проверяем,
        // что модель ПОНЯЛА задание: в ответе должно быть само слово «привет».
        val genOk = !out.isNullOrBlank()
        val understands = genOk && out!!.lowercase().contains("привет")
        if (!genOk) sb.append("5. Генерация: пустой результат\n")
        else {
            sb.append("5. Генерация: ${if (understands) "РАБОТАЕТ" else "отвечает, но задание НЕ понято"} " +
                "(${System.currentTimeMillis()-t1} мс)\n")
            sb.append("   ответ: ").append(out!!.take(60)).append("\n")
            if (!understands) sb.append("   ожидалось слово «привет» — похоже на проблему токенизатора\n")
        }
        sb.append("\nИтог: ").append(when {
            understands -> "OK Локальный ИИ работает!"
            genOk -> "Модель отвечает, но не понимает русский текст (режим токенизатора: $tokMode)."
            else -> "Модель грузится, но не генерирует."
        })
        lastStatus = if (understands) "работает" else if (genOk) "не понимает русский" else "генерация пустая"
        Diagnostics.info("САМОПРОВЕРКА:\n$sb")
        sb.toString()
    }
}
