package com.paw.agent.chatgpt

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.paw.agent.R
import com.paw.agent.data.settings.UiThemeMode
import com.paw.agent.ui.theme.AgentPawAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatGptAccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialLight() = capture(UiThemeMode.MATERIAL, false, "material-light")
    @Test fun materialDark() = capture(UiThemeMode.MATERIAL, true, "material-dark")
    @Test fun miuixLight() = capture(UiThemeMode.MIUIX, false, "miuix-light")
    @Test fun miuixDark() = capture(UiThemeMode.MIUIX, true, "miuix-dark")

    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun busyLoginPreventsDuplicateAttemptsAndBackRemainsReachableAtLargeFont() {
        var back = 0
        compose.setContent {
            AgentPawAppTheme(UiThemeMode.MATERIAL, darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    ChatGptAccountPage(ChatGptAuthState(busy = true, status = ChatGptAuthStatus.WAITING),
                        onLogin = { error("Duplicate login") }, onSignOut = { error("Invalid logout") }, onBack = { back++ })
                }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(context.getString(R.string.chatgpt_continue)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.chatgpt_logout)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.chatgpt_back)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, back) }
    }

    private fun capture(theme: UiThemeMode, dark: Boolean, name: String) {
        lateinit var root: View
        compose.setContent {
            root = LocalView.current
            AgentPawAppTheme(theme, darkTheme = dark, dynamicColor = false) {
                ChatGptAccountPage(ChatGptAuthState(signedIn = true, accountLabel = "account@example.test",
                    status = ChatGptAuthStatus.SIGNED_IN), {}, {}, {}, Modifier.testTag("account"))
            }
        }
        compose.onNodeWithTag("account").fetchSemanticsNode()
        compose.runOnIdle {
            val output = File("build/reports/chatgpt-ui", "$name.png")
            output.parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
