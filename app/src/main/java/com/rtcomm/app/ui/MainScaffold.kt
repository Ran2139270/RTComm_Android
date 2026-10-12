package com.rtcomm.app.ui

import com.rtcomm.app.ui.theme.Corner

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rtcomm.app.R
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.ui.ai.AiFlow
import com.rtcomm.app.ui.call.CallLogsScreen
import com.rtcomm.app.ui.chat.ConversationFlow
import com.rtcomm.app.ui.device.DeviceDetailScreen
import com.rtcomm.app.ui.device.DevicesScreen
import com.rtcomm.app.ui.files.FilesScreen
import com.rtcomm.app.ui.profile.AboutScreen
import com.rtcomm.app.ui.profile.ProfileDetailScreen
import com.rtcomm.app.ui.profile.ProfileScreen
import com.rtcomm.app.ui.profile.SettingsScreen
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.Motion

object Routes {
    const val CONVERSATIONS = "conversations"
    const val AI = "ai"
    const val DEVICES = "devices"
    const val FILES = "files"
    const val ME = "me"

    const val DEVICE_DETAIL = "device/{deviceId}"
    const val CALLS = "calls"
    /** 管理后台：仅管理员可达。 */
    const val ADMIN = "admin"
    /** 「我的」子页面。 */
    const val PROFILE_DETAIL = "profile"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    const val ABOUT = "about"
    const val CHANGELOG = "changelog"
    const val STORAGE = "storage"

    fun device(deviceId: String) = "device/$deviceId"
}

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.CONVERSATIONS, R.string.tab_conversations, Icons.Filled.Forum),
    Tab(Routes.AI, R.string.tab_ai, Icons.Filled.SmartToy),
    Tab(Routes.DEVICES, R.string.tab_devices, Icons.Filled.Memory),
    Tab(Routes.FILES, R.string.tab_files, Icons.Filled.Folder),
    Tab(Routes.ME, R.string.tab_me, Icons.Filled.Person),
)

