package com.assistant.taba9

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceBackgroundService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_BACKGROUND_AI"
        const val ACTION_STOP_ALL = "ACTION_STOP_ALL_FEATURES"
        const val BROADCAST_LOG = "com.assistant.taba9.LOG_EVENT"
        const val EXTRA_LOG_MSG = "log_msg"

        @Volatile
        var isMasterEnabled = false

        fun stopAllFeatures(context: Context) {
            isMasterEnabled = false
            TabletAssistantService.cancelPendingAutomation()
            FileOrganizer.isCancelled = true
            val stopIntent = Intent(context, VoiceBackgroundService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            context.startService(stopIntent)
            broadcastLog(context, "🛑 MASTER OFF TRIGGERED: All background voice, file, and WhatsApp tasks stopped.")
        }

        fun broadcastLog(context: Context, message: String) {
            val intent = Intent(BROADCAST_LOG).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_LOG_MSG, message)
            }
            context.sendBroadcast(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var aiEngine: OfflineAiEngine
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var fileOrganizer: FileOrganizer

    override fun onCreate() {
        super.onCreate()
        aiEngine = OfflineAiEngine(this)
        actionExecutor = ActionExecutor(this)
        fileOrganizer = FileOrganizer(this, aiEngine)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabA9AI::VoiceWakeLock").apply {
            setReferenceCounted(false)
        }

        serviceScope.launch(Dispatchers.IO) {
            aiEngine.initEngine()
            broadcastLog(this@VoiceBackgroundService, "Background AI Engine ready.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            shutdownService()
            return START_NOT_STICKY
        }

        isMasterEnabled = true
        FileOrganizer.isCancelled = false
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // Up to 12 hours continuous wake lock
        }

        startForegroundWithMicType()
        initAndStartListeningLoop()
        broadcastLog(this, "🟢 Background Voice Assistant Active (Works with app closed).")
        return START_STICKY
    }

    private fun startForegroundWithMicType() {
        val channelId = "taba9_ai_bg_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Tab A9 Offline Voice Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAllIntent = Intent(this, VoiceBackgroundService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tab A9 AI Listening Offline")
            .setContentText("Ready for voice commands in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "🛑 TURN OFF ALL", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun initAndStartListeningLoop() {
        mainHandler.post {
            if (!isMasterEnabled) return@post
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            // Automatically restart listening loop if still enabled
                            if (isMasterEnabled) {
                                scheduleNextListen(800L)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!spoken.isNullOrBlank() && isMasterEnabled) {
                                handleSpokenCommand(spoken)
                            }
                            if (isMasterEnabled) {
                                scheduleNextListen(600L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
            triggerListening()
        }
    }

    private fun scheduleNextListen(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isMasterEnabled) triggerListening()
        }, delayMs)
    }

    private fun triggerListening() {
        if (!isMasterEnabled) return
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            speechRecognizer?.startListening(recognizerIntent)
        } catch (_: Exception) {
            scheduleNextListen(1500L)
        }
    }

    private fun handleSpokenCommand(commandText: String) {
        broadcastLog(this, "🎙️ Heard in BG: \"$commandText\"")
        serviceScope.launch {
            val parsed = withContext(Dispatchers.Default) {
                aiEngine.parseVoiceCommand(commandText)
            }
            broadcastLog(this@VoiceBackgroundService, "AI Intent: ${parsed.intentId} (${"%.2f".format(parsed.confidence)})")

            val result = when (parsed.intentId) {
                "STOP_ALL" -> {
                    stopAllFeatures(this@VoiceBackgroundService)
                    "All features turned off."
                }
                "WHATSAPP_SEND" -> actionExecutor.sendWhatsApp(parsed.targetContact, parsed.messageBody)
                "MAKE_CALL" -> actionExecutor.makePhoneCall(parsed.targetContact)
                "SORT_FOLDER" -> fileOrganizer.organizeWholeStorage()
                "SYSTEM_CONTROL" -> actionExecutor.handleSystemCommand(parsed.rawText)
                else -> "Ignored (Did not match command rules)."
            }
            broadcastLog(this@VoiceBackgroundService, result)
        }
    }

    private fun shutdownService() {
        isMasterEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdownService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
