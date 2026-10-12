package com.rtcomm.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新管理器。
 *
 * 下载生命周期与对话框组合解耦：即使用户关闭“检查更新”对话框或切换到其它标签页，
 * 下载仍在应用级协程中继续；重新打开时按 [state] 恢复进度与结果。
 *
 * 另外会**复用已下载的安装包**：检查到新版本时若 updates 目录里已有同名且校验通过的
 * APK，直接进入 [State.Downloaded]，避免“明明下载过却再次要求下载”。
 */
object UpdateManager {
    sealed interface State {
        object Idle : State
        object Checking : State
        data class Available(val info: AppUpdateInfo) : State
        data class Downloading(val info: AppUpdateInfo, val progress: Float) : State
        data class Downloaded(val info: AppUpdateInfo, val apk: File) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val state = MutableStateFlow<State>(State.Idle)

    private fun updatesDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")

    /** 已下载且校验通过的安装包文件名（与 FileTransfer.downloadByUrl 保持一致）。 */
    private fun apkName(info: AppUpdateInfo): String =
        "rtcomm-${info.versionName}.apk".replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "_")

    /**
     * 查找已下载且 SHA-256 匹配的安装包；不匹配或**缺失 sha 时**一律删除并返回 null。
     *
     * 旧实现只在 sha 非空时才校验，服务端未下发 sha 时任何本地同名文件都会被当成有效更新包，
     * 等于绕过了完整性校验。没有 sha 就无法确认复用包未损坏/未被替换，宁可不复用。
     */
    private fun existingApk(context: Context, info: AppUpdateInfo): File? {
        val f = File(updatesDir(context), apkName(info))
        if (!f.exists() || f.length() <= 0) return null
        val expected = info.sha256?.trim().orEmpty()
        if (expected.isEmpty() || !FileTransfer.sha256Hex(f).equals(expected, ignoreCase = true)) {
            runCatching { f.delete() }
            return null
        }
        return f
    }

    /** 检查新版本；检查或下载进行中时不重复触发。 */
    fun check(context: Context) {
        if (job?.isActive == true) return
        state.value = State.Checking
        val appContext = context.applicationContext
        job = scope.launch {
            val installed = AppVersion.code(appContext)
            val res = runCatching { Api.appLatest() }
            state.value = res.fold(
                onSuccess = { info ->
                    val existing = existingApk(appContext, info)
                    when {
                        info.versionCode <= installed -> {
                            // 已是最新（或更高）：清掉可能残留的旧安装包，不提示。
                            existing?.let { runCatching { it.delete() } }
                            State.Idle
                        }
                        existing != null -> State.Downloaded(info, existing)
                        else -> State.Available(info)
                    }
                },
                onFailure = { State.Failed("检查失败：${Api.userMessage(it)}") },
            )
        }
    }

    /**
     * 启动检查：执行一次并同步返回**确实比本机更新**的版本信息（同时更新 [state]）。
     * 供启动提示使用，避免后续 state 变化重复触发提示。
     */
    suspend fun checkAndAwait(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        if (job?.isActive == true) return@withContext null
        state.value = State.Checking
        val appContext = context.applicationContext
        val installed = AppVersion.code(appContext)
        val res = runCatching { Api.appLatest() }
        res.fold(
            onSuccess = { info ->
                if (info.versionCode <= installed) {
                    existingApk(appContext, info)?.let { runCatching { it.delete() } }
                    state.value = State.Idle
                    null
                } else {
                    val existing = existingApk(appContext, info)
                    state.value = if (existing != null) State.Downloaded(info, existing) else State.Available(info)
                    info
                }
            },
            onFailure = {
                state.value = State.Failed("检查失败：${Api.userMessage(it)}")
                null
            },
        )
    }

    /** 下载更新包；路径优先使用服务端 apkPath，落盘前校验 SHA-256/包名/签名/版本。 */
    fun download(context: Context, info: AppUpdateInfo) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        // 服务端版本不高于本机时无需下载（防御性，避免陈旧响应触发重复下载）。
        if (info.versionCode <= AppVersion.code(appContext)) {
            state.value = State.Idle
            return
        }
        // 已存在校验通过的包时直接复用，不再联网。
        existingApk(appContext, info)?.let {
            state.value = State.Downloaded(info, it)
            return
        }
        state.value = State.Downloading(info, 0f)
        job = scope.launch {
            val res = runCatching {
                FileTransfer.downloadByUrl(
                    context = appContext,
                    url = Api.resolveUrl(info.apkPath),
                    fileName = "rtcomm-${info.versionName}.apk",
                    expectedSha256 = info.sha256,
                ) { done, total ->
                    if (total > 0) state.value = State.Downloading(info, done.toFloat() / total)
                }
            }
            state.value = res.fold(
                onSuccess = { State.Downloaded(info, it) },
                onFailure = { State.Failed("下载失败：${Api.userMessage(it)}") },
            )
        }
    }

    /** 关闭对话框时清掉终态；进行中的下载不受影响。 */
    fun clearFinished() {
        if (state.value is State.Downloaded || state.value is State.Failed) state.value = State.Idle
    }
}
