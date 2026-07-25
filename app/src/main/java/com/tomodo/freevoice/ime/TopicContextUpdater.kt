package com.tomodo.freevoice.ime

import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.network.VoiceApiClient
import com.tomodo.freevoice.network.toVoiceApiConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Distills committed text into the per-app topic context off the IME thread. */
internal class TopicContextUpdater(
    private val settings: SecureSettingsRepository,
    private val topicContext: TopicContextStore,
    private val diagnostics: DiagLogger,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    @Volatile private var activeClient: VoiceApiClient? = null

    fun update(packageName: String, text: String) {
        val current = settings.load()
        if (
            packageName.isBlank() ||
            !current.formatEnabled ||
            !current.contextAwareFormatting ||
            !topicContext.beginDistill(packageName)
        ) {
            return
        }

        executor.execute {
            try {
                val client = VoiceApiClient(settings.load().toVoiceApiConfig())
                activeClient = client
                val result = client.distill(topicContext.get(packageName).orEmpty(), text)
                if (result.fallback) {
                    diagnostics.warn("context", "Topic context distill fallback: ${result.fallbackReason}")
                } else {
                    topicContext.put(packageName, result.text)
                }
            } finally {
                activeClient = null
                topicContext.finishDistill(packageName)
            }
        }
    }

    override fun close() {
        activeClient?.cancel()
        executor.shutdownNow()
    }
}
