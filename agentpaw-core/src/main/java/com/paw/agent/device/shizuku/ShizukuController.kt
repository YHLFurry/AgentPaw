package com.paw.agent.device.shizuku

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ShizukuController {

    val isAvailable: Boolean
        get() = runCatching {
            // 确保监听已注册（幂等），避免未初始化时 binder 状态未知
            ShizukuInitializer.initialize()
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private val newProcessMethod by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private fun createProcess(cmd: Array<String>): Process? =
        runCatching {
            newProcessMethod?.invoke(null, cmd, null, null) as? Process
        }.getOrNull()

    suspend fun executeCommand(cmd: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext "Error: Shizuku is not running or not granted"
        runCatching {
            val process = createProcess(arrayOf("sh", "-c", "$cmd 2>&1"))
                ?: return@withContext "Error: Shizuku process invocation failed"
            val completed = runInterruptible {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!completed) {
                process.destroyForcibly()
                return@withContext "Error: Shizuku command timed out after ${timeoutMs}ms"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() != 0 && output.isBlank()) {
                "Error: exit code ${process.exitValue()}"
            } else {
                output.trim()
            }
        }.getOrElse { "Error: ${it.message}" }
    }

    suspend fun captureScreen(timeoutMs: Long = 8_000L): Bitmap? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        runCatching {
            val process = createProcess(arrayOf("screencap", "-p")) ?: return@withContext null
            val bytes = runInterruptible {
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return@runInterruptible null
                }
                readAllBytes(process.inputStream)
            }
            if (bytes != null && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input tap $x $y")
        !result.startsWith("Error:")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
        !result.startsWith("Error:")
    }

    suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input keyevent $keyCode")
        !result.startsWith("Error:")
    }

    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val lines = text.split("\n")
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isNotEmpty()) {
                if (!com.paw.agent.device.ShellEscape.isPrintableAscii(line)) {
                    return@withContext false
                }
                val cmd = com.paw.agent.device.ShellEscape.buildInputTextCommand(line)
                val result = executeCommand(cmd)
                if (result.startsWith("Error:")) return@withContext false
            }
            if (i < lines.lastIndex) {
                val enterRes = executeCommand("input keyevent 66") // KEYCODE_ENTER
                if (enterRes.startsWith("Error:")) return@withContext false
            }
        }
        true
    }

    suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext ""
        val out = executeCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        // Extract package name from e.g. "u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI"
        val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
        regex.find(out)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(16384)
        var n: Int
        while (input.read(data, 0, data.size).also { n = it } != -1) {
            buffer.write(data, 0, n)
        }
        return buffer.toByteArray()
    }
}
