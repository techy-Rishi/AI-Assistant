package com.assistant.taba9

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var aiEngine: OfflineAiEngine
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var fileOrganizer: FileOrganizer
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var logView: TextView
    private lateinit var inputField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiEngine = OfflineAiEngine(this)
        actionExecutor = ActionExecutor(this)
        fileOrganizer = FileOrganizer(this, aiEngine)

        setupUi()
        requestRuntimePermissions()

        lifecycleScope.launch(Dispatchers.IO) {
            aiEngine.initEngine()
            withContext(Dispatchers.Main) {
                appendLog("Offline AI Engine & rules.json initialized.")
            }
        }
        initOfflineSpeechRecognizer()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val btnSpeak = Button(this).apply {
            text = "🎙️ Tap to Speak (Offline Voice Command)"
            setOnClickListener { startOfflineListening() }
        }

        inputField = EditText(this).apply {
            hint = "Or type command (e.g., send whatsapp to Rahul saying hello)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val btnRunText = Button(this).apply {
            text = "▶️ Execute Typed Command"
            setOnClickListener {
                val cmd = inputField.text.toString()
                if (cmd.isNotBlank()) processUserCommand(cmd)
            }
        }

        val btnSortNow = Button(this).apply {
            text = "📂 Sort /Download Folder by Content Now"
            setOnClickListener {
                lifecycleScope.launch {
                    appendLog("Scanning PDFs, Images (OCR) & Files in /Download...")
                    val res = fileOrganizer.organizeDownloadsFolder()
                    appendLog(res)
                }
            }
        }

        val btnSettings = Button(this).apply {
            text = "⚙️ Enable Accessibility & All Files Access"
            setOnClickListener { openSpecialPermissions() }
        }

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 14f
            setPadding(16, 24, 16, 24)
            text = "--- Galaxy Tab A9 Offline AI Log ---\n"
        }

        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(btnSpeak)
        root.addView(inputField)
        root.addView(btnRunText)
        root.addView(btnSortNow)
        root.addView(btnSettings)
        root.addView(scroll)
        setContentView(root)
    }

    private fun initOfflineSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendLog("SpeechRecognizer not available on this device.")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = appendLog("Listening (Offline)...")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = appendLog("Speech Error Code: $error (Ensure offline voice pack is downloaded)")
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        processUserCommand(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startOfflineListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processUserCommand(commandText: String) {
        appendLog("\n> Heard: \"$commandText\"")
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                aiEngine.parseVoiceCommand(commandText)
            }
            appendLog("AI Intent: ${parsed.intentId} (score: ${"%.2f".format(parsed.confidence)})")

            val outcome = when (parsed.intentId) {
                "WHATSAPP_SEND" -> actionExecutor.sendWhatsApp(parsed.targetContact, parsed.messageBody)
                "MAKE_CALL" -> actionExecutor.makePhoneCall(parsed.targetContact)
                "SORT_FOLDER" -> fileOrganizer.organizeDownloadsFolder()
                "SYSTEM_CONTROL" -> actionExecutor.handleSystemCommand(parsed.rawText)
                else -> "Command did not match rules.json thresholds."
            }
            appendLog(outcome)
        }
    }

    private fun requestRuntimePermissions() {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun openSpecialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun appendLog(msg: String) {
        logView.append("$msg\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}
