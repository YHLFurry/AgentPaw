package com.paw.agent.ui.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionControllerTest {

    @Test
    fun `execution lifecycle triggers correct state transitions and stop callback`() {
        var stopCallbackInvoked = false
        AgentExecutionController.registerStopCallback {
            stopCallbackInvoked = true
        }

        // Start
        AgentExecutionController.markStarted(maxSteps = 20)
        var state = AgentExecutionController.state.value
        assertTrue(state.isRunning)
        assertEquals(1, state.currentStep)
        assertEquals(20, state.maxSteps)

        // Progress
        AgentExecutionController.updateProgress(step = 3, maxSteps = 20, action = "tap 500 500")
        state = AgentExecutionController.state.value
        assertTrue(state.isRunning)
        assertEquals(3, state.currentStep)
        assertEquals("tap 500 500", state.currentAction)

        // Request Stop
        AgentExecutionController.requestStop()
        state = AgentExecutionController.state.value
        assertFalse(state.isRunning)
        assertTrue(stopCallbackInvoked)
        assertEquals("已手动停止", state.currentAction)

        AgentExecutionController.unregisterStopCallback()
    }

    @Test
    fun `non-positive maxSteps safely falls back to bounded default steps`() {
        AgentExecutionController.markStarted(maxSteps = 0)
        var state = AgentExecutionController.state.value
        assertTrue(state.isRunning)
        assertEquals(AgentExecutionController.DEFAULT_MAX_STEPS, state.maxSteps)

        AgentExecutionController.updateProgress(step = 6, maxSteps = -1, action = "swipe 500 800")
        state = AgentExecutionController.state.value
        assertEquals(6, state.currentStep)
        assertEquals(AgentExecutionController.DEFAULT_MAX_STEPS, state.maxSteps)
        assertEquals("swipe 500 800", state.currentAction)

        AgentExecutionController.markCompleted()
        state = AgentExecutionController.state.value
        assertFalse(state.isRunning)
    }

    @Test
    fun `markPaused and requestResume manage breakpoint pause state properly`() {
        var resumeInvoked = false
        AgentExecutionController.registerResumeCallback {
            resumeInvoked = true
        }

        AgentExecutionController.markStarted(maxSteps = 15)
        AgentExecutionController.updateProgress(step = 4, maxSteps = 15, action = "tap 200 400")

        AgentExecutionController.markPaused(step = 4, reason = "已在第4步断点处暂停")
        var state = AgentExecutionController.state.value
        assertFalse(state.isRunning)
        assertTrue(state.isPaused)
        assertEquals(4, state.currentStep)
        assertEquals("已在第4步断点处暂停", state.currentAction)

        AgentExecutionController.requestResume()
        assertTrue(resumeInvoked)

        AgentExecutionController.unregisterResumeCallback()
    }
}
