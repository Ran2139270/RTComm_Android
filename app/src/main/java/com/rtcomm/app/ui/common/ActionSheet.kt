package com.rtcomm.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rtcomm.app.ui.theme.Space
import kotlinx.coroutines.launch

/** 动作面板中的一项。 */
data class SheetAction(
    val icon: ImageVector,
    val label: String,
    /** 危险操作（删除/撤回/退出等）标红。 */
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 统一的「动作面板」：长按 / 二级操作从这里滑出。
 *
 * 用 [ModalBottomSheet]（滑入 + 遮罩淡入的原生动画）替代原先居中的 [androidx.compose.material3.AlertDialog]
 * 纯文字按钮列表：每项带图标、危险项标红、可下滑关闭。点选后先播完收起动画再执行动作，
 * 避免弹层「啪」地瞬间消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
    title: String? = null,
    subtitle: String? = null,
    /** 点选动画收起后是否再执行动作；默认 true。 */
    animateDismiss: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 点选后先 hide 播完收起动画，再执行动作（动作通常会把状态置空、令本层离场）。
    val run = { action: () -> Unit ->
        if (animateDismiss) {
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        } else {
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            if (!title.isNullOrBlank()) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            actions.forEach { a ->
                val contentColor = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { run(a.onClick) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        a.icon,
                        contentDescription = null,
                        tint = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Space.lg))
                    Text(a.label, color = contentColor, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
