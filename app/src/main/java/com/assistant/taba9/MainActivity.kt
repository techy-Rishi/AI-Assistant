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
import android.view.ViewGroup
import android.view.WindowManager
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
    private val actionButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiEngine = OfflineAiEngine(this)
        fileOrganizer = FileOrganizer(this, aiEngine)

        setupDashboardUi()
        requestLegacyStoragePermissions()

        // Buttons stay disabled until the engine is fully loaded.
        setActionsEnabled(false)
        appendLog("Loading knowledge base and models...")
        lifecycleScope.launch(Dispatchers.IO) {
            aiEngine.initEngine()
            withContext(Dispatchers.Main) {
                appendLog(aiEngine.initMessage)
                setActionsEnabled(aiEngine.isReady)
            }
        }
    }

    override fun onDestroy() {
        FileOrganizer.isCancelled = true
        fileOrganizer.close()
        aiEngine.close()
        super.onDestroy()
    }

    private fun makeButton(label: String, color: String?, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            if (color != null) {
                setBackgroundColor(Color.parseColor(color))
                setTextColor(Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

    private fun setupDashboardUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val btnPreview = makeButton("PREVIEW (nothing is moved)", "#546E7A") { runScan(dryRun = true, reprocess = false) }
        val btnOrganize = makeButton("ORGANIZE NEW DOCUMENTS", "#1E88E5") { runScan(dryRun = false, reprocess = false) }
        val btnRecheck = makeButton("RE-CHECK ALREADY SORTED FILES", "#3949AB") { runScan(dryRun = false, reprocess = true) }
        val btnUndo = makeButton("UNDO LAST RUN", "#F9A825") { runUndo() }
        val btnStop = makeButton("STOP", "#D32F2F") {
            FileOrganizer.isCancelled = true
            appendLog("Stop signal sent.")
        }
        val btnPerm = makeButton("Grant All Files Access", null) { openStoragePermissionSettings() }

        actionButtons.addAll(listOf(btnPreview, btnOrganize, btnRecheck, btnUndo))

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            setPadding(16, 24, 16, 24)
            text = "--- AI Document Organizer Log ---\n"
        }

        scrollView = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(btnPreview)
        root.addView(btnOrganize)
        root.addView(btnRecheck)
        root.addView(btnUndo)
        root.addView(btnStop)
        root.addView(btnPerm)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun runScan(dryRun: Boolean, reprocess: Boolean) {
        if (!hasAllFilesAccess()) {
            appendLog("All Files Access is not granted. Tap 'Grant All Files Access' first.")
            return
        }
        setActionsEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            appendLog(if (dryRun) "--- Preview: nothing will be moved ---" else "--- Organizing ---")
            try {
                val summary = fileOrganizer.organizeWholeStorage(dryRun, reprocess) { line ->
                    runOnUiThread { appendLog(line) }
                }
                appendLog(summary)
            } catch (e: Exception) {
                appendLog("Scan failed: ${e.message}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setActionsEnabled(aiEngine.isReady)
            }
        }
    }

    private fun runUndo() {
        if (!hasAllFilesAccess()) {
            appendLog("All Files Access is not granted. Tap 'Grant All Files Access' first.")
            return
        }
        setActionsEnabled(false)
        lifecycleScope.launch {
            try {
                appendLog(fileOrganizer.undoLastRun())
            } catch (e: Exception) {
                appendLog("Undo failed: ${e.message}")
            } finally {
                setActionsEnabled(aiEngine.isReady)
            }
        }
    }

    // Only Android 10 and below use the old runtime permissions.
    private fun requestLegacyStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
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
            appendLog("All Files Access is already granted.")
        }
    }

    private fun appendLog(msg: String) {
        logView.append("$msg\n\n")
        // Keep the log small so a long scan cannot slow the UI.
        if (logView.length() > 30000) {
            logView.text = logView.text.takeLast(20000)
        }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
