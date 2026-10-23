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
open class RootController {

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * 检查当前系统是否具有 root 权限并已授予本应用。
     * 具备内存双检锁缓存与快速探测机制。
     *
     * 注意：缓存结果**不会自动过期**——Root 授权可在应用生命周期内任意变化
     * （用户在 Magisk/KernelSU 授权或撤销）。调用方应在授权可能变化的时机
     * （回到前台、开启 Root 模式、设置页手动测试）主动调用
     * [refreshAvailability] / [checkAvailability] 刷新，否则会沿用陈旧结果
     * 导致 ROOT 通道静默失效（ISSUE-202）。
     */
    open val isAvailable: Boolean
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

    /**
     * 直接探测 su 提权可用性。protected open 以便测试注入替身，
     * 验证「缓存可被主动刷新」这一真实语义（ISSUE-202）。
     */
    protected open fun checkSuDirectly(timeoutMs: Long = 5_000L): Boolean {
        // 先检查常见 su 路径或 which su
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
            runCatching {
                val whichProc = ProcessBuilder("sh", "-c", "which su").redirectErrorStream(true).start()
                if (!whichProc.waitFor(800, TimeUnit.MILLISECONDS)) whichProc.destroyForcibly()
            }
            // 注意：在 KernelSU / APatch / Magisk Zygisk 环境下，受限应用沙箱可能无法 stat 探测 su 文件，
            // 因此不在此直接 return false，而是直接尝试 ProcessBuilder("su") 进行最终判定。
        }

        return runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().use { com.paw.agent.core.io.BoundedIO.readText(it, 4096) }
            output.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * 在 root 提权环境中执行 shell 命令，具备合并流读取、超时与取消强制销毁机制
     */
    open suspend fun executeCommand(cmd: String, timeoutMs: Long = 10_000L): String = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                process = p
                com.paw.agent.core.io.ProcessLifetime.read(p) {
                    val output = p.inputStream.bufferedReader().use { com.paw.agent.core.io.BoundedIO.readText(it) }
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
     * 利用 root 权限高速截取全屏快照，先读取完整数据流再等待退出，彻底避免缓冲区死锁。
     * 内置针对部分系统 shell/PTY 自动把 \n 转换为 \r\n 破坏 PNG 数据流的脱壳修复机制。
     */
    open suspend fun captureScreen(timeoutMs: Long = 8_000L): Bitmap? = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            withTimeout(timeoutMs) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
                process = p
                com.paw.agent.core.io.ProcessLifetime.read(p) {
                    val bytes = readAllBytes(p.inputStream)
                    p.waitFor()
                    if (bytes.isNotEmpty() && p.exitValue() == 0) {
                        val directBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (directBitmap != null) {
                            directBitmap
                        } else {
                            val cleanBytes = stripCrLfIfPng(bytes)
                            if (cleanBytes != null) {
                                BitmapFactory.decodeByteArray(cleanBytes, 0, cleanBytes.size)
                            } else {
                                null
                            }
                        }
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

    @androidx.annotation.VisibleForTesting(otherwise = androidx.annotation.VisibleForTesting.PRIVATE)
    internal fun stripCrLfIfPng(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10) return null
        val mangledHeader = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(),
            0x0D.toByte(), 0x0A.toByte(),
        )
        val isMangled = bytes.take(10).toByteArray().contentEquals(mangledHeader)
        if (!isMangled) return null

        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (i < bytes.size - 1 && bytes[i] == 0x0D.toByte() && bytes[i + 1] == 0x0A.toByte()) {
                out.write(0x0A)
                i += 2
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    open suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input tap $x $y")
        !result.startsWith("Error:")
    }

    open suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
        !result.startsWith("Error:")
    }

    open suspend fun keyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("input keyevent $keyCode")
        !result.startsWith("Error:")
    }

    /**
     * 安全输入文本：处理单引号与特殊字符转义，遇非 ASCII（中文、表情）整体返回 false 触发保底方案，
     * 避免逐行部分执行后回退产生重复文本输入。
     */
    open suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
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

    open suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        getForegroundInfo().first
    }

    open suspend fun getForegroundInfo(): Pair<String, String> = withContext(Dispatchers.IO) {
        val regex = Regex("""([a-zA-Z0-9._]+)/([a-zA-Z0-9._]+)""")
        val out = executeCommand("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        val match = regex.find(out)
        var pkg = match?.groupValues?.getOrNull(1).orEmpty()
        var activity = match?.groupValues?.getOrNull(2).orEmpty()

        // Android 13/14/15 / 澎湃OS 适配：dumpsys window 可能不再包含 mCurrentFocus，改用 dumpsys activity 备选检索
        if (pkg.isBlank()) {
            val actOut = executeCommand("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity'")
            val actMatch = regex.find(actOut)
            pkg = actMatch?.groupValues?.getOrNull(1).orEmpty()
            activity = actMatch?.groupValues?.getOrNull(2).orEmpty()
        }
        Pair(pkg, activity)
    }

    private fun readAllBytes(input: InputStream): ByteArray = com.paw.agent.core.io.BoundedIO.readBytes(input)

    /**
     * 通过 ROOT 权限静默一键开启无障碍服务，自动穿透受限设置与悬浮窗拦截。
     */
    suspend fun enableAccessibilityViaRoot(packageName: String, serviceClassName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext false
        val validIdentifier = Regex("^[a-zA-Z0-9._]+$")
        if (!packageName.matches(validIdentifier) || !serviceClassName.matches(validIdentifier)) {
            return@withContext false
        }
        val comp = "$packageName/$serviceClassName"

        // 针对 Android 13+ / 澎湃OS：直接通过 ROOT 授予 ACCESS_RESTRICTED_SETTINGS 与 SYSTEM_ALERT_WINDOW，
        // 一键穿透受限设置与悬浮窗拦截，免除用户繁琐的手动解锁流程
        executeCommand("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
        executeCommand("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        executeCommand("pm grant $packageName android.permission.SYSTEM_ALERT_WINDOW")

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
