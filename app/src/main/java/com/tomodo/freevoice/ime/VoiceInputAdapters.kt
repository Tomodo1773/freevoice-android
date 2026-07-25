package com.tomodo.freevoice.ime

import com.tomodo.freevoice.audio.WavRecorder
import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.data.TranscriptionProvider
import com.tomodo.freevoice.network.LangsmithTracer
import com.tomodo.freevoice.network.VoiceApiClient
import com.tomodo.freevoice.network.sinkFor
import com.tomodo.freevoice.network.toVoiceApiConfig
import java.io.File

internal class AndroidVoiceRecorder(
    cacheDir: File,
    onMaxDurationReached: () -> Unit,
) : VoiceInputController.Recorder {
    private val recorder = WavRecorder(cacheDir, onMaxDurationReached)

    override fun start() = recorder.start()
    override fun stop() = recorder.stop()
    override fun cancel() = recorder.cancel()
}

internal class SettingsVoiceGateway(
    private val settings: SecureSettingsRepository,
    private val topicContext: TopicContextStore,
    private val tracer: LangsmithTracer? = null,
) : VoiceInputController.Gateway {
    @Volatile private var client: VoiceApiClient? = null
    private var activeSettings: AppSettings? = null

    override fun transcribe(wav: File): String {
        val current = settings.load()
        activeSettings = current
        val activeClient = VoiceApiClient(current.toVoiceApiConfig(), tracer.sinkFor(current))
        client = activeClient
        return when (current.transcriptionProvider) {
            TranscriptionProvider.AZURE_OPENAI -> activeClient.transcribeAzureOpenAi(wav)
            TranscriptionProvider.AZURE_SPEECH -> activeClient.transcribeAzureSpeech(wav)
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
