package com.assistant.taba9

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import java.util.Locale

class ActionExecutor(private val context: Context) {

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    fun speakOut(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "TABA9_TTS")
    }

    // 1. UNIVERSAL APP LAUNCHER: Opens ANY installed app on the tablet by voice!
    fun openAnyApp(appNameQuery: String): String {
        val cleanQuery = appNameQuery.trim().lowercase()
        if (cleanQuery.isBlank()) return "Tell me which app to open."

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        for (info in resolveInfos) {
            val label = info.loadLabel(pm).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            if (label.contains(cleanQuery) || cleanQuery.contains(label) || pkg.contains(cleanQuery)) {
                val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val launchCtx = TabletAssistantService.instance ?: context
                    launchCtx.startActivity(launchIntent)
                    val msg = "Opening ${info.loadLabel(pm)}"
                    speakOut(msg)
                    return msg
                }
            }
        }
        val failMsg = "Could not find an installed app named $appNameQuery"
        speakOut(failMsg)
        return failMsg
    }

    // 2. UNIVERSAL SCREEN CLICK / TYPE / SCROLL
    fun interactWithScreen(rawCommand: String, target: String): String {
        val service = TabletAssistantService.instance
            ?: return "Enable 'Tab A9 AI Controller' in Accessibility Settings first."
        val res = service.interactWithCurrentScreen(rawCommand, target)
        speakOut(res)
        return res
    }

    // 3. WHATSAPP AUTO-SENDER
    fun sendWhatsApp(contactName: String, message: String): String {
        if (contactName.isBlank() || message.isBlank()) {
            return "Say: 'Send WhatsApp to [Name] saying [Message]'"
        }
        val phone = findPhoneNumber(contactName)
            ?: return "Contact '$contactName' not found in Contacts."

        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        return try {
            TabletAssistantService.triggerWhatsAppAutoSendWatchdog()
            val launchContext = TabletAssistantService.instance ?: context
            launchContext.startActivity(intent)
            speakOut("Sending WhatsApp to $contactName")
            "Auto-sending WhatsApp to $contactName ($cleanPhone): \"$message\""
        } catch (e: Exception) {
            TabletAssistantService.cancelPendingAutomation()
            "Failed to launch WhatsApp: ${e.message}"
        }
    }

    // 4. PHONE CALLER
    fun makePhoneCall(contactName: String): String {
        if (contactName.isBlank()) return "Specify who to call."
        val phone = findPhoneNumber(contactName)
            ?: return "Contact '$contactName' not found."

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phone")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val launchContext = TabletAssistantService.instance ?: context
            launchContext.startActivity(intent)
            speakOut("Calling $contactName")
            "Calling $contactName ($phone)..."
        } catch (e: SecurityException) {
            "Call permission not granted."
        }
    }

    // 5. HARDWARE, BATTERY & SYSTEM CONTROLS
    fun handleSystemCommand(rawText: String): String {
        val lower = rawText.lowercase()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (lower.contains("battery")) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val msg = "Tablet battery is at $level percent"
            speakOut(msg)
            return msg
        }
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val turnOn = !lower.contains("off")
            return toggleFlashlight(turnOn)
        }
        if (lower.contains("volume up")) {
            audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return "Increased tablet volume"
        }
        if (lower.contains("volume down")) {
            audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return "Decreased tablet volume"
        }
        if (lower.contains("mute")) {
            audio.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return "Muted tablet audio"
        }

        val service = TabletAssistantService.instance
            ?: return "Enable 'Tab A9 AI Controller' in Accessibility Settings first."
        val res = service.executeSystemAction(rawText)
        speakOut(res)
        return res
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = camManager.cameraIdList.firstOrNull() ?: return "No flash hardware on this tablet"
            camManager.setTorchMode(camId, enable)
            val msg = if (enable) "Flashlight turned on" else "Flashlight turned off"
            speakOut(msg)
            msg
        } catch (_: Exception) {
            "This Galaxy Tab A9 model does not have a rear LED flash."
        }
    }

    @SuppressLint("Range")
    private fun findPhoneNumber(nameQuery: String): String? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$nameQuery%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return null
    }
}
