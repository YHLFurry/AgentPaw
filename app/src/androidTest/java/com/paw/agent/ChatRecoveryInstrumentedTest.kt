package com.paw.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.chat.ChatScreen
import com.paw.agent.ui.theme.AgentPawAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ChatRecoveryInstrumentedTest(private val theme: UiThemeMode) {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun text(id: Int) = context.getString(id)

    @Test fun newChatProtectsUnsentDraftUntilConfirmed() {
        var newChats = 0
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                ChatScreen(
                    messages = emptyList(), draft = "unfinished task", isGenerating = false,
                    configReady = true, modelLabel = "test", onDraftChange = {}, onSendOrStop = {},
                    onOpenSettings = {}, onOpenHistory = {}, onOpenSkills = {},
                    onNewConversation = { newChats++ },
                )
            }
        }
        compose.onNodeWithContentDescription(text(R.string.chat_new_conversation)).performClick()
        compose.onNodeWithText(text(R.string.chat_new_confirm_title)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, newChats) }
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        compose.onNodeWithText(text(R.string.chat_new_confirm_title)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, newChats) }
        compose.onNodeWithContentDescription(text(R.string.chat_new_conversation)).performClick()
        compose.onNodeWithText(text(R.string.chat_new_conversation)).performClick()
        compose.runOnIdle { assertEquals(1, newChats) }
    }

    @Test fun saveFailureCanBeRetriedAndDuplicateMessageIdsRender() {
        var retries = 0
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                ChatScreen(
                    messages = listOf(
                        Message("duplicate", MessageRole.USER, "first message"),
                        Message("duplicate", MessageRole.USER, "second message"),
                    ),
                    draft = "", isGenerating = false, configReady = true, modelLabel = "test",
                    storageError = "save failed", onRetrySave = { retries++ },
                    onDraftChange = {}, onSendOrStop = {}, onOpenSettings = {},
                    onOpenHistory = {}, onOpenSkills = {}, onNewConversation = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.history_retry_save)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("first message").assertIsDisplayed()
        compose.onNodeWithText("second message").assertIsDisplayed()
    }

    @Test fun firstUseOpensModelConfigurationDirectly() {
        var modelOpens = 0
        var settingsOpens = 0
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                ChatScreen(
                    messages = emptyList(), draft = "", isGenerating = false, configReady = false,
                    modelLabel = "", onDraftChange = {}, onSendOrStop = {},
                    onConfigureModel = { modelOpens++ }, onOpenSettings = { settingsOpens++ },
                    onOpenHistory = {}, onOpenSkills = {}, onNewConversation = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.chat_configure_model)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, modelOpens); assertEquals(0, settingsOpens) }
    }

    @Test fun loadingHistoryDoesNotPretendToBeFirstUse() {
        compose.setContent {
            AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                ChatScreen(
                    messages = emptyList(), draft = "", isGenerating = false, configReady = false,
                    isInitialized = false, modelLabel = "", onDraftChange = {}, onSendOrStop = {},
                    onOpenSettings = {}, onOpenHistory = {}, onOpenSkills = {}, onNewConversation = {},
                )
            }
        }
        compose.onAllNodesWithText(text(R.string.chat_loading_history))[0].assertIsDisplayed()
        compose.onNodeWithText(text(R.string.chat_configure_model)).assertDoesNotExist()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = UiThemeMode.entries.map { arrayOf<Any>(it) }
    }
}
