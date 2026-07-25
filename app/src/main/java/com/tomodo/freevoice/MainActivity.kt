package com.tomodo.freevoice

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.SecureSettingsRepository
import com.tomodo.freevoice.databinding.ActivityMainBinding
import com.tomodo.freevoice.databinding.ScreenHistoryBinding
import com.tomodo.freevoice.databinding.ScreenLogsBinding
import com.tomodo.freevoice.databinding.ScreenModelSettingsBinding
import com.tomodo.freevoice.databinding.ScreenPromptBinding
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.history.HistoryRepository
import com.tomodo.freevoice.ime.FreeVoiceInputMethodService
import com.tomodo.freevoice.ui.HistoryScreen
import com.tomodo.freevoice.ui.LogsScreen
import com.tomodo.freevoice.ui.ModelSettingsScreen
import com.tomodo.freevoice.ui.PromptScreen

/** IME の設定・履歴・診断を提供する通常 Activity。音声入力ジョブは所有しない。 */
class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SecureSettingsRepository
    private lateinit var diagnostics: DiagLogger
    private lateinit var modelScreen: ModelSettingsScreen
    private lateinit var promptScreen: PromptScreen
    private lateinit var historyScreen: HistoryScreen
    private lateinit var logsScreen: LogsScreen
    private lateinit var draft: AppSettings
    private var selectedTab = AppTab.MODEL
    private var setupStep = SetupStep.MICROPHONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(window, binding.root)

        settingsRepository = SecureSettingsRepository(this)
        diagnostics = DiagLogger(this)
        draft = settingsRepository.load()

        val modelBinding = ScreenModelSettingsBinding.inflate(layoutInflater, binding.pageContainer, true)
        val promptBinding = ScreenPromptBinding.inflate(layoutInflater, binding.pageContainer, true)
        val historyBinding = ScreenHistoryBinding.inflate(layoutInflater, binding.pageContainer, true)
        val logsBinding = ScreenLogsBinding.inflate(layoutInflater, binding.pageContainer, true)
        modelScreen = ModelSettingsScreen(this, modelBinding) { saveSettings(AppTab.MODEL) }
        promptScreen = PromptScreen(promptBinding) { saveSettings(AppTab.PROMPT) }
        historyScreen = HistoryScreen(this, historyBinding, HistoryRepository(this))
        logsScreen = LogsScreen(this, logsBinding, diagnostics)
        modelScreen.bind(draft)
        promptScreen.bind(draft)

        binding.setupAction.setOnClickListener {
            when (setupStep) {
                SetupStep.MICROPHONE -> requestMicrophone()
                SetupStep.ENABLE_IME ->
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                SetupStep.SELECT_IME -> showImePicker()
                SetupStep.COMPLETE -> Unit
            }
        }
        binding.tabBar.setOnCheckedChangeListener { _, checkedId ->
            AppTab.entries.firstOrNull { it.buttonId == checkedId }?.let(::showTab)
        }

        selectedTab = savedInstanceState?.getString(STATE_TAB)
            ?.let { saved -> AppTab.entries.firstOrNull { it.name == saved } }
            ?: AppTab.MODEL
        binding.tabBar.check(selectedTab.buttonId)
        showTab(selectedTab)
        diagnostics.info("settings", "Settings activity opened")
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshActivePage()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TAB, selectedTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            diagnostics.info("setup", "Microphone permission granted")
        } else {
            diagnostics.warn("setup", "Microphone permission denied")
        }
        refreshSetup()
    }

    private fun saveSettings(origin: AppTab) {
        draft = promptScreen.collect(modelScreen.collect(draft))
        settingsRepository.save(draft)
        diagnostics.info("settings", "Settings saved from ${origin.name.lowercase()}")
        val message = draft.validateForVoiceInput()
            ?.let { getString(R.string.save_needs_setup, it) }
            ?: getString(R.string.saved)
        when (origin) {
            AppTab.MODEL -> modelScreen.showStatus(message)
            AppTab.PROMPT -> promptScreen.showStatus(message)
            AppTab.HISTORY, AppTab.LOGS -> Unit
        }
    }

    private fun showTab(tab: AppTab) {
        selectedTab = tab
        modelScreen.binding.root.isVisible = tab == AppTab.MODEL
        promptScreen.binding.root.isVisible = tab == AppTab.PROMPT
        historyScreen.binding.root.isVisible = tab == AppTab.HISTORY
        logsScreen.binding.root.isVisible = tab == AppTab.LOGS
        keepSelectedTabVisible(tab)
        refreshActivePage()
    }

    private fun keepSelectedTabVisible(tab: AppTab) {
        val selectedButton = binding.root.findViewById<View>(tab.buttonId)
        binding.tabScroller.post {
            val centeredOffset = (binding.tabScroller.width - selectedButton.width) / 2
            binding.tabScroller.smoothScrollTo(
                (selectedButton.left - centeredOffset).coerceAtLeast(0),
                0,
            )
        }
    }

    private fun refreshActivePage() {
        when (selectedTab) {
            AppTab.HISTORY -> historyScreen.refresh()
            AppTab.LOGS -> logsScreen.refresh()
            AppTab.MODEL, AppTab.PROMPT -> Unit
        }
    }

    private fun refreshSetup() {
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val imeComponent = ComponentName(this, FreeVoiceInputMethodService::class.java)
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val imeEnabled = inputMethodManager.enabledInputMethodList.any {
            it.serviceInfo.packageName == imeComponent.packageName &&
                it.serviceInfo.name == imeComponent.className
        }
        val defaultIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        val imeSelected = ComponentName.unflattenFromString(defaultIme.orEmpty()) == imeComponent

        setupStep = nextSetupStep(microphone, imeEnabled, imeSelected)
        binding.setupCard.isVisible = setupStep != SetupStep.COMPLETE
        binding.setupAction.setText(
            when (setupStep) {
                SetupStep.MICROPHONE -> R.string.allow_microphone
                SetupStep.ENABLE_IME -> R.string.enable_keyboard
                SetupStep.SELECT_IME -> R.string.select_keyboard
                SetupStep.COMPLETE -> R.string.setup_title
            },
        )
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshSetup()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun showImePicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private var View.isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) {
            visibility = if (value) View.VISIBLE else View.GONE
        }

    private enum class AppTab(val buttonId: Int) {
        MODEL(R.id.tab_model),
        PROMPT(R.id.tab_prompt),
        HISTORY(R.id.tab_history),
        LOGS(R.id.tab_logs),
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 10
        private const val STATE_TAB = "selected_tab"
    }
}
