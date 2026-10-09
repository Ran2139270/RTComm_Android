package com.rtcomm.app.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.rtcomm.app.R

/**
 * 运行时手动初始化 FirebaseApp（不使用 google-services 插件）。
 *
 * 设计：Firebase 配置以字符串资源提供（见 res/values/strings.xml 的 fcm_* 项，
 * 默认全为空）。四项齐全时才初始化并置 [isEnabled]=true；否则整套推送降级为
 * no-op，App 仍可正常编译运行（缺配置的自建/开源部署完全不受影响）。
 *
 * 与服务端 pushService 对称：服务端缺服务账号时同样整体 no-op。
 */
object FirebaseInit {
    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Application 上下文，供无 Context 的推送路径记录诊断。 */
    @Volatile
    internal var appContext: Context? = null
        private set

    /** 在 Application.onCreate 调用一次。幂等：重复调用不会重复初始化。 */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (isEnabled) return
        val projectId = appCtx.getString(R.string.fcm_project_id).trim()
        val appId = appCtx.getString(R.string.fcm_app_id).trim()
        val apiKey = appCtx.getString(R.string.fcm_api_key).trim()
        val senderId = appCtx.getString(R.string.fcm_sender_id).trim()
        if (projectId.isEmpty() || appId.isEmpty() || apiKey.isEmpty() || senderId.isEmpty()) {
            Log.i("FirebaseInit", "FCM 配置缺失，推送已禁用（WebSocket + 前台服务仍照常工作）")
            return
        }
        try {
            if (FirebaseApp.getApps(appCtx).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(projectId)
                    .setApplicationId(appId)
                    .setApiKey(apiKey)
                    .setGcmSenderId(senderId)
                    .build()
                FirebaseApp.initializeApp(appCtx, options)
            }
            isEnabled = true
            Log.i("FirebaseInit", "FCM 已启用（project=$projectId）")
        } catch (e: Exception) {
            Log.w("FirebaseInit", "Firebase 初始化失败，推送已禁用", e)
            isEnabled = false
        }
    }
}
