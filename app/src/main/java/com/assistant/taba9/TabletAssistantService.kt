package com.assistant.taba9

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TabletAssistantService : AccessibilityService() {

    companion object {
        var instance: TabletAssistantService? = null

        @Volatile
        var isAutoSendPending = false
        private val mainHandler = Handler(Looper.getMainLooper())

        fun triggerWhatsAppAutoSendWatchdog() {
            isAutoSendPending = true
            mainHandler.removeCallbacksAndMessages(null)
            // Poll every 450ms up to 12 times in case WhatsApp takes a moment to render the Send button
            for (attempt in 1..12) {
                mainHandler.postDelayed({
                    if (isAutoSendPending && VoiceBackgroundService.isMasterEnabled) {
                        instance?.tryClickWhatsAppSend()
                    }
                }, attempt * 450L)
            }
        }

        fun cancelPendingAutomation() {
            isAutoSendPending = false
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoSendPending) return
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("whatsapp")) {
            tryClickWhatsAppSend()
        }
    }

    fun tryClickWhatsAppSend() {
        if (!isAutoSendPending) return
        val rootNode = rootInActiveWindow ?: return

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        candidates.addAll(rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send"))
        candidates.addAll(rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/send"))
        candidates.addAll(rootNode.findAccessibilityNodeInfosByText("Send"))

        // Also check contentDescription == "Send" recursively
        findSendByDescription(rootNode, candidates)

        for (node in candidates) {
            val clickableNode = findClickableParent(node)
            if (clickableNode != null) {
                isAutoSendPending = false
                mainHandler.removeCallbacksAndMessages(null)
                clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // Wait 900ms for WhatsApp to dispatch the message before returning Home
                mainHandler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 900L)
                break
            }
        }
    }

    private fun findSendByDescription(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc == "send" || desc == "send message") {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            findSendByDescription(node.getChild(i), list)
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var curr = node
        while (curr != null) {
            if (curr.isClickable) return curr
            curr = curr.parent
        }
        return null
    }

    fun executeSystemAction(rawCommand: String): String {
        val lower = rawCommand.lowercase()
        return when {
            lower.contains("home") -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                "Navigated Home"
            }
            lower.contains("back") -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                "Pressed Back"
            }
            lower.contains("lock") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                "Locked Tablet Screen"
            }
            lower.contains("notification") -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "Opened Notifications"
            }
            lower.contains("split") -> {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                "Toggled Split Screen"
            }
            else -> "System command not recognized"
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
