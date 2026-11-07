package com.assistant.taba9

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var aiEngine: OfflineAiEngine
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var fileOrganizer: FileOrganizer
    private lateinit var logView: TextView
    private lateinit var inputField: EditText

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(VoiceBackgroundService.EXTRA_LOG_MSG) ?: return
            appendLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiEngine = OfflineAiEngine(this)
        actionExecutor = ActionExecutor(this)
        fileOrganizer = FileOrganizer(this, aiEngine)

        setupUi()
        requestRuntimePermissions()

        val filter = IntentFilter(VoiceBackgroundService.BROADCAST_LOG)
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch(Dispatchers.IO) {
            aiEngine.initEngine()
            withContext(Dispatchers.Main) {
                appendLog("Offline AI Engine ready. Tap 'Start Always-On Background Voice' to run even when app is closed.")
            }
        }
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // MASTER KILL SWITCH BUTTON (Turns off everything immediately)
        val btnMasterOff = Button(this).apply {
            text = "🛑 TURN OFF ALL FEATURES (MASTER OFF)"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                VoiceBackgroundService.stopAllFeatures(this@MainActivity)
            }
        }

        // START BACKGROUND ALWAYS-ON VOICE SERVICE
        val btnStartBg = Button(this).apply {
            text = "🟢 Start Always-On Background Voice (Works Closed)"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                VoiceBackgroundService.isMasterEnabled = true
                val serviceIntent = Intent(this@MainActivity, VoiceBackgroundService::class.java).apply {
                    action = VoiceBackgroundService.ACTION_START
                }
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
            }
        }

        inputField = EditText(this).apply {
            hint = "Type command (e.g., send whatsapp to Rahul saying hello)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val btnRunText = Button(this).apply {
            text = "▶️ Execute Typed Command"
            setOnClickListener {
                val cmd = inputField.text.toString()
                if (cmd.isNotBlank()) {
                    VoiceBackgroundService.isMasterEnabled = true
                    processManualCommand(cmd)
                }
            }
        }

        val btnSortWholeTablet = Button(this).apply {
            text = "📂 Organize Whole Tablet Storage by Content"
            setOnClickListener {
                VoiceBackgroundService.isMasterEnabled = true
                lifecycleScope.launch {
                    appendLog("Scanning entire tablet storage (skipping system folders)...")
                    val res = fileOrganizer.organizeWholeStorage()
                    appendLog(res)
                }
            }
        }

        val btnPermissions = Button(this).apply {
            text = "⚙️ Grant All Files, Overlay & Accessibility"
            setOnClickListener { openSpecialPermissions() }
        }

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13.5f
            setPadding(16, 20, 16, 20)
            text = "--- Galaxy Tab A9 Offline AI Log ---\n"
        }

        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(btnMasterOff)
        root.addView(btnStartBg)
        root.addView(inputField)
        root.addView(btnRunText)
        root.addView(btnSortWholeTablet)
        root.addView(btnPermissions)
        root.addView(scroll)
        setContentView(root)
    }

    private fun processManualCommand(commandText: String) {
        appendLog("\n> Command: \"$commandText\"")
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                aiEngine.parseVoiceCommand(commandText)
            }
            appendLog("AI Intent: ${parsed.intentId} (score: ${"%.2f".format(parsed.confidence)})")

            val outcome = when (parsed.intentId) {
                "STOP_ALL" -> {
                    VoiceBackgroundService.stopAllFeatures(this@MainActivity)
                    "All features stopped."
                }
                "WHATSAPP_SEND" -> actionExecutor.sendWhatsApp(parsed.targetContact, parsed.messageBody)
                "MAKE_CALL" -> actionExecutor.makePhoneCall(parsed.targetContact)
                "SORT_FOLDER" -> fileOrganizer.organizeWholeStorage()
                "SYSTEM_CONTROL" -> actionExecutor.handleSystemCommand(parsed.rawText)
                else -> "Command did not match rules.json thresholds."
            }
            appendLog(outcome)
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun openSpecialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun appendLog(msg: String) {
        logView.append("$msg\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
    }
}