@Composable
fun MainScaffold(authStore: AuthStore) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // ConversationFlow keeps list and chat in one composition during shared expansion.
    var conversationOverlay by remember { androidx.compose.runtime.mutableStateOf(false) }
    val isTopLevel = currentRoute in TABS.map { it.route }
    // 底部栏只在聊天覆盖时隐藏；列表自身的内边距保持不变，否则展开动画途中整张列表会重排。
    val bottomBarVisible = isTopLevel && !conversationOverlay

    // Tab 切换时清掉可能残留的共享 FAB 信号，避免「切过去后又莫名弹出新建弹层」。
    LaunchedEffect(currentRoute) {
        AppState.fabNewConversation.value = false
        AppState.fabCreateBot.value = false
        AppState.fabRegisterDevice.value = false
        AppState.fabUploadFile.value = false
    }

    // 应用内消息横幅：前台收到非当前会话的新消息时从顶部滑入
    var banner by remember { androidx.compose.runtime.mutableStateOf<BannerData?>(null) }
    LaunchedEffect(Unit) {
        com.rtcomm.app.data.WsBus.events.collect { e ->
            if (e.type != "new_message") return@collect
            val data = e.data
            val convId = data["conversationId"] as? String ?: return@collect
            val meId = com.rtcomm.app.data.AppState.currentUser.value?.id
            val msg = com.google.gson.Gson().fromJson(
                com.google.gson.Gson().toJson(data["message"]),
                com.rtcomm.app.data.Message::class.java,
            ) ?: return@collect
            if (msg.senderId == meId) return@collect
            if (convId == com.rtcomm.app.data.AppState.activeConversationId.value) return@collect
            val conv = com.rtcomm.app.data.AppState.conversations.value.firstOrNull { it.id == convId }
            val senderName = msg.sender?.displayName ?: "新消息"
            val preview = when {
                msg.isDeleted -> "消息已撤回"
                msg.isFileType -> "[文件] " + (msg.file?.fileName ?: "")
                else -> com.rtcomm.app.ui.common.Format.mdToPlain(msg.content)
            }
            banner = BannerData(convId, conv?.name ?: senderName, "$senderName：$preview".take(60))
        }
    }
    // 横幅 4 秒自动消失
    LaunchedEffect(banner) {
        if (banner != null) {
            kotlinx.coroutines.delay(4000)
            banner = null
        }
    }

    // 通知点击深链：切到会话标签，由 ConversationFlow 用展开动画打开目标会话。
    LaunchedEffect(Unit) {
        AppState.pendingOpenConversationId.collect { id ->
            if (id != null && AppState.loggedIn.value) {
                navigateTab(nav, Routes.CONVERSATIONS)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    val useRail = configuration.screenWidthDp >= 600
    // NavigationBar 的实际高度 = 80dp 容器 + 系统 navigation-bar inset（三键导航/横屏会更高）。
    // 底栏以覆盖层绘制，内容区必须预留真实总高度，固定 80dp 会遮住列表末项/FAB。
    val bottomBarSafeArea = 80.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 该内边距只作用于标签页内容，不再施加于整个 NavHost——否则聊天覆盖层会底部留白。
    val contentBottomInset = if (useRail) 0.dp else bottomBarSafeArea

    Box(Modifier.fillMaxSize()) {
        AppBackgroundLayer()
        if (useRail) {
            Row(Modifier.fillMaxSize()) {
                // 大屏始终保留导航栏宽度，详情进退不再触发内容区横向重排。
                NavRail(currentRoute) { navigateTab(nav, it) }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AppNavHost(
                        nav = nav,
                        authStore = authStore,
                        modifier = Modifier.fillMaxSize(),
                        contentBottomInset = contentBottomInset,
                        onConversationOverlayChange = { conversationOverlay = it },
                    )
                }
            }
        } else {
            // 导航栏作为覆盖层显示，不再随详情页进退改变 NavHost 高度。
            // 顶层页面预留与导航栏等高的安全区，避免覆盖「发起会话 / 上传 / 新建 AI」FAB
            // 及列表末项；详情页继续占满全屏，保证返回动画稳定。
            Box(Modifier.fillMaxSize()) {
                AppNavHost(
                    nav = nav,
                    authStore = authStore,
                    modifier = Modifier.fillMaxSize(),
                    contentBottomInset = contentBottomInset,
                    onConversationOverlayChange = { conversationOverlay = it },
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = bottomBarVisible,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 160)) + androidx.compose.animation.slideInVertically(tween(if (reducedMotion) 0 else 180)) { it / 2 },
                    exit = fadeOut(tween(if (reducedMotion) 0 else 120)) + androidx.compose.animation.slideOutVertically(tween(if (reducedMotion) 0 else 140)) { it / 2 },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                ) {
                    BottomBar(currentRoute) { navigateTab(nav, it) }
                }
            }
        }
        // 共享 FAB：位于 NavHost 之外，切 Tab 时保持原位；只按路由单独淡入淡出/缩放。
        SharedFab(
            currentRoute = currentRoute,
            visible = bottomBarVisible,
            reduced = reducedMotion,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = if (useRail) 16.dp else bottomBarSafeArea + 16.dp,
                ),
        )
        // 应用内消息横幅（顶部滑入，点击直达会话）
        // 只用纵向滑入、不叠加 fade：横幅带 shadowElevation=8dp，若淡入期间 alpha<1，
        // 与 FAB 同理阴影会被暂缓绘制、落定才补上（顶部那圈投影会「闪一下」）。
        // 从屏幕上方整体滑入本身已是完整入场动画，alpha 恒为 1，阴影全程稳定。
        androidx.compose.animation.AnimatedVisibility(
            visible = banner != null,
            enter = androidx.compose.animation.slideInVertically(tween(if (reducedMotion) 0 else 260)) { -it },
            exit = androidx.compose.animation.slideOutVertically(tween(if (reducedMotion) 0 else 200)) { -it },
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
        ) {
            banner?.let { b ->
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(Corner.small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable {
                            AppState.pendingOpenConversationId.value = b.conversationId
                            banner = null
                        },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Filled.Forum,
                            contentDescription = null,
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(
                                b.title,
                                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            androidx.compose.material3.Text(
                                b.preview,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 横幅数据。 */
private data class BannerData(val conversationId: String, val title: String, val preview: String)

/** 主界面背景层：基础底色 + 可选自定义图片（叠加遮罩保证内容可读）。 */
@Composable
private fun AppBackgroundLayer() {
    val bg by AppState.appBackground.collectAsState()
    val dim by AppState.bgDim.collectAsState()
    val blur by AppState.bgBlur.collectAsState()
    // 「减少透明 / 毛玻璃」开启时跳过全屏 RenderEffect 高斯模糊（与聊天页背景一致的降级），
    // 省一次全屏离屏合成；此时用户通常也更想要清晰的纯背景。
    val reduceTransparency = com.rtcomm.app.ui.common.LocalReduceTransparency.current
    val effectiveBlur = if (reduceTransparency) 0 else blur
    Box(
        Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background),
    ) {
        bg?.let { path ->
            coil.compose.AsyncImage(
                model = java.io.File(path),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(backgroundBlur(effectiveBlur)),
            )
            Box(
                Modifier.fillMaxSize()
                    .background(androidx.compose.material3.MaterialTheme.colorScheme.background.copy(alpha = dim.coerceIn(0f, 1f))),
            )
        }
    }
}

/** 背景模糊（仅 Android 12+；低版本忽略，避免无效 RenderEffect）。 */
private fun backgroundBlur(dp: Int): Modifier =
    if (dp > 0 && android.os.Build.VERSION.SDK_INT >= 31) {
        Modifier.blur(dp.dp)
    } else {
        Modifier
    }

private fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 顶层 Tab 切换用**纯交叉淡入淡出**（crossfade）：新旧两页等时长交叠淡变，不做缩放、
 * 不做位移。原来的「弹性放大 + 轻微上浮」在快速连点时观感生硬（尺度回弹跟手感差），
 * 交叉淡变则始终柔和、且方向中立（Tab 之间无层级，横滑会误导方向感）。
 * 出入时长一致才能形成真正的交叉；系统「减少动态效果」时直接瞬时切换。
 */
private fun tabEnterSpec(reduced: Boolean): EnterTransition = if (reduced) {
    EnterTransition.None
} else {
    fadeIn(tween(Motion.TabCrossfade, easing = Motion.StandardEasing))
}

private fun tabExitSpec(reduced: Boolean): ExitTransition = if (reduced) {
    ExitTransition.None
} else {
    fadeOut(tween(Motion.TabCrossfade, easing = Motion.StandardEasing))
}

/**
 * 详情页用水平推进（shared-axis X），进入与返回严格互逆：新页从右滑入、返回向右滑出，
 * 被覆盖/露出的页面做 1/4 宽的小幅视差。两套时间参数对称，避免「进入没动画/返回动画不一致」。
 */
private fun detailPushEnterSpec(reduced: Boolean): EnterTransition = if (reduced) {
    EnterTransition.None
} else {
    fadeIn(tween(220, easing = Motion.StandardEasing)) +
        slideInHorizontally(tween(300, easing = Motion.EmphasizedEasing)) { it }
}

private fun detailPopExitSpec(reduced: Boolean): ExitTransition = if (reduced) {
    ExitTransition.None
} else {
    fadeOut(tween(180, easing = Motion.StandardEasing)) +
        slideOutHorizontally(tween(280, easing = Motion.EmphasizedEasing)) { it }
}

private fun detailCoveredExitSpec(reduced: Boolean): ExitTransition = if (reduced) {
    ExitTransition.None
} else {
    fadeOut(tween(220, easing = Motion.StandardEasing)) +
        slideOutHorizontally(tween(300, easing = Motion.StandardEasing)) { -it / 4 }
}

private fun detailRevealEnterSpec(reduced: Boolean): EnterTransition = if (reduced) {
    EnterTransition.None
} else {
    fadeIn(tween(220, easing = Motion.StandardEasing)) +
        slideInHorizontally(tween(300, easing = Motion.EmphasizedEasing)) { -it / 4 }
}

private fun tabEnter(reduced: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    tabEnterSpec(reduced)
}

private fun tabExit(reduced: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    tabExitSpec(reduced)
}

// 标签页被详情页覆盖（exit）或从详情页返回（popEnter）时，改用与详情一致的轻动画；
// 仅在标签页之间互切时保留 tabEnter/tabExit，因此不影响原有 Tab 切换手感。
private fun tabExitSmart(reduced: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (isTabEntry(targetState)) tabExitSpec(reduced) else detailCoveredExitSpec(reduced)
}

private fun tabEnterSmart(reduced: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (isTabEntry(initialState)) tabEnterSpec(reduced) else detailRevealEnterSpec(reduced)
}

/** 顶层标签路由集合：用于区分「标签互切」与「详情页进退」。 */
private val TAB_ROUTES = TABS.map { it.route }.toSet()
private fun isTabEntry(entry: NavBackStackEntry) = entry.destination.route in TAB_ROUTES

/** 单个路由对应的 FAB 配置；null 表示该页无 FAB。 */
private data class FabSpec(val text: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * 顶层共享 FAB：放在 NavHost 之外，切 Tab 时保持原位。
 *
 * 关键：只保留「一个」常驻的 ExtendedFloatingActionButton 实例，切 Tab 时仅更换 文案/图标/动作
 * 并平滑改变宽度，绝不整颗重建、也不对它做 alpha 淡入。
 * 原实现用 AnimatedContent 按路由重建整颗 FAB 并 fadeIn/scaleIn：Compose 在 graphicsLayer
 * alpha<1 期间会暂缓绘制 elevation 阴影，等淡入结束才补齐，表现为「切界面时阴影卡一下才完整渲染」。
 * 常驻 surface 恒 alpha=1，阴影不再闪；外层 AnimatedVisibility 只在 FAB 整体出现/消失时才触发。
 */
@Composable
private fun SharedFab(
    currentRoute: String?,
    visible: Boolean,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val isAdmin = AppState.currentUser.value?.isAdmin == true
    val fab: FabSpec? = when (currentRoute) {
        Routes.CONVERSATIONS -> FabSpec("发起", Icons.Filled.Add) { AppState.fabNewConversation.value = true }
        Routes.AI -> if (isAdmin) FabSpec("新建机器人", Icons.Filled.Add) { AppState.fabCreateBot.value = true } else null
        Routes.DEVICES -> FabSpec("注册设备", Icons.Filled.Add) { AppState.fabRegisterDevice.value = true }
        Routes.FILES -> FabSpec("上传文件", Icons.Filled.UploadFile) { AppState.fabUploadFile.value = true }
        else -> null
    }
    // 记住最后一次有效配置：FAB 退场动画期间 fab 已为 null，仍需按旧配置继续渲染，避免文字瞬间清空。
    // 用 SideEffect 在提交后更新，避免在组合期写 state（会触发额外重组/被视为副作用）。
    var shown by remember { mutableStateOf(fab) }
    SideEffect { if (fab != null) shown = fab }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible && fab != null,
        enter = fadeIn(tween(if (reduced) 0 else 160)) +
            androidx.compose.animation.scaleIn(tween(if (reduced) 0 else 200), initialScale = 0.85f),
        exit = fadeOut(tween(if (reduced) 0 else 120)) +
            androidx.compose.animation.scaleOut(tween(if (reduced) 0 else 140), targetScale = 0.85f),
        modifier = modifier,
    ) {
        val spec = shown
        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = { spec?.onClick?.invoke() },
            // 去掉阴影：FAB 悬于半透明底栏上方，投影会被底栏白条截断/挡住，很难看。
            elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
            icon = {
                // 图标与文案同步淡切：仅在图标真的变化时（如 上传↔新建）才交叉淡入，
                // 同页文案变化但图标不变则保持不动，避免无谓闪动。
                Crossfade(
                    targetState = spec?.icon,
                    animationSpec = tween(if (reduced) 0 else 160),
                    label = "fab-icon",
                ) { ic -> if (ic != null) Icon(ic, null) }
            },
            text = {
                // 只淡切内部文案：不触碰外层投影 surface 的 alpha，阴影保持稳定不闪。
                Crossfade(
                    targetState = spec?.text.orEmpty(),
                    animationSpec = tween(if (reduced) 0 else 160),
                    label = "fab-label",
                ) { Text(it) }
            },
            // 文案宽度变化时平滑改变 FAB 宽度，阴影随之平滑，不再突变。
            modifier = Modifier.animateContentSize(tween(if (reduced) 0 else 220)),
        )
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val convs by AppState.conversations.collectAsState()
    val unread = remember(convs) { convs.sumOf { it.unreadCount } }
    // 与毛玻璃顶栏统一的「半透明着色」底：之前用全透明容器意在与背景融合，
    // 但浅色背景下整条 80dp 安全区就成了一片空白白条（图标上方尤其明显）。
    // 这里给一层 surface 半透明着色 + 顶部发丝分隔线，让它读成「一条栏」而非空白；
    // 关闭透明度（reduceTransparency）时退化为近实心，避免叠加壁纸时糊成一片。
    val reduceTransparency = com.rtcomm.app.ui.common.LocalReduceTransparency.current
    val barColor = if (reduceTransparency) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    NavigationBar(
        containerColor = barColor,
        modifier = Modifier.drawWithContent {
            // 顶部一条发丝分隔线，明确底栏与内容的边界。
            drawContent()
            val stroke = 1.dp.toPx()
            drawLine(
                color = dividerColor.copy(alpha = com.rtcomm.app.ui.theme.Alpha.divider),
                start = androidx.compose.ui.geometry.Offset(0f, stroke / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, stroke / 2f),
                strokeWidth = stroke,
            )
        },
    ) {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { TabIcon(tab, if (tab.route == Routes.CONVERSATIONS) unread else 0) },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun NavRail(current: String?, onSelect: (String) -> Unit) {
    val convs by AppState.conversations.collectAsState()
    val unread = remember(convs) { convs.sumOf { it.unreadCount } }
    NavigationRail(Modifier.width(84.dp)) {
        TABS.forEach { tab ->
            NavigationRailItem(
                selected = current == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { TabIcon(tab, if (tab.route == Routes.CONVERSATIONS) unread else 0) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

/** 带未读角标的 Tab 图标（会话页显示全局未读总数）。 */
@Composable
private fun TabIcon(tab: Tab, count: Int) {
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    if (count > 0) {
        androidx.compose.material3.BadgedBox(
            badge = {
                androidx.compose.material3.Badge {
                    androidx.compose.animation.AnimatedContent(
                        targetState = count,
                        transitionSpec = {
                            // reducedMotion 时整体瞬时切换：之前只有 fade 的 tween 读了该开关，
                            // slideIn/slideOut 用默认 spec，导致「减少动态效果」下数字仍会滑动。
                            if (reducedMotion) {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            } else {
                                (fadeIn(tween(160)) +
                                    androidx.compose.animation.slideInVertically { it / 2 }) togetherWith
                                    (fadeOut(tween(120)) +
                                        androidx.compose.animation.slideOutVertically { -it / 2 })
                            }
                        },
                        label = "badge-count",
                    ) { c ->
                        Text(if (c > 99) "99+" else c.toString())
                    }
                }
            },
        ) {
            Icon(tab.icon, contentDescription = stringResource(tab.label))
        }
    } else {
        Icon(tab.icon, contentDescription = stringResource(tab.label))
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    authStore: AuthStore,
    modifier: Modifier = Modifier,
    /** 底栏安全区：只给标签页内容，详情页与聊天覆盖层保持全屏。 */
    contentBottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onConversationOverlayChange: (Boolean) -> Unit = {},
) {
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    NavHost(
        navController = nav,
        startDestination = Routes.CONVERSATIONS,
        modifier = modifier.fillMaxSize(),
        // 详情页：水平推进 shared-axis（进入从右滑入 / 返回向右滑出），减少动态效果时瞬时切换。
        enterTransition = { detailPushEnterSpec(reducedMotion) },
        exitTransition = { detailCoveredExitSpec(reducedMotion) },
        popEnterTransition = { detailRevealEnterSpec(reducedMotion) },
        popExitTransition = { detailPopExitSpec(reducedMotion) },
    ) {
        composable(
            route = Routes.CONVERSATIONS,
            enterTransition = tabEnter(reducedMotion),
            exitTransition = tabExitSmart(reducedMotion),
            popEnterTransition = tabEnterSmart(reducedMotion),
            popExitTransition = tabExit(reducedMotion),
        ) {
            // 这两个 lambda 必须稳定：打开会话时 conversationOverlay 翻转会让 MainScaffold
            // 重组，若回调每次都是新实例，整张会话列表会被重组+重新布局，正好压在动画第一帧。
            val openCalls: () -> Unit = remember { { nav.navigate(Routes.CALLS) } }
            val overlayChange: (Boolean) -> Unit = remember { { onConversationOverlayChange(it) } }
            ConversationFlow(
                onOpenCalls = openCalls,
                onOverlayVisibleChange = overlayChange,
                // 底部安全区交给列表自己承担，覆盖层才能全屏，避免聊天页底部留白。
                listBottomPadding = contentBottomInset,
            )
        }
        composable(
            route = Routes.AI,
            enterTransition = tabEnter(reducedMotion),
            exitTransition = tabExitSmart(reducedMotion),
            popEnterTransition = tabEnterSmart(reducedMotion),
            popExitTransition = tabExit(reducedMotion),
        ) {
            // 列表与 AI 会话在同一组合里，复用会话列表的展开/回收动画。
            AiFlow(
                listBottomPadding = contentBottomInset,
                onOverlayVisibleChange = onConversationOverlayChange,
            )
        }
        composable(
            route = Routes.DEVICES,
            enterTransition = tabEnter(reducedMotion),
            exitTransition = tabExitSmart(reducedMotion),
            popEnterTransition = tabEnterSmart(reducedMotion),
            popExitTransition = tabExit(reducedMotion),
        ) {
            val openDevice: (String) -> Unit = remember { { nav.navigate(Routes.device(it)) } }
            Box(Modifier.fillMaxSize().padding(bottom = contentBottomInset)) {
                DevicesScreen(onOpenDevice = openDevice)
            }
        }
        composable(
            route = Routes.FILES,
            enterTransition = tabEnter(reducedMotion),
            exitTransition = tabExitSmart(reducedMotion),
            popEnterTransition = tabEnterSmart(reducedMotion),
            popExitTransition = tabExit(reducedMotion),
        ) {
            Box(Modifier.fillMaxSize().padding(bottom = contentBottomInset)) {
                FilesScreen()
            }
        }
        composable(
            route = Routes.ME,
            enterTransition = tabEnter(reducedMotion),
            exitTransition = tabExitSmart(reducedMotion),
            popEnterTransition = tabEnterSmart(reducedMotion),
            popExitTransition = tabExit(reducedMotion),
        ) {
            // 回调必须稳定（与 CONVERSATIONS 同理），否则设置页会随 MainScaffold 重组而重组。
            val openCalls: () -> Unit = remember { { nav.navigate(Routes.CALLS) } }
            val openAdmin: () -> Unit = remember { { nav.navigate(Routes.ADMIN) } }
            val openProfile: () -> Unit = remember { { nav.navigate(Routes.PROFILE_DETAIL) } }
            val openSettings: () -> Unit = remember { { nav.navigate(Routes.SETTINGS) } }
            val openAbout: () -> Unit = remember { { nav.navigate(Routes.ABOUT) } }
            Box(Modifier.fillMaxSize().padding(bottom = contentBottomInset)) {
                ProfileScreen(
                    authStore = authStore,
                    onOpenCalls = openCalls,
                    onOpenAdmin = openAdmin,
                    onOpenProfileDetail = openProfile,
                    onOpenSettings = openSettings,
                    onOpenAbout = openAbout,
                )
            }
        }
        composable(Routes.PROFILE_DETAIL) {
            ProfileDetailScreen(authStore = authStore, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val openStorage: () -> Unit = remember { { nav.navigate(Routes.STORAGE) } }
            val openSection: (String) -> Unit = remember { { s -> nav.navigate("settings/$s") } }
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenStorage = openStorage,
                onOpenSection = openSection,
            )
        }
        composable(Routes.SETTINGS_SECTION) { entry ->
            val section = entry.arguments?.getString("section") ?: ""
            com.rtcomm.app.ui.profile.SettingsDetailScreen(
                section = section,
                authStore = authStore,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.STORAGE) {
            com.rtcomm.app.ui.profile.StorageScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) {
            val openChangelog: () -> Unit = remember { { nav.navigate(Routes.CHANGELOG) } }
            AboutScreen(authStore = authStore, onBack = { nav.popBackStack() }, onOpenChangelog = openChangelog)
        }
        composable(Routes.CHANGELOG) {
            com.rtcomm.app.ui.update.ChangelogScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.DEVICE_DETAIL) { entry ->
            val deviceId = entry.arguments?.getString("deviceId") ?: ""
            DeviceDetailScreen(deviceId = deviceId, onBack = { nav.popBackStack() })
        }
        composable(Routes.ADMIN) {
            // 客户端只在管理员时才给入口；服务端仍然独立校验，越权请求会 403。
            if (AppState.currentUser.value?.isAdmin == true) {
                com.rtcomm.app.ui.admin.AdminScreen(onBack = { nav.popBackStack() })
            } else {
                // 正常入口对非管理员不可见；深链等越权路径给出可返回的明确提示，避免卡住。
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Text("需要管理员权限", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text(
                        "当前账号无权访问管理后台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(20.dp))
                    androidx.compose.material3.Button(onClick = { nav.popBackStack() }) { Text("返回") }
                }
            }
        }
        composable(Routes.CALLS) {
            CallLogsScreen(onBack = { nav.popBackStack() })
        }
    }
}
