package com.paw.agent.core.tool.android

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.model.ToolDefinition
import com.paw.agent.device.PhoneController
import com.paw.agent.device.RectBounds
import com.paw.agent.device.VisionResolutionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

object SafetyGuard {
    private val sensitiveKeywords = listOf(
        "支付密码", "输入密码", "确认付款", "确认支付", "验证码支付", "指纹支付",
        "payment password", "enter password", "confirm payment", "cvv", "安全凭据",
    )

    fun isSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return sensitiveKeywords.any { lower.contains(it) }
    }
}

class TakeScreenshotTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "take_screenshot",
        description = "Takes a screenshot of the current Android phone screen. Supports dynamic resolution: 'AUTO' (recommended, balanced token/speed), 'FAST' (low token, 720p), 'HIGH' (1080p for dense text). Can also crop a specific region [ymin, xmin, ymax, xmax] in 0..1000 range.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "mode": {
              "type": "string",
              "enum": ["AUTO", "FAST", "HIGH"],
              "description": "Adaptive resolution mode. AUTO saves tokens; HIGH provides full detail."
            },
            "crop_roi": {
              "type": "array",
              "items": { "type": "integer" },
              "description": "Optional bounding box to crop [ymin, xmin, ymax, xmax] in normalized 0..1000 coords"
            }
          }
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val modeStr = root?.get("mode")?.jsonPrimitive?.contentOrNull ?: "AUTO"
        val mode = when (modeStr.uppercase()) {
            "FAST" -> VisionResolutionMode.FAST
            "HIGH" -> VisionResolutionMode.HIGH
            else -> VisionResolutionMode.AUTO
        }

        val cropRoi = root?.get("crop_roi")?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.size == 4 }

        val result = phoneController.takeScreenshot(mode, cropRoi)
            ?: return """{"status":"error","message":"Failed to capture screen. Ensure Accessibility Service or Shizuku is enabled."}"""

        val screenState = phoneController.getScreenState()
        val allText = screenState.elements.joinToString(" ") { it.text + " " + it.contentDescription }
        val isSensitive = SafetyGuard.isSensitive(allText)
        val safetyAlert = if (isSensitive) """,\n  "safety_alert": "SENSITIVE_PAYMENT_OR_PASSWORD_SCREEN_PAUSED"""" else ""

        // 回显本次截图所用的裁剪区域与真实源分辨率，方便模型在后续 tap/double_tap/
        // long_press 中传入相同的 crop_roi，使基于裁剪截图推断的坐标正确落回全屏。
        val cropEcho = if (cropRoi != null) """,\n  "crop_roi": [${cropRoi[0]}, ${cropRoi[1]}, ${cropRoi[2]}, ${cropRoi[3]}]""" else ""
        val sourceEcho = """,\n  "source_width": ${result.width},\n  "source_height": ${result.height}"""

        return """
        {
          "status": "success",
          "width": ${result.width},
          "height": ${result.height},
          "mode": "${result.modeUsed}",
          "estimated_tokens": ${result.estimatedTokens},
          "image_base64": "${result.base64Data}"$cropEcho$sourceEcho$safetyAlert
        }
        """.trimIndent()
    }
}

class TapTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "tap",
        description = "Taps on the Android screen at normalized coordinates [x, y] in range 0..1000 where (0,0) is top-left and (1000,1000) is bottom-right. If the screenshot you inferred these coordinates from was cropped via take_screenshot's crop_roi, pass the SAME crop_roi here so the point maps back to the full screen. For clicking a known button, prefer `click_element` (pixel-accurate via the accessibility tree).",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "x": { "type": "integer", "description": "Horizontal coordinate in 0..1000 range" },
            "y": { "type": "integer", "description": "Vertical coordinate in 0..1000 range" },
            "crop_roi": {
              "type": "array",
              "items": { "type": "integer" },
              "description": "Optional [ymin, xmin, ymax, xmax] in 0..1000 used by the screenshot this tap is based on. Must match take_screenshot's crop_roi."
            }
          },
          "required": ["x", "y"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val x = root["x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x' is required"
        val y = root["y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y' is required"
        val cropRoi = root["crop_roi"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.size == 4 }

        val state = phoneController.getScreenState()
        val allText = state.elements.joinToString(" ") { it.text + " " + it.contentDescription }
        if (SafetyGuard.isSensitive(allText)) {
            return """{"status":"paused","message":"[SAFETY PAUSE] Detected sensitive password/payment screen. Automated tapping is paused for security. Please complete this step manually on your device."}"""
        }

        val ok = phoneController.tap(x, y, cropRoi)
        return if (ok) """{"status":"success","action":"tap","x":$x,"y":$y}"""
        else """{"status":"error","message":"Tap failed. Check accessibility or Shizuku permissions."}"""
    }
}

class SwipeTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "swipe",
        description = "Swipes/scrolls on the Android screen from (start_x, start_y) to (end_x, end_y) using 0..1000 normalized coordinates. Example: to scroll down, swipe from y=800 to y=200.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "start_x": { "type": "integer", "description": "Start X in 0..1000 range" },
            "start_y": { "type": "integer", "description": "Start Y in 0..1000 range" },
            "end_x": { "type": "integer", "description": "End X in 0..1000 range" },
            "end_y": { "type": "integer", "description": "End Y in 0..1000 range" },
            "duration_ms": { "type": "integer", "description": "Swipe duration in milliseconds, default 350" }
          },
          "required": ["start_x", "start_y", "end_x", "end_y"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val sx = root["start_x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'start_x' is required"
        val sy = root["start_y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'start_y' is required"
        val ex = root["end_x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'end_x' is required"
        val ey = root["end_y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'end_y' is required"
        val duration = root["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 350L

        val ok = phoneController.swipe(sx, sy, ex, ey, duration)
        return if (ok) """{"status":"success","action":"swipe","from":[$sx,$sy],"to":[$ex,$ey]}"""
        else """{"status":"error","message":"Swipe failed. Check permissions."}"""
    }
}

class InputTextTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "input_text",
        description = "Types text into the currently active/focused input field on Android.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "text": { "type": "string", "description": "The text string to type" },
            "clear_before": { "type": "boolean", "description": "Whether to clear existing text before typing" },
            "press_enter": { "type": "boolean", "description": "Whether to press Enter after typing" }
          },
          "required": ["text"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val text = root["text"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'text' is required"
        val clear = root["clear_before"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val enter = root["press_enter"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

        val state = phoneController.getScreenState()
        val allText = state.elements.joinToString(" ") { it.text + " " + it.contentDescription }
        if (SafetyGuard.isSensitive(allText)) {
            return """{"status":"paused","message":"[SAFETY PAUSE] Detected sensitive password/payment screen. Automated text input is paused for security. Please complete this step manually on your device."}"""
        }

        val ok = phoneController.inputText(text, clear)
        if (ok && enter) {
            phoneController.pressEnter()
        }
        return if (ok) """{"status":"success","typed":"$text"}"""
        else """{"status":"error","message":"Input text failed. Make sure an input field is focused."}"""
    }
}

class KeyActionTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "key_action",
        description = "Simulates global Android system keys: 'BACK', 'HOME', 'RECENTS', 'ENTER'.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["BACK", "HOME", "RECENTS", "ENTER"],
              "description": "System key action to trigger"
            }
          },
          "required": ["action"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val action = root["action"]?.jsonPrimitive?.contentOrNull?.uppercase()
            ?: return "Error: 'action' is required"

        val ok = when (action) {
            "BACK" -> phoneController.pressBack()
            "HOME" -> phoneController.pressHome()
            "RECENTS" -> phoneController.pressRecents()
            "ENTER" -> phoneController.pressEnter()
            else -> false
        }

        return if (ok) """{"status":"success","action":"$action"}"""
        else """{"status":"error","message":"Key action $action failed"}"""
    }
}

class LaunchAppTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "launch_app",
        description = "Launches an application by app name or package name (e.g. 'wechat', 'settings', 'calculator', 'com.netease.cloudmusic').",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "app_name": { "type": "string", "description": "App display name or Android package identifier" }
          },
          "required": ["app_name"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val appName = root["app_name"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'app_name' is required"

        val ok = phoneController.launchApp(appName)
        return if (ok) """{"status":"success","launched":"$appName"}"""
        else """{"status":"error","message":"Could not launch app '$appName'. Ensure it is installed."}"""
    }
}

class DeepLinkTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "open_deeplink",
        description = "Directly navigates to an Android DeepLink URI (e.g., 'alipays://...', 'weixin://...', 'https://...').",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "uri": { "type": "string", "description": "DeepLink URI or Web URL" }
          },
          "required": ["uri"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val uri = root["uri"]?.jsonPrimitive?.contentOrNull ?: return "Error: 'uri' is required"

        val ok = phoneController.openDeepLink(uri)
        return if (ok) """{"status":"success","opened":"$uri"}"""
        else """{"status":"error","message":"Could not open deeplink URI '$uri'"}"""
    }
}

class GetScreenStateTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "get_screen_state",
        description = "Retrieves the current foreground app package, activity, and key interactive UI elements with their text and bounds.",
        parametersSchema = """{ "type": "object", "properties": {} }""",
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val state = phoneController.getScreenState()
        val elementsSummary = state.elements.take(30).mapIndexed { idx, elem ->
            """{"index":$idx,"text":"${elem.text}","desc":"${elem.contentDescription}","id":"${elem.viewId}","clickable":${elem.isClickable},"bounds":[${elem.bounds.left},${elem.bounds.top},${elem.bounds.right},${elem.bounds.bottom}],"center":[${elem.bounds.centerX},${elem.bounds.centerY}]}"""
        }.joinToString(",")

        return """
        {
          "foreground_package": "${state.foregroundPackage}",
          "foreground_activity": "${state.foregroundActivity}",
          "elements_count": ${state.elements.size},
          "elements": [$elementsSummary]
        }
        """.trimIndent()
    }
}

class ClickElementTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "click_element",
        description = "Clicks a specific on-screen UI element with PIXEL-ACCURATE precision using the accessibility hierarchy — no coordinate guessing, so it almost never needs retries. Prefer this over `tap` whenever the target is a known button/control (e.g. inside a user-installed app). Resolve the target by ONE of: `bounds` (exact [left, top, right, bottom] in real screen pixels from get_screen_state), `index` (0-based element index from get_screen_state), or `text`/`view_id` to match. The element's true center is clicked in real screen pixels.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "bounds": {
              "type": "array",
              "items": { "type": "integer" },
              "description": "Element bounds [left, top, right, bottom] in real screen pixels (from get_screen_state). Highest priority."
            },
            "index": { "type": "integer", "description": "0-based index of the element as listed by get_screen_state." },
            "text": { "type": "string", "description": "Substring to match against an element's text or content description." },
            "view_id": { "type": "string", "description": "Android view id resource name to match exactly." }
          }
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject

        val state = phoneController.getScreenState()
        val allText = state.elements.joinToString(" ") { it.text + " " + it.contentDescription }
        if (SafetyGuard.isSensitive(allText)) {
            return """{"status":"paused","message":"[SAFETY PAUSE] Detected sensitive password/payment screen. Automated tapping is paused for security. Please complete this step manually on your device."}"""
        }

        // 1) Resolve the target element's pixel bounds.
        val rect: RectBounds? = resolveBounds(root, state)

        if (rect == null) {
            return """{"status":"error","message":"Could not resolve a target element. Provide `bounds` (from get_screen_state), a valid `index`, or matching `text`/`view_id`."}"""
        }

        // 2) Click the exact center in real screen pixels (no normalization round-trip).
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val ok = phoneController.tapAtPixel(cx, cy)
        return if (ok) {
            """{"status":"success","action":"click_element","bounds":[${rect.left},${rect.top},${rect.right},${rect.bottom}],"center":[$cx,$cy]}"""
        } else {
            """{"status":"error","message":"click_element failed. Check accessibility or Shizuku permissions."}"""
        }
    }

    private fun resolveBounds(
        root: kotlinx.serialization.json.JsonObject,
        state: com.paw.agent.device.ScreenStateInfo,
    ): RectBounds? {
        // Priority 1: explicit pixel bounds.
        val bounds = root["bounds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.size == 4 }
        if (bounds != null) {
            return RectBounds(bounds[0], bounds[1], bounds[2], bounds[3])
        }

        // Priority 2: index into the current accessibility hierarchy.
        val index = root["index"]?.jsonPrimitive?.intOrNull
        if (index != null && index in state.elements.indices) {
            return state.elements[index].bounds
        }

        // Priority 3: match by text / content description / view id.
        val text = root["text"]?.jsonPrimitive?.contentOrNull
        val viewId = root["view_id"]?.jsonPrimitive?.contentOrNull
        return state.elements.firstOrNull { elem ->
            (viewId != null && elem.viewId == viewId) ||
                (text != null && ((elem.text + " " + elem.contentDescription).contains(text, ignoreCase = true)))
        }?.bounds
    }
}

class DoubleTapTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "double_tap",
        description = "Double taps at normalized coordinates [x, y] in 0..1000 range. Pass the same crop_roi used by the screenshot if it was cropped.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "x": { "type": "integer", "description": "Horizontal coordinate in 0..1000 range" },
            "y": { "type": "integer", "description": "Vertical coordinate in 0..1000 range" },
            "crop_roi": {
              "type": "array",
              "items": { "type": "integer" },
              "description": "Optional [ymin, xmin, ymax, xmax] in 0..1000 matching take_screenshot's crop_roi."
            }
          },
          "required": ["x", "y"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val x = root["x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x' is required"
        val y = root["y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y' is required"
        val cropRoi = root["crop_roi"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.size == 4 }

        val ok = phoneController.doubleTap(x, y, cropRoi)
        return if (ok) """{"status":"success","action":"double_tap","x":$x,"y":$y}"""
        else """{"status":"error","message":"Double tap failed"}"""
    }
}

class LongPressTool(
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition = ToolDefinition(
        name = "long_press",
        description = "Long presses at normalized coordinates [x, y] in 0..1000 range for a given duration. Pass the same crop_roi used by the screenshot if it was cropped.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "x": { "type": "integer", "description": "Horizontal coordinate in 0..1000 range" },
            "y": { "type": "integer", "description": "Vertical coordinate in 0..1000 range" },
            "duration_ms": { "type": "integer", "description": "Hold duration in milliseconds (default 1000)" },
            "crop_roi": {
              "type": "array",
              "items": { "type": "integer" },
              "description": "Optional [ymin, xmin, ymax, xmax] in 0..1000 matching take_screenshot's crop_roi."
            }
          },
          "required": ["x", "y"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val x = root["x"]?.jsonPrimitive?.intOrNull ?: return "Error: 'x' is required"
        val y = root["y"]?.jsonPrimitive?.intOrNull ?: return "Error: 'y' is required"
        val duration = root["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 1000L
        val cropRoi = root["crop_roi"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
            ?.takeIf { it.size == 4 }

        val ok = phoneController.longPress(x, y, duration, cropRoi)
        return if (ok) """{"status":"success","action":"long_press","x":$x,"y":$y,"duration_ms":$duration}"""
        else """{"status":"error","message":"Long press failed"}"""
    }
}

class WaitTool : AgentTool {
    override val definition = ToolDefinition(
        name = "wait_seconds",
        description = "Waits for a given number of seconds (1..10) to allow animations, network loading, or page transitions to settle.",
        parametersSchema = """
        {
          "type": "object",
          "properties": {
            "seconds": { "type": "number", "description": "Seconds to wait (between 0.5 and 10.0)" }
          },
          "required": ["seconds"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val seconds = root["seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.0
        val clamped = seconds.coerceIn(0.5, 10.0)
        kotlinx.coroutines.delay((clamped * 1000).toLong())
        return """{"status":"success","waited_seconds":$clamped}"""
    }
}

