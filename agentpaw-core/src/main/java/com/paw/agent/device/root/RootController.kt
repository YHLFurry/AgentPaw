package com.paw.agent.device.root

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Android ROOT 权限执行控制器。
 *
 * 通过直接调用系统的 `su` 二进制文件，获得真正的超级用户权限。
 * 支持在免无障碍服务、免 Shizuku 的情况下执行最高特权的高速指令、
 * 屏幕截图以及系统级输入模拟。
 */
class RootController {

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * 检查当前系统是否具有 root 权限并已授予本应用。
     * 具备内存缓存与快速探测机制。
     */
    val isAvailable: Boolean
        get() {
            cachedRootAvailable?.let { return it }
            val available = checkSuDirectly()
            cachedRootAvailable = available
            return available
        }

    /**
     * 主动刷新/测试 Root 权限并重新探测
     */
    suspend fun refreshAvailability(): Boolean = withContext(Dispatchers.IO) {
        val ok = checkSuDirectly()
        cachedRootAvailable = ok
        ok
    }

    private fun checkSuDirectly(timeoutMs: Long = 3_000L): Boolean {
        // 先检查常见 su 路径是否存在，避免在完全未 root 的设备上启动阻塞进程
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/data/local/su",
            "/su/bin/su",
        )
        val suExists = suPaths.any { File(it).exists() }
        if (!suExists) {
            // 也可能通过 magisk su 放在环境变量 PATH 中
            val whichResult = runCatching {
                val whichProc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which su"))
                val finished = whichProc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    whichProc.destroy()
                    return false
                }
                whichProc.inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            if (whichResult.isBlank() || whichResult.contains("not found")) {
                return false
            }
        }

        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * 在 root 提权环境中执行任意 shell 命令，具备超时与协程取消响应
     */
    suspend fun executeCommand(cmd: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process = p
                val output = p.inputStream.bufferedReader().use { it.readText() }
                val error = p.errorStream.bufferedReader().use { it.readText() }
                p.waitFor()
                if (error.isNotBlank() && output.isBlank()) {
                    "Error: $error".trim()
                } else {
                    output.trim()
                }
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroy()
            "Error: Command timed out after ${timeoutMs}ms"
        } catch (e: CancellationException) {
            process?.destroy()
            throw e
        } catch (e: Throwable) {
            process?.destroy()
            "Error: ${e.message}"
        }
    }

    /**
     * 利用 root 权限高速截取全屏快照，具备超时与取消保护
     */
    suspend fun captureScreen(timeoutMs: Long = 8_000L): Bitmap? = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
                process = p
                val bytes = readAllBytes(p.inputStream)
                p.waitFor()
                if (bytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroy()
            null
        } catch (e: CancellationException) {
            process?.destroy()
            throw e
        } catch (e: Throwable) {
            process?.destroy()
            null
        }
    }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input tap $x $y")
        !result.startsWith("Error:")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
        !result.startsWith("Error:")
    }

    suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input keyevent $keyCode")
        !result.startsWith("Error:")
    }

    /**
     * 安全输入文本：处理单引号与特殊字符转义，遇非 ASCII（中文、表情）返回 false 触发保底方案
     */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        // 1. Android input text 仅支持 ASCII 字符，非 ASCII (如中文、表情) 会被静默丢弃
        // 若包含非 ASCII 字符，返回 false 交由无障碍/剪贴板软键盘保底机制输入
        if (text.any { it.code > 127 }) {
            return@withContext false
        }

        // 2. 按换行拆分处理，避免多行文本导致命令中断
        val lines = text.split("\n")
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isNotEmpty()) {
                // 安全转义：将单引号 ' 转义为 '\''，避免 shell 注入与语法崩溃；空格转为 %s
                val escaped = line.replace("'", "'\\''").replace(" ", "%s")
                val result = executeCommand("input text '$escaped'")
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
        val out = executeCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
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

    companion object {
        fun escapeForInputText(text: String): String =
            text.replace("'", "'\\''").replace(" ", "%s")

        fun hasNonAscii(text: String): Boolean =
            text.any { it.code > 127 }
    }
}
