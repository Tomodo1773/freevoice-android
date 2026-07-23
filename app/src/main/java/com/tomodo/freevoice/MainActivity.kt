package com.tomodo.freevoice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.tomodo.freevoice.data.*
import com.tomodo.freevoice.diag.DiagLogger
import com.tomodo.freevoice.history.HistoryRepository
import java.text.DateFormat
import java.util.Date

/** 初期設定と診断のための通常 Activity。IME 本体は別の Service にある。 */
class MainActivity : Activity() {
    private lateinit var repository: SecureSettingsRepository
    private lateinit var transcriptionProvider: Spinner
    private lateinit var formatProvider: Spinner
    private lateinit var transcriptionEndpoint: EditText
    private lateinit var transcriptionApiKey: EditText
    private lateinit var transcriptionModel: EditText
    private lateinit var speechEndpoint: EditText
    private lateinit var speechLanguage: EditText
    private lateinit var transcriptionEndpointRow: View
    private lateinit var transcriptionModelRow: View
    private lateinit var speechEndpointRow: View
    private lateinit var speechLanguageRow: View
    private lateinit var formatEnabled: Switch
    private lateinit var formatEndpoint: EditText
    private lateinit var formatEndpointRow: View
    private lateinit var formatApiKey: EditText
    private lateinit var formatModel: EditText
    private lateinit var postprocessPrompt: EditText
    private lateinit var reasoningEffort: Spinner
    private lateinit var contextAware: Switch
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var diagnostics: TextView
    private lateinit var historyRepository: HistoryRepository
    private lateinit var diagLogger: DiagLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SecureSettingsRepository(this)
        historyRepository = HistoryRepository(this)
        diagLogger = DiagLogger(this)
        setContentView(buildContent())
        bind(repository.load())
    }

    override fun onResume() {
        super.onResume()
        refreshOperationalStatus()
        refreshLogs()
    }

    private fun buildContent(): ScrollView = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            addView(title("FreeVoice 設定"))
            addView(note("音声入力キーボードの初期設定。API キーは端末の Android Keystore で暗号化して保存される。"))
            addView(button("マイク権限を許可", ::requestMicrophone))
            addView(button("キーボード設定を開く") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
            addView(button("FreeVoice に切り替える", ::showImePicker))
            addView(section("文字起こし"))
            transcriptionProvider = spinner(arrayOf("Azure OpenAI", "Azure Speech")); row("プロバイダー", transcriptionProvider)
            transcriptionEndpoint = edit("https://resource.services.ai.azure.com/api/projects/project", false)
            transcriptionEndpointRow = row("Azure OpenAI エンドポイント", transcriptionEndpoint)
            transcriptionApiKey = edit("API キー", true); row("文字起こし API キー", transcriptionApiKey)
            transcriptionModel = edit("gpt-4o-transcribe", false)
            transcriptionModelRow = row("文字起こしモデル", transcriptionModel)
            speechEndpoint = edit("https://...", false)
            speechEndpointRow = row("Speech エンドポイント", speechEndpoint)
            speechLanguage = edit("ja-JP", false)
            speechLanguageRow = row("音声言語", speechLanguage)
            addView(section("テキスト整形"))
            formatEnabled = Switch(this@MainActivity).apply { setText(R.string.format_enabled) }; addView(formatEnabled)
            formatProvider = spinner(arrayOf("Azure OpenAI", "OpenAI")); row("整形プロバイダー", formatProvider)
            formatEndpoint = edit("https://...", false)
            formatEndpointRow = row("整形エンドポイント", formatEndpoint)
            formatApiKey = edit("API キー", true); row("整形 API キー", formatApiKey)
            formatModel = edit(AppSettings.DEFAULT_FORMAT_MODEL, false); row("整形モデル", formatModel)
            postprocessPrompt = edit("整形指示", false, 5); row("整形プロンプト", postprocessPrompt)
            reasoningEffort = spinner(REASONING_EFFORTS); row("推論強度", reasoningEffort)
            contextAware = Switch(this@MainActivity).apply { setText(R.string.context_aware_formatting) }; addView(contextAware)
            addView(button("設定を保存") { save() })
            status = note("")
            addView(status)
            addView(section("履歴")); addView(button("履歴を消去") { historyRepository.clear(); refreshLogs() })
            history = note(""); addView(history)
            addView(section("診断")); addView(button("診断ログを消去") { diagLogger.clear(); refreshLogs() })
            diagnostics = note(""); addView(diagnostics)
        })
        transcriptionProvider.onSelectionChanged(::updateFieldVisibility)
        formatProvider.onSelectionChanged(::updateFieldVisibility)
    }

    private fun bind(s: AppSettings) {
        transcriptionProvider.setSelection(s.transcriptionProvider.ordinal)
        transcriptionEndpoint.setText(s.transcriptionEndpoint); transcriptionApiKey.setText(s.transcriptionApiKey)
        transcriptionModel.setText(s.transcriptionModel); speechEndpoint.setText(s.speechEndpoint)
        speechLanguage.setText(s.speechLanguage); formatEnabled.isChecked = s.formatEnabled
        formatProvider.setSelection(s.formatProvider.ordinal); formatEndpoint.setText(s.formatEndpoint)
        formatApiKey.setText(s.formatApiKey); formatModel.setText(s.formatModel)
        postprocessPrompt.setText(s.postprocessPrompt)
        reasoningEffort.setSelection(REASONING_EFFORTS.indexOf(s.reasoningEffort).takeIf { it >= 0 } ?: DEFAULT_REASONING_EFFORT_INDEX)
        contextAware.isChecked = s.contextAwareFormatting
        updateFieldVisibility()
    }

    private fun updateFieldVisibility() {
        val visibility = settingsFieldVisibility(
            TranscriptionProvider.entries[transcriptionProvider.selectedItemPosition],
            FormatProvider.entries[formatProvider.selectedItemPosition],
        )
        transcriptionEndpointRow.isVisible = visibility.transcriptionEndpoint
        transcriptionModelRow.isVisible = visibility.transcriptionModel
        speechEndpointRow.isVisible = visibility.speechEndpoint
        speechLanguageRow.isVisible = visibility.speechLanguage
        formatEndpointRow.isVisible = visibility.formatEndpoint
    }

    private fun save() {
        val settings = AppSettings(
            transcriptionProvider = TranscriptionProvider.entries[transcriptionProvider.selectedItemPosition],
            transcriptionEndpoint = transcriptionEndpoint.text.toString(), transcriptionApiKey = transcriptionApiKey.text.toString(),
            transcriptionModel = transcriptionModel.text.toString(), speechEndpoint = speechEndpoint.text.toString(),
            speechLanguage = speechLanguage.text.toString(), formatEnabled = formatEnabled.isChecked,
            formatProvider = FormatProvider.entries[formatProvider.selectedItemPosition], formatEndpoint = formatEndpoint.text.toString(),
            formatApiKey = formatApiKey.text.toString(), formatModel = formatModel.text.toString(),
            postprocessPrompt = postprocessPrompt.text.toString(), reasoningEffort = reasoningEffort.selectedItem as String,
            contextAwareFormatting = contextAware.isChecked,
        )
        repository.save(settings)
        status.text = settings.validateForVoiceInput()?.let { "保存した。入力を始めるには: $it" } ?: "保存したわ。FreeVoice キーボードから音声入力できる。"
    }

    private fun requestMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else status.text = "マイク権限はすでに許可されている。"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            status.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "マイク権限を許可したわ。次は FreeVoice キーボードを有効にして。"
            } else "マイク権限が必要よ。端末設定から許可して。"
        }
    }

    private fun refreshOperationalStatus() {
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val imeEnabled = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .enabledInputMethodList.any { it.serviceInfo.packageName == packageName && it.serviceInfo.name == IME_SERVICE }
        status.text = getString(
            R.string.setup_status,
            getString(if (microphone) R.string.status_allowed else R.string.status_not_allowed),
            getString(if (imeEnabled) R.string.status_enabled else R.string.status_not_enabled),
        )
    }

    private fun refreshLogs() {
        val entries = historyRepository.list().take(20)
        history.text = if (entries.isEmpty()) "履歴はまだない。" else entries.joinToString("\n") { entry ->
            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))}  " +
                "${if (entry.success) "成功" else "失敗"}: ${entry.text.take(160)}${entry.error?.let { " ($it)" }.orEmpty()}"
        }
        diagnostics.text = diagLogger.readLatest(DIAGNOSTIC_DISPLAY_LINES)
            .let { logs -> if (logs.isBlank()) "診断ログはまだない。" else "最新${DIAGNOSTIC_DISPLAY_LINES}件を表示中。古いログは自動で削除される。\n$logs" }
    }
    private fun showImePicker() = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    private fun title(text: String) = TextView(this).apply { this.text = text; textSize = 26f }
    private fun section(text: String) = TextView(this).apply { this.text = text; textSize = 20f; setPadding(0, dp(24), 0, dp(8)) }
    private fun note(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
    private fun spinner(items: Array<String>) = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items) }
    private fun edit(hint: String, secret: Boolean, lines: Int = 1) = EditText(this).apply {
        this.hint = hint; minLines = lines; maxLines = lines
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            lines > 1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
    }
    private fun Spinner.onSelectionChanged(action: () -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    private var View.isVisible: Boolean
        get() = visibility == View.VISIBLE
        set(value) { visibility = if (value) View.VISIBLE else View.GONE }
    private fun LinearLayout.row(label: String, view: View): View = LinearLayout(this@MainActivity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)); addView(note(label)); addView(view)
        this@row.addView(this)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private const val REQUEST_RECORD_AUDIO = 10
        private const val IME_SERVICE = "com.tomodo.freevoice.ime.FreeVoiceInputMethodService"
        private val REASONING_EFFORTS = arrayOf("none", "low", "medium", "high")
        private val DEFAULT_REASONING_EFFORT_INDEX = REASONING_EFFORTS.indexOf(AppSettings.DEFAULT_REASONING_EFFORT)
        private const val DIAGNOSTIC_DISPLAY_LINES = 20
    }
}
