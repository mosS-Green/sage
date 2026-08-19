package com.mossgreen.sage.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mossgreen.sage.model.ScreenCaptureLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ScreenContextExtractor {

    data class ExtractedNode(
        val depth: Int,
        val className: String,
        val packageName: String,
        val viewId: String,
        val uniqueId: String,
        val text: String?,
        val contentDescription: String?,
        val hintText: String?,
        val tooltipText: String?,
        val errorText: String?,
        val paneTitle: String?,
        val stateDescription: String?,
        val boundsInScreen: Rect,
        val boundsInParent: Rect,
        val drawingOrder: Int,
        val isClickable: Boolean,
        val isLongClickable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isFocusable: Boolean,
        val isFocused: Boolean,
        val isAccessibilityFocused: Boolean,
        val isSelected: Boolean,
        val isEnabled: Boolean,
        val isPassword: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isVisibleToUser: Boolean,
        val isImportantForAccessibility: Boolean,
        val isHeading: Boolean,
        val isScreenReaderFocusable: Boolean,
        val isShowingHintText: Boolean,
        val isContentInvalid: Boolean,
        val isContextClickable: Boolean,
        val isDismissable: Boolean,
        val isMultiLine: Boolean,
        val inputType: Int,
        val maxTextLength: Int,
        val liveRegion: Int,
        val collectionInfo: String?,
        val collectionItemInfo: String?,
        val rangeInfo: String?,
        val actions: List<String>,
        val children: MutableList<ExtractedNode> = mutableListOf()
    )

    data class ExtractedWindow(
        val id: Int,
        val type: Int,
        val typeName: String,
        val layer: Int,
        val title: String?,
        val boundsInScreen: Rect,
        val isActive: Boolean,
        val isFocused: Boolean,
        val isAccessibilityFocused: Boolean,
        val isPip: Boolean,
        val displayId: Int,
        val rootNode: ExtractedNode?
    )

    /**
     * Captures the entire accessibility screen context across all windows and nodes.
     */
    fun capture(service: AccessibilityService): ScreenCaptureLog {
        val timestamp = System.currentTimeMillis()
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

        val extractedWindows = mutableListOf<ExtractedWindow>()
        val allNodes = mutableListOf<ExtractedNode>()

        // 1. Traverse all interactive windows if available
        val serviceWindows = try {
            service.windows
        } catch (_: Exception) {
            null
        }

        if (!serviceWindows.isNullOrEmpty()) {
            for (window in serviceWindows) {
                val windowBounds = Rect()
                try { window.getBoundsInScreen(windowBounds) } catch (_: Exception) {}

                val windowTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try { window.title?.toString() } catch (_: Exception) { null }
                } else null

                val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { window.isInPictureInPictureMode } catch (_: Exception) { false }
                } else false

                val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { window.displayId } catch (_: Exception) { 0 }
                } else 0

                val root = try { window.root } catch (_: Exception) { null }
                val extractedRoot = root?.let { extractNodeRecursive(it, depth = 0, allNodes) }

                extractedWindows.add(
                    ExtractedWindow(
                        id = window.id,
                        type = window.type,
                        typeName = getWindowTypeName(window.type),
                        layer = window.layer,
                        title = windowTitle,
                        boundsInScreen = windowBounds,
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        isAccessibilityFocused = window.isAccessibilityFocused,
                        isPip = isPip,
                        displayId = displayId,
                        rootNode = extractedRoot
                    )
                )
            }
        } else {
            // Fallback: active root window
            val fallbackRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
            if (fallbackRoot != null) {
                val fallbackBounds = Rect()
                try { fallbackRoot.getBoundsInScreen(fallbackBounds) } catch (_: Exception) {}
                val extractedRoot = extractNodeRecursive(fallbackRoot, depth = 0, allNodes)
                extractedWindows.add(
                    ExtractedWindow(
                        id = 0,
                        type = AccessibilityWindowInfo.TYPE_APPLICATION,
                        typeName = "TYPE_APPLICATION",
                        layer = 0,
                        title = null,
                        boundsInScreen = fallbackBounds,
                        isActive = true,
                        isFocused = true,
                        isAccessibilityFocused = false,
                        isPip = false,
                        displayId = 0,
                        rootNode = extractedRoot
                    )
                )
            }
        }

        // Determine primary package
        val primaryPackage = allNodes.firstOrNull { it.packageName.isNotBlank() && it.packageName != "android" && !it.packageName.contains("systemui") }?.packageName
            ?: allNodes.firstOrNull { it.packageName.isNotBlank() }?.packageName
            ?: "Unknown"

        // Build summaries
        val textSummary = buildTextSummary(allNodes)
        val interactiveSummary = buildInteractiveSummary(allNodes)
        val treeDump = buildTreeDump(extractedWindows)
        val fullDump = buildFullDump(timestamp, formattedTime, primaryPackage, extractedWindows, allNodes, textSummary, interactiveSummary, treeDump)
        val jsonDump = buildJsonDump(timestamp, formattedTime, primaryPackage, extractedWindows, allNodes)

        return ScreenCaptureLog(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            formattedTime = formattedTime,
            primaryPackage = primaryPackage,
            windowCount = extractedWindows.size,
            nodeCount = allNodes.size,
            textSummary = textSummary,
            interactiveSummary = interactiveSummary,
            treeDump = treeDump,
            fullDump = fullDump,
            jsonDump = jsonDump
        )
    }

    private fun extractNodeRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        allNodesCollector: MutableList<ExtractedNode>
    ): ExtractedNode {
        val boundsScreen = Rect()
        val boundsParent = Rect()
        try { node.getBoundsInScreen(boundsScreen) } catch (_: Exception) {}
        try { node.getBoundsInParent(boundsParent) } catch (_: Exception) {}

        val className = node.className?.toString() ?: "android.view.View"
        val packageName = node.packageName?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val uniqueId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { node.uniqueId ?: "" } catch (_: Exception) { "" }
        } else ""

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.hintText?.toString() } catch (_: Exception) { null }
        } else null

        val tooltipText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.tooltipText?.toString() } catch (_: Exception) { null }
        } else null

        val errorText = try { node.error?.toString() } catch (_: Exception) { null }

        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.paneTitle?.toString() } catch (_: Exception) { null }
        } else null

        val stateDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { node.stateDescription?.toString() } catch (_: Exception) { null }
        } else null

        val drawingOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { node.drawingOrder } catch (_: Exception) { 0 }
        } else 0

        val isImportant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { node.isImportantForAccessibility } catch (_: Exception) { true }
        } else true

        val isHeading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.isHeading } catch (_: Exception) { false }
        } else false

        val isScreenReaderFocusable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { node.isScreenReaderFocusable } catch (_: Exception) { false }
        } else false

        val isShowingHintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.isShowingHintText } catch (_: Exception) { false }
        } else false

        val isContextClickable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { node.isContextClickable } catch (_: Exception) { false }
        } else false

        val collectionInfoStr = node.collectionInfo?.let {
            "rows=${it.rowCount}, cols=${it.columnCount}, hierarchical=${it.isHierarchical}"
        }

        val collectionItemInfoStr = node.collectionItemInfo?.let {
            "row=${it.rowIndex}, rowSpan=${it.rowSpan}, col=${it.columnIndex}, colSpan=${it.columnSpan}, isHeading=${it.isHeading}, isSelected=${it.isSelected}"
        }

        val rangeInfoStr = node.rangeInfo?.let {
            "type=${it.type}, min=${it.min}, max=${it.max}, current=${it.current}"
        }

        val actionsList = mutableListOf<String>()
        try {
            for (action in node.actionList) {
                val label = action.label?.toString()
                val standardName = getActionName(action.id)
                if (label.isNullOrBlank()) {
                    actionsList.add(standardName)
                } else {
                    actionsList.add("$standardName($label)")
                }
            }
        } catch (_: Exception) {}

        val extracted = ExtractedNode(
            depth = depth,
            className = className,
            packageName = packageName,
            viewId = viewId,
            uniqueId = uniqueId,
            text = text,
            contentDescription = contentDesc,
            hintText = hintText,
            tooltipText = tooltipText,
            errorText = errorText,
            paneTitle = paneTitle,
            stateDescription = stateDesc,
            boundsInScreen = boundsScreen,
            boundsInParent = boundsParent,
            drawingOrder = drawingOrder,
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isFocusable = node.isFocusable,
            isFocused = node.isFocused,
            isAccessibilityFocused = node.isAccessibilityFocused,
            isSelected = node.isSelected,
            isEnabled = node.isEnabled,
            isPassword = node.isPassword,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isVisibleToUser = node.isVisibleToUser,
            isImportantForAccessibility = isImportant,
            isHeading = isHeading,
            isScreenReaderFocusable = isScreenReaderFocusable,
            isShowingHintText = isShowingHintText,
            isContentInvalid = node.isContentInvalid,
            isContextClickable = isContextClickable,
            isDismissable = node.isDismissable,
            isMultiLine = node.isMultiLine,
            inputType = node.inputType,
            maxTextLength = node.maxTextLength,
            liveRegion = node.liveRegion,
            collectionInfo = collectionInfoStr,
            collectionItemInfo = collectionItemInfoStr,
            rangeInfo = rangeInfoStr,
            actions = actionsList
        )

        allNodesCollector.add(extracted)

        // Recurse children (protect against infinite depth loops)
        if (depth < 64) {
            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val extractedChild = extractNodeRecursive(child, depth + 1, allNodesCollector)
                extracted.children.add(extractedChild)
            }
        }

        return extracted
    }

    private fun getWindowTypeName(type: Int): String {
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
            5 -> "TYPE_MAGNIFICATION_OVERLAY"
            else -> "TYPE_UNKNOWN($type)"
        }
    }

    private fun getActionName(actionId: Int): String {
        return when (actionId) {
            AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
            AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "ACTION_CLEAR_FOCUS"
            AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "ACTION_CLEAR_SELECTION"
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "ACTION_ACCESSIBILITY_FOCUS"
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "ACTION_CLEAR_ACCESSIBILITY_FOCUS"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
            AccessibilityNodeInfo.ACTION_COPY -> "ACTION_COPY"
            AccessibilityNodeInfo.ACTION_PASTE -> "ACTION_PASTE"
            AccessibilityNodeInfo.ACTION_CUT -> "ACTION_CUT"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "ACTION_SET_SELECTION"
            AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
            AccessibilityNodeInfo.ACTION_DISMISS -> "ACTION_DISMISS"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "ACTION_NEXT_AT_MOVEMENT_GRANULARITY"
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY -> "ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY"
            else -> "ACTION_$actionId"
        }
    }

    private fun buildTextSummary(nodes: List<ExtractedNode>): String {
        val texts = mutableListOf<String>()
        for (node in nodes) {
            val t = node.text?.trim()
            val cd = node.contentDescription?.trim()
            val hint = node.hintText?.trim()

            if (!t.isNullOrEmpty()) {
                val tag = if (node.viewId.isNotBlank()) " [${node.viewId.substringAfterLast('/')}]" else ""
                texts.add("\"$t\"$tag")
            } else if (!cd.isNullOrEmpty()) {
                val tag = if (node.viewId.isNotBlank()) " [${node.viewId.substringAfterLast('/')}]" else ""
                texts.add("desc:\"$cd\"$tag")
            } else if (!hint.isNullOrEmpty() && node.isShowingHintText) {
                texts.add("hint:\"$hint\"")
            }
        }
        return if (texts.isEmpty()) "(No readable text found)" else texts.joinToString("\n")
    }

    private fun buildInteractiveSummary(nodes: List<ExtractedNode>): String {
        val interactive = mutableListOf<String>()
        for (node in nodes) {
            if (node.isClickable || node.isEditable || node.isCheckable || node.isScrollable) {
                val types = mutableListOf<String>()
                if (node.isClickable) types.add("clickable")
                if (node.isEditable) types.add("editable")
                if (node.isCheckable) types.add(if (node.isChecked) "checked" else "checkable")
                if (node.isScrollable) types.add("scrollable")
                if (node.isFocused) types.add("focused")

                val label = when {
                    !node.text.isNullOrBlank() -> "\"${node.text}\""
                    !node.contentDescription.isNullOrBlank() -> "desc:\"${node.contentDescription}\""
                    !node.hintText.isNullOrBlank() -> "hint:\"${node.hintText}\""
                    else -> "<unlabeled>"
                }

                val simpleClass = node.className.substringAfterLast('.')
                val idStr = if (node.viewId.isNotBlank()) " id=${node.viewId}" else ""
                val bounds = "[${node.boundsInScreen.left},${node.boundsInScreen.top},${node.boundsInScreen.right},${node.boundsInScreen.bottom}]"

                interactive.add("• [$simpleClass] $label (${types.joinToString(", ")})$idStr $bounds")
            }
        }
        return if (interactive.isEmpty()) "(No interactive elements found)" else interactive.joinToString("\n")
    }

    private fun buildTreeDump(windows: List<ExtractedWindow>): String {
        val sb = StringBuilder()
        for ((winIndex, win) in windows.withIndex()) {
            sb.appendLine("═══ WINDOW #${winIndex + 1}: ${win.typeName} (id=${win.id}, layer=${win.layer}, active=${win.isActive}, focused=${win.isFocused}, bounds=[${win.boundsInScreen.left},${win.boundsInScreen.top},${win.boundsInScreen.right},${win.boundsInScreen.bottom}]) ═══")
            if (win.title != null) sb.appendLine("  Title: ${win.title}")
            if (win.rootNode != null) {
                appendNodeTree(win.rootNode, sb)
            } else {
                sb.appendLine("  (No root node accessible)")
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
    }

    private fun appendNodeTree(node: ExtractedNode, sb: StringBuilder) {
        val indent = "  ".repeat(node.depth)
        val simpleClass = node.className.substringAfterLast('.')
        val flags = mutableListOf<String>()
        if (node.isClickable) flags.add("clickable")
        if (node.isEditable) flags.add("editable")
        if (node.isFocused) flags.add("focused")
        if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "checkable")
        if (node.isSelected) flags.add("selected")
        if (node.isScrollable) flags.add("scrollable")
        if (!node.isEnabled) flags.add("disabled")
        if (node.isPassword) flags.add("password")
        if (node.isHeading) flags.add("heading")

        val flagStr = if (flags.isNotEmpty()) " (${flags.joinToString(",")})" else ""
        val idStr = if (node.viewId.isNotBlank()) " id=${node.viewId.substringAfterLast('/')}" else ""
        val textStr = when {
            !node.text.isNullOrBlank() -> " text=\"${node.text.replace("\n", "\\n")}\""
            !node.contentDescription.isNullOrBlank() -> " desc=\"${node.contentDescription.replace("\n", "\\n")}\""
            !node.hintText.isNullOrBlank() -> " hint=\"${node.hintText}\""
            else -> ""
        }
        val boundsStr = " [${node.boundsInScreen.left},${node.boundsInScreen.top},${node.boundsInScreen.right},${node.boundsInScreen.bottom}]"

        sb.appendLine("$indent├─ [$simpleClass]$idStr$textStr$boundsStr$flagStr")

        for (child in node.children) {
            appendNodeTree(child, sb)
        }
    }

    private fun buildFullDump(
        timestamp: Long,
        formattedTime: String,
        primaryPackage: String,
        windows: List<ExtractedWindow>,
        allNodes: List<ExtractedNode>,
        textSummary: String,
        interactiveSummary: String,
        treeDump: String
    ): String {
        return buildString {
            appendLine("╔══════════════════════════════════════════════════════════════════════════════╗")
            appendLine("║               SAGE ACCESSIBILITY SCREEN CONTEXT DUMP (?ss)                   ║")
            appendLine("╚══════════════════════════════════════════════════════════════════════════════╝")
            appendLine("Timestamp: $formattedTime ($timestamp)")
            appendLine("Primary Package: $primaryPackage")
            appendLine("Total Windows: ${windows.size}")
            appendLine("Total Nodes Traversed: ${allNodes.size}")
            appendLine()
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine("📖 VISIBLE TEXT READING ORDER:")
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine(textSummary)
            appendLine()
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine("🎯 INTERACTIVE ELEMENTS (Buttons, Inputs, Toggles):")
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine(interactiveSummary)
            appendLine()
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine("🌳 COMPLETE ACCESSIBILITY HIERARCHY TREE:")
            appendLine("────────────────────────────────────────────────────────────────────────────────")
            appendLine(treeDump)
        }
    }

    private fun buildJsonDump(
        timestamp: Long,
        formattedTime: String,
        primaryPackage: String,
        windows: List<ExtractedWindow>,
        allNodes: List<ExtractedNode>
    ): String {
        val rootJson = JSONObject()
        rootJson.put("timestamp", timestamp)
        rootJson.put("formattedTime", formattedTime)
        rootJson.put("primaryPackage", primaryPackage)
        rootJson.put("windowCount", windows.size)
        rootJson.put("nodeCount", allNodes.size)

        val windowsArray = JSONArray()
        for (win in windows) {
            val winObj = JSONObject()
            winObj.put("id", win.id)
            winObj.put("type", win.typeName)
            winObj.put("layer", win.layer)
            winObj.put("title", win.title ?: JSONObject.NULL)
            winObj.put("isActive", win.isActive)
            winObj.put("isFocused", win.isFocused)
            winObj.put("isAccessibilityFocused", win.isAccessibilityFocused)
            winObj.put("isPip", win.isPip)
            winObj.put("displayId", win.displayId)
            winObj.put("bounds", JSONObject().apply {
                put("left", win.boundsInScreen.left)
                put("top", win.boundsInScreen.top)
                put("right", win.boundsInScreen.right)
                put("bottom", win.boundsInScreen.bottom)
                put("width", win.boundsInScreen.width())
                put("height", win.boundsInScreen.height())
            })
            if (win.rootNode != null) {
                winObj.put("root", nodeToJson(win.rootNode))
            }
            windowsArray.put(winObj)
        }
        rootJson.put("windows", windowsArray)

        return rootJson.toString(2)
    }

    private fun nodeToJson(node: ExtractedNode): JSONObject {
        val json = JSONObject()
        json.put("className", node.className)
        json.put("packageName", node.packageName)
        json.put("viewId", node.viewId)
        if (node.uniqueId.isNotBlank()) json.put("uniqueId", node.uniqueId)
        if (node.text != null) json.put("text", node.text)
        if (node.contentDescription != null) json.put("contentDescription", node.contentDescription)
        if (node.hintText != null) json.put("hintText", node.hintText)
        if (node.tooltipText != null) json.put("tooltipText", node.tooltipText)
        if (node.errorText != null) json.put("errorText", node.errorText)
        if (node.paneTitle != null) json.put("paneTitle", node.paneTitle)
        if (node.stateDescription != null) json.put("stateDescription", node.stateDescription)

        json.put("bounds", JSONObject().apply {
            put("left", node.boundsInScreen.left)
            put("top", node.boundsInScreen.top)
            put("right", node.boundsInScreen.right)
            put("bottom", node.boundsInScreen.bottom)
            put("width", node.boundsInScreen.width())
            put("height", node.boundsInScreen.height())
        })

        val states = JSONObject()
        states.put("clickable", node.isClickable)
        states.put("longClickable", node.isLongClickable)
        states.put("checkable", node.isCheckable)
        states.put("checked", node.isChecked)
        states.put("focusable", node.isFocusable)
        states.put("focused", node.isFocused)
        states.put("accessibilityFocused", node.isAccessibilityFocused)
        states.put("selected", node.isSelected)
        states.put("enabled", node.isEnabled)
        states.put("password", node.isPassword)
        states.put("scrollable", node.isScrollable)
        states.put("editable", node.isEditable)
        states.put("visibleToUser", node.isVisibleToUser)
        states.put("importantForAccessibility", node.isImportantForAccessibility)
        states.put("heading", node.isHeading)
        states.put("screenReaderFocusable", node.isScreenReaderFocusable)
        states.put("showingHintText", node.isShowingHintText)
        states.put("contextClickable", node.isContextClickable)
        states.put("dismissable", node.isDismissable)
        states.put("multiLine", node.isMultiLine)
        json.put("states", states)

        if (node.collectionInfo != null) json.put("collectionInfo", node.collectionInfo)
        if (node.collectionItemInfo != null) json.put("collectionItemInfo", node.collectionItemInfo)
        if (node.rangeInfo != null) json.put("rangeInfo", node.rangeInfo)

        if (node.actions.isNotEmpty()) {
            val actionsArray = JSONArray()
            for (action in node.actions) {
                actionsArray.put(action)
            }
            json.put("actions", actionsArray)
        }

        if (node.children.isNotEmpty()) {
            val childrenArray = JSONArray()
            for (child in node.children) {
                childrenArray.put(nodeToJson(child))
            }
            json.put("children", childrenArray)
        }

        return json
    }
}
