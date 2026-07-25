package com.tomodo.freevoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.tomodo.freevoice.MainActivity
import com.tomodo.freevoice.context.TopicContextStore
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.databinding.ImeFreevoiceKeyboardBinding
import com.tomodo.freevoice.history.HistoryRepository

/**
 * Android lifecycle and composition root for the IME.
 *
 * VoiceInputController exclusively owns the active voice job. This service only
 * keeps the matching editor target so a stale job can never write into a newer field.
 */
class FreeVoiceInputMethodService : InputMethodService() {
    private data class Target(
        val jobId: Long,
        val packageName: String,
        val connection: InputConnection,
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: VoiceInputController
    private lateinit var settings: SecureSettingsRepository
    private lateinit var topicContext: TopicContextStore
    private lateinit var history: HistoryRepository
    private lateinit var diagnostics: DiagLogger
    private lateinit var contextUpdater: TopicContextUpdater
    private lateinit var editor: ImeEditor
    private var keyboardUi: ImeKeyboardUi? = null
    @Volatile private var target: Target? = null

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettingsRepository(this)
        topicContext = TopicContextStore()
        history = HistoryRepository(this)
        diagnostics = DiagLogger(this)
        contextUpdater = TopicContextUpdater(settings, topicContext, diagnostics)
        editor = ImeEditor(
            connection = { currentInputConnection },
            editorInfo = { currentInputEditorInfo },
        )
        controller = VoiceInputController(
            recorder = AndroidVoiceRecorder(cacheDir) { main.post { controller.stop() } },
            gateway = SettingsVoiceGateway(settings, topicContext),
            callbacks = voiceCallbacks(),
        )
    }

    override fun onCreateInputView(): View {
        keyboardUi?.close()
        val binding = ImeFreevoiceKeyboardBinding.inflate(layoutInflater)
        keyboardUi = ImeKeyboardUi(
            binding = binding,
            actions = object : ImeKeyboardUi.Actions {
                override fun onMic() = onMicTapped()
                override fun onCancel() = cancelVoiceInput()
                override fun onSwitchKeyboard() = showKeyboardPicker()
                override fun onOpenSettings() = openSettings()
                override fun onSpace() = editor.insertSpace()
                override fun onDelete() = editor.deleteBackward()
                override fun onEnter() = editor.enter()
            },
        ).also {
            it.render(controller.currentState().toImeKeyboardUiState())
            it.setEnterCommand(resolveImeEnterCommand(currentInputEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_NONE))
        }
        return binding.root
    }

    override fun onStartInputView(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(attribute, restarting)
        keyboardUi?.setEnterCommand(
            resolveImeEnterCommand(attribute?.imeOptions ?: EditorInfo.IME_ACTION_NONE),
        )
    }

    override fun onFinishInput() {
        cancelVoiceInput()
        super.onFinishInput()
    }

    override fun onDestroy() {
        keyboardUi?.close()
        keyboardUi = null
        controller.close()
        contextUpdater.close()
        super.onDestroy()
    }

    private fun voiceCallbacks() = object : VoiceInputController.Callbacks {
        override fun stateChanged(state: VoiceInputState) {
            main.post { keyboardUi?.render(state.toImeKeyboardUiState()) }
        }

        override fun committed(
            jobId: Long,
            text: String,
            packageName: String,
            formatFallback: Boolean,
        ) {
            main.post {
                val active = target?.takeIf { it.jobId == jobId && it.packageName == packageName }
                val committed = active?.connection?.commitText(text, 1) == true
                clearTarget(jobId)
                if (!committed) {
                    history.add("", false, "入力先へ文字を挿入できなかった")
                    diagnostics.error("ime", "InputConnection.commitText failed")
                    return@post
                }
                history.add(text, true)
                diagnostics.info("ime", "Voice input committed (${text.length} chars)")
                if (!formatFallback) contextUpdater.update(packageName, text)
            }
        }

        override fun failed(jobId: Long, message: String, error: Throwable?) {
            main.post {
                clearTarget(jobId)
                history.add("", false, message)
                diagnostics.error("voice", message, error)
            }
        }
    }

    private fun onMicTapped() {
        when (controller.currentState().micTapAction()) {
            MicTapAction.Start -> startRecording()
            MicTapAction.Stop -> controller.stop()
            MicTapAction.Ignore -> Unit
        }
    }

    private fun startRecording() {
        val permissionError =
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                "マイク権限を許可して"
            } else {
                null
            }
        val appSettings = settings.load()
        val packageName = currentInputEditorInfo?.packageName.orEmpty()
        val connection = currentInputConnection ?: return
        controller.start(
            targetPackage = packageName,
            validationError = permissionError ?: appSettings.validateForVoiceInput(),
        )?.let { jobId ->
            target = Target(jobId, packageName, connection)
        }
    }

    private fun cancelVoiceInput() {
        controller.cancel()
        clearTarget()
    }

    private fun showKeyboardPicker() {
        cancelVoiceInput()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun openSettings() {
        cancelVoiceInput()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun clearTarget(jobId: Long? = null) {
        if (jobId == null || target?.jobId == jobId) target = null
    }
}
