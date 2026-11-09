package com.paw.agent.chatgpt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Rotation only replaces collectors; it does not start another OAuth attempt. */
class ChatGptLoginViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = ChatGptAuth.get(application)
    val state = auth.state
    private val browserChannel = Channel<ChatGptAuth.LoginAttempt>(Channel.BUFFERED)
    val browserRequests = browserChannel.receiveAsFlow()
    private var loginJob: Job? = null
    private var logoutJob: Job? = null

    init {
        viewModelScope.launch {
            try { auth.load() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { auth.reportFailure() }
        }
    }

    fun login() {
        if (loginJob?.isActive == true || logoutJob?.isActive == true) return
        loginJob = viewModelScope.launch {
            var attempt: ChatGptAuth.LoginAttempt? = null
            try {
                attempt = auth.prepareLogin()
                browserChannel.send(attempt)
                attempt.awaitCallback()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { auth.reportFailure(attempt) }
            finally { attempt?.cancel() }
        }
    }

    fun browserFailed(attempt: ChatGptAuth.LoginAttempt) {
        if (!attempt.isCurrent) return
        cancelLogin()
        auth.reportFailure()
    }

    fun cancelLogin() {
        auth.cancelLogin()
        loginJob?.cancel()
        while (browserChannel.tryReceive().isSuccess) { /* Discard cancelled authorization URLs. */ }
    }

    fun signOut() {
        if (logoutJob?.isActive == true) return
        cancelLogin()
        logoutJob = viewModelScope.launch {
            try { auth.signOut() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { auth.reportFailure() }
        }
    }

    override fun onCleared() {
        cancelLogin()
        browserChannel.close()
    }
}
