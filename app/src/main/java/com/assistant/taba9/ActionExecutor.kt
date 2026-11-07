package com.assistant.taba9

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract

class ActionExecutor(private val context: Context) {

    fun sendWhatsApp(contactName: String, message: String): String {
        if (contactName.isBlank() || message.isBlank()) {
            return "Say: 'Send WhatsApp to [Name] saying [Message]'"
        }
        val phone = findPhoneNumber(contactName)
            ?: return "Contact '$contactName' not found in tablet contacts."

        val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
        TabletAssistantService.isAutoSendPending = true

        val url = "https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            setPackage("com.whatsapp")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            "Sending WhatsApp to $contactName ($cleanPhone): \"$message\""
        } catch (e: Exception) {
            TabletAssistantService.isAutoSendPending = false
            "WhatsApp is not installed or accessible."
        }
    }

    fun makePhoneCall(contactName: String): String {
        if (contactName.isBlank()) return "Specify who to call."
        val phone = findPhoneNumber(contactName)
            ?: return "Contact '$contactName' not found."

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phone")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            "Calling $contactName ($phone)..."
        } catch (e: SecurityException) {
            "Call permission not granted."
        }
    }

    fun handleSystemCommand(rawText: String): String {
        val lower = rawText.lowercase()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        return service.executeSystemAction(rawText)
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
