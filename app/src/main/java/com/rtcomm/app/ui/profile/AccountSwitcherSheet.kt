package com.rtcomm.app.ui.profile

import com.rtcomm.app.ui.theme.Space
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rtcomm.app.data.AuthStore
import com.rtcomm.app.ui.common.ConfirmDialog
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.MetadataTag
import com.rtcomm.app.ui.common.motionPress

/**
 * 账号切换弹层：列出已保存的账号（令牌加密存储），点选即可切换，无需重新输入密码。
 * 「添加账号」会退出当前会话并回到登录页（不会注销旧令牌，便于切回）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    accounts: List<AuthStore.SavedAccount>,
    currentKey: String,
    onDismiss: () -> Unit,
    onSwitch: (AuthStore.SavedAccount) -> Unit,
    onAdd: () -> Unit,
    onRemove: (AuthStore.SavedAccount) -> Unit,
) {
    var pendingRemove by remember { mutableStateOf<AuthStore.SavedAccount?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                "切换账号",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            accounts.forEach { acc ->
                val isCurrent = acc.key == currentKey
                ListItem(
                    modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(enabled = !isCurrent) { onSwitch(acc) },
                    leadingContent = {
                        InitialsAvatar(
                            name = acc.user?.displayName ?: "?",
                            size = 44.dp,
                            isBot = acc.user?.isBot == true,
                            avatarUrl = acc.user?.avatarUrl,
                        )
                    },
                    headlineContent = {
                        Text(
                            (acc.user?.displayName ?: "账号") + if (isCurrent) "（当前）" else "",
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            "@" + (acc.user?.username ?: "") + " · " + acc.serverUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    },
                    trailingContent = {
                        if (isCurrent) {
                            MetadataTag("当前")
                        } else {
                            IconButton(onClick = { pendingRemove = acc }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除账号", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(Space.xs))
            HorizontalDivider()
            ListItem(
                modifier = Modifier.motionPress(pressedScale = 0.99f).clickable(onClick = onAdd),
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                headlineContent = { Text("添加账号") },
                supportingContent = {
                    Text("退出当前登录并前往登录页（旧账号仍保留，可随时切回）", style = MaterialTheme.typography.bodySmall)
                },
            )
        }
    }

    pendingRemove?.let { acc ->
        ConfirmDialog(
            title = "删除已保存账号",
            message = "将从本机移除「${acc.user?.displayName ?: acc.user?.username ?: "该账号"}」的登录信息，需要重新输入密码才能再次登录。",
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { onRemove(acc) },
            onDismiss = { pendingRemove = null },
        )
    }
}
