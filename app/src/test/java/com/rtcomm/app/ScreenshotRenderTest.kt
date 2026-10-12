package com.rtcomm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.rtcomm.app.data.AppState
import com.rtcomm.app.data.WsState
import com.rtcomm.app.ui.common.AvatarSeed
import com.rtcomm.app.ui.common.ConnectionChip
import com.rtcomm.app.ui.common.EmptyState
import com.rtcomm.app.ui.common.ErrorBanner
import com.rtcomm.app.ui.common.InitialsAvatar
import com.rtcomm.app.ui.common.LoadingBox
import com.rtcomm.app.ui.common.MetadataTag
import com.rtcomm.app.ui.common.TypingDots
import com.rtcomm.app.ui.theme.AppColors
import com.rtcomm.app.ui.theme.RtcommTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 截图回归测试：把应用真实的 Compose 运行时渲染成 PNG，供人工/自动比对。
 *
 * 纯 JVM 运行（Robolectric + Roborazzi），不需要设备或模拟器。
 * 输出：app/build/outputs/roborazzi/ 目录下的 PNG 文件。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w420dp-h2400dp-mdpi")
class ScreenshotRenderTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun freezeMotion() {
        // 固定为“减少动态效果”，避免无限动画让 Compose 测试时钟永不稳定。
        AppState.reduceMotion.value = "on"
    }

    private fun shoot(name: String) {
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun showcaseLight() {
        rule.setContent { RtcommTheme(themeMode = "light") { Showcase() } }
        shoot("01_showcase_light")
    }

    @Test
    fun showcaseDark() {
        rule.setContent { RtcommTheme(themeMode = "dark") { Showcase() } }
        shoot("02_showcase_dark")
    }

    @Test
    fun colorsGreenLight() {
        rule.setContent { RtcommTheme(themeMode = "light", themePreset = "green") { Column(Modifier.padding(16.dp)) { ColorRoles() } } }
        shoot("03_colors_green_light")
    }

    @Test
    fun colorsAmberLight() {
        rule.setContent { RtcommTheme(themeMode = "light", themePreset = "amber") { Column(Modifier.padding(16.dp)) { ColorRoles() } } }
        shoot("04_colors_amber_light")
    }

    @Test
    fun colorsRoseLight() {
        rule.setContent { RtcommTheme(themeMode = "light", themePreset = "rose") { Column(Modifier.padding(16.dp)) { ColorRoles() } } }
        shoot("05_colors_rose_light")
    }

    @Test
    fun showcaseAmoledDark() {
        rule.setContent { RtcommTheme(themeMode = "dark", amoled = true) { Showcase() } }
        shoot("06_showcase_amoled_dark")
    }

    @Test
    @Config(qualifiers = "w1280dp-h1400dp-mdpi")
    fun showcaseTabletLight() {
        rule.setContent { RtcommTheme(themeMode = "light") { Showcase() } }
        shoot("07_showcase_tablet_light")
    }

    @Test
    fun markdown() {
        rule.setContent {
            RtcommTheme(themeMode = "light") {
                Column(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    com.rtcomm.app.ui.common.MarkdownText(
                        src = "# 一级标题\n" +
                            "## 二级标题\n" +
                            "**粗体**、*斜体*、~~删除线~~、`行内代码`、[链接示例](https://example.com)\n\n" +
                            "- 无序项 A\n" +
                            "* 无序项 B\n\n" +
                            "1. 有序一\n" +
                            "2. 有序二\n\n" +
                            "> 引用一段话\n\n" +
                            "```\nval x = 1\n```\n\n" +
                            "---\n\n" +
                            "普通段落，含 <b>HTML</b> 标签应被剔除；单个 * 号不应变斜体。",
                    )
                }
            }
        }
        shoot("08_markdown")
    }
}

@Composable
private fun Showcase() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Color roles")
        ColorRoles()
        Section("Typography")
        TypographySpecimen()
        Section("Components")
        ComponentSamples()
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ColorRoles() {
    val cs = MaterialTheme.colorScheme
    val roles = listOf(
        "primary" to (cs.primary to cs.onPrimary),
        "secondary" to (cs.secondary to cs.onSecondary),
        "tertiary" to (cs.tertiary to cs.onTertiary),
        "error" to (cs.error to cs.onError),
        "primaryContainer" to (cs.primaryContainer to cs.onPrimaryContainer),
        "secondaryContainer" to (cs.secondaryContainer to cs.onSecondaryContainer),
        "tertiaryContainer" to (cs.tertiaryContainer to cs.onTertiaryContainer),
        "errorContainer" to (cs.errorContainer to cs.onErrorContainer),
        "surfaceVariant" to (cs.surfaceVariant to cs.onSurfaceVariant),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        roles.forEach { (name, pair) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(pair.first, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Text("$name → on-color", color = pair.second, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().height(30.dp)) {
            // 语义色随明暗主题取不同变体，不再写死色值标签。
            StatusCell("Online", AppColors.Online, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            StatusCell("Warning", AppColors.Warning, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            StatusCell("Offline", AppColors.Offline, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusCell(label: String, bg: Color, modifier: Modifier) {
    Box(modifier.fillMaxHeight().background(bg, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TypographySpecimen() {
    val t = MaterialTheme.typography
    val styles = listOf(
        "displaySmall" to t.displaySmall,
        "headlineMedium" to t.headlineMedium,
        "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge,
        "titleMedium" to t.titleMedium,
        "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge,
        "labelMedium" to t.labelMedium,
        "labelSmall" to t.labelSmall,
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        styles.forEach { (name, style) ->
            Text("$name · 永和九年岁在癸丑", style = style, maxLines = 1)
        }
    }
}

@Composable
private fun ComponentSamples() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(name = "张三")
            InitialsAvatar(name = "Li", online = true)
            InitialsAvatar(name = "AI", isBot = true, online = false)
            InitialsAvatar(
                name = "群组",
                group = listOf(AvatarSeed("张三"), AvatarSeed("李四"), AvatarSeed("王五"), AvatarSeed("赵六")),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MetadataTag("OpenAI")
            MetadataTag("friendly")
            ConnectionChip(WsState.Connected)
            ConnectionChip(WsState.Connecting)
            ConnectionChip(WsState.Disconnected)
            TypingDots(reducedMotion = true)
        }
        ErrorBanner("网络连接失败，请检查后重试", reducedMotion = true, onRetry = {})
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            EmptyState(icon = Icons.Filled.Forum, title = "暂无会话", subtitle = "发起一个新会话开始聊天")
        }
        Box(Modifier.fillMaxWidth().height(220.dp)) { LoadingBox() }
    }
}
