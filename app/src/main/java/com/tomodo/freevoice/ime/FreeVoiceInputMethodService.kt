package com.tomodo.freevoice.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.inputmethodservice.InputMethodService
import com.tomodo.freevoice.R
import com.tomodo.freevoice.MainActivity
import com.tomodo.freevoice.audio.WavRecorder
import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.history.HistoryRepository
import com.tomodo.freevoice.network.VoiceApiClient
import com.tomodo.freevoice.network.toVoiceApiConfig
import java.io.File
import java.util.concurrent.Executors
import android.view.inputmethod.InputConnection

class FreeVoiceInputMethodService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var mic: Button
    private lateinit var controller: VoiceInputController
    private lateinit var settings: SecureSettingsRepository
    private lateinit var context: TopicContextStore
    private lateinit var history: HistoryRepository
    private lateinit var diagnostics: DiagLogger
    private val contextExecutor = Executors.newSingleThreadExecutor()
    private data class Target(val jobId: Long, val packageName: String, val connection: InputConnection)
    @Volatile private var target: Target? = null

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettingsRepository(this); context = TopicContextStore()
        history = HistoryRepository(this); diagnostics = DiagLogger(this)
        controller = VoiceInputController(AndroidRecorder(cacheDir), ApiGateway(), object : VoiceInputController.Callbacks {
            override fun stateChanged(state: VoiceInputState) { main.post { render(state) } }
            override fun committed(jobId: Long, text: String, packageName: String, formatFallback: Boolean) { main.post {
                val active = target?.takeIf { it.jobId == jobId && it.packageName == packageName }
                val committed = active?.connection?.commitText(text, 1) == true
                clearTarget(jobId)
                if (!committed) {
                    history.add("", false, "入力先へ文字を挿入できなかった")
                    diagnostics.log("InputConnection.commitText failed")
                    return@post
                }
                history.add(text, true); diagnostics.log("Voice input committed (${text.length} chars)")
                if (!formatFallback) updateContextAsync(packageName, text)
            } }
            override fun failed(jobId: Long, message: String, error: Throwable?) { main.post {
                clearTarget(jobId); history.add("", false, message); diagnostics.log(message, error)
            } }
        })
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams") // PTT needs raw UP; IME attaches this root itself.
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_freevoice_keyboard, null)
        status = view.findViewById(R.id.ime_status); mic = view.findViewById(R.id.ime_mic)
        mic.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> startPtt()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> controller.stop()
            }
            // Keep accessibility click semantics although recording intentionally follows press duration.
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) button.performClick()
            true
        }
        mic.setOnClickListener { /* accessibility click: recording is touch/PTT-only */ }
        view.findViewById<Button>(R.id.ime_cancel).setOnClickListener { controller.cancel(); clearTarget() }
        view.findViewById<Button>(R.id.ime_switch).setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        view.findViewById<Button>(R.id.ime_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        render(controller.currentState()); return view
    }

    override fun onDestroy() { controller.close(); contextExecutor.shutdownNow(); super.onDestroy() }
    override fun onFinishInput() { controller.cancel(); clearTarget(); super.onFinishInput() }

    private fun startPtt() {
        val permissionError = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) "マイク権限を許可して" else null
        val appSettings = settings.load()
        val packageName = currentInputEditorInfo?.packageName.orEmpty()
        val connection = currentInputConnection ?: return
        controller.start(packageName, permissionError ?: appSettings.validateForVoiceInput())?.let { target = Target(it, packageName, connection) }
    }

    private fun render(state: VoiceInputState) {
        if (!::status.isInitialized) return
        status.text = when (state) {
            VoiceInputState.Idle -> "長押しして話す"
            VoiceInputState.Starting -> "録音を準備中…"
            VoiceInputState.Recording -> "録音中… 指を離すと送信"
            VoiceInputState.Transcribing -> "文字起こし中…"
            VoiceInputState.Formatting -> "文章を整形中…"
            is VoiceInputState.Error -> state.message
        }
        mic.isEnabled = state is VoiceInputState.Idle || state is VoiceInputState.Error || state is VoiceInputState.Recording
    }

    private fun updateContextAsync(packageName: String, text: String) {
        val current = settings.load()
        if (packageName.isBlank() || !current.formatEnabled || !current.contextAwareFormatting || !context.beginDistill(packageName)) return
        contextExecutor.execute {
            try {
                val result = VoiceApiClient(settings.load().toVoiceApiConfig()).distill(context.get(packageName).orEmpty(), text)
                if (!result.fallback) context.put(packageName, result.text)
                else diagnostics.log("Topic context distill fallback: ${result.fallbackReason}")
            } finally { context.finishDistill(packageName) }
        }
    }

    private fun clearTarget(jobId: Long? = null) { if (jobId == null || target?.jobId == jobId) target = null }

    private inner class AndroidRecorder(private val dir: File) : VoiceInputController.Recorder {
        private val delegate = WavRecorder(dir) { main.post { controller.stop() } }
        override fun start() = delegate.start(); override fun stop() = delegate.stop(); override fun cancel() = delegate.cancel()
    }
    private inner class ApiGateway : VoiceInputController.Gateway {
        @Volatile private var client: VoiceApiClient? = null
        private var activeSettings: AppSettings? = null
        override fun transcribe(wav: File): String {
            activeSettings = settings.load(); val s = requireNotNull(activeSettings)
            client = VoiceApiClient(s.toVoiceApiConfig())
            return if (s.transcriptionProvider.name == "AZURE_SPEECH") client!!.transcribeAzureSpeech(wav) else client!!.transcribeAzureOpenAi(wav)
        }
        override fun format(text: String, packageName: String): VoiceInputController.FormattedText {
            val s = activeSettings ?: return VoiceInputController.FormattedText(text, false)
            if (!s.formatEnabled) return VoiceInputController.FormattedText(text, false)
            val result = client!!.format(text, s.postprocessPrompt, if (s.contextAwareFormatting) context.get(packageName) else null)
            return VoiceInputController.FormattedText(result.text, result.fallback)
        }
        override fun cancel() { client?.cancel() }
    }
}
