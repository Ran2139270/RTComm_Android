package com.rtcomm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson

/**
 * 登录态与本地设置持久化。
 *
 * 安全存储：JWT / 用户信息使用 EncryptedSharedPreferences（AES-256-GCM），
 * UI 偏好与服务器地址为非敏感数据，使用普通 SharedPreferences。
 */
class AuthStore(context: Context) {
    private val appCtx = context.applicationContext
    private val gson = Gson()

    /**
     * 敏感数据仅允许使用 Android Keystore 加密存储。
     *
     * 运行时已切换到自研 [SecurePrefs]（替代已废弃的 `security-crypto`）。首次升级时把旧的
     * `rtcomm_secure`（EncryptedSharedPreferences）内容一次性迁移到新格式；迁移失败则**退回旧库读取**，
     * 绝不把 JWT 降级为明文，也不因此把用户登出。旧库仅保留用于这次迁移，下个版本移除。
     */
    private val secure: SharedPreferences? by lazy { openSecure() }

    /** 旧版 security-crypto 存储（仅迁移用，下个版本删除）。 */
    private fun legacySecure(): SharedPreferences? = try {
        // stability: security-crypto 1.0.0 使用 MasterKeys（别名 _androidx_security_master_key_）。
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "rtcomm_secure",
            masterKeyAlias,
            appCtx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("AuthStore", "旧 EncryptedSharedPreferences 不可用", e)
        null
    }

    private fun openSecure(): SharedPreferences? {
        val newStore = SecurePrefs.open(appCtx) ?: return legacySecure()
        if (sp.getBoolean(KEY_SECURE_MIGRATED, false)) return newStore
        // 旧库文件不存在 => 全新安装（或已迁移过）：直接用新库，避免平白创建旧 master key。
        val legacyFile = java.io.File(appCtx.applicationInfo.dataDir, "shared_prefs/rtcomm_secure.xml")
        if (!legacyFile.exists()) {
            sp.edit().putBoolean(KEY_SECURE_MIGRATED, true).apply()
            return newStore
        }
        val legacy = legacySecure() ?: return null
        val data = runCatching { legacy.all }.getOrNull()
        if (data.isNullOrEmpty()) {
            sp.edit().putBoolean(KEY_SECURE_MIGRATED, true).apply()
            return newStore
        }
        val ok = runCatching {
            val e = newStore.edit()
            data.forEach { (k, v) -> if (v is String) e.putString(k, v) }
            e.commit()
        }.getOrDefault(false)
        if (!ok) {
            // 迁移失败：退回旧库，保证登录态不丢（下版本移除旧库前会再次尝试迁移）。
            Log.w("AuthStore", "安全存储迁移失败，暂回退旧库")
            return legacy
        }
        sp.edit().putBoolean(KEY_SECURE_MIGRATED, true).apply()
        // 迁移成功后清掉旧库，避免两份敏感数据并存。
        runCatching { legacy.edit().clear().apply() }
        return newStore
    }

    private fun secureOrThrow(): SharedPreferences =
        requireNotNull(secure) { "安全存储不可用，请重启应用后重新登录" }

    /** 非敏感设置。 */
    private val sp: SharedPreferences = appCtx.getSharedPreferences("rtcomm_prefs", Context.MODE_PRIVATE)

