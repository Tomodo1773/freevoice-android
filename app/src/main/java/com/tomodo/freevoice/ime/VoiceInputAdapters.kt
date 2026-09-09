package com.tomodo.freevoice.ime

import com.tomodo.freevoice.audio.WavRecorder
import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.network.ChainSpan
import com.tomodo.freevoice.network.ChatMessage
import com.tomodo.freevoice.network.LangsmithConfig
import com.tomodo.freevoice.network.LangsmithTracer
import com.tomodo.freevoice.network.LlmSpan
import com.tomodo.freevoice.network.VoiceApiClient
import com.tomodo.freevoice.network.configFor
import com.tomodo.freevoice.network.genAiSystem
import com.tomodo.freevoice.network.toVoiceApiConfig
import java.io.File

/** WAV header size; a file this small holds no samples. */
private const val WAV_HEADER_BYTES = 44L

private const val TRANSCRIBE_SPAN = "transcribe"

/**
 * Records the whole utterance, then uploads it once.  Nothing leaves the device
 * until the user stops speaking, so the wait scales with how long they talked.
 */
internal class BatchVoiceSession(
    cacheDir: File,
    private val client: VoiceApiClient,
    private val transcribe: (File) -> String,
    onStopSignal: () -> Unit,
) : VoiceInputController.VoiceSession {
    private val recorder = WavRecorder(cacheDir, onStopSignal)

    override fun start() = recorder.start()

    override fun finish(): String {
        val wav = try {
            recorder.stop()
        } catch (error: Exception) {
            throw UserVisibleException("録音を終了できなかった", error)
        } ?: throw UserVisibleException("音声が録音されていない")

        return try {
            if (wav.length() <= WAV_HEADER_BYTES) throw UserVisibleException("音声が録音されていない")
            transcribe(wav)
        } finally {
            wav.delete()
        }
    }

    override fun cancel() {
        recorder.cancel()
        client.cancel()
    }
}

/**
 * 文字起こしを 1 か所で計測する。3 プロバイダーのセッション実装には手を入れない。
 * STT は chat ではないが、LangChain の voice-agents-tracing に倣って LLM スパンで表す。
 * 所要時間に発話時間が含まれるのは、ストリーミング認識が喋っている間ずっと続くという事実の反映。
 */
internal class TracedVoiceSession(
    private val delegate: VoiceInputController.VoiceSession,
    private val system: String,
    private val model: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSpan: (LlmSpan) -> Unit,
) : VoiceInputController.VoiceSession {
    private var startedAtMs = 0L

    override fun start() {
        startedAtMs = now()
        delegate.start()
    }

    override fun finish(): String = try {
        delegate.finish().also { emit(completion = it.trim()) }
    } catch (error: Exception) {
        emit(error = error.message ?: error.javaClass.simpleName)
        throw error
    }

    /** キャンセルは録音そのものが無かったことになるので、スパンを残さない。 */
    override fun cancel() = delegate.cancel()

    private fun emit(completion: String? = null, error: String? = null) = onSpan(
        LlmSpan(
            spanName = TRANSCRIBE_SPAN,
            system = system,
            requestModel = model,
            messages = listOf(ChatMessage("user", "audio_segment")),
            completion = completion,
            startTimeMs = startedAtMs,
            endTimeMs = now(),
            errorMessage = error,
        ),
    )
}

/**
 * 1 録音ぶんの子スパンを溜め、テキストが入力できたときだけ root ごと 1 リクエストで送る。
 * LangSmith は親が結局送られてこない子スパンを破棄するため、都度送ると無音・認識エラー・
 * キャンセルで終わった録音が孤児スパンになる。溜めて捨てれば、それが構造的に起きない。
 * 所有ジョブは常に 1 件（ADR 0001）なのでバッファも 1 枠でよく、世代で古いジョブを弾く。
 */
