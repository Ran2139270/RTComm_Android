package com.rtcomm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.AppState
import com.rtcomm.app.ui.common.ExpandFlow

/**
 * 会话列表 → 聊天页 的展开/回收动画。
 *
 * 动画实现已抽到通用的 [ExpandFlow]（与 AI 会话共用同一套共享元素逻辑），
 * 本组件只负责把会话列表与聊天页接上去。
 */
@Composable
fun ConversationFlow(
    onOpenCalls: () -> Unit,
    onOverlayVisibleChange: (Boolean) -> Unit = {},
    /**
     * 底栏安全区。只作用于会话列表，**不能**作用到展开覆盖层，
     * 否则聊天页底部会被挤出一条空白。
     */
    listBottomPadding: Dp = 0.dp,
) {
    ExpandFlow(
        titleOf = ::conversationTitle,
        listBottomPadding = listBottomPadding,
        deepLink = AppState.pendingOpenConversationId,
        onDeepLinkConsumed = { AppState.pendingOpenConversationId.value = null },
        onOverlayVisibleChange = onOverlayVisibleChange,
        listContent = { onOpen, onBounds, onTitleBounds ->
            ConversationsScreen(
                onOpenChat = { id -> onOpen(id, conversationTitle(id)) },
                onOpenCalls = onOpenCalls,
                onConversationBounds = onBounds,
                onConversationTitleBounds = onTitleBounds,
            )
        },
        detailContent = { id, progress, onHeaderTitleBounds, onClose ->
            ChatScreen(
                conversationId = id,
                onBack = onClose,
                progress = progress,
                onHeaderTitleBounds = onHeaderTitleBounds,
            )
        },
    )
}

/** 会话标题（私聊取对端昵称，群聊取群名）。 */
private fun conversationTitle(id: String): String {
    val conv = AppState.conversations.value.firstOrNull { it.id == id } ?: return "聊天"
    val meId = AppState.currentUser.value?.id
    if (conv.type == "direct") {
        conv.members.firstOrNull { it.id != meId }?.displayName?.let { return it }
    }
    return conv.name?.takeIf { it.isNotBlank() } ?: if (conv.type == "direct") "私聊" else "群组"
}
