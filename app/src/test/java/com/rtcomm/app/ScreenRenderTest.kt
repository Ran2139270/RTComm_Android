package com.rtcomm.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.gson.Gson
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.CallLog
import com.rtcomm.app.data.Conversation
import com.rtcomm.app.data.ConversationMember
import com.rtcomm.app.data.Device
import com.rtcomm.app.data.FileMeta
import com.rtcomm.app.data.Message
import com.rtcomm.app.data.MessageFile
import com.rtcomm.app.data.PrefsCache
import com.rtcomm.app.data.PublicUser
import com.rtcomm.app.data.ReplyMessage
import com.rtcomm.app.data.WsState
import com.rtcomm.app.ui.ai.AiScreen
import com.rtcomm.app.ui.call.CallLogsScreen
import com.rtcomm.app.ui.chat.ChatScreen
import com.rtcomm.app.ui.chat.ConversationsScreen
import com.rtcomm.app.ui.device.DevicesScreen
import com.rtcomm.app.ui.files.FilesScreen
import com.rtcomm.app.ui.login.LoginFlow
import com.rtcomm.app.ui.profile.AboutScreen
import com.rtcomm.app.ui.profile.ProfileScreen
import com.rtcomm.app.ui.profile.SettingsScreen
import com.rtcomm.app.ui.theme.RtcommTheme
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 整页截图：注入假数据后渲染应用真实页面，输出到 app/build/outputs/roborazzi 下的 PNG。
 *
 * 网络指向不可达地址，页面自身的加载会失败并回落到 AppState/PrefsCache 的假数据，
 * 从而在无设备、无后端的情况下得到接近真机的静态界面。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-mdpi")
class ScreenRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppState.reset()
        // Robolectric 无 AndroidKeystore：注入普通 SharedPreferences 作为缓存后端，
        // 让页面能读到预置假数据；生产路径仍强制使用加密实现，不受影响。
        PrefsCache.testBackend = context.getSharedPreferences("rtcomm_test_cache", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
        Api.baseUrl = "https://127.0.0.1:1"
        AppState.reduceMotion.value = "on"
        AppState.reduceTransparency.value = true
        AppState.signIn(me)
        AppState.wsState.value = WsState.Connected
    }

    private fun shoot(name: String) {
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun waitText(text: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 网络指向不可达地址时，等待错误横幅出现，消除“缓存先到、错误后到”的抓拍竞态。
     * 文案已统一走 [com.rtcomm.app.data.Api.userMessage]（中文），不再出现底层英文异常。
     */
    private fun waitNetError() {
        rule.waitUntil(5_000) {
            val nodes = rule.onAllNodes(hasText("无法连接", substring = true)).fetchSemanticsNodes() +
                rule.onAllNodes(hasText("请求超时", substring = true)).fetchSemanticsNodes() +
                rule.onAllNodes(hasText("网络异常", substring = true)).fetchSemanticsNodes()
            nodes.isNotEmpty()
        }
    }

    private fun render(
        name: String,
        themeMode: String = "light",
        preset: String = "blue",
        fontScale: String = "default",
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            RtcommTheme(themeMode = themeMode, themePreset = preset, fontScale = fontScale) { content() }
        }
    }

    @Test
    fun login() {
        render("10_login") { LoginFlow(authStore = AuthStore(context), onLoggedIn = {}) }
        shoot("10_login")
    }

    @Test
    fun conversations() {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        render("x") { ConversationsScreen(onOpenChat = {}, onOpenCalls = {}) }
        waitText("设计评审")
        waitNetError()
        shoot("11_conversations")
    }

    @Test
    fun chat() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x") { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("项目进展")
        waitNetError()
        shoot("12_chat")
    }

    @Test
    fun profile() {
        render("x") { ProfileScreen(authStore = AuthStore(context), onOpenCalls = {}) }
        waitText("艾丽丝")
        shoot("13_profile")
    }

    @Test
    fun settings() {
        render("x") { SettingsScreen(authStore = AuthStore(context), onBack = {}) }
        shoot("14_settings")
    }

    @Test
    fun about() {
        render("x") { AboutScreen(authStore = AuthStore(context), onBack = {}) }
        shoot("19_about")
    }

    @Test
    fun ai() {
        PrefsCache.save(context, "bots", Gson().toJson(bots))
        render("x") { AiScreen(onOpenBot = { _, _ -> }) }
        waitText("助手小R")
        waitNetError()
        shoot("15_ai")
    }

    @Test
    fun devices() {
        PrefsCache.save(context, "devices", Gson().toJson(devices))
        render("x") { DevicesScreen(onOpenDevice = {}) }
        waitText("办公室主机")
        waitNetError()
        shoot("16_devices")
    }

    @Test
    fun files() {
        PrefsCache.save(context, "files", Gson().toJson(files))
        render("x") { FilesScreen() }
        waitText("需求文档")
        shoot("17_files")
    }

    @Test
    fun calls() {
        PrefsCache.save(context, "call_logs", Gson().toJson(calls))
        render("x") { CallLogsScreen(onBack = {}) }
        waitText("3 人通话")
        waitNetError()
        shoot("18_calls")
    }

    // ---------- 深色 / AMOLED 变体 ----------

    @Test
    fun darkConversations() {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        render("x", themeMode = "dark") { ConversationsScreen(onOpenChat = {}, onOpenCalls = {}) }
        waitText("设计评审")
        waitNetError()
        shoot("20_dark_conversations")
    }

    @Test
    fun darkChat() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x", themeMode = "dark") { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("项目进展")
        waitNetError()
        shoot("21_dark_chat")
    }

    @Test
    fun darkSettings() {
        render("x", themeMode = "dark") { SettingsScreen(authStore = AuthStore(context), onBack = {}) }
        shoot("22_dark_settings")
    }

    @Test
    fun amoledChat() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        rule.setContent { RtcommTheme(themeMode = "dark", amoled = true) { ChatScreen(conversationId = convGroup.id, onBack = {}) } }
        waitText("项目进展")
        waitNetError()
        shoot("23_amoled_chat")
    }

    // ---------- 大屏（平板）变体：验证“无宽度上限”问题 ----------

    @Test
    @Config(qualifiers = "w1280dp-h900dp-mdpi")
    fun tabletConversations() {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        render("x") { ConversationsScreen(onOpenChat = {}, onOpenCalls = {}) }
        waitText("设计评审")
        waitNetError()
        shoot("24_tablet_conversations")
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp-mdpi")
    fun tabletSettings() {
        render("x") { SettingsScreen(authStore = AuthStore(context), onBack = {}) }
        shoot("25_tablet_settings")
    }

    // ---------- 大字号（应用内 1.3×）变体 ----------

    @Test
    fun fontScaleLargeChat() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x", fontScale = "1.3") { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("项目进展")
        waitNetError()
        shoot("26_fontscale_chat")
    }

    @Test
    fun fontScaleLargeConversations() {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        render("x", fontScale = "1.3") { ConversationsScreen(onOpenChat = {}, onOpenCalls = {}) }
        waitText("设计评审")
        waitNetError()
        shoot("27_fontscale_conversations")
    }

    // ---------- 长按菜单 ----------

    @Test
    fun messageActionMenu() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x") { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("整体完成")
        rule.onNodeWithText("整体完成 80%，今天会出测试包。", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/30_message_menu.png")
    }

    @Test
    fun darkMessageActionMenu() {
        AppState.conversations.value = listOf(convGroup)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x", themeMode = "dark") { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("整体完成")
        rule.onNodeWithText("整体完成 80%，今天会出测试包。", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/31_dark_message_menu.png")
    }

    @Test
    fun conversationActionMenu() {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        render("x") { ConversationsScreen(onOpenChat = {}, onOpenCalls = {}) }
        waitText("设计评审")
        rule.onNodeWithText("设计评审").performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/32_conversation_menu.png")
    }

    // ---------- 更多交互状态 ----------

    private fun renderChat(themeMode: String = "light") {
        AppState.conversations.value = listOf(convGroup, convBob, convBot)
        AppState.messages.value = mapOf(convGroup.id to chatMessages)
        render("x", themeMode = themeMode) { ChatScreen(conversationId = convGroup.id, onBack = {}) }
        waitText("整体完成")
    }

    private fun openMessageMenu() {
        rule.onNodeWithText("整体完成 80%，今天会出测试包。", substring = true)
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitUntil(5_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 底部弹层/对话框可能落在 Dialog 或 Popup 窗口，这里两者都尝试并整体截图。 */
    private fun captureOverlay(name: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()
        }
        val dialogs = rule.onAllNodes(isDialog())
        val target = if (dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs[0] else rule.onAllNodes(isPopup())[0]
        target.captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun multiSelectState() {
        renderChat()
        openMessageMenu()
        rule.onNodeWithText("多选").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("已选", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        waitNetError()
        shoot("40_multiselect")
    }

    @Test
    fun forwardDialog() {
        renderChat()
        openMessageMenu()
        rule.onNodeWithText("转发到其他会话").performClick()
        captureOverlay("41_forward_dialog")
    }

    @Test
    fun attachSheet() {
        renderChat()
        rule.onNodeWithContentDescription("附件").performClick()
        captureOverlay("42_attach_sheet")
    }

    @Test
    fun quickRepliesSheet() {
        AppState.quickReplies.value = listOf("收到，我看一下", "稍等，马上回你", "好的，谢谢！")
        renderChat()
        rule.onNodeWithContentDescription("快捷回复").performClick()
        captureOverlay("43_quick_replies")
    }

    @Test
    fun chatInfoSheet() {
        renderChat()
        rule.onNodeWithContentDescription("聊天信息").performClick()
        captureOverlay("44_chat_info")
    }

    @Ignore("AiChatScreen 存在持续动画，Robolectric/Espresso 无法进入 idle；需真机或冻结动画后再截")
    @Test
    fun aiConfigDialog() {
        render("x") { com.rtcomm.app.ui.ai.AiChatScreen(botId = "u_bot", onBack = {}) }
        rule.onNodeWithContentDescription("机器人配置").performClick()
        captureOverlay("45_ai_config")
    }
}

// 用固定的过去日期：Format.relative() 依赖“当前时间”，用今天会导致截图随运行时刻变化（非确定）。
private const val T = "2024-01-05T"

private val me = PublicUser(id = "u_me", username = "alice", nickname = "艾丽丝", isAdmin = true, isOnline = true)
private val bob = PublicUser(id = "u_bob", username = "bob", nickname = "鲍勃", isOnline = true)
private val bot = PublicUser(id = "u_bot", username = "helper", nickname = "助手小R", accountType = "bot")

private fun member(u: PublicUser) =
    ConversationMember(id = u.id, username = u.username, nickname = u.nickname, accountType = u.accountType, isOnline = u.isOnline)

private fun msg(
    id: String,
    sender: PublicUser,
    content: String? = null,
    type: String = "text",
    minute: Int = 0,
    file: MessageFile? = null,
    replyTo: ReplyMessage? = null,
): Message = Message(
    id = id,
    conversationId = "c1",
    senderId = sender.id,
    sender = sender,
    messageType = type,
    content = content,
    fileId = file?.id,
    file = file,
    replyToMessageId = replyTo?.id,
    replyTo = replyTo,
    createdAt = "${T}10:%02d:00.000Z".format(minute),
)

private val convGroup = Conversation(
    id = "c1",
    type = "group",
    name = "设计评审",
    members = listOf(member(me), member(bob), member(bot)),
    lastMessage = msg("m0", bob, "好的，下午同步", minute = 20),
    unreadCount = 3,
)

private val convBob = Conversation(
    id = "c2",
    type = "direct",
    name = "鲍勃",
    members = listOf(member(me), member(bob)),
    lastMessage = msg("m1", bob, "接口文档我补上了", minute = 12),
    unreadCount = 1,
)

private val convBot = Conversation(
    id = "c3",
    type = "direct",
    name = "助手小R",
    members = listOf(member(me), member(bot)),
    lastMessage = msg("m2", bot, "已为你生成周报摘要", minute = 5),
    unreadCount = 0,
)

private val chatMessages = listOf(
    msg("m1", bob, "项目进展怎么样了？", minute = 0),
    msg(
        "m2", me, "整体完成 80%，今天会出测试包。", minute = 2,
        replyTo = ReplyMessage(id = "m1", senderId = bob.id, sender = bob, content = "项目进展怎么样了？"),
    ),
    msg(
        "m3", bot,
        "**接口清单**\n- `POST /api/auth/login`\n- `GET /api/conversations`\n- `PUT /api/messages/:id/read`",
        type = "markdown", minute = 3,
    ),
    msg(
        "m4", bob, type = "file", minute = 4,
        file = MessageFile(id = "f1", fileName = "需求文档-v3.pdf", fileSize = 2_400_000, mimeType = "application/pdf"),
    ),
)

private val bots = listOf(
    com.rtcomm.app.data.BotSummary(
        id = "u_bot", username = "helper", nickname = "助手小R", personality = "friendly",
        modelProvider = "openai", modelName = "gpt-4o-mini", visibility = "public",
    ),
    com.rtcomm.app.data.BotSummary(
        id = "u_bot2", username = "coder", nickname = "代码助手", personality = "professional",
        modelProvider = "anthropic", modelName = "claude-3-5-sonnet", visibility = "private",
    ),
)

private val devices = listOf(
    Device(id = "d1", deviceName = "办公室主机", agentVersion = "1.4.2", isOnline = true, lastSeen = "${T}11:00:00.000Z"),
    Device(id = "d2", deviceName = "树莓派-客厅", agentVersion = "1.4.0", isOnline = false, lastSeen = "${T}08:30:00.000Z"),
)

private val files = listOf(
    FileMeta(id = "f1", fileName = "需求文档-v3.pdf", fileSize = 2_400_000, mimeType = "application/pdf", createdAt = "${T}10:04:00.000Z"),
    FileMeta(id = "f2", fileName = "首页截图.png", fileSize = 512_000, mimeType = "image/png", createdAt = "${T}09:40:00.000Z"),
)

private val calls = listOf(
    CallLog(
        id = "call1", roomId = "r1", conversationId = "c1", initiatorId = me.id,
        participants = listOf(me.id, bob.id, bot.id), startTime = "${T}09:00:00.000Z",
        endTime = "${T}09:12:30.000Z", durationSec = 750,
    ),
    CallLog(
        id = "call2", roomId = "r2", conversationId = "c2", initiatorId = bob.id,
        participants = listOf(me.id, bob.id), startTime = "${T}08:10:00.000Z",
        endTime = "${T}08:10:45.000Z", durationSec = 45,
    ),
)
