package com.rtcomm.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 语义色（非 Material 角色）。
 *
 * 这些颜色不属于 [androidx.compose.material3.ColorScheme] 的角色，
 * 但会在多处复用（在线点、头像兜底、终端、代码块等）。
 * 集中在这里可以避免散落在各文件里的 `Color(0xFF...)` 魔法值，
 * 也便于在明暗主题下统一校准。
 */
object AppColors {

    // ── 在线状态 ────────────────────────────────────────────────
    // 非文本图形（状态点）对所在表面需满足 WCAG 2.2 AA 的 3:1 对比度：
    // 深色主题用亮色、浅色主题用加深色，避免浅底上的绿/琥珀点糊成一片。
    private val OnlineDark = Color(0xFF22C55E)
    private val OnlineLight = Color(0xFF15803D)

    /** 在线（绿），随主题明暗自动校准对比度。 */
    val Online: Color
        @Composable @ReadOnlyComposable get() = if (isDarkScheme()) OnlineDark else OnlineLight

    // ── 离线状态 ────────────────────────────────────────────────
    private val OfflineDark = Color(0xFF94A3B8)
    private val OfflineLight = Color(0xFF64748B)

    /** 离线（灰），随主题明暗自动校准对比度。 */
    val Offline: Color
        @Composable @ReadOnlyComposable get() = if (isDarkScheme()) OfflineDark else OfflineLight

    // ── 头像兜底调色板 ──────────────────────────────────────────
    /**
     * 无自定义头像时按名称哈希取色。刻意选中等明度的饱和色，
     * 保证白色首字母在浅色/深色主题下都有足够对比度。
     */
    val AvatarPalette = listOf(
        Color(0xFF2563EB), Color(0xFF0EA5E9), Color(0xFF7C3AED), Color(0xFF059669),
        Color(0xFFD97706), Color(0xFFDC2626), Color(0xFFDB2777), Color(0xFF0891B2),
    )

    // ── 终端 ────────────────────────────────────────────────────
    /** 终端背景（始终深色，模拟真实终端）。 */
    val TerminalBackground = Color(0xFF0B1020)
    /** 终端前景（浅绿）。 */
    val TerminalForeground = Color(0xFFB9F5C8)

    // ── 通用提示 ────────────────────────────────────────────────
    private val WarningDark = Color(0xFFFBBF24)
    private val WarningLight = Color(0xFFB45309)

    /** 提醒/进行中（琥珀），随主题明暗自动校准对比度。 */
    val Warning: Color
        @Composable @ReadOnlyComposable get() = if (isDarkScheme()) WarningDark else WarningLight
    /** 成功（绿）。 */
    val Success = Color(0xFF22C55E)
}

/** 当前最终配色是否为深色：直接读表面亮度，兼容「按时间/强制深浅/动态取色/AMOLED」。 */
@Composable
@ReadOnlyComposable
private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
