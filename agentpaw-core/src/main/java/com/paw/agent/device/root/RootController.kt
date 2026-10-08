package com.paw.agent.device.root

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
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
     * 具备内存双检锁缓存与快速探测机制。
     */
    val isAvailable: Boolean
        get() {
            cachedRootAvailable?.let { return it }
            synchronized(this) {
                cachedRootAvailable?.let { return it }
                val available = checkSuDirectly()
                cachedRootAvailable = available
                return available
            }
        }

    /**
     * 主动刷新/测试 Root 权限并重新探测
     */
    suspend fun refreshAvailability(): Boolean = withContext(Dispatchers.IO) {
        val ok = checkSuDirectly()
        cachedRootAvailable = ok
        ok
    }

    /**
     * 异步探测 Root 权限可用性
     */
    suspend fun checkAvailability(): Boolean = withContext(Dispatchers.IO) {
        cachedRootAvailable?.let { return@withContext it }
        val ok = checkSuDirectly()
        cachedRootAvailable = ok
        ok
    }

    private fun checkSuDirectly(timeoutMs: Long = 800L): Boolean {
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
                val whichProc = ProcessBuilder("sh", "-c", "which su").redirectErrorStream(true).start()
                val output = whichProc.inputStream.bufferedReader().use { it.readText().trim() }
                val finished = whichProc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    whichProc.destroyForcibly()
                    return false
                }
                output
            }.getOrDefault("")
            if (whichResult.isBlank() || whichResult.contains("not found")) {
                return false
            }
        }

        return runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * 在 root 提权环境中执行 shell 命令，具备合并流读取、超时与取消强制销毁机制
     */
    suspend fun executeCommand(cmd: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                runInterruptible {
                    val pb = ProcessBuilder("su", "-c", "$cmd 2>&1")
                    val p = pb.start()
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
            "Error: Command timed out after ${timeoutMs}ms"
        } catch (e: CancellationException) {
            process?.destroyForcibly()
            throw e
        } catch (e: Throwable) {
            process?.destroyForcibly()
            "Error: ${e.message}"
        }
    }

    /**
     * 利用 root 权限高速截取全屏快照，先读取完整数据流再等待退出，彻底避免缓冲区死锁
     */
    suspend fun captureScreen(timeoutMs: Long = 8_000L): Bitmap? = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                runInterruptible {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
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
     * 安全输入文本：处理单引号与特殊字符转义，遇非 ASCII（中文、表情）整体返回 false 触发保底方案，
     * 避免逐行部分执行后回退产生重复文本输入。
     */
    suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        if (hasNonAscii(text)) return@withContext false
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

    /**
     * 通过 ROOT 权限静默一键开启无障碍服务，避免用户手动翻找复杂系统设置。
     */
    suspend fun enableAccessibilityViaRoot(packageName: String, serviceClassName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val validIdentifier = Regex("^[a-zA-Z0-9._]+$")
        if (!packageName.matches(validIdentifier) || !serviceClassName.matches(validIdentifier)) {
            return@withContext false
        }
        val comp = "$packageName/$serviceClassName"
        val currentSetting = executeCommand("settings get secure enabled_accessibility_services").trim()
        val cleanSetting = currentSetting.takeIf { !it.startsWith("Error:") && it != "null" }.orEmpty()
        
        // 过滤保留已存在的有效服务组件，杜绝外部非法字符导致整个操作被误拒
        val validCompRegex = Regex("^[a-zA-Z0-9._/-]+$")
        val existingServices = cleanSetting.split(":")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.matches(validCompRegex) }
            .toMutableList()

        if (!existingServices.contains(comp)) {
            existingServices.add(comp)
        }
        val newSetting = existingServices.joinToString(":")
        val res1 = executeCommand("settings put secure enabled_accessibility_services '$newSetting'")
        val res2 = executeCommand("settings put secure accessibility_enabled 1")
        !res1.startsWith("Error:") && !res2.startsWith("Error:")
    }

    /**
     * 获取当前系统 su 二进制信息与授权类型 (Magisk / KernelSU / APatch / SuperSU 等)
     */
    suspend fun getSuBinaryInfo(): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext "未授权 / 不可用"
        val version = executeCommand("su -v").trim()
        if (version.isNotBlank() && !version.startsWith("Error:")) {
            version
        } else {
            val which = executeCommand("which su").trim()
            if (which.isNotBlank()) which else "Root 特权模式"
        }
    }

    companion object {
        fun escapeForInputText(text: String): String =
            com.paw.agent.device.ShellEscape.escapeForInputText(text)

        fun hasNonAscii(text: String): Boolean =
            text.any { it.code > 127 }
    }
}
