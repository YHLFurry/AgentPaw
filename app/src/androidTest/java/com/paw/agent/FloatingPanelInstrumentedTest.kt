package com.paw.agent

import android.content.res.Configuration
import android.view.View
import android.widget.Button
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.paw.agent.ui.floating.AgentExecutionController.ExecutionState
import com.paw.agent.ui.floating.AgentFloatingPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FloatingPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapsingKeepsTheStopActionAvailableAndCanExpandAgain() {
        lateinit var panel: AgentFloatingPanel
        var actions = 0
        compose.setContent {
            Box(Modifier.width(296.dp)) {
                AndroidView(factory = { context ->
                    AgentFloatingPanel(context, onAction = { actions++ }, onOpenChat = {}, onSizeChanged = {}).also {
                        panel = it
                        it.render(ExecutionState(isRunning = true, currentStep = 3, maxSteps = 15, currentAction = "Inspecting the screen"))
                    }
                })
            }
        }
        onView(withId(R.id.floating_collapse)).perform(click())
        compose.waitForIdle()
        onView(withId(R.id.floating_action)).check(matches(isDisplayed())).perform(click())
        compose.runOnIdle {
            assertFalse(panel.isExpanded)
            assertEquals(1, actions)
            assertEquals(View.GONE, panel.findViewById<View>(R.id.floating_detail).visibility)
        }
        onView(withId(R.id.floating_toggle)).perform(click())
        compose.runOnIdle { assertTrue(panel.isExpanded) }
        onView(withId(R.id.floating_detail)).check(matches(isDisplayed()))
    }

    @Test fun pauseAndFinishUpdateActionLabelsAtLargeFontSize() {
        lateinit var panel: AgentFloatingPanel
        var actions = 0
        compose.setContent {
            Box(Modifier.width(256.dp)) {
                AndroidView(factory = { context ->
                    val largeTextContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                        fontScale = 2f
                    })
                    AgentFloatingPanel(largeTextContext, onAction = { actions++ }, onOpenChat = {}, onSizeChanged = {}).also {
                        panel = it
                        it.render(ExecutionState(isPaused = true, currentStep = 4, maxSteps = 0, currentAction = "Waiting for manual input"))
                    }
                })
            }
        }
        onView(withId(R.id.floating_action)).check(matches(withText(R.string.floating_resume))).perform(click())
        compose.runOnIdle {
            assertEquals(1, actions)
            val action = panel.findViewById<Button>(R.id.floating_action)
            assertTrue(action.height >= 48 * panel.resources.displayMetrics.density)
            assertTrue(action.right <= panel.width)
            panel.setExpanded(false)
            panel.render(ExecutionState(currentStep = 4, currentAction = "Task completed"))
        }
        onView(withId(R.id.floating_action)).check(matches(withText(R.string.floating_view))).check(matches(isDisplayed()))
    }
}