    fun save(token: String, user: PublicUser?) {
        secureOrThrow().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER, gson.toJson(user))
            .apply()
        Api.token = token
        Api.bumpAuthGeneration()
        // 登录成功后把该账号加入「可切换账号」列表（多账号快速切换）。
        rememberAccount(serverUrl, token, user)
    }

    fun saveUser(user: PublicUser?) {
        secureOrThrow().edit().putString(KEY_USER, gson.toJson(user)).apply()
        // 同步更新可切换账号列表里的资料（昵称/头像等）。
        val key = currentAccountKey()
        val list = savedAccounts().map { if (it.key == key) it.copy(user = user) else it }
        writeAccounts(list)
    }

    fun token(): String = Api.token.ifEmpty { secure?.getString(KEY_TOKEN, "") ?: "" }

    fun user(): PublicUser? = secure?.getString(KEY_USER, null)?.let {
        runCatching { gson.fromJson(it, PublicUser::class.java) }.getOrNull()
    }

    fun clearSession() {
        secure?.edit()?.remove(KEY_TOKEN)?.remove(KEY_USER)?.apply()
        Api.token = ""
        // 使所有在途请求的迟到 401 失效，避免清掉下一次登录的新会话。
        Api.bumpAuthGeneration()
    }

    /** 登出/切服前快照：注销请求必须发往旧服务器并携带旧令牌。 */
    data class SessionSnapshot(val baseUrl: String, val token: String)

    fun snapshot(): SessionSnapshot = SessionSnapshot(Api.baseUrl, Api.token)

    fun hasSession(): Boolean = token().isNotEmpty()

    // ---- 多账号切换 ----
    /** 一个已保存、可快速切换的账号（令牌加密存储）。 */
    data class SavedAccount(val serverUrl: String, val token: String, val user: PublicUser?) {
        val key: String get() = serverUrl.trimEnd('/') + "|" + (user?.id ?: "")
    }

    fun savedAccounts(): List<SavedAccount> =
        secure?.getString(KEY_ACCOUNTS, null)?.let {
            runCatching { gson.fromJson(it, Array<SavedAccount>::class.java)?.toList() }.getOrNull()
        } ?: emptyList()

    private fun writeAccounts(list: List<SavedAccount>) {
        secureOrThrow().edit().putString(KEY_ACCOUNTS, gson.toJson(list)).apply()
    }

    /** 记录/更新一个可切换账号；最多保留 5 个（最近登录的排最后）。 */
    fun rememberAccount(serverUrl: String, token: String, user: PublicUser?) {
        if (token.isEmpty()) return
        val acc = SavedAccount(serverUrl.trimEnd('/'), token, user)
        val list = (savedAccounts().filterNot { it.key == acc.key } + acc).takeLast(5)
        writeAccounts(list)
    }

    fun forgetAccount(key: String) {
        writeAccounts(savedAccounts().filterNot { it.key == key })
    }

    fun currentAccountKey(): String = serverUrl.trimEnd('/') + "|" + (user()?.id ?: "")

    /** 切换到已保存账号：写入服务器地址/令牌/用户并设为当前。 */
    fun restoreAccount(acc: SavedAccount): PublicUser? {
        serverUrl = acc.serverUrl
        Api.token = acc.token
        Api.bumpAuthGeneration()
        secureOrThrow().edit()
            .putString(KEY_TOKEN, acc.token)
            .putString(KEY_USER, gson.toJson(acc.user))
            .apply()
        return acc.user
    }

    // ---- 服务器地址 ----
    var serverUrl: String
        get() = (sp.getString(KEY_SERVER, Api.DEFAULT_BASE_URL) ?: Api.DEFAULT_BASE_URL)
        set(value) {
            val v = value.trim().ifEmpty { Api.DEFAULT_BASE_URL }
            sp.edit().putString(KEY_SERVER, v).apply()
            Api.baseUrl = v
        }

    /** 启动时把持久化配置装载到运行时。 */
    fun applyToApi() {
        Api.baseUrl = serverUrl
        Api.token = token()
    }

    // ---- 本地 UI 偏好 ----
    /** system / light / dark */
    var themeMode: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    var notificationsEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    var autoConnectWs: Boolean
        get() = sp.getBoolean(KEY_AUTOCONN, true)
        set(v) = sp.edit().putBoolean(KEY_AUTOCONN, v).apply()

    var autoDownload: Boolean
        get() = sp.getBoolean(KEY_AUTODL, false)
        set(v) = sp.edit().putBoolean(KEY_AUTODL, v).apply()

    /** 通知正文：full=显示内容，generic=仅提示有新消息。默认 generic，避免锁屏泄露内容。 */
    var notificationPreview: String
        get() = sp.getString(KEY_NOTIFY_PREVIEW, "generic") ?: "generic"
        set(v) = sp.edit().putString(KEY_NOTIFY_PREVIEW, v).apply()

    /**
     * 单会话免打扰：被静音的会话 id 集合。
     * 只影响本机通知，不影响消息接收与未读计数（本地偏好，故不入服务端）。
     */
    var mutedConversations: Set<String>
        get() = sp.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_MUTED, v).apply()

    fun isMuted(conversationId: String): Boolean = mutedConversations.contains(conversationId)

    fun setMuted(conversationId: String, muted: Boolean) {
        val cur = mutedConversations.toMutableSet()
        if (muted) cur.add(conversationId) else cur.remove(conversationId)
        mutedConversations = cur
    }

    /** 置顶会话 id 集合（本地偏好，置顶的会话排在列表最前）。 */
    var pinnedConversations: Set<String>
        get() = sp.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_PINNED, v).apply()

    fun isPinned(conversationId: String): Boolean = pinnedConversations.contains(conversationId)

    fun setPinned(conversationId: String, pinned: Boolean) {
        val cur = pinnedConversations.toMutableSet()
        if (pinned) cur.add(conversationId) else cur.remove(conversationId)
        pinnedConversations = cur
    }

    /** Material You 动态取色（Android 12+ 从壁纸取色）。 */
    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply()

    /** AMOLED 纯黑表面（仅深色主题生效）。 */
    var amoled: Boolean
        get() = sp.getBoolean(KEY_AMOLED, false)
        set(v) = sp.edit().putBoolean(KEY_AMOLED, v).apply()

    // ---- 个性化（外观 / 聊天 / 通知） ----

    /** 主题主色预设：blue/violet/green/rose/amber/teal/custom。 */
    var themePreset: String
        get() = sp.getString(KEY_THEME_PRESET, "blue") ?: "blue"
        set(v) = sp.edit().putString(KEY_THEME_PRESET, v).apply()

    /** 自定义主色（ARGB）；-1 表示未设置。 */
    var customPrimary: Int
        get() = sp.getInt(KEY_CUSTOM_PRIMARY, -1)
        set(v) = sp.edit().putInt(KEY_CUSTOM_PRIMARY, v).apply()

    /** 深色时段（themeMode=time 时生效）：默认 19:00–07:00。 */
    var darkStart: Int
        get() = sp.getInt(KEY_DARK_START, 19)
        set(v) = sp.edit().putInt(KEY_DARK_START, v).apply()

    var darkEnd: Int
        get() = sp.getInt(KEY_DARK_END, 7)
        set(v) = sp.edit().putInt(KEY_DARK_END, v).apply()

    /** 字号：small/default/large 或连续数值字符串（如 "1.08"）。 */
    var fontScale: String
        get() = sp.getString(KEY_FONT_SCALE, "default") ?: "default"
        set(v) = sp.edit().putString(KEY_FONT_SCALE, v).apply()

    /** 字体族：system/serif/mono。 */
    var fontFamily: String
        get() = sp.getString(KEY_FONT_FAMILY, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_FONT_FAMILY, v).apply()

    /** AI：是否请求模型深度思考（推理）。 */
    var aiThinking: Boolean
        get() = sp.getBoolean(KEY_AI_THINKING, false)
        set(v) = sp.edit().putBoolean(KEY_AI_THINKING, v).apply()

    /** AI：是否展示模型的思考内容。 */
    var aiShowThinking: Boolean
        get() = sp.getBoolean(KEY_AI_SHOW_THINKING, true)
        set(v) = sp.edit().putBoolean(KEY_AI_SHOW_THINKING, v).apply()

    /** 每个机器人的「深度思考」开关（本地，缺省回退全局设置）。 */
    fun aiThinkingFor(botId: String): Boolean = sp.getBoolean("ai_thinking_$botId", aiThinking)
    fun setAiThinkingFor(botId: String, v: Boolean) = sp.edit().putBoolean("ai_thinking_$botId", v).apply()

    /** 每个机器人的「展示思考内容」开关（本地，缺省回退全局设置）。 */
    fun aiShowThinkingFor(botId: String): Boolean = sp.getBoolean("ai_show_thinking_$botId", aiShowThinking)
    fun setAiShowThinkingFor(botId: String, v: Boolean) = sp.edit().putBoolean("ai_show_thinking_$botId", v).apply()

    /** 聊天气泡圆角：small/medium/large（默认 medium）。 */
    var bubbleCorner: String
        get() = sp.getString(KEY_BUBBLE_CORNER, "medium") ?: "medium"
        set(v) = sp.edit().putString(KEY_BUBBLE_CORNER, v).apply()

    /** 列表头像使用圆角方形（false=圆形）。 */
    var roundedAvatars: Boolean
        get() = sp.getBoolean(KEY_ROUNDED_AVATARS, false)
        set(v) = sp.edit().putBoolean(KEY_ROUNDED_AVATARS, v).apply()

    /** 消息密度：comfortable/compact。 */
    var chatDensity: String
        get() = sp.getString(KEY_CHAT_DENSITY, "comfortable") ?: "comfortable"
        set(v) = sp.edit().putString(KEY_CHAT_DENSITY, v).apply()

    /** 聊天背景：default/warm/cool/mint/dusk/grid。 */
    var chatWallpaper: String
        get() = sp.getString(KEY_CHAT_WALLPAPER, "default") ?: "default"
        set(v) = sp.edit().putString(KEY_CHAT_WALLPAPER, v).apply()

    /** 自定义聊天背景图片路径（为空则用内置样式）。 */
    var chatBackground: String?
        get() = sp.getString(KEY_CHAT_BG, null)
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove(KEY_CHAT_BG) else e.putString(KEY_CHAT_BG, v)
            e.apply()
        }

    /** 自定义主界面背景图片路径（为空则不显示图片）。 */
    var appBackground: String?
        get() = sp.getString(KEY_APP_BG, null)
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove(KEY_APP_BG) else e.putString(KEY_APP_BG, v)
            e.apply()
        }

    /** 主界面背景的暗化强度（0..1，越大越暗以提升可读性）。 */
    var bgDim: Float
        get() = sp.getFloat(KEY_BG_DIM, 0.82f)
        set(v) = sp.edit().putFloat(KEY_BG_DIM, v).apply()

    /** 主界面背景的模糊半径（dp，0=不模糊；仅 Android 12+ 生效）。 */
    var bgBlur: Int
        get() = sp.getInt(KEY_BG_BLUR, 0)
        set(v) = sp.edit().putInt(KEY_BG_BLUR, v).apply()

    /**
     * 聊天界面背景的暗化强度（0..1）。
     * 与主界面独立：默认 0.1，与旧版固定遮罩一致；老用户未设置时沿用旧值。
     */
    var chatBgDim: Float
        get() = sp.getFloat(KEY_CHAT_BG_DIM, 0.10f)
        set(v) = sp.edit().putFloat(KEY_CHAT_BG_DIM, v).apply()

    /** 聊天界面背景的模糊半径（dp，0=不模糊；仅 Android 12+ 生效）。 */
    var chatBgBlur: Int
        get() = sp.getInt(KEY_CHAT_BG_BLUR, 0)
        set(v) = sp.edit().putInt(KEY_CHAT_BG_BLUR, v).apply()

    /** 每会话聊天背景覆盖：convId -> wallpaper key（缺省则跟随全局）。 */
    var conversationWallpapers: Map<String, String>
        get() = runCatching {
            gson.fromJson(sp.getString(KEY_CONV_WALLPAPERS, "{}"), Map::class.java)
                ?.entries?.associate { it.key.toString() to it.value.toString() }
        }.getOrNull() ?: emptyMap()
        set(v) = sp.edit().putString(KEY_CONV_WALLPAPERS, gson.toJson(v)).apply()

    fun wallpaperFor(conversationId: String): String? = conversationWallpapers[conversationId]

    fun setConversationWallpaper(conversationId: String, key: String?) {
        val cur = conversationWallpapers.toMutableMap()
        if (key == null) cur.remove(conversationId) else cur[conversationId] = key
        conversationWallpapers = cur
    }

    /** 动画：system/on/off。 */
    var reduceMotionPref: String
        get() = sp.getString(KEY_REDUCE_MOTION, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_REDUCE_MOTION, v).apply()

    /** 减少透明/毛玻璃：true=关闭实时高斯模糊（默认 false）。 */
    var reduceTransparencyPref: Boolean
        get() = sp.getBoolean(KEY_REDUCE_TRANSPARENCY, false)
        set(v) = sp.edit().putBoolean(KEY_REDUCE_TRANSPARENCY, v).apply()

    /** 时间制式：system/h24/h12。 */
    var timeFormatPref: String
        get() = sp.getString(KEY_TIME_FORMAT, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_TIME_FORMAT, v).apply()

    /** 免打扰时段（本地时间小时，[start, end) 跨天有效）。 */
    var dndEnabled: Boolean
        get() = sp.getBoolean(KEY_DND, false)
        set(v) = sp.edit().putBoolean(KEY_DND, v).apply()

    var dndStart: Int
        get() = sp.getInt(KEY_DND_START, 22)
        set(v) = sp.edit().putInt(KEY_DND_START, v).apply()

    var dndEnd: Int
        get() = sp.getInt(KEY_DND_END, 7)
        set(v) = sp.edit().putInt(KEY_DND_END, v).apply()

    /** 通知声音 / 震动。 */
    var notifSound: Boolean
        get() = sp.getBoolean(KEY_NOTIF_SOUND, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIF_SOUND, v).apply()

    var notifVibrate: Boolean
        get() = sp.getBoolean(KEY_NOTIF_VIBRATE, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIF_VIBRATE, v).apply()

    /**
     * 启动更新提示的跳过阈值：可用版本 versionCode <= 该值时不再自动提示。
     * 点「跳过此版本」时设为 `versionCode + 2`，即最多跳过当前及之后 2 个版本。
     */
    var updateSkipUntil: Int
        get() = sp.getInt(KEY_UPDATE_SKIP, 0)
        set(v) = sp.edit().putInt(KEY_UPDATE_SKIP, v).apply()

    /** 快捷回复短语。 */
    var quickReplies: List<String>
        get() = runCatching {
            gson.fromJson(sp.getString(KEY_QUICK_REPLIES, "[]"), Array<String>::class.java)?.toList()
        }.getOrNull() ?: emptyList()
        @Suppress("unused")
        set(v) = sp.edit().putString(KEY_QUICK_REPLIES, gson.toJson(v)).apply()

    /** 自定义表情（本地图片/GIF 的绝对路径列表）。 */
    var customEmojis: List<String>
        get() = runCatching {
            gson.fromJson(sp.getString(KEY_CUSTOM_EMOJIS, "[]"), Array<String>::class.java)?.toList()
        }.getOrNull() ?: emptyList()
        set(v) = sp.edit().putString(KEY_CUSTOM_EMOJIS, gson.toJson(v)).apply()

    companion object {
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user"
        private const val KEY_ACCOUNTS = "saved_accounts"
        /** 标记：已从旧 security-crypto 存储迁移到自研 SecurePrefs（F-32）。 */
        private const val KEY_SECURE_MIGRATED = "secure_store_migrated_v2"
        private const val KEY_SERVER = "server_url"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_NOTIFY = "notifications"
        private const val KEY_AUTOCONN = "auto_connect_ws"
        private const val KEY_AUTODL = "auto_download"
        private const val KEY_NOTIFY_PREVIEW = "notification_preview"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_AMOLED = "amoled_black"
        private const val KEY_MUTED = "muted_conversations"
        private const val KEY_PINNED = "pinned_conversations"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_CUSTOM_PRIMARY = "custom_primary"
        private const val KEY_DARK_START = "dark_start"
        private const val KEY_DARK_END = "dark_end"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_CHAT_DENSITY = "chat_density"
        private const val KEY_CHAT_WALLPAPER = "chat_wallpaper"
        private const val KEY_CHAT_BG = "chat_background"
        private const val KEY_APP_BG = "app_background"
        private const val KEY_BG_DIM = "bg_dim"
        private const val KEY_BG_BLUR = "bg_blur"
        private const val KEY_CHAT_BG_DIM = "chat_bg_dim"
        private const val KEY_CHAT_BG_BLUR = "chat_bg_blur"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_AI_THINKING = "ai_thinking"
        private const val KEY_AI_SHOW_THINKING = "ai_show_thinking"
        private const val KEY_BUBBLE_CORNER = "bubble_corner"
        private const val KEY_ROUNDED_AVATARS = "rounded_avatars"
        private const val KEY_CONV_WALLPAPERS = "conversation_wallpapers"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_REDUCE_TRANSPARENCY = "reduce_transparency"
        private const val KEY_TIME_FORMAT = "time_format"
        private const val KEY_DND = "dnd_enabled"
        private const val KEY_DND_START = "dnd_start"
        private const val KEY_DND_END = "dnd_end"
        private const val KEY_NOTIF_SOUND = "notif_sound"
        private const val KEY_NOTIF_VIBRATE = "notif_vibrate"
        private const val KEY_QUICK_REPLIES = "quick_replies"
        private const val KEY_CUSTOM_EMOJIS = "custom_emojis"
        private const val KEY_UPDATE_SKIP = "update_skip_until"
    }
}
