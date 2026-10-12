package com.rtcomm.app.ui.theme

import com.rtcomm.app.ui.theme.Corner

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.rtcomm.app.ui.common.Format
import com.rtcomm.app.ui.common.LocalBubbleCorner
import com.rtcomm.app.ui.common.LocalReduceTransparency
import com.rtcomm.app.ui.common.LocalReducedMotion
import com.rtcomm.app.ui.common.LocalRoundedAvatars
import java.time.LocalTime

private val LightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B2A66),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF083344),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF3B0764),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = Color(0xFF52606D),
    surfaceDim = Color(0xFFDDE3EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEBF0F6),
    surfaceContainerHighest = Color(0xFFE4EAF2),
    inverseSurface = Color(0xFF2A3548),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = Color(0xFF93C5FD),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    scrim = Color(0xFF000000),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF07203F),
    primaryContainer = Color(0xFF1E3A6A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF042235),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF2A1065),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111A2E),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293E),
    onSurfaceVariant = Color(0xFF9FB0C3),
    surfaceDim = Color(0xFF0B1220),
    surfaceBright = Color(0xFF2A3852),
    surfaceContainerLowest = Color(0xFF0A101C),
    surfaceContainerLow = Color(0xFF131C30),
    surfaceContainer = Color(0xFF182238),
    surfaceContainerHigh = Color(0xFF1E293E),
    surfaceContainerHighest = Color(0xFF25314A),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF111A2E),
    inversePrimary = Color(0xFF2563EB),
    outline = Color(0xFF33415C),
    outlineVariant = Color(0xFF24324A),
    scrim = Color(0xFF000000),
    error = Color(0xFFF87171),
    onError = Color(0xFF3F0A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

/** 品牌主色三元组：由一个主色派生完整、和谐的 primary/secondary/tertiary 家族。 */
data class Preset(val primary: Color, val secondary: Color, val tertiary: Color)

private val PRESETS: Map<String, Preset> = mapOf(
    "blue" to Preset(Color(0xFF2563EB), Color(0xFF0EA5E9), Color(0xFF7C3AED)),
    "violet" to Preset(Color(0xFF7C3AED), Color(0xFFA855F7), Color(0xFF6366F1)),
    "green" to Preset(Color(0xFF059669), Color(0xFF10B981), Color(0xFF0D9488)),
    "rose" to Preset(Color(0xFFE11D48), Color(0xFFF43F5E), Color(0xFFDB2777)),
    "amber" to Preset(Color(0xFFD97706), Color(0xFFF59E0B), Color(0xFFEA580C)),
    "teal" to Preset(Color(0xFF0D9488), Color(0xFF14B8A6), Color(0xFF0891B2)),
    "indigo" to Preset(Color(0xFF4F46E5), Color(0xFF6366F1), Color(0xFF8B5CF6)),
    "cyan" to Preset(Color(0xFF0891B2), Color(0xFF06B6D4), Color(0xFF0EA5E9)),
    "slate" to Preset(Color(0xFF475569), Color(0xFF64748B), Color(0xFF0EA5E9)),
)

/**
 * 设置页展示用的预设列表（唯一数据源）。
 * 之前 ProfileScreen 里又抄了一份，改动容易不同步。
 */
val ThemePresets: List<Pair<String, Color>> =
    PRESETS.entries.map { it.key to it.value.primary }

private fun defaultPreset(): Preset = PRESETS.getValue("blue")

/** 由单个主色派生预设：次色/第三色用色相偏移，保证自定义主色也有和谐的辅助色。 */
private fun presetFromPrimary(c: Color): Preset {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    fun at(dh: Float, ds: Float, dv: Float): Color {
        val h = ((hsv[0] + dh) % 360f + 360f) % 360f
        val s = (hsv[1] * ds).coerceIn(0f, 1f)
        val v = (hsv[2] * dv).coerceIn(0f, 1f)
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    }
    return Preset(c, at(24f, 0.85f, 1.0f), at(-40f, 1.0f, 1.0f))
}

/** 向白色混合（变浅），用于浅色容器与深色主题的强调色。 */
private fun tint(c: Color, amount: Float): Color = Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = 1f,
)

