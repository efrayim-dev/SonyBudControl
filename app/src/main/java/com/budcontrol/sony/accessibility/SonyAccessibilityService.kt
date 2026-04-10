package com.budcontrol.sony.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.budcontrol.sony.protocol.SonyCommands
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SonyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SonyA11y"

        val SONY_PACKAGES = listOf(
            "com.sony.songpal.mdr",
            "com.sony.soundconnect",
        )

        private val instance = MutableStateFlow<SonyAccessibilityService?>(null)
        val running: StateFlow<SonyAccessibilityService?> = instance.asStateFlow()

        fun isRunning(): Boolean = instance.value != null

        private val _pendingAction = MutableStateFlow<PendingAction?>(null)
        private val _readState = MutableStateFlow(SonyAppState())
        val readState: StateFlow<SonyAppState> = _readState.asStateFlow()

        fun requestAction(action: PendingAction) {
            _pendingAction.value = action
        }
    }

    data class SonyAppState(
        val ancMode: SonyCommands.AncMode? = null,
        val batteryLeft: Int = -1,
        val batteryRight: Int = -1,
        val batteryCase: Int = -1,
        val lastScanTime: Long = 0
    )

    sealed class PendingAction {
        data class SetAnc(val mode: SonyCommands.AncMode) : PendingAction()
        data object ReadState : PendingAction()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var actionJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.value = this
        Log.i(TAG, "Accessibility service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 200
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in SONY_PACKAGES) return

        val action = _pendingAction.value ?: return

        actionJob?.cancel()
        actionJob = scope.launch {
            delay(300)
            val root = rootInActiveWindow ?: return@launch
            Log.i(TAG, "Sony app event: ${event.eventType}, scanning nodes…")
            dumpTree(root, 0)

            when (action) {
                is PendingAction.SetAnc -> executeAncAction(root, action.mode)
                is PendingAction.ReadState -> readStateFromUi(root)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance.value = null
        scope.cancel()
        super.onDestroy()
    }

    // ── ANC control ────────────────────────────────────────────────

    private fun executeAncAction(root: AccessibilityNodeInfo, mode: SonyCommands.AncMode) {
        val targetTexts = when (mode) {
            SonyCommands.AncMode.NOISE_CANCELING -> listOf(
                "noise cancel", "noise canceling", "noise cancelling", "nc"
            )
            SonyCommands.AncMode.AMBIENT_SOUND -> listOf(
                "ambient sound", "ambient", "transparency"
            )
            SonyCommands.AncMode.OFF -> listOf(
                "off"
            )
        }

        for (text in targetTexts) {
            val nodes = findNodesByText(root, text)
            for (node in nodes) {
                if (tryClick(node)) {
                    Log.i(TAG, "Clicked ANC target: '$text'")
                    _pendingAction.value = null
                    returnToApp()
                    return
                }
            }
        }

        // Fallback: try content descriptions
        for (text in targetTexts) {
            val nodes = findNodesByContentDescription(root, text)
            for (node in nodes) {
                if (tryClick(node)) {
                    Log.i(TAG, "Clicked ANC via content description: '$text'")
                    _pendingAction.value = null
                    returnToApp()
                    return
                }
            }
        }

        Log.w(TAG, "Could not find ANC target for $mode")
        _pendingAction.value = null
    }

    // ── State reading ──────────────────────────────────────────────

    private fun readStateFromUi(root: AccessibilityNodeInfo) {
        var ancMode: SonyCommands.AncMode? = null
        var battL = -1; var battR = -1; var battC = -1

        val allText = collectAllText(root)
        Log.i(TAG, "UI text: ${allText.take(20)}")

        for (text in allText) {
            val lower = text.lowercase()
            if (ancMode == null) {
                when {
                    lower.contains("noise cancel") -> ancMode = SonyCommands.AncMode.NOISE_CANCELING
                    lower.contains("ambient sound") -> ancMode = SonyCommands.AncMode.AMBIENT_SOUND
                }
            }

            val battMatch = Regex("""(\d{1,3})\s*%""").find(text)
            if (battMatch != null) {
                val pct = battMatch.groupValues[1].toIntOrNull() ?: -1
                when {
                    lower.contains("l") && battL < 0 -> battL = pct
                    lower.contains("r") && battR < 0 -> battR = pct
                    lower.contains("case") && battC < 0 -> battC = pct
                }
            }
        }

        _readState.value = SonyAppState(
            ancMode = ancMode,
            batteryLeft = battL,
            batteryRight = battR,
            batteryCase = battC,
            lastScanTime = System.currentTimeMillis()
        )
        _pendingAction.value = null
    }

    // ── Node tree utilities ────────────────────────────────────────

    private fun findNodesByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val found = root.findAccessibilityNodeInfosByText(text)
        if (found != null) results.addAll(found)
        return results
    }

    private fun findNodesByContentDescription(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        traverseTree(root) { node ->
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains(text.lowercase())) results.add(node)
        }
        return results
    }

    private fun collectAllText(root: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        traverseTree(root) { node ->
            node.text?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) texts.add(it) }
        }
        return texts
    }

    private fun traverseTree(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, action)
        }
    }

    private fun tryClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun returnToApp() {
        scope.launch {
            delay(400)
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
            }
        }
    }

    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 6) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val click = if (node.isClickable) " [click]" else ""
        val check = if (node.isChecked) " [checked]" else ""
        val sel = if (node.isSelected) " [selected]" else ""

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            Log.d(TAG, "${indent}$cls text='$text' desc='$desc'$click$check$sel")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpTree(child, depth + 1)
        }
    }
}
