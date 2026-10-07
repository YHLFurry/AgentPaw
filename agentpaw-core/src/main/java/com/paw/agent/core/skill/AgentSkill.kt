package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.model.ToolDefinition
import com.paw.agent.device.PhoneController
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/**
 * High-level composite capability that orchestrates multiple atomic phone operations.
 * Decoupled from low-level tools, allowing complex app behaviors to be reused, tested,
 * and maintained independently.
 */
interface AgentSkill {
    val name: String
    val description: String
    val parametersSchema: String

    suspend fun execute(
        arguments: String,
        phoneController: PhoneController,
        context: AgentContext,
    ): String
}

/**
 * Adapter that exposes any [AgentSkill] as an [AgentTool], allowing the model
 * to call it directly via standard function-calling.
 */
class SkillToolAdapter(
    private val skill: AgentSkill,
    private val phoneController: PhoneController,
) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = skill.name,
        description = "[SKILL] " + skill.description,
        parametersSchema = skill.parametersSchema,
    )

    override suspend fun execute(arguments: String, context: AgentContext): String =
        skill.execute(arguments, phoneController, context)
}

class SkillRegistry(initialSkills: List<AgentSkill> = emptyList()) {
    private val skillsByName = java.util.concurrent.ConcurrentHashMap<String, AgentSkill>().apply {
        initialSkills.forEach { put(it.name, it) }
    }

    val allSkills: List<AgentSkill> get() = skillsByName.values.toList()

    fun find(name: String): AgentSkill? = skillsByName[name]

    fun register(skill: AgentSkill) {
        skillsByName[skill.name] = skill
    }

    fun unregister(name: String) {
        skillsByName.remove(name)
    }

    fun replaceAll(skills: List<AgentSkill>) {
        skillsByName.clear()
        skills.forEach { skillsByName[it.name] = it }
    }

    fun toTools(phoneController: PhoneController): List<AgentTool> =
        allSkills.map { SkillToolAdapter(it, phoneController) }
}

/**
 * Skill: Safely returns to the Android home screen and verifies desktop state.
 */
class ReturnHomeAndResetSkill : AgentSkill {
    override val name: String = "skill_return_home"
    override val description: String =
        "Navigates back to the Android home desktop screen, clearing foreground app focus."
    override val parametersSchema: String = """{"type":"object","properties":{}}"""

    override suspend fun execute(
        arguments: String,
        phoneController: PhoneController,
        context: AgentContext,
    ): String {
        phoneController.pressHome()
        delay(300)
        phoneController.pressHome()
        delay(300)
        val state = phoneController.getScreenState()
        return """{"status":"success","current_package":"${state.foregroundPackage}"}"""
    }
}

/**
 * Skill: Launches a specified application, waits for page stabilization, and initiates a search.
 */
class OpenAndSearchSkill : AgentSkill {
    override val name: String = "skill_open_and_search"
    override val description: String =
        "Launches an app and inputs a search keyword, submitting the search request."
    override val parametersSchema: String = """
    {
      "type": "object",
      "properties": {
        "app_name": { "type": "string", "description": "Target application name or package" },
        "keyword": { "type": "string", "description": "Search query or keyword" }
      },
      "required": ["app_name", "keyword"]
    }
    """.trimIndent()

    override suspend fun execute(
        arguments: String,
        phoneController: PhoneController,
        context: AgentContext,
    ): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val appName = root["app_name"]?.jsonPrimitive?.contentOrNull ?: return "Error: app_name required"
        val keyword = root["keyword"]?.jsonPrimitive?.contentOrNull ?: return "Error: keyword required"

        // 1. Launch App
        val launched = phoneController.launchApp(appName)
        if (!launched) return """{"status":"error","message":"Could not launch app $appName"}"""

        delay(1200) // Wait for launch animation and initial load

        // 2. Check if search box or input element exists on screen
        val state = phoneController.getScreenState()
        val searchNode = state.elements.firstOrNull { elem ->
            val text = (elem.text + " " + elem.contentDescription).lowercase()
            text.contains("搜索") || text.contains("search") || elem.isEditable
        }

        if (searchNode != null) {
            // Click the search element at its exact on-screen pixel center — no
            // hardcoded resolution, so this works on any device density/size.
            phoneController.tapAtPixel(searchNode.bounds.centerX.toFloat(), searchNode.bounds.centerY.toFloat())
            delay(500)
        }

        // 3. Type text and enter
        phoneController.inputText(keyword, clearBeforeInput = false)
        delay(200)
        phoneController.pressEnter()
        delay(800)

        return """
        {
          "status": "success",
          "app": "$appName",
          "searched_keyword": "$keyword",
          "foreground_package": "${phoneController.getScreenState().foregroundPackage}"
        }
        """.trimIndent()
    }
}

/**
 * Skill: Scrolls through a scrollable container to locate a target text/element,
 * avoiding multiple repetitive VLM screenshot inferences and dramatically saving tokens.
 */
class ScrollAndFindSkill : AgentSkill {
    override val name: String = "skill_scroll_and_find"
    override val description: String =
        "Smoothly scrolls on the screen to find an item matching target_text and optionally taps it."
    override val parametersSchema: String = """
    {
      "type": "object",
      "properties": {
        "target_text": { "type": "string", "description": "The text or button label to search for" },
        "max_swipes": { "type": "integer", "description": "Maximum number of swipes to attempt (default 5)" },
        "auto_tap": { "type": "boolean", "description": "Whether to automatically tap the element once found (default true)" }
      },
      "required": ["target_text"]
    }
    """.trimIndent()

    override suspend fun execute(
        arguments: String,
        phoneController: PhoneController,
        context: AgentContext,
    ): String {
        val root = json.parseToJsonElement(arguments).jsonObject
        val targetText = root["target_text"]?.jsonPrimitive?.contentOrNull ?: return "Error: target_text required"
        val maxSwipes = root["max_swipes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5
        val autoTap = root["auto_tap"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        var currentAttempts = 0
        while (currentAttempts <= maxSwipes) {
            val state = phoneController.getScreenState()
            val matched = state.elements.firstOrNull { elem ->
                elem.text.contains(targetText, ignoreCase = true) ||
                    elem.contentDescription.contains(targetText, ignoreCase = true)
            }

            if (matched != null) {
                val cx = matched.bounds.centerX.toFloat()
                val cy = matched.bounds.centerY.toFloat()
                if (autoTap) {
                    phoneController.tapAtPixel(cx, cy)
                    delay(500)
                    return """{"status":"success","action":"found_and_tapped","text":"$targetText","bounds":[${matched.bounds.left},${matched.bounds.top},${matched.bounds.right},${matched.bounds.bottom}]}"""
                } else {
                    return """{"status":"success","action":"found","text":"$targetText","bounds":[${matched.bounds.left},${matched.bounds.top},${matched.bounds.right},${matched.bounds.bottom}]}"""
                }
            }

            if (currentAttempts < maxSwipes) {
                // 向下滑动：从屏幕下方向上方拉动
                phoneController.swipe(500, 750, 500, 300, 400)
                delay(700) // 等待滑动惯性停止
            }
            currentAttempts++
        }

        return """{"status":"not_found","message":"Could not find element containing '$targetText' after $maxSwipes swipes"}"""
    }
}

