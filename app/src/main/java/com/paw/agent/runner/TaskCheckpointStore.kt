package com.paw.agent.runner

import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import com.paw.agent.core.io.BoundedIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reads once, then processes checkpoint writes/deletions in submission order. */
internal class TaskCheckpointStore(
    private val directory: File,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val breakpointFile = File(directory, "active_breakpoint.json")
    private val runningTaskFile = File(directory, "running_task.json")
    private val recovered = CompletableDeferred<TaskBreakpoint?>()
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(ioDispatcher) {
            recovered.complete(runCatching { readCheckpoint() }.getOrNull())
            for (write in writes) runCatching { write() }
        }
    }

    suspend fun recover(): TaskBreakpoint? = recovered.await()

    fun saveBreakpoint(breakpoint: TaskBreakpoint?) {
        writes.trySend {
            if (breakpoint == null) breakpointFile.delete()
            else atomicWrite(breakpointFile, json.encodeToString(breakpoint))
        }
    }

    fun markRunning(goal: String) {
        writes.trySend { atomicWrite(runningTaskFile, goal) }
    }

    fun clearRunning() {
        writes.trySend { runningTaskFile.delete() }
    }

    private fun readCheckpoint(): TaskBreakpoint? {
        // A damaged breakpoint must not hide a still-valid interrupted-task marker.
        runCatching {
            json.decodeFromString<TaskBreakpoint>(BoundedIO.readFile(breakpointFile))
        }.getOrNull()?.let { return it }
        if (!runningTaskFile.isFile) return null
        val goal = BoundedIO.readFile(runningTaskFile).trim().ifBlank { "自动化任务" }
        return TaskBreakpoint(
            originalGoal = goal,
            stoppedAtStep = 1,
            interruptedReason = "任务在执行期间应用被系统中断/进程重启，现场已保存",
        )
    }

    private fun atomicWrite(target: File, content: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create checkpoint directory" }
        val temp = File.createTempFile("checkpoint_", ".tmp", directory)
        try {
            temp.writeText(content)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