/** 向黑色混合（变深），用于 on 容器色与深色主题容器。 */
private fun shade(c: Color, amount: Float): Color = Color(
    red = c.red * (1f - amount),
    green = c.green * (1f - amount),
    blue = c.blue * (1f - amount),
    alpha = 1f,
)

/** WCAG 2.x 相对对比度（1.0 ~ 21.0）。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 浅色主题正文墨色：比纯黑柔和，同时与各预设填充色保持足够对比。 */
private val InkOnLight = Color(0xFF0F172A)

/**
 * 为浅色主题的填充色挑选 on 色，并保证至少 [minRatio] 对比度。
 *
 * 旧实现用固定亮度阈值 0.55 选 on 色，导致 `secondary #0EA5E9`、`amber #D97706`
 * 等主色配白字只有 2.8~3.2，达不到 WCAG AA 的 4.5。这里改为**实测对比度择优**：
 * 若白字更清晰就用白字，否则用墨色；两者都不足时把填充色向相应方向微调（保持色相）。
 */
private fun readablePair(fill: Color, minRatio: Float = 4.5f): Pair<Color, Color> {
    val on = if (contrastRatio(Color.White, fill) >= contrastRatio(InkOnLight, fill)) Color.White else InkOnLight
    var surface = fill
    var guard = 0
    while (contrastRatio(on, surface) < minRatio && guard < 40) {
        surface = if (on == Color.White) shade(surface, 0.05f) else tint(surface, 0.10f)
        guard++
    }
    return surface to on
}

/**
 * 由一个 [Preset] 派生明/暗两套完整配色。
 *
 * 用「向白/向黑混合」派生容器色与 on 色，而不是手写几十个色值：
 * 既能保证同一预设内部和谐，也让以后接入自定义取色器时无需再补色板。
 */
private fun brandScheme(base: ColorScheme, dark: Boolean, preset: Preset): ColorScheme {
    fun family(p: Color, s: Color, t: Color) = if (!dark) {
        val pf = readablePair(p)
        val sf = readablePair(s)
        val tf = readablePair(t)
        Triple(
            Triple(pf.first, pf.second, tint(p, 0.86f) to shade(p, 0.55f)),
            Triple(sf.first, sf.second, tint(s, 0.86f) to shade(s, 0.55f)),
            Triple(tf.first, tf.second, tint(t, 0.86f) to shade(t, 0.55f)),
        )
    } else {
        Triple(
            Triple(tint(p, 0.28f), shade(p, 0.80f), shade(p, 0.55f) to tint(p, 0.82f)),
            Triple(tint(s, 0.28f), shade(s, 0.80f), shade(s, 0.55f) to tint(s, 0.82f)),
            Triple(tint(t, 0.28f), shade(t, 0.80f), shade(t, 0.55f) to tint(t, 0.82f)),
        )
    }

    val (pf, sf, tf) = family(preset.primary, preset.secondary, preset.tertiary)
    return base.copy(
        primary = pf.first,
        onPrimary = pf.second,
        primaryContainer = pf.third.first,
        onPrimaryContainer = pf.third.second,
        secondary = sf.first,
        onSecondary = sf.second,
        secondaryContainer = sf.third.first,
        onSecondaryContainer = sf.third.second,
        tertiary = tf.first,
        onTertiary = tf.second,
        tertiaryContainer = tf.third.first,
        onTertiaryContainer = tf.third.second,
        surfaceTint = pf.first,
        inversePrimary = if (dark) preset.primary else tint(preset.primary, 0.55f),
    )
}

/** 纯黑（AMOLED）覆盖：仅替换深色主题下的表面色，省电且对比更强。 */
private fun amoledScheme(base: ColorScheme): ColorScheme = base.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF070707),
    surfaceContainer = Color(0xFF0C0C0C),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF181818),
)

private val RtcommShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Corner.small),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(Corner.extra),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Android 12(API 31) 起支持从壁纸提取配色方案。 */
val supportsDynamicColor: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * @param themeMode system / light / dark / time（按时间自动深浅）
 * @param dynamicColor Material You 动态取色；仅 Android 12+ 且 themeMode=system 时生效。
 * @param themePreset 主题主色预设（dynamicColor 未生效时使用）。
 * @param fontScale small / default / large
 * @param amoled 深色主题下使用纯黑表面（省电/高对比）。
 */
