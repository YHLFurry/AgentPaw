package com.paw.agent.runner

import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCheckpointStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `queued save and delete leave no stale checkpoint after restart`() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        val breakpoint = TaskBreakpoint(originalGoal = "old goal", stoppedAtStep = 3)
        repeat(25) {
            store.markRunning("old goal")
            store.saveBreakpoint(breakpoint)
            store.clearRunning()
            store.saveBreakpoint(null)
        }
        runCurrent()

        assertFalse(File(directory, "active_breakpoint.json").exists())
        assertFalse(File(directory, "running_task.json").exists())
        val restarted = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        runCurrent()
        assertNull(restarted.recover())
    }

    @Test
    fun `latest running goal survives queued start stop and restart`() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        store.markRunning("old goal")
        store.clearRunning()
        store.markRunning("new goal")
        runCurrent()

        val restarted = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        runCurrent()
        assertEquals("new goal", restarted.recover()?.originalGoal)
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun `damaged breakpoint falls back to running task marker`() = runTest {
        val directory = temporaryFolder.newFolder()
        File(directory, "active_breakpoint.json").writeText("{broken")
        File(directory, "running_task.json").writeText("interrupted goal")
        val store = TaskCheckpointStore(directory, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals("interrupted goal", store.recover()?.originalGoal)
    }

    @Test
    fun `valid breakpoint retains completed state when a running marker also exists`() = runTest {
        val directory = temporaryFolder.newFolder()
        val breakpoint = TaskBreakpoint(originalGoal = "paused goal", stoppedAtStep = 7, timestamp = 123L)
        File(directory, "active_breakpoint.json").writeText(Json.encodeToString(breakpoint))
        File(directory, "running_task.json").writeText("older goal")
        val store = TaskCheckpointStore(directory, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(breakpoint, store.recover())
    }

    @Test
    fun `saved checkpoint preserves timestamps rather than regenerating defaults`() = runTest {
        val directory = temporaryFolder.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val breakpoint = TaskBreakpoint(originalGoal = "paused goal", stoppedAtStep = 2)
        val store = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        store.saveBreakpoint(breakpoint)
        runCurrent()
        assertTrue(File(directory, "active_breakpoint.json").readText().contains("\"timestamp\""))

        val restarted = TaskCheckpointStore(directory, backgroundScope, dispatcher)
        runCurrent()
        assertEquals(breakpoint, restarted.recover())
    }

    @Test
    fun `failed write does not prevent later queued recovery operations`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = TaskCheckpointStore(directory, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(directory.delete())
        directory.writeText("block directory creation")
        store.markRunning("cannot save")
        runCurrent()

        assertTrue(directory.delete())
        assertTrue(directory.mkdirs())
        store.markRunning("retry goal")
        runCurrent()
        assertEquals("retry goal", File(directory, "running_task.json").readText())
    }
}
