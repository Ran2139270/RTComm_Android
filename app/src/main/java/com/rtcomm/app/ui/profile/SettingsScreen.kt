package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.data.FileTransfer
import com.rtcomm.app.notify.Notify
import com.rtcomm.app.notify.RealtimeService
import com.rtcomm.app.ui.chat.ChatWallpaper
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppTopBar
import com.rtcomm.app.ui.common.LocalBubbleCorner
import com.rtcomm.app.ui.common.LocalRoundedAvatars
import com.rtcomm.app.ui.common.Reveal
import com.rtcomm.app.ui.common.motionPress
import com.rtcomm.app.ui.common.rememberReducedMotion
import com.rtcomm.app.ui.theme.ThemePresets
import com.rtcomm.app.ui.theme.supportsDynamicColor
import com.rtcomm.app.ws.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置首页：只展示分组入口，点击进入各自的二级页，避免一屏堆满所有选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenStorage: () -> Unit = {},
    onOpenSection: (String) -> Unit = {},
) {
    Scaffold(topBar = { AppTopBar("设置", onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                SectionTitle("个性化")
                GroupCard {
                    NavRow(Icons.Filled.Palette, "外观", "主题、主题色、字体与字号", showChevron = true) { onOpenSection(SectionAppearance) }
                    NavRow(Icons.Filled.Forum, "聊天", "气泡、消息密度与快捷回复", showChevron = true) { onOpenSection(SectionChat) }
                    NavRow(Icons.Filled.Wallpaper, "背景", "聊天与主界面背景", showChevron = true) { onOpenSection(SectionBackground) }
                }
                SectionTitle("系统")
                GroupCard {
                    NavRow(Icons.Filled.Notifications, "通知", "消息通知、声音与免打扰", showChevron = true) { onOpenSection(SectionNotification) }
                    NavRow(Icons.Filled.BlurOn, "动效", "动画效果与毛玻璃", showChevron = true) { onOpenSection(SectionMotion) }
                    NavRow(Icons.Filled.Wifi, "连接与 AI", "实时连接与文件自动下载", showChevron = true) { onOpenSection(SectionConnection) }
                    NavRow(Icons.Filled.Storage, "存储管理", "查看并清理本地占用", showChevron = true) { onOpenStorage() }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

internal const val SectionAppearance = "appearance"
internal const val SectionChat = "chat"
internal const val SectionBackground = "background"
internal const val SectionNotification = "notification"
internal const val SectionMotion = "motion"
internal const val SectionConnection = "connection"

/**
 * 设置二级页：按 [section] 渲染对应的详细选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDetailScreen(section: String, authStore: AuthStore, onBack: () -> Unit) {
    val title = when (section) {
        SectionAppearance -> "外观"
        SectionChat -> "聊天"
        SectionBackground -> "背景"
        SectionNotification -> "通知"
        SectionMotion -> "动效"
        SectionConnection -> "连接与 AI"
        else -> "设置"
    }
    Scaffold(topBar = { AppTopBar(title, onBack = onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                when (section) {
                    SectionAppearance -> AppearanceSection(authStore)
                    SectionChat -> ChatSection(authStore)
                    SectionBackground -> BackgroundSection(authStore)
                    SectionNotification -> NotificationSection(authStore)
                    SectionMotion -> MotionSection(authStore)
                    SectionConnection -> ConnectionSection(authStore)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** 偏好同时写本地与服务端（尽力同步）。 */
private fun CoroutineScope.pushPref(key: String, value: Any?) {
    launch { withContext(Dispatchers.IO) { runCatching { Api.putPreference(key, value) } } }
}

// ── 外观 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    val themeMode by AppState.themeMode.collectAsState()
    val dynamicColor by AppState.dynamicColor.collectAsState()
    val amoled by AppState.amoled.collectAsState()
    var themePreset by remember { mutableStateOf(authStore.themePreset) }
    var customPrimary by remember { mutableStateOf(authStore.customPrimary) }
    var darkStart by remember { mutableStateOf(authStore.darkStart) }
    var darkEnd by remember { mutableStateOf(authStore.darkEnd) }
    var showColorPicker by remember { mutableStateOf(false) }
    var fontScale by remember { mutableStateOf(authStore.fontScale) }
    var fontFamily by remember { mutableStateOf(authStore.fontFamily) }
    var timeFormat by remember { mutableStateOf(authStore.timeFormatPref) }

    SettingsCard { ThemePreview() }

    SectionTitle("主题")
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DarkMode, null); Spacer(Modifier.width(Space.md)); Text("主题", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(Space.sm))
            val modes = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色", "time" to "按时间")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (key, label) ->
                    SegmentedButton(
                        selected = themeMode == key,
                        onClick = { AppState.themeMode.value = key; authStore.themeMode = key; scope.pushPref("theme", key) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    ) { Text(label) }
                }
            }
            Reveal(themeMode == "time") {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text("深色开始：%02d:00".format(darkStart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = darkStart.toFloat(),
                        onValueChange = { darkStart = it.roundToInt().coerceIn(0, 23) },
                        onValueChangeFinished = {
                            authStore.darkStart = darkStart
                            AppState.darkStart.value = darkStart
                            scope.pushPref("darkStart", darkStart)
                        },
                        valueRange = 0f..23f,
                    )
                    Text("深色结束：%02d:00".format(darkEnd), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = darkEnd.toFloat(),
                        onValueChange = { darkEnd = it.roundToInt().coerceIn(0, 23) },
                        onValueChangeFinished = {
                            authStore.darkEnd = darkEnd
                            AppState.darkEnd.value = darkEnd
                            scope.pushPref("darkEnd", darkEnd)
                        },
                        valueRange = 0f..23f,
                    )
                    if (darkStart > darkEnd) {
                        Text(
                            "已跨越午夜：深色从当天 %02d:00 持续到次日 %02d:00".format(darkStart, darkEnd),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (supportsDynamicColor) {
            SwitchRow(Icons.Filled.Palette, "动态取色（跟随壁纸）", dynamicColor) {
                AppState.dynamicColor.value = it
                authStore.dynamicColor = it
                scope.pushPref("dynamicColor", it)
            }
            Reveal(themeMode != "system") {
                Text(
                    "仅「跟随系统」主题下生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp, bottom = 6.dp),
                )
            }
        }
        SwitchRow(Icons.Filled.DarkMode, "纯黑背景（AMOLED）", amoled) {
            AppState.amoled.value = it
            authStore.amoled = it
            scope.pushPref("amoled", it)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("主题色", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Space.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                ThemePresets.forEach { (key, color) ->
                    Box(
                        Modifier.size(48.dp).motionPress(pressedScale = 0.9f).clickable {
                            themePreset = key
                            AppState.themePreset.value = key
                            authStore.themePreset = key
                            scope.pushPref("themePreset", key)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(38.dp).then(
                                if (themePreset == key) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                if (themePreset == key) {
                                    Icon(Icons.Filled.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                // 自定义主色
                Box(
                    Modifier.size(48.dp).motionPress(pressedScale = 0.9f).clickable { showColorPicker = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (customPrimary != -1) {
                        Box(
                            Modifier.size(38.dp).then(
                                if (themePreset == "custom") Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(customPrimary)), contentAlignment = Alignment.Center) {
                                if (themePreset == "custom") {
                                    Icon(Icons.Filled.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Palette, contentDescription = "自定义主色", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    SectionTitle("文字")
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val factor = fontScale.toFloatOrNull() ?: when (fontScale) {
                "small" -> 0.9f; "large" -> 1.15f; else -> 1f
            }
            val pct = (factor * 100).roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", modifier = Modifier.weight(1f))
                if (kotlin.math.abs(factor - 1f) > 0.001f) {
                    TextButton(
                        onClick = {
                            fontScale = "1.00"
                            AppState.fontScale.value = "1.00"
                            authStore.fontScale = "1.00"
                            scope.pushPref("fontScale", "1.00")
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("重置", style = MaterialTheme.typography.labelMedium) }
                }
                Text("$pct%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Space.xs))
            Slider(
                value = factor,
                onValueChange = { v ->
                    // 固定 Locale：避免阿拉伯语等区域把小数点/数字转成本地格式。
                    val s = "%.2f".format(java.util.Locale.ROOT, v)
                    fontScale = s
                    AppState.fontScale.value = s
                    authStore.fontScale = s
                },
                onValueChangeFinished = { scope.pushPref("fontScale", fontScale) },
                valueRange = 0.85f..1.3f,
            )
        }
        SegmentedChoice("字体", listOf("system" to "系统", "serif" to "衬线", "mono" to "等宽"), fontFamily) { v ->
            fontFamily = v; AppState.fontFamily.value = v; authStore.fontFamily = v; scope.pushPref("fontFamily", v)
        }
        SegmentedChoice("时间显示", listOf("system" to "跟随系统", "h24" to "24 小时", "h12" to "12 小时"), timeFormat) { v ->
            timeFormat = v; AppState.timeFormat.value = v; authStore.timeFormatPref = v; scope.pushPref("timeFormat", v)
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initial = if (customPrimary != -1) customPrimary else 0xFF2563EB.toInt(),
            onDismiss = { showColorPicker = false },
            onPick = { argb ->
                customPrimary = argb
                themePreset = "custom"
                authStore.customPrimary = argb
                authStore.themePreset = "custom"
                AppState.customPrimary.value = argb
                AppState.themePreset.value = "custom"
                scope.pushPref("customPrimary", argb)
                scope.pushPref("themePreset", "custom")
                showColorPicker = false
            },
        )
    }
}

// ── 聊天 ────────────────────────────────────────────────────────────────

@Composable
private fun ChatSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    var bubbleCorner by remember { mutableStateOf(authStore.bubbleCorner) }
    var roundedAvatars by remember { mutableStateOf(authStore.roundedAvatars) }
    var chatDensity by remember { mutableStateOf(authStore.chatDensity) }
    var showQuickReplies by remember { mutableStateOf(false) }
    var quickReplies by remember { mutableStateOf(authStore.quickReplies) }

    SectionTitle("消息样式")
    SettingsCard {
        SegmentedChoice("气泡圆角", listOf("small" to "小", "medium" to "中", "large" to "大"), bubbleCorner) { v ->
            bubbleCorner = v; AppState.bubbleCorner.value = v; authStore.bubbleCorner = v; scope.pushPref("bubbleCorner", v)
        }
        SwitchRow(Icons.Filled.Person, "圆角方形头像", roundedAvatars) {
            roundedAvatars = it; AppState.roundedAvatars.value = it; authStore.roundedAvatars = it; scope.pushPref("roundedAvatars", it)
        }
        SegmentedChoice("消息密度", listOf("comfortable" to "标准", "compact" to "紧凑"), chatDensity) { v ->
            chatDensity = v; AppState.chatDensity.value = v; authStore.chatDensity = v; scope.pushPref("chatDensity", v)
        }
    }

    SectionTitle("快捷回复")
    SettingsCard {
        NavRow(
            Icons.Filled.Star,
            "快捷回复",
            if (quickReplies.isEmpty()) "添加常用语，聊天页一键插入" else "已设置 ${quickReplies.size} 条",
            showChevron = true,
        ) { showQuickReplies = true }
    }

    if (showQuickReplies) {
        QuickRepliesDialog(
            initial = quickReplies,
            onDismiss = { showQuickReplies = false },
            onSaved = { list ->
                quickReplies = list
                authStore.quickReplies = list
                AppState.quickReplies.value = list
                showQuickReplies = false
            },
        )
    }
}

// ── 背景 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    var chatWallpaper by remember { mutableStateOf(authStore.chatWallpaper) }
    var chatBgDim by remember { mutableStateOf(authStore.chatBgDim.coerceIn(0f, 0.9f)) }
    var chatBgBlur by remember { mutableStateOf(authStore.chatBgBlur) }
    var bgDim by remember { mutableStateOf(authStore.bgDim.coerceIn(0f, 0.95f)) }
    var bgBlur by remember { mutableStateOf(authStore.bgBlur) }
    var bgBusy by remember { mutableStateOf(false) }
    var bgMessage by remember { mutableStateOf<String?>(null) }

    val chatBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            bgBusy = true; bgMessage = null
            val path = withContext(Dispatchers.IO) { FileTransfer.importBackground(context, uri) }
            bgBusy = false
            if (path != null) {
                authStore.chatBackground = path
                AppState.chatBackground.value = path
                bgMessage = "已设置聊天背景图"
            } else {
                bgMessage = "图片读取失败，请重试"
            }
        }
    }
    val appBgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            bgBusy = true; bgMessage = null
            val path = withContext(Dispatchers.IO) { FileTransfer.importBackground(context, uri) }
            bgBusy = false
            if (path != null) {
                authStore.appBackground = path
                AppState.appBackground.value = path
                bgMessage = "已设置主界面背景图"
            } else {
                bgMessage = "图片读取失败，请重试"
            }
        }
    }

    SectionTitle("聊天背景")
    SettingsCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("背景样式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                ChatWallpaper.entries.forEach { w ->
                    FilterChip(
                        selected = chatWallpaper == w.key,
                        onClick = {
                            chatWallpaper = w.key
                            AppState.chatWallpaper.value = w.key
                            authStore.chatWallpaper = w.key
                            scope.pushPref("chatWallpaper", w.key)
                        },
                        label = { Text(w.label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "也可选择一张自定义图片作为聊天背景（优先于上方样式）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NavRow(
            Icons.Filled.Image,
            "聊天背景图",
            if (authStore.chatBackground != null) "已设置，点击更换" else "选择本地图片",
            showChevron = true,
        ) { chatBgPicker.launch("image/*") }
        Reveal(authStore.chatBackground != null) {
            if (authStore.chatBackground != null) {
                NavRow(Icons.Filled.Image, "清除聊天背景图") {
                    authStore.chatBackground = null
                    AppState.chatBackground.value = null
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景暗化", modifier = Modifier.weight(1f))
                Text("${(chatBgDim * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = chatBgDim,
                onValueChange = { chatBgDim = it },
                onValueChangeFinished = {
                    authStore.chatBgDim = chatBgDim
                    AppState.chatBgDim.value = chatBgDim
                    scope.pushPref("chatBgDim", chatBgDim)
                },
                valueRange = 0f..0.9f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景模糊", modifier = Modifier.weight(1f))
                Text(if (chatBgBlur == 0) "关" else "${chatBgBlur}dp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = chatBgBlur.toFloat(),
                onValueChange = { chatBgBlur = it.roundToInt() },
                onValueChangeFinished = {
                    authStore.chatBgBlur = chatBgBlur
                    AppState.chatBgBlur.value = chatBgBlur
                    scope.pushPref("chatBgBlur", chatBgBlur)
                },
                valueRange = 0f..24f,
            )
        }
    }

    SectionTitle("主界面背景")
    SettingsCard {
        NavRow(
            Icons.Filled.Wallpaper,
            "主界面背景图",
            if (authStore.appBackground != null) "已设置，点击更换" else "选择本地图片",
            showChevron = true,
        ) { appBgPicker.launch("image/*") }
        Reveal(authStore.appBackground != null) {
            if (authStore.appBackground != null) {
                NavRow(Icons.Filled.Wallpaper, "清除主界面背景图") {
                    authStore.appBackground = null
                    AppState.appBackground.value = null
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景暗化", modifier = Modifier.weight(1f))
                Text("${(bgDim * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = bgDim,
                onValueChange = { bgDim = it },
                onValueChangeFinished = {
                    authStore.bgDim = bgDim
                    AppState.bgDim.value = bgDim
                    scope.pushPref("bgDim", bgDim)
                },
                valueRange = 0f..0.95f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("背景模糊", modifier = Modifier.weight(1f))
                Text(if (bgBlur == 0) "关" else "${bgBlur}dp", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = bgBlur.toFloat(),
                onValueChange = { bgBlur = it.roundToInt() },
                onValueChangeFinished = {
                    authStore.bgBlur = bgBlur
                    AppState.bgBlur.value = bgBlur
                    scope.pushPref("bgBlur", bgBlur)
                },
                valueRange = 0f..24f,
            )
        }
    }

    // 背景图处理中：遮罩 + 加载动画（导入大图耗时）；完成后短暂提示。
    LaunchedEffect(bgMessage) {
        if (!bgBusy && bgMessage != null) {
            kotlinx.coroutines.delay(1800)
            bgMessage = null
        }
    }
    AnimatedVisibility(
        visible = bgBusy || bgMessage != null,
        enter = fadeIn(tween(if (reducedMotion) 0 else 160)) +
            scaleIn(tween(if (reducedMotion) 0 else 200), initialScale = 0.96f),
        exit = fadeOut(tween(if (reducedMotion) 0 else 140)) +
            scaleOut(tween(if (reducedMotion) 0 else 160), targetScale = 0.96f),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(Corner.medium),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bgBusy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(Space.md))
                    }
                    Text(if (bgBusy) "正在处理背景图…" else (bgMessage ?: ""))
                }
            }
        }
    }
}

// ── 通知 ────────────────────────────────────────────────────────────────

@Composable
private fun NotificationSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(authStore.notificationsEnabled) }
    var notificationPreview by remember { mutableStateOf(authStore.notificationPreview) }
    var notifSound by remember { mutableStateOf(authStore.notifSound) }
    var notifVibrate by remember { mutableStateOf(authStore.notifVibrate) }
    var dndEnabled by remember { mutableStateOf(authStore.dndEnabled) }
    var dndStart by remember { mutableStateOf(authStore.dndStart) }
    var dndEnd by remember { mutableStateOf(authStore.dndEnd) }
    var showDnd by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SectionTitle("通知")
    SettingsCard {
        SwitchRow(Icons.Filled.Notifications, "消息通知", notifications) {
            notifications = it; authStore.notificationsEnabled = it; scope.pushPref("notifications", it)
            if (it) {
                requestNotifPermission()
                if (AppState.loggedIn.value && authStore.autoConnectWs) RealtimeService.start(context)
                // 开启通知即注册 FCM 令牌（即便未开自动连接，也可靠推送兜底）。
                com.rtcomm.app.push.PushRegistrar.register()
            } else {
                RealtimeService.stop(context)
                Notify.cancelAll(context)
                // 关闭通知即注销令牌，避免服务端继续向本机推送、无谓唤醒进程。
                com.rtcomm.app.push.PushRegistrar.unregister()
            }
        }
        Reveal(notifications) {
            Column {
                NavRow(Icons.Filled.Notifications, "通知内容", if (notificationPreview == "full") "显示会话和消息内容" else "仅显示“收到新消息”") {
                    notificationPreview = if (notificationPreview == "full") "generic" else "full"
                    authStore.notificationPreview = notificationPreview
                    scope.pushPref("notificationPreview", notificationPreview)
                }
                SwitchRow(Icons.Filled.Notifications, "通知声音", notifSound) {
                    notifSound = it; authStore.notifSound = it; scope.pushPref("notifSound", it)
                }
                SwitchRow(Icons.Filled.Notifications, "通知震动", notifVibrate) {
                    notifVibrate = it; authStore.notifVibrate = it; scope.pushPref("notifVibrate", it)
                }
                SwitchRow(Icons.Filled.Schedule, "免打扰时段", dndEnabled) {
                    dndEnabled = it; authStore.dndEnabled = it; scope.pushPref("dndEnabled", it)
                    if (it) showDnd = true
                }
                Reveal(dndEnabled) {
                    NavRow(Icons.Filled.Schedule, "免打扰时间", "%02d:00 - %02d:00".format(dndStart, dndEnd), showChevron = true) { showDnd = true }
                }
            }
        }
    }

    if (showDnd) {
        DndDialog(
            start = dndStart,
            end = dndEnd,
            onDismiss = { showDnd = false },
            onSaved = { s, e ->
                dndStart = s; dndEnd = e
                authStore.dndStart = s; authStore.dndEnd = e
                dndEnabled = true; authStore.dndEnabled = true
                showDnd = false
            },
        )
    }
}

// ── 动效 ────────────────────────────────────────────────────────────────

@Composable
private fun MotionSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf(authStore.reduceMotionPref) }
    var reduceTransparency by remember { mutableStateOf(authStore.reduceTransparencyPref) }

    SectionTitle("动效")
    SettingsCard {
        SegmentedChoice("动画效果", listOf("system" to "跟随系统", "off" to "完整", "on" to "减少"), motion) { v ->
            motion = v; AppState.reduceMotion.value = v; authStore.reduceMotionPref = v; scope.pushPref("reduceMotion", v)
        }
        SwitchRow(Icons.Filled.BlurOn, "减少毛玻璃(省电/低端机)", reduceTransparency) {
            reduceTransparency = it
            AppState.reduceTransparency.value = it
            authStore.reduceTransparencyPref = it
            scope.pushPref("reduceTransparency", it)
        }
    }
}

// ── 连接与 AI ───────────────────────────────────────────────────────────

@Composable
private fun ConnectionSection(authStore: AuthStore) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var autoConnect by remember { mutableStateOf(authStore.autoConnectWs) }
    var autoDownload by remember { mutableStateOf(authStore.autoDownload) }

    SectionTitle("连接与 AI")
    SettingsCard {
        SwitchRow(Icons.Filled.Wifi, "自动连接实时通道", autoConnect) {
            autoConnect = it; authStore.autoConnectWs = it; scope.pushPref("autoConnectWs", it)
            if (it) {
                if (AppState.loggedIn.value) WsClient.start()
                if (authStore.notificationsEnabled) RealtimeService.start(context)
            } else {
                WsClient.stop()
                RealtimeService.stop(context)
            }
        }
        SwitchRow(Icons.Filled.Download, "文件自动下载", autoDownload) {
            autoDownload = it; authStore.autoDownload = it; scope.pushPref("autoDownload", it)
        }
    }
}

// ── 通用组件 ────────────────────────────────────────────────────────────

/** 设置分组卡片：圆角表面容器，替代裸分隔线，视觉更整、更现代。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(Corner.medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/** 顶部主题预览：用当前配色画一个迷你对话，切换主题/字号即时可见。 */
@Composable
private fun ThemePreview() {
    val scheme = MaterialTheme.colorScheme
    val roundedAvatars = LocalRoundedAvatars.current
    val cornerStyle = LocalBubbleCorner.current
    val big = when (cornerStyle) {
        "small" -> 10.dp
        "large" -> 24.dp
        else -> 18.dp
    }
    val tail = if (cornerStyle == "large") 6.dp else 5.dp
    val avatarShape: androidx.compose.ui.graphics.Shape = if (roundedAvatars) RoundedCornerShape(11.dp) else CircleShape
    val incomingShape = RoundedCornerShape(big, big, big, tail)
    val outgoingShape = RoundedCornerShape(big, big, tail, big)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(avatarShape).background(scheme.primary))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("主题预览", style = MaterialTheme.typography.titleSmall)
                Text("颜色、字号、圆角随设置即时变化", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(Space.md))
        Box(
            Modifier.clip(incomingShape)
                .background(scheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("你好，这是收到的消息", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Space.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.clip(outgoingShape)
                    .background(scheme.primary)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("颜色真好看", style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimary)
            }
        }
    }
}

/** 自定义主色取色器：HSV 三个滑杆 + 实时预览。 */
@Composable
private fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义主色") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(56.dp)
                        .clip(RoundedCornerShape(Corner.small))
                        .background(color),
                )
                Spacer(Modifier.height(Space.sm))
                Text("色相", style = MaterialTheme.typography.labelSmall)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("饱和度", style = MaterialTheme.typography.labelSmall)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("明度", style = MaterialTheme.typography.labelSmall)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
