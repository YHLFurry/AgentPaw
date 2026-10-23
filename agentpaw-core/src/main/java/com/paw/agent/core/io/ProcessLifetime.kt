package com.paw.agent.core.io

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Cancellation must destroy the process before waiting for a blocking pipe read to finish. */
object ProcessLifetime {
    suspend fun <T> read(process: Process, block: () -> T): T = coroutineScope {
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { process.destroyForcibly() }
        }
        try {
            runInterruptible(Dispatchers.IO) { block() }
        } finally {
            cancellation.cancel()
            process.destroyForcibly()
        }
    }
}
