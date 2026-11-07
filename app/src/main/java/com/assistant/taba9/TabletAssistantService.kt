package com.assistant.taba9

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TabletAssistantService : AccessibilityService() {

    companion object {
        var instance: TabletAssistantService? = null
        var isAutoSendPending = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoSendPending || event?.packageName != "com.whatsapp") return
        val rootNode = rootInActiveWindow ?: return

        val sendNodes = mutableListOf<AccessibilityNodeInfo>()
        sendNodes.addAll(rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send"))
        if (sendNodes.isEmpty()) {
            sendNodes.addAll(rootNode.findAccessibilityNodeInfosByText("Send"))
        }

        for (node in sendNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                isAutoSendPending = false
                performGlobalAction(GLOBAL_ACTION_HOME)
                break
            }
        }
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
            else -> "Accessibility action not recognized"
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
