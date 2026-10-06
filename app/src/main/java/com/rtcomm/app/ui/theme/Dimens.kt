package com.rtcomm.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 全局间距刻度。所有内边距 / 间隔从这里取值，避免各页随意写数值导致风格漂移。
 * 语义：xs 紧凑、sm 行内、md 卡片内、lg 屏幕边距、xl 分组间距。
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 全局圆角刻度。语义：
 * - [chip] 小徽章 / 代码块 / 内嵌小标签；
 * - [thumb] 缩略图 / 头像小图；
 * - [small] 小控件 / 输入区内元素；
 * - [medium] 列表卡片 / 一般容器；
 * - [large] 大卡片 / 资料卡；
 * - [extra] 对话框 / 主输入框；
 * - [sheet] 底部弹层顶角。
 * 之前各页硬编码 6/7/8/10/12/14/16/20/22/24dp 无节奏，统一收敛到这套刻度。
 */
object Corner {
    val chip = 6.dp
    val thumb = 10.dp
    val small = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val extra = 24.dp
    val sheet = 22.dp
}

/**
 * 全局透明度刻度。集中语义化各处散落的 alpha 魔数，保证「同一语义同一透明度」。
 * - scrim* 蒙层由浅到深；divider 分割线；disabled 禁用/次要；hairline 极淡描边/底纹。
 */
object Alpha {
    const val hairline = 0.10f
    const val scrimSoft = 0.28f
    const val disabled = 0.40f
    const val divider = 0.45f
    const val scrimMedium = 0.50f
    const val scrimStrong = 0.85f
    const val scrimFull = 0.96f
}
