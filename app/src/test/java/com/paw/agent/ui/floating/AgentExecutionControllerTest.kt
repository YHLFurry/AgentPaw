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
}
