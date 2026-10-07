package com.paw.agent.device.root

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream

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

    private fun checkSuDirectly(): Boolean {
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
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "which su")).inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            if (whichResult.isBlank() || whichResult.contains("not found")) {
                return false
            }
        }

        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * 在 root 提权环境中同步/异步执行任意 shell 命令
     */
    suspend fun executeCommand(cmd: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (error.isNotBlank() && output.isBlank()) {
                "Error: $error".trim()
            } else {
                output.trim()
            }
        }.getOrElse { "Error: ${it.message}" }
    }

    /**
     * 利用 root 权限高速截取全屏快照
     */
    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
            val bytes = readAllBytes(process.inputStream)
            process.waitFor()
            if (bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                null
            }
        }.getOrNull()
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

    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        val escaped = text.replace(" ", "%s").replace("&", "\\&")
        val result = executeCommand("input text '$escaped'")
        !result.startsWith("Error:")
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
}