internal class RecordingTrace(
    private val configFor: (AppSettings) -> LangsmithConfig?,
    private val send: (LangsmithConfig, ChainSpan, List<LlmSpan>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(tracer: LangsmithTracer?) : this(
        configFor = { tracer.configFor(it) },
        send = { config, root, children -> tracer?.send(config, root, children) },
    )

    private val lock = Any()
    private var generation = 0L
    private var config: LangsmithConfig? = null
    private var startedAtMs = 0L
    private val children = mutableListOf<LlmSpan>()

    /** 設定が揃っていなければ null を返し、呼び出し側でスパン組み立てごと省かせる。 */
    fun begin(settings: AppSettings): Long? = synchronized(lock) {
        generation++
        children.clear()
        startedAtMs = now()
        config = configFor(settings)
        if (config == null) null else generation
    }

    fun add(generation: Long, span: LlmSpan) = synchronized(lock) {
        if (config != null && this.generation == generation) children += span
    }

    /**
     * 実際に入力できたテキストで root を閉じ、この録音ぶんをまとめて送る。
     * send は executor へ渡すだけなので、ロックを持ったまま呼んでよい。
     */
    fun flush(output: String) = synchronized(lock) {
        val active = config
        val spans = children.toList()
        val startedAt = startedAtMs
        discardLocked()
        if (active == null || spans.isEmpty()) return@synchronized
        send(
            active,
            ChainSpan(
                input = spans.firstOrNull { it.spanName == TRANSCRIBE_SPAN }?.completion.orEmpty(),
                output = output,
                startTimeMs = startedAt,
                endTimeMs = now(),
            ),
            spans,
        )
    }

    /** 何度呼んでも安全。録音を捨てた印として世代を進める。 */
    fun discard() = synchronized(lock) { discardLocked() }

    private fun discardLocked() {
        generation++
        config = null
        children.clear()
    }
}

internal class SettingsVoiceGateway(
    private val settings: SecureSettingsRepository,
    private val topicContext: TopicContextStore,
    private val cacheDir: File,
    private val diagnostics: DiagLogger,
    tracer: LangsmithTracer? = null,
    private val onInterim: (String) -> Unit,
    private val onStopSignal: () -> Unit,
) : VoiceInputController.Formatter {
    private val trace = RecordingTrace(tracer)
    @Volatile private var client: VoiceApiClient? = null
    @Volatile private var activeSettings: AppSettings? = null

    /**
     * Builds the session for one recording, and is the only place that knows the three
     * transcription providers differ.  Settings are read here so a change made
     * mid-recording cannot alter the job that is already running.
     */
    fun createSession(): VoiceInputController.VoiceSession {
        val current = settings.load()
        activeSettings = current
        val provider = current.transcriptionProvider
        val transcription = current.transcriptionProfiles[provider]
        val generation = trace.begin(current)
        val sink = generation?.let { job -> { span: LlmSpan -> trace.add(job, span) } }
        val activeClient = VoiceApiClient(current.toVoiceApiConfig(), sink)
        client = activeClient
        val session = when (provider) {
            TranscriptionProvider.AZURE_OPENAI -> BatchVoiceSession(
                cacheDir = cacheDir,
                client = activeClient,
                transcribe = activeClient::transcribeAzureOpenAi,
                onStopSignal = onStopSignal,
            )
            TranscriptionProvider.AZURE_SPEECH -> StreamingVoiceSession(
                endpoint = transcription.endpoint,
                apiKey = transcription.apiKey,
                language = current.speechLanguage,
                diagnostics = diagnostics,
                onInterim = onInterim,
                onStopSignal = onStopSignal,
            )
            TranscriptionProvider.GEMINI_LIVE -> GeminiLiveVoiceSession(
                apiKey = transcription.apiKey,
                model = transcription.model,
                language = current.speechLanguage,
                diagnostics = diagnostics,
                onInterim = onInterim,
                onStopSignal = onStopSignal,
            )
        }
        return sink?.let { TracedVoiceSession(session, provider.genAiSystem, transcription.model, onSpan = it) } ?: session
    }

    override fun format(text: String, packageName: String): VoiceInputController.FormattedText {
        val current = activeSettings ?: return VoiceInputController.FormattedText(text, false)
        if (!current.formatEnabled) return VoiceInputController.FormattedText(text, false)
        val activeClient = client ?: return VoiceInputController.FormattedText(text, false)
        val result = activeClient.format(
            original = text,
            prompt = current.postprocessPrompt,
            context = if (current.contextAwareFormatting) topicContext.get(packageName) else null,
        )
        return VoiceInputController.FormattedText(result.text, result.fallback)
    }

    /** 実際に入力できたテキストで、この録音ぶんのトレースを送る。 */
    fun finish(text: String) = trace.flush(text)

    /** 録音を捨てる唯一の口。キャンセルも失敗も挿入失敗もここへ寄せる。 */
    override fun cancel() {
        client?.cancel()
        trace.discard()
    }
}
