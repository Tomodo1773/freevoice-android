package com.tomodo.freevoice.ime

import com.tomodo.freevoice.audio.WavRecorder
import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.network.LangsmithTracer
import com.tomodo.freevoice.network.VoiceApiClient
import com.tomodo.freevoice.network.sinkFor
import com.tomodo.freevoice.network.toVoiceApiConfig
import java.io.File

/** WAV header size; a file this small holds no samples. */
private const val WAV_HEADER_BYTES = 44L

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

internal class SettingsVoiceGateway(
    private val settings: SecureSettingsRepository,
    private val topicContext: TopicContextStore,
    private val cacheDir: File,
    private val diagnostics: DiagLogger,
    private val tracer: LangsmithTracer? = null,
    private val onInterim: (String) -> Unit,
    private val onStopSignal: () -> Unit,
) : VoiceInputController.Formatter {
    @Volatile private var client: VoiceApiClient? = null
    @Volatile private var activeSettings: AppSettings? = null

    /**
     * Builds the session for one recording, and is the only place that knows the two
     * transcription providers differ.  Settings are read here so a change made
     * mid-recording cannot alter the job that is already running.
     */
    fun createSession(): VoiceInputController.VoiceSession {
        val current = settings.load()
        activeSettings = current
        val activeClient = VoiceApiClient(current.toVoiceApiConfig(), tracer.sinkFor(current))
        client = activeClient
        return when (current.transcriptionProvider) {
            TranscriptionProvider.AZURE_OPENAI -> BatchVoiceSession(
                cacheDir = cacheDir,
                client = activeClient,
                transcribe = activeClient::transcribeAzureOpenAi,
                onStopSignal = onStopSignal,
            )
            TranscriptionProvider.AZURE_SPEECH -> StreamingVoiceSession(
                endpoint = current.speechEndpoint,
                apiKey = current.transcriptionApiKey,
                language = current.speechLanguage,
                diagnostics = diagnostics,
                onInterim = onInterim,
                onStopSignal = onStopSignal,
            )
        }
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

    override fun cancel() {
        client?.cancel()
    }
}
