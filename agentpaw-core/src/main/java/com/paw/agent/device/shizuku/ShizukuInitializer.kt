package com.paw.agent.device.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 服务状态。
 */
enum class ShizukuStatus {
    /** binder 尚未收到：Shizuku 服务未运行（或宿主未声明 ShizukuProvider） */
    NOT_RUNNING,

    /** binder 已收到，但用户尚未授予 API 权限 */
    RUNNING_NO_PERMISSION,

    /** binder 已收到且已授权，可正常执行 Shizuku 命令 */
    GRANTED,
}

/**
 * Shizuku 全局初始化与授权状态管理。
 *
 * 修复要点：
 * 1. 必须通过 [initialize] 注册 sticky binder 监听 —— 这是 Shizuku binder 到达的唯一感知途径；
 *    未初始化时 [Shizuku.pingBinder] 在部分场景下不可靠。
 * 2. 授权结果通过 [Shizuku.addRequestPermissionResultListener] 回调，之前完全没人接这个回调，
 *    导致授权成功后状态不刷新。
 * 3. [requestAuthorization] 不再静默吞异常：binder 未就绪时明确返回 false，
 *    由调用方（如设置页授权按钮）决定兜底动作（例如引导用户打开 Shizuku 应用）。
 *
 * 幂等：多次调用 [initialize] 只注册一次监听。
 */
object ShizukuInitializer {

    const val DEFAULT_REQUEST_CODE = 1001

    private val _status = MutableStateFlow(ShizukuStatus.NOT_RUNNING)

    /** 当前 Shizuku 状态，初始化后随 binder 到达 / 授权结果实时更新 */
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var initialized = false

    /**
     * 初始化监听（幂等）。建议在 Application.onCreate 调用；
     * binder 已就绪时 sticky 监听会立即回调并刷新状态。
     */
    @Synchronized
    fun initialize() {
        if (initialized) return
        initialized = true

        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                refreshStatus()
            }
        }
        runCatching {
            Shizuku.addBinderDeadListener {
                _status.value = ShizukuStatus.NOT_RUNNING
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == DEFAULT_REQUEST_CODE) {
                    refreshStatus(grantResult)
                }
            }
        }
        refreshStatus()
    }

    /** binder 是否已就绪 */
    fun isBinderReceived(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /** 是否已授予 Shizuku API 权限 */
    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 发起授权请求。
     *
     * @return true 表示授权对话框已成功拉起（结果经 status 流回调）；
     *         false 表示 binder 未就绪或请求失败，调用方需自行兜底。
     */
    fun requestAuthorization(requestCode: Int = DEFAULT_REQUEST_CODE): Boolean {
        initialize()
        if (!isBinderReceived()) return false
        if (isPermissionGranted()) {
            _status.value = ShizukuStatus.GRANTED
            return true
        }
        val dispatched = runCatching {
            Shizuku.requestPermission(requestCode)
            true
        }.getOrDefault(false)
        if (!dispatched) refreshStatus()
        return dispatched
    }

    /**
     * 重新同步状态。grantResult 传入时以授权回调结果为准
     * （回调期间 checkSelfPermission 可能尚未反映最新授权）。
     */
    private fun refreshStatus(grantResult: Int? = null) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED || isPermissionGranted()
        _status.value = when {
            granted -> ShizukuStatus.GRANTED
            isBinderReceived() -> ShizukuStatus.RUNNING_NO_PERMISSION
            else -> ShizukuStatus.NOT_RUNNING
        }
    }
}
