package com.rtcomm.app.ui.common
import com.rtcomm.app.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rtcomm.app.ui.theme.Corner
import com.rtcomm.app.ui.theme.Space

/**
 * 毛玻璃标题栏底色：半透明表面 + 向下渐隐，让内容/壁纸透出、底边柔和过渡，
 * 避免不透明标题栏的硬边界。
 */
@Composable
private fun glassBarScrim(): Brush {
    val c = MaterialTheme.colorScheme.surface
    return Brush.verticalGradient(
        listOf(c.copy(alpha = 0.92f), c.copy(alpha = 0.72f), c.copy(alpha = 0.42f)),
    )
}

/** 标准页面标题：统一字号/字重，替代各页 inline fontWeight（标题一律走 Typography）。 */
@Composable
fun TopBarTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Text(text, modifier = modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * 统一顶栏：透明容器 + 标准标题 + 可选返回与操作。
 * 取代各屏各自一份 TopAppBar（容器色/标题字重不一致的问题）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    TopAppBar(
        title = { TopBarTitle(title, style = titleStyle) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier.background(glassBarScrim()),
    )
}

/** 自定义标题内容的顶栏（如聊天页头像 + 昵称 + 状态）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBarContent(
    title: @Composable () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = title,
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        modifier = modifier.background(glassBarScrim()),
    )
}

/** 标准卡片：统一圆角/表面色/高度/内边距，可点击时带按压反馈。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickMod = if (onClick != null) {
        Modifier.motionPress(pressedScale = 0.99f).clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.fillMaxWidth().then(clickMod),
        shape = RoundedCornerShape(Corner.medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** 标准分组标题（与设置页一致）。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Space.lg, top = Space.md, bottom = Space.xs),
    )
}
