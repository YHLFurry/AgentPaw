package com.paw.agent.chatgpt

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Cancellation owns the call until the response body has been consumed, not just its headers. */
internal suspend fun <T> Call.consumeCancellable(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@consumeCancellable
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, resource, _ -> resource.close() }
                }
            })
        }
        response.use { block(it) }
    } finally {
        cancellation.cancel()
        call.cancel()
    }
}
