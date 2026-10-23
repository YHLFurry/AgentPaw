package com.paw.agent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.chat.ChatScreen
import com.paw.agent.ui.theme.AgentPawAppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AttachmentComposerInstrumentedTest(private val theme: UiThemeMode) {
    @get:Rule val compose = createComposeRule()

    @Test fun attachmentControlsRemainUsableWithLargeTextAndNarrowWidth() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var sent = false
        var removed = false
        compose.setContent {
            var images by remember { mutableStateOf(listOf("file:///missing-preview.jpg")) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AgentPawAppTheme(theme, darkTheme = false, dynamicColor = false) {
                    Box(Modifier.width(320.dp)) {
                        ChatScreen(
                            messages = emptyList(), draft = "Analyze image", isGenerating = false, configReady = true,
                            modelLabel = "test", images = images,
                            onRemoveImage = { images = emptyList(); removed = true },
                            onDraftChange = {}, onSendOrStop = { sent = true }, onOpenSettings = {},
                            onOpenHistory = {}, onOpenSkills = {}, onNewConversation = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.chat_add_images)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_remove_image)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_send)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(removed); assertTrue(sent) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun themes(): List<Array<Any>> = UiThemeMode.entries.map { arrayOf<Any>(it) }
    }
}
