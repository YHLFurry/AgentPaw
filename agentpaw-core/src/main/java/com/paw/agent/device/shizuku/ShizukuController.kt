package com.paw.agent.device.shizuku

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

open class ShizukuController {

    open val isAvailable: Boolean
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

    open suspend fun executeCommand(cmd: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext "Error: Shizuku is not running or not granted"
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                runInterruptible {
                    val p = createProcess(arrayOf("sh", "-c", "$cmd 2>&1"))
                        ?: return@runInterruptible "Error: Shizuku process invocation failed"
                    process = p
                    val output = p.inputStream.bufferedReader().use { it.readText() }
                    p.waitFor()
                    val trimmed = output.trim()
                    if (p.exitValue() != 0) {
                        if (trimmed.startsWith("Error:", ignoreCase = true)) {
                            trimmed
                        } else if (trimmed.isNotBlank()) {
                            "Error: $trimmed (exit code ${p.exitValue()})"
                        } else {
                            "Error: exit code ${p.exitValue()}"
                        }
                    } else {
                        if (trimmed.startsWith("Error:", ignoreCase = true) || trimmed.startsWith("error:", ignoreCase = true)) {
                            "Error: $trimmed"
                        } else {
                            trimmed
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroyForcibly()
            "Error: Shizuku command timed out after ${timeoutMs}ms"
        } catch (e: CancellationException) {
            process?.destroyForcibly()
            throw e
        } catch (e: Throwable) {
            process?.destroyForcibly()
            "Error: ${e.message}"
        }
    }

    open suspend fun captureScreen(timeoutMs: Long = 8_000L): Bitmap? = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext null
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                runInterruptible {
                    val p = createProcess(arrayOf("screencap", "-p")) ?: return@runInterruptible null
                    process = p
                    val bytes = readAllBytes(p.inputStream)
                    p.waitFor()
                    if (bytes.isNotEmpty() && p.exitValue() == 0) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        null
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroyForcibly()
            null
        } catch (e: CancellationException) {
            process?.destroyForcibly()
            throw e
        } catch (e: Throwable) {
            process?.destroyForcibly()
            null
        }
    }

    open suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input tap $x $y")
        !result.startsWith("Error:")
    }

    open suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
        !result.startsWith("Error:")
    }

    open suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val result = executeCommand("input keyevent $keyCode")
        !result.startsWith("Error:")
    }

    open suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        if (text.any { it.code > 127 }) return@withContext false
        val lines = text.split("\n")
        if (lines.any { line -> line.isNotEmpty() && !com.paw.agent.device.ShellEscape.isPrintableAscii(line) }) {
            return@withContext false
        }

        for (i in lines.indices) {
            val line = lines[i]
            if (line.isNotEmpty()) {
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

    open suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        getForegroundInfo().first
    }

    open suspend fun getForegroundInfo(): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext Pair("", "")
        val out = executeCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        // Extract package name and activity from e.g. "u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI"
        val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
        val match = regex.find(out)
        val pkg = match?.groupValues?.getOrNull(1).orEmpty()
        val activity = match?.groupValues?.getOrNull(2).orEmpty()
        Pair(pkg, activity)
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
