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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var aiEngine: OfflineAiEngine
    private lateinit var fileOrganizer: FileOrganizer
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiEngine = OfflineAiEngine(this)
        fileOrganizer = FileOrganizer(this, aiEngine)

        setupDashboardUi()
        requestStoragePermissions()

        lifecycleScope.launch(Dispatchers.IO) {
            aiEngine.initEngine()
            withContext(Dispatchers.Main) {
                appendLog("✅ Offline Document Knowledge Base (38+ Archetypes) & Dual OCR (English + Hindi) Ready.")
            }
        }
    }

    private fun setupDashboardUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val btnStartScan = Button(this).apply {
            text = "📂 DEEP-SCAN & ORGANIZE ALL DOCUMENTS NOW"
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                lifecycleScope.launch {
                    logView.text = "--- Starting Deep Content + OCR Scan ---\n"
                    val summary = fileOrganizer.organizeWholeStorage { progressLine ->
                        runOnUiThread { appendLog(progressLine) }
                    }
                    appendLog(summary)
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "🛑 STOP SCAN IMMEDIATELY"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                FileOrganizer.isCancelled = true
                appendLog("🛑 Stop signal sent.")
            }
        }

        val btnStoragePerm = Button(this).apply {
            text = "⚙️ Grant All Files Access Permission"
            setOnClickListener { openStoragePermissionSettings() }
        }

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            setPadding(16, 24, 16, 24)
            text = "--- AI Document Organizer Diagnostic Log ---\n"
        }

        scrollView = ScrollView(this).apply { addView(logView) }

        root.addView(btnStartScan)
        root.addView(btnStop)
        root.addView(btnStoragePerm)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun requestStoragePermissions() {
        val perms = arrayOf(
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

    private fun openStoragePermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } else {
            appendLog("All Files Access is already granted!")
        }
    }

    private fun appendLog(msg: String) {
        logView.append("$msg\n\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
