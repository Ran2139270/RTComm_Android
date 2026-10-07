package com.rtcomm.app.ui.ai

import com.rtcomm.app.ui.theme.Corner

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.Api
import com.rtcomm.app.data.BotConfig
import com.rtcomm.app.data.BotDetail
import com.rtcomm.app.data.ProviderPreset
import com.rtcomm.app.data.PublicUser
import com.rtcomm.app.data.UpdateBotConfigReq
import com.rtcomm.app.ui.common.AppAlertDialog
import com.rtcomm.app.ui.common.AppSwitch
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.Motion
import com.rtcomm.app.ui.common.Reveal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 从 AiChatScreen.kt 拆出的无消息引导 / 机器人配置弹窗 / 气泡与流式光标（同包 internal）。

/** 无消息时的引导：头像 + 说明 + 快捷提问（淡入上移）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyAiState(botName: String, onSend: (String) -> Unit) {
    val reducedMotion = com.rtcomm.app.ui.common.rememberReducedMotion()
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(Motion.duration(reducedMotion, Motion.Emphasized))) +
            slideInVertically(tween(Motion.duration(reducedMotion, Motion.Emphasized), easing = Motion.EnterEasing)) { it / 12 },
    ) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        InitialsAvatar(botName, size = 64.dp, isBot = true)
        Spacer(Modifier.height(Space.md))
        Text("和 $botName 开始对话", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        Text(
            "支持流式回复；输入 /reset 清空上下文，/compact 压缩上下文",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.lg))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            AI_SUGGESTIONS.forEach { s ->
                SuggestionChip(onClick = { onSend(s) }, label = { Text(s) })
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BotConfigDialog(
    botId: String,
    initial: BotConfig?,
    thinking: Boolean,
    showThinking: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onShowThinkingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSaved: (BotDetail?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderPreset>>(emptyList()) }
    var personality by remember { mutableStateOf(initial?.personality ?: "friendly") }
    var replyStyle by remember { mutableStateOf(initial?.replyStyle ?: "concise") }
    var provider by remember { mutableStateOf(initial?.modelProvider ?: "echo") }
    var modelName by remember { mutableStateOf(initial?.modelName.orEmpty()) }
    var temperature by remember { mutableStateOf((initial?.temperature ?: 0.7).toFloat().coerceIn(0f, 2f)) }
    var memoryEnabled by remember { mutableStateOf(initial?.memoryEnabled ?: true) }
    var knowledgeBase by remember { mutableStateOf(initial?.knowledgeBase.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(initial?.systemPrompt.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf(initial?.visibility ?: "private") }
    var sharedIds by remember { mutableStateOf(initial?.sharedUserIds ?: emptyList()) }
    var sharedNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var shareQuery by remember { mutableStateOf("") }
    var shareResults by remember { mutableStateOf<List<PublicUser>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { Api.providers() } }
        r.onSuccess { providers = it }
    }

    // 已选共享用户只回传 id，这里补上显示名。
    LaunchedEffect(initial?.sharedUserIds) {
        val ids = initial?.sharedUserIds.orEmpty()
        if (ids.isEmpty()) return@LaunchedEffect
        sharedNames = withContext(Dispatchers.IO) {
            ids.associateWith { id -> runCatching { Api.userById(id)?.displayName }.getOrNull() ?: id.take(8) }
        }
    }

    LaunchedEffect(shareQuery) {
        val q = shareQuery.trim()
        if (q.isEmpty()) { shareResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val r = withContext(Dispatchers.IO) { runCatching { Api.searchUsers(q, 10) } }
        r.onSuccess { list -> shareResults = list.filter { it.id != botId } }
    }

    val personalityOptions = listOf("friendly" to "友好", "professional" to "专业", "humorous" to "幽默", "custom" to "自定义")
    val styleOptions = listOf("concise" to "简洁", "detailed" to "详细", "casual" to "口语")
    val providerOptions = providers.ifEmpty {
        listOf(
            ProviderPreset(id = "echo", label = "Echo（测试）"),
            ProviderPreset(id = "openai", label = "OpenAI"),
            ProviderPreset(id = "anthropic", label = "Anthropic"),
            ProviderPreset(id = "ollama", label = "Ollama（本地）"),
        )
    }

    AppAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("机器人配置") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("模型提供者", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    providerOptions.forEach { p ->
                        FilterChip(
                            selected = provider == p.id,
                            onClick = {
                                provider = p.id
                                if (modelName.isBlank()) p.model?.let { modelName = it }
                            },
                            label = { Text(p.label) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(modelName, { modelName = it }, label = { Text("模型名（留空用默认）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    apiKey, { apiKey = it },
                    label = { Text(if (initial?.hasApiKey == true) "API Key（留空则保持不变）" else "API Key（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text("性格", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    personalityOptions.forEach { (key, label) ->
                        FilterChip(selected = personality == key, onClick = { personality = key }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("回复风格", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    styleOptions.forEach { (key, label) ->
                        FilterChip(selected = replyStyle == key, onClick = { replyStyle = key }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = memoryEnabled, onValueChange = { memoryEnabled = it }, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("携带上下文记忆", modifier = Modifier.weight(1f))
                    AppSwitch(checked = memoryEnabled, onCheckedChange = null)
                }
                // 思考开关移到这里（按机器人保存）：默认低强度推理，兼顾质量与耗时。
                Row(
                    Modifier.fillMaxWidth().toggleable(value = thinking, onValueChange = onThinkingChange, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("深度思考（低强度）", modifier = Modifier.weight(1f))
                    AppSwitch(checked = thinking, onCheckedChange = null)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = showThinking, onValueChange = onShowThinkingChange, enabled = thinking, role = Role.Switch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("展示思考内容", modifier = Modifier.weight(1f))
                    // 只有开启思考后才能开启展示；关闭思考（none）时该开关禁用。
                    AppSwitch(checked = showThinking, onCheckedChange = null, enabled = thinking)
                }
                Spacer(Modifier.height(Space.xs))
                Text("温度 ${"%.2f".format(temperature)}（越高越随机）", style = MaterialTheme.typography.labelMedium)
                Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(systemPrompt, { systemPrompt = it }, label = { Text("系统提示词（自定义人设）") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(knowledgeBase, { knowledgeBase = it }, label = { Text("知识库范围（可选）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Spacer(Modifier.height(10.dp))
                Text("可见性", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("private" to "仅自己", "public" to "所有人", "shared" to "指定用户").forEach { (key, label) ->
                        FilterChip(selected = visibility == key, onClick = { visibility = key }, label = { Text(label) })
                    }
                }
                if (visibility == "shared") {
                    if (sharedIds.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sharedIds.forEach { id ->
                                InputChip(
                                    selected = false,
                                    onClick = { sharedIds = sharedIds - id },
                                    label = { Text(sharedNames[id] ?: id.take(8)) },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(shareQuery, { shareQuery = it }, label = { Text("搜索用户添加") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    shareResults.take(8).forEach { u ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (u.id !in sharedIds) {
                                    sharedIds = sharedIds + u.id
                                    sharedNames = sharedNames + (u.id to u.displayName)
                                }
                                shareQuery = ""; shareResults = emptyList()
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InitialsAvatar(u.displayName, size = 28.dp, isBot = u.isBot)
                            Spacer(Modifier.width(Space.sm))
                            Text(u.displayName + "  @" + u.username, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                err?.let {
                    Spacer(Modifier.height(Space.sm))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; err = null
                scope.launch {
                    val req = UpdateBotConfigReq(
                        personality = personality,
                        replyStyle = replyStyle,
                        knowledgeBase = knowledgeBase,
                        memoryEnabled = memoryEnabled,
                        modelProvider = provider,
                        modelName = modelName.trim(),
                        apiKey = apiKey.takeIf { it.isNotBlank() },
                        systemPrompt = systemPrompt,
                        temperature = temperature.toDouble(),
                    )
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            Api.updateBotConfig(botId, req)
                            Api.updateBotVisibility(botId, visibility, sharedIds)
                        }
                    }
                    busy = false
                    r.onSuccess { onSaved(it) }
                    r.onFailure { err = Api.userMessage(it) }
                }
            }) { Text(if (busy) "保存中…" else stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AiBubble(m: AiMsg, showThinking: Boolean, modifier: Modifier = Modifier, onLongPress: () -> Unit) {
    // 大屏下限制气泡宽度，避免 AI 回复在平板上拉满整屏。
    val maxBubbleWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.8f)
        .coerceAtMost(560f).dp
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (m.fromHuman) Arrangement.End else Arrangement.Start,
    ) {
        // 气泡圆角跟随全局「气泡圆角」设置，与聊天页保持一致。
        val cornerStyle = com.rtcomm.app.ui.common.LocalBubbleCorner.current
        val bubbleRadius = when (cornerStyle) {
            "small" -> 10.dp
            "large" -> 22.dp
            else -> 14.dp
        }
        Surface(
            color = if (m.fromHuman) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = if (m.fromHuman) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer,
            shape = RoundedCornerShape(bubbleRadius),
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            // AI 回复以 Markdown 渲染（Compose 原生，不信任 HTML）；流式时尾部加闪烁光标。
            if (m.fromHuman) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    m.imageUri?.let { uri ->
                        coil.compose.AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(180.dp, 180.dp).clip(RoundedCornerShape(Corner.thumb)),
                        )
                        if (m.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }
                    if (m.content.isNotBlank()) {
                        com.rtcomm.app.ui.common.MarkdownText(
                            src = m.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            compact = true,
                        )
                    }
                }
            } else {
                Column {
                    if (showThinking && !m.reasoning.isNullOrBlank()) {
                        ReasoningPanel(m.reasoning, streaming = m.streaming && m.content.isBlank())
                    }
                    com.rtcomm.app.ui.common.MarkdownText(
                        src = m.content,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        compact = true,
                    )
                    if (m.streaming) {
                        StreamingCursor(
                            Modifier.padding(start = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
        if (m.failed) {
            Spacer(Modifier.width(Space.xs))
            Icon(Icons.Filled.Refresh, contentDescription = "发送失败", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp).align(Alignment.CenterVertically))
        }
    }
}

/** 流式生成时的闪烁光标。 */
@Composable
internal fun StreamingCursor(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cursor-alpha",
    )
    Box(
        modifier
            .size(width = 7.dp, height = 14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = alpha)),
    )
}

/** 思考内容面板：可折叠；流式思考时自动展开，回答开始后可手动收起。 */
@Composable
internal fun ReasoningPanel(text: String, streaming: Boolean) {
    var expanded by remember { mutableStateOf(streaming) }
    LaunchedEffect(streaming) { if (streaming) expanded = true }
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        shape = RoundedCornerShape(Corner.thumb),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (streaming) "正在思考…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Reveal(expanded) {
                Column {
                Spacer(Modifier.height(6.dp))
                com.rtcomm.app.ui.common.MarkdownText(src = text, compact = true)
                }
            }
        }
    }
}
