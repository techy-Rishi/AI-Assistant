package com.assistant.taba9

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
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
        findNodeByDescription(rootNode, "send", candidates)

        for (node in candidates) {
            val clickableNode = findClickableParent(node)
            if (clickableNode != null) {
                isAutoSendPending = false
                mainHandler.removeCallbacksAndMessages(null)
                clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 900L)
                break
            }
        }
    }

    // UNIVERSAL UI AGENT: Click any button by text, Scroll Up/Down, or Type text into any input box!
    fun interactWithCurrentScreen(rawCommand: String, targetText: String): String {
        val rootNode = rootInActiveWindow ?: return "No active screen content found."
        val lower = rawCommand.lowercase()

        // 1. Scroll Commands
        if (lower.contains("scroll down") || lower.contains("swipe up")) {
            val scrollable = findScrollableNode(rootNode)
            return if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
                "Scrolled down on screen"
            } else "Nothing scrollable on this screen"
        }
        if (lower.contains("scroll up") || lower.contains("swipe down")) {
            val scrollable = findScrollableNode(rootNode)
            return if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id)
                "Scrolled up on screen"
            } else "Nothing scrollable on this screen"
        }

        // 2. Type/Write into focused or first EditText on screen
        if (lower.startsWith("type ") || lower.startsWith("write ") || lower.startsWith("search for ")) {
            val editNode = findEditableNode(rootNode)
            if (editNode != null && targetText.isNotBlank()) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, targetText)
                }
                editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return "Typed \"$targetText\" into text box"
            }
            return "Could not find an input text field on screen"
        }

        // 3. Universal Click by Visible Text or Content Description
        if (targetText.isNotBlank()) {
            val matches = mutableListOf<AccessibilityNodeInfo>()
            matches.addAll(rootNode.findAccessibilityNodeInfosByText(targetText))
            findNodeByDescription(rootNode, targetText.lowercase(), matches)

            for (node in matches) {
                val clickable = findClickableParent(node)
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return "Clicked on \"$targetText\""
                }
            }
            return "Could not find button or text \"$targetText\" on screen"
        }

        return "Specify what to click, type, or scroll."
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
            lower.contains("screenshot") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                "Took a Screenshot"
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

    private fun findNodeByDescription(node: AccessibilityNodeInfo?, query: String, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(query)) list.add(node)
        for (i in 0 until node.childCount) {
            findNodeByDescription(node.getChild(i), query, list)
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

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