@Composable
fun RtcommTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = false,
    themePreset: String = "blue",
    customPrimary: Int = -1,
    darkStart: Int = 19,
    darkEnd: Int = 7,
    fontScale: String = "default",
    fontFamily: String = "system",
    roundedAvatars: Boolean = false,
    bubbleCorner: String = "medium",
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 「按时间」模式必须定时刷新：旧实现只在组合时读一次 LocalTime.now()，
    // 跨过深色时段边界（如 19:00/07:00）不会自动切换。
    var currentHour by remember(themeMode) { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(themeMode) {
        if (themeMode != "time") return@LaunchedEffect
        while (true) {
            currentHour = LocalTime.now().hour
            kotlinx.coroutines.delay(60_000L)
        }
    }
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        "time" -> {
            val hour = currentHour
            when {
                darkStart == darkEnd -> false
                darkStart < darkEnd -> hour in darkStart until darkEnd
                else -> hour >= darkStart || hour < darkEnd
            }
        }
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    // 收集应用内「动画效果」偏好，保证切换后 LocalReducedMotion 立即更新（之前只在启动时读一次）。
    val reduceMotionPref by com.rtcomm.app.data.AppState.reduceMotion.collectAsState()
    val reduceTransparencyPref by com.rtcomm.app.data.AppState.reduceTransparency.collectAsState()
    val systemReduced = remember(context) { Format.systemAnimationsReduced(context) }
    val reducedMotion = when (reduceMotionPref) {
        "on" -> true
        "off" -> false
        else -> systemReduced
    }
    // 动态取色只跟随系统深浅：显式选浅色/深色/按时间时仍用品牌配色，避免语义打架。
    val useDynamic = dynamicColor && supportsDynamicColor && themeMode == "system"

    // 预设切换时平滑过渡主色，而不是硬切；减少动态效果时立即生效。
    val target = if (themePreset == "custom" && customPrimary != -1) {
        presetFromPrimary(Color(customPrimary))
    } else {
        PRESETS[themePreset] ?: defaultPreset()
    }
    val animSpec = tween<Color>(if (reducedMotion) 0 else 280)
    val animPrimary by animateColorAsState(target.primary, animSpec, label = "preset-primary")
    val animSecondary by animateColorAsState(target.secondary, animSpec, label = "preset-secondary")
    val animTertiary by animateColorAsState(target.tertiary, animSpec, label = "preset-tertiary")
    val animatedPreset = Preset(animPrimary, animSecondary, animTertiary)

    val scheme = remember(useDynamic, dark, animatedPreset, amoled, context) {
        val base = when {
            useDynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
        when {
            useDynamic -> if (dark && amoled) amoledScheme(base) else base
            dark -> {
                val branded = brandScheme(base, true, animatedPreset)
                if (amoled) amoledScheme(branded) else branded
            }
            else -> brandScheme(base, false, animatedPreset)
        }
    }
    // 应用内可强制浅/深色（独立于系统），系统栏图标颜色必须跟随最终主题。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    // 字号：通过放大 fontScale 让全部文本跟随，避免逐条改 Typography。
    // 支持历史枚举值（small/default/large）与新的连续数值（设置页滑杆）；非法值回退 1.0。
    val factor = (fontScale.toFloatOrNull() ?: when (fontScale) {
        "small" -> 0.9f
        "large" -> 1.15f
        else -> 1f
    }).coerceIn(0.85f, 1.3f)
    // 字体族：system 用系统默认，serif/mono 用内置族（无需引入外部字体资源）。
    val family = when (fontFamily) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * factor),
        LocalReducedMotion provides reducedMotion,
        LocalRoundedAvatars provides roundedAvatars,
        LocalBubbleCorner provides bubbleCorner,
        LocalReduceTransparency provides reduceTransparencyPref,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = RtcommTypography.withFontFamily(family),
            shapes = RtcommShapes,
            content = content,
        )
    }
}
