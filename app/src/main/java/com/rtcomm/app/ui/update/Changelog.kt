package com.rtcomm.app.ui.update

/** 单条更新记录。 */
data class ChangelogItem(val category: String, val text: String)

/** 一个版本的更新日志。 */
data class ChangelogVersion(
    val versionName: String,
    val versionCode: Int,
    val date: String,
    val items: List<ChangelogItem>,
)

/**
 * 历史更新内容（版本倒序）。
 *
 * 静态内置：更新日志属于随包发布的产物，避免额外接口与网络依赖；
 * 新增版本时在此追加即可（最新在最前）。
 */
object Changelog {
    const val NEW = "新增"
    const val IMPROVE = "优化"
    const val FIX = "修复"

    val versions: List<ChangelogVersion> = listOf(
        ChangelogVersion(
            versionName = "2.3.4",
            versionCode = 60,
            date = "2026-10-07",
            items = listOf(
                ChangelogItem(NEW, "新增合并转发：多选消息后可合并为一条「聊天记录」转发，点开可查看全部内容"),
                ChangelogItem(IMPROVE, "转发对话框支持「合并转发 / 逐条转发」切换"),
                ChangelogItem(IMPROVE, "点击引用消息不再跳转到原消息，避免误触"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.3.3",
            versionCode = 59,
            date = "2026-10-07",
            items = listOf(
                ChangelogItem(IMPROVE, "滑动操作分离：左滑引用回复、右滑进入多选；多选选中气泡改用高对比描边并叠加主色，深色主题下也能一眼分辨"),
                ChangelogItem(IMPROVE, "界面外预览统一：会话列表、搜索结果与系统通知中的表情消息显示为「动画表情」"),
                ChangelogItem(IMPROVE, "自定义表情按原始尺寸显示，小表情不再被放大模糊"),
                ChangelogItem(NEW, "发起会话支持按用户 ID 精确搜索并添加好友"),
                ChangelogItem(NEW, "群聊支持设置群头像与群名称（群主或群内管理员）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.3.2",
            versionCode = 58,
            date = "2026-10-07",
            items = listOf(
                ChangelogItem(IMPROVE, "适配服务端权限撤销：账号被禁用 / 改密 / 踢下线时，实时连接以关闭码 4003 断开，客户端立即退出登录并回到登录页（不再反复重连）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.3.1",
            versionCode = 57,
            date = "2026-10-07",
            items = listOf(
                ChangelogItem(FIX, "修复自定义表情发送后显示为文件：发送时按扩展名指定图片 MIME，正确渲染为表情"),
                ChangelogItem(FIX, "修复 AI 助手选择上传文件时卡死：改为限长读取（上限 256KB），不再把大文件整个读进内存"),
                ChangelogItem(IMPROVE, "性能优化：纯文本消息走 Markdown 快速渲染，会话列表预览与引用预览加缓存，减少滚动卡顿"),
                ChangelogItem(FIX, "安全：文件下载必须登录并校验归属（修复越权下载）；release 构建强制使用正式签名，不再回退调试签名"),
                ChangelogItem(FIX, "安全：更新下载地址限制为同源或白名单主机；通知跳转校验发起方，防外部应用越权导航"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.3.0",
            versionCode = 56,
            date = "2026-10-06",
            items = listOf(
                ChangelogItem(NEW, "新增自定义表情：可添加本地图片或 GIF，发出后按原始比例缩小显示（最长边 120dp，GIF 自动播放）；长按图片消息可一键「添加为表情」"),
                ChangelogItem(NEW, "Markdown 全局渲染：普通消息、AI 用户消息、引用预览、会话列表最后一条、搜索结果均按 Markdown 呈现（保留粗体/斜体/代码/链接等行内样式）"),
                ChangelogItem(FIX, "优化未读逻辑：翻看历史时不再把新消息误标已读，与「N 条新消息」提示一致；重连后自动校正会话未读/已读"),
                ChangelogItem(FIX, "修复引用消息：不再允许回复未发送成功的本地消息（避免发送失败死循环）；乐观气泡即时显示引用内容；编辑消息后引用同步更新"),
                ChangelogItem(IMPROVE, "引用预览区分 [图片]/[视频]/[语音]/[文件]，超长内容省略号截断"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.18",
            versionCode = 55,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(NEW, "设置页改为二级菜单：外观 / 聊天 / 背景 / 通知 / 动效 / 连接与 AI，点击进入查看详细选项"),
                ChangelogItem(IMPROVE, "Markdown 全面增强：代码块一键复制 + 语言标签、表格（含列对齐）、任务列表、粗斜体、图片与裸链接、引用与列表合并"),
                ChangelogItem(FIX, "修复 AI 流式回复时上部文本闪烁（移除列表项位移动画，改为逐项原地更新）"),
                ChangelogItem(FIX, "修复品牌主题色下开关滑块显示黑色：开关选中滑块改用表面色，轨道用主题色"),
                ChangelogItem(IMPROVE, "AI 上传图片/文件改为与聊天一致的底部菜单；文件仅只读参考、不执行"),
                ChangelogItem(IMPROVE, "空状态新增快捷操作按钮；开关行整行可点；文件列表整行可点下载；长昵称/文件名自动省略"),
                ChangelogItem(IMPROVE, "主题色选择器选中项增加高亮环"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.17",
            versionCode = 54,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(NEW, "AI 助手支持文件附件：可发送文本/代码/CSV 等文件供 AI 阅读（仅只读参考、绝不执行，二进制文件拒绝）"),
                ChangelogItem(IMPROVE, "深度思考与「展示思考内容」移到机器人配置面板并按机器人保存；关闭思考（none）时展示开关不可用，开启后为低强度推理"),
                ChangelogItem(FIX, "修复默认模型 deepseek-flash 无法上传图片：按模型能力放行图像输入（多模态模型自动启用）"),
                ChangelogItem(IMPROVE, "AI 机器人列表支持搜索；AI 气泡圆角跟随全局设置"),
                ChangelogItem(IMPROVE, "多媒体：语音支持拖动进度定位，视频改为全屏沉浸式播放"),
                ChangelogItem(FIX, "修复 AI 流式回复剧烈闪烁（改为逐项更新，避免整表重组）"),
                ChangelogItem(FIX, "修复「添加账号」后被困在登录页：登录页新增返回并恢复已保存账号，重启后同样可用"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.16",
            versionCode = 53,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(NEW, "统一弹窗入场动画：所有对话框（兑换码、修改资料、设备注册、机器人配置、管理后台等）出现时淡入并轻微放大"),
                ChangelogItem(IMPROVE, "管理后台体验优化：页签内容淡切、加载进度淡入、空状态图标化、搜索框支持回车与一键清空、账号操作改为底部动作面板"),
                ChangelogItem(IMPROVE, "AI 流式输出更顺滑：新回复柔和淡入、内容增长不再抖动、自动跟随滚动更稳、文字出现更连续"),
                ChangelogItem(IMPROVE, "聊天细节：长按消息触感反馈、顶栏副标题淡切、消息「已编辑」标签淡入"),
                ChangelogItem(IMPROVE, "账户权限差异：管理后台仅管理员可见，越权访问给出可返回的明确提示；AI 页空状态文案按角色区分"),
                ChangelogItem(IMPROVE, "实时终端、头像全屏、头像上传遮罩等自绘弹层补齐淡入动画"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.15",
            versionCode = 52,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(IMPROVE, "全面补齐界面动画：卡片按压反馈、列表项进入、条件内容展开、状态切换统一为平滑过渡"),
                ChangelogItem(IMPROVE, "「加载 → 内容」统一淡入淡出：存储管理、个人资料额度、检查更新、会话首屏、图片/视频加载等"),
                ChangelogItem(IMPROVE, "「点击出现」的二级内容补齐展开动画：Markdown 工具栏、附件/快捷回复、待发送图片、设备命令结果等"),
                ChangelogItem(IMPROVE, "聊天细节：发送状态图标淡切、多选选中描边过渡、多选顶栏淡切、聊天壁纸切换淡切"),
                ChangelogItem(IMPROVE, "管理后台页签切换、邀请码空/列表、错误横幅出现均改为平滑过渡"),
                ChangelogItem(IMPROVE, "首屏（启动动画结束 → 登录页/主界面）淡入；错误提示统一为可读横幅并带出现动画"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.14",
            versionCode = 51,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(FIX, "修复分组卡片顶部/底部出现的「线条」：列表行原来的不透明底色盖住了卡片，只露出上下各 4dp 卡片色"),
                ChangelogItem(IMPROVE, "设置页、关于页统一为「分组标题 + 卡片」样式，去掉多余分隔线，与「我的」页保持一致"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.13",
            versionCode = 50,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(FIX, "修复「我的」资料卡与下方分组卡片左右内缩不一致（16dp / 12dp）造成的轻微错位，卡片现已对齐"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.12",
            versionCode = 49,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(IMPROVE, "「我的」资料卡恢复为点击进入「个人资料」二级页；移除二级页里重复的资料卡，两张卡片合并为一张"),
                ChangelogItem(FIX, "修复 Markdown 显示：工具栏可插入的删除线 ~~、链接 [文本](链接)、有序列表 1. 、分隔线 --- 此前都显示成原文，现已正确渲染"),
                ChangelogItem(FIX, "修正 Markdown 斜体解析（含 ** 粗体时不再误判，单个 * 号保持不变）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.11",
            versionCode = 48,
            date = "2026-10-05",
            items = listOf(
                ChangelogItem(FIX, "修复点击底部标签切到「会话 / AI」时会自动打开上次的会话（标签恢复误触发）"),
                ChangelogItem(IMPROVE, "主界面标签切换改为平滑的交叉淡入淡出"),
                ChangelogItem(IMPROVE, "设置页重新分组：外观 / 文字 / 消息样式 / 动效 / 聊天背景 / 主界面背景 / 通知 / 聊天与 AI / 存储"),
                ChangelogItem(IMPROVE, "长按消息、会话、AI 回复的操作菜单改为带图标的底部动作面板，收发更有动画"),
                ChangelogItem(IMPROVE, "多处「点击出现」的二级内容与「加载 → 内容」补齐淡入 / 展开动画"),
                ChangelogItem(IMPROVE, "合并「我的」与「个人资料」顶部卡片，移除冗余的个人资料页"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.10",
            versionCode = 47,
            date = "2026-10-02",
            items = listOf(
                ChangelogItem(FIX, "修复浅色主题下部分主题色按钮文字与在线/告警状态点对比度不足的问题"),
                ChangelogItem(FIX, "修复聊天页展开后点击气泡与输入栏之间的空白会误触到下层其它会话"),
                ChangelogItem(FIX, "修复弱网下发送失败的消息在网络恢复后被自动重复发送（失败消息改为仅手动重发）"),
                ChangelogItem(FIX, "修复快速切换会话时旧请求把数据写回新会话，导致标题/草稿/成员串台"),
                ChangelogItem(FIX, "修复聊天底部输入区与提示条被系统导航栏或输入法遮挡"),
                ChangelogItem(FIX, "修复转发「待上传」媒体会生成坏消息，改为跳过并提示等待上传完成"),
                ChangelogItem(FIX, "修复图片查看器在查看期间收到新消息后页码/图片错位，改为按图片锚定"),
                ChangelogItem(FIX, "管理后台操作、切换账号/服务器、清空缓存等破坏性操作增加二次确认与影响说明"),
                ChangelogItem(FIX, "网络异常统一为中文可读提示，不再直接显示底层英文异常"),
                ChangelogItem(FIX, "修复通话记录页加载遮罩盖住已有缓存、设备页事件风暴导致列表反复整表刷新"),
                ChangelogItem(IMPROVE, "主界面标签切换改为平滑的交叉淡入淡出，不再有缩放回弹的生硬感"),
                ChangelogItem(IMPROVE, "大屏/平板内容限宽居中，聊天气泡不再拉满整屏"),
                ChangelogItem(IMPROVE, "语音消息改用共享播放器、视频缩略图与时长本地缓存，滚动更流畅、更省电"),
                ChangelogItem(IMPROVE, "优化消息列表重组，长列表滚动更顺滑"),
                ChangelogItem(IMPROVE, "本地安全加固：列表缓存加密、数据库字段绑定上下文防篡改、登录态迁移到自研 Keystore 加密存储、更新包复用前强制 SHA-256 校验、缓存上限裁剪到热路径"),
                ChangelogItem(IMPROVE, "拆分超大聊天/AI 界面文件、收敛设计 token 与通用文案，提升可维护性"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.9",
            versionCode = 46,
            date = "2026-09-27",
            items = listOf(
                ChangelogItem(FIX, "修复头像裁切：平移取景后点「确定」仍按初始居中裁切的问题，现在预览与最终裁切完全一致"),
                ChangelogItem(IMPROVE, "AI 直聊单次加载的历史消息上限从 50 条提升到 200 条"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.5",
            versionCode = 42,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(FIX, "详情页（设置/存储/关于等）进入动画缺失：恢复为清晰的水平推进，且与返回动画严格对称"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.4",
            versionCode = 41,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(NEW, "设置 → 外观新增「减少毛玻璃（省电/低端机）」开关，可关闭标题栏实时模糊"),
                ChangelogItem(IMPROVE, "毛玻璃标题栏按设备能力自动降级：Android 11 及以下、低内存设备不再白跑一次全屏离屏合成"),
                ChangelogItem(FIX, "统一详情页（设置/存储/关于等）的返回动画并与标签页协调，不再出现多种/随机的返回动画"),
                ChangelogItem(FIX, "修正历史更新日志中多个版本的发布日期"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.3",
            versionCode = 40,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(FIX, "修复 9 种内置聊天壁纸完全不显示的问题（修饰链未挂到渲染节点）"),
                ChangelogItem(FIX, "修复头像裁切连续旋转/镜像可能因大图累积而崩溃，退出即释放内存"),
                ChangelogItem(FIX, "撤回消息时本地缓存中的发送者/文件/引用内容也一并清除"),
                ChangelogItem(FIX, "前台服务被系统重启后不再因缓存未初始化而丢失消息落库"),
                ChangelogItem(FIX, "「正在输入」状态在清空输入或停止输入后正确消失"),
                ChangelogItem(FIX, "按时间自动深色现在会跨时段边界自动切换"),
                ChangelogItem(FIX, "AI 对话 /reset 增加二次确认，避免误清上下文"),
                ChangelogItem(IMPROVE, "冷启动更快：加密存储与偏好读取移出主线程"),
                ChangelogItem(IMPROVE, "AI 流式回复与长聊天记录更顺畅：Markdown 解析缓存、搜索/多选减少重组"),
                ChangelogItem(IMPROVE, "安全与构建：release 恢复混淆收缩、安全存储改用稳定版、签名密钥支持外部配置"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.2",
            versionCode = 39,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(FIX, "修复会话卡片展开动画：全屏聊天背景层不再第一帧就盖住共享元素展开，改为随展开进度淡入"),
                ChangelogItem(IMPROVE, "AI 助手、设备、文件页的毛玻璃标题栏固定显示，滚动时不会被翻过（与首页一致）"),
                ChangelogItem(FIX, "切换标签页时底部功能按钮（发起 / 新建机器人 / 注册设备 / 上传文件）不再抖动，固定在原位、单独淡入淡出"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.1",
            versionCode = 38,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(NEW, "「我的」页入口改为圆角分组卡片（账户/通用/关于/管理），与设置页风格统一"),
                ChangelogItem(NEW, "主界面与聊天界面的背景暗化、模糊可分别自定义（设置 → 外观）"),
                ChangelogItem(IMPROVE, "各标签页（会话/AI/设备/文件/我的）切换动画更灵动：弹性缩放入场 + 轻微上浮，退场带纵深视差"),
                ChangelogItem(IMPROVE, "详情页（设备详情/设置等）推进与返回动画更顺滑（弹簧落位）"),
                ChangelogItem(FIX, "修复会话卡片展开动画：会话卡片改用与 AI 助手一致的结构，按压缩放与共享元素展开都恢复正常"),
                ChangelogItem(FIX, "主界面「会话」毛玻璃标题栏固定显示，滑动时不再被挤压消失"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.26.0",
            versionCode = 37,
            date = "2026-09-26",
            items = listOf(
                ChangelogItem(FIX, "修复会话列表/设备列表卡片按压动画残缺：现在整张卡片一起缩放，底色不再「定住不动」"),
                ChangelogItem(IMPROVE, "聊天列表与历史消息滚动/加载更快：本地缓存数据库补齐排序索引"),
                ChangelogItem(IMPROVE, "冷启动解密本地缓存显著提速（复用 Keystore 密钥，不再逐字段重新加载）"),
                ChangelogItem(IMPROVE, "草稿与搜索词更抗丢失：进程被系统回收后返回仍保留（AI 草稿、兑换码/邀请码、搜索词、分页游标）"),
                ChangelogItem(IMPROVE, "通知按会话合并为一条对话式通知（MessagingStyle），同一会话不再刷屏"),
                ChangelogItem(IMPROVE, "气泡圆角改由主题统一下发，长消息列表滚动更顺滑"),
                ChangelogItem(IMPROVE, "无障碍：可点击头像补上可读标签，读屏不再念「未命名按钮」"),
                ChangelogItem(IMPROVE, "构建工具链升级：kapt→KSP、版本目录（Version Catalog）、release 开启 R8（安装包体积大幅减小）、新增 JVM 单元测试"),
                ChangelogItem(FIX, "清理误导性的半翻译英文资源"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.25.0",
            versionCode = 36,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "聊天窗口与 AI 对话标题栏改为毛玻璃（真实背景高斯模糊，Android 12+）"),
                ChangelogItem(NEW, "AI 深度思考：可折叠展示模型的思考过程，并可在设置中选择是否开启思考 / 是否展示思考内容"),
                ChangelogItem(NEW, "设置自定义背景图时显示处理中的加载动画"),
                ChangelogItem(IMPROVE, "底部导航改为透明并与背景融合，去掉导航栏上方的白条"),
                ChangelogItem(IMPROVE, "列表卡片圆角统一（会话/设备/文件/通话/存储/管理后台）"),
                ChangelogItem(IMPROVE, "高刷新率适配：使用与当前分辨率一致的最高刷新率模式"),
                ChangelogItem(IMPROVE, "聊天滑动更顺畅：Markdown 行内解析缓存、列表按类型复用、启动初始化移出主线程"),
                ChangelogItem(FIX, "修复会话列表滚动卡顿（移除每个头像各自订阅状态）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.24.0",
            versionCode = 35,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "图片查看器支持双击 / 双指缩放与平移，翻页手势不受影响"),
                ChangelogItem(NEW, "高刷新率适配：使用设备支持的最高刷新率，滚动与动画更跟手"),
                ChangelogItem(IMPROVE, "全局 UI 风格统一：顶栏、列表卡片圆角与间距、设置分组卡片一致"),
                ChangelogItem(IMPROVE, "聊天滑动更顺畅：Markdown 行内解析缓存、列表按类型复用"),
                ChangelogItem(IMPROVE, "启动初始化移到后台线程，减少首帧卡顿"),
                ChangelogItem(IMPROVE, "深色时段滑杆增加「开始 / 结束」标签"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.23.0",
            versionCode = 34,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "自定义主色（HSV 取色器）；主色预设扩充至 9 种"),
                ChangelogItem(NEW, "深色「按时间」可自定义深色时段"),
                ChangelogItem(NEW, "自定义背景支持暗化与模糊（模糊需 Android 12+）；新增 4 款内置聊天背景"),
                ChangelogItem(NEW, "字体族（系统 / 衬线 / 等宽）、聊天气泡圆角、圆角方形头像开关"),
                ChangelogItem(NEW, "应用图标支持 Android 13+ 主题图标（monochrome）"),
                ChangelogItem(IMPROVE, "设置页改为圆角分组卡片，顶部新增主题实时预览；字号改为连续滑杆"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.22.0",
            versionCode = 33,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "图片查看器支持下滑关闭；打开时背景淡入、图片轻微放大"),
                ChangelogItem(IMPROVE, "全局动效统一：按压 / 发送等反馈改用弹簧（spring），更顺滑不生硬，并统一遵循「减少动态效果」"),
                ChangelogItem(IMPROVE, "顶层标签切换改为淡入淡出过渡，详情页保留水平推进，方向感更清晰"),
                ChangelogItem(IMPROVE, "列表加载改为骨架屏微光，代替单个转圈；未读角标数字滚动过渡"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.21.1",
            versionCode = 32,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(FIX, "修复动态头像（GIF/WebP）不播放、只显示首帧的问题：将 Coil 的 ImageLoaderFactory 移到 Application 注册动态图解码器"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.21.0",
            versionCode = 31,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "AI 对话支持发送图片（≤10MB），仅在多模态模型下可用，非多模态模型会明确提示"),
                ChangelogItem(NEW, "系统提示词默认注入当前时间（按客户端时区），模型可正确回答日期 / 时间 / 星期"),
                ChangelogItem(NEW, "群聊 AI 助手：回复机器人消息即可触发（无需 @）；发送图片后 @机器人 可让多模态模型看图"),
                ChangelogItem(IMPROVE, "群聊 AI 助手：使用群摘要 + 最近消息作为上下文，按昵称区分发言人，回复引用被回应的消息"),
                ChangelogItem(IMPROVE, "AI 流式输出：批量合并增量并始终跟随底部，长回复不再被挤出屏幕"),
                ChangelogItem(IMPROVE, "动态头像上限放宽至 10MB（静态头像 5MB）"),
                ChangelogItem(FIX, "修复流式接口在请求体读取完成后被误判为断开、导致回复总是回退为非流式的问题"),
                ChangelogItem(FIX, "修复 AI 图片输入对 HEIC 等格式及超大图片的兼容处理"),
                ChangelogItem(FIX, "修复群聊 / 会话内 AI 回复未计入 AI 用量与额度的问题"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.20.0",
            versionCode = 30,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "自定义聊天背景图与主界面背景图，可选择本地图片或清除"),
                ChangelogItem(NEW, "存储管理：分类查看并清理图片缓存、聊天数据库、下载、安装包与临时文件"),
                ChangelogItem(NEW, "视频消息显示首帧缩略图与时长"),
                ChangelogItem(IMPROVE, "图片 / 头像 / 视频首帧本地磁盘缓存与预取，列表先读本地再联网校验，重进页面秒显"),
                ChangelogItem(IMPROVE, "切换账号不再清理缓存，仅删除账号时清理并注销令牌"),
                ChangelogItem(IMPROVE, "AI 流式输出改为批量合并 + 始终跟随底部，长回复不再被挤出屏幕"),
                ChangelogItem(FIX, "修复历史头像不显示：新增按历史头像 id 取图接口，不再全部指向当前头像"),
                ChangelogItem(FIX, "修复动态头像上传无反馈、失败无提示的问题"),
                ChangelogItem(FIX, "修复更新到新版本后仍提示更新（本地版本改用 PackageManager，仅服务端更高时提示）"),
                ChangelogItem(FIX, "修复已下载安装包后仍重复要求下载"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.19.0",
            versionCode = 29,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "启动自动检查更新：展示更新内容，支持「稍后 / 跳过此版本 / 立即更新」，最多跳过三个版本"),
                ChangelogItem(NEW, "图片 / 视频发送即时占位：缩略图、文件大小、上传进度与百分比、取消与重试，进度持久化并支持重启续传"),
                ChangelogItem(NEW, "「我的」拆分为个人资料 / 设置 / 关于三个独立页面"),
                ChangelogItem(NEW, "会话内图片统一查看器：左右滑动浏览，支持保存到相册"),
                ChangelogItem(NEW, "多账号一键切换（加密保存，无需重新输入密码）"),
                ChangelogItem(NEW, "会话置顶、消息日期分隔、会话内搜索、每会话聊天背景、聊天信息聚合、底部未读角标"),
                ChangelogItem(NEW, "QQ 式头像体系：查看 / 修改 / 历史头像，裁剪支持圆形遮罩与三分参考线"),
                ChangelogItem(NEW, "关于页新增历史更新内容"),
                ChangelogItem(IMPROVE, "图片 / 视频消息无边框、按原始比例显示，最大高度限制为屏幕 60%"),
                ChangelogItem(IMPROVE, "上传头像显示加载动画；动态头像上限放宽至 5MB（后续为 10MB）"),
                ChangelogItem(FIX, "修复已下载安装包后仍重复要求下载（复用本地已下载包）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.18.0",
            versionCode = 28,
            date = "2026-09-25",
            items = listOf(
                ChangelogItem(NEW, "启动自动检查更新：弹窗展示更新内容，支持「稍后 / 跳过此版本 / 立即更新」，最多跳过三个版本"),
                ChangelogItem(NEW, "图片/视频发送即时占位：缩略图、文件大小、上传进度与百分比、取消与重试，逐文件独立上传，不阻塞输入"),
                ChangelogItem(NEW, "媒体上传进度持久化：退出会话或旋转屏幕不丢失，重启后自动续传"),
                ChangelogItem(NEW, "「我的」拆分为个人资料 / 设置 / 关于三个独立页面"),
                ChangelogItem(NEW, "会话内图片统一查看器：左右滑动浏览，支持保存到相册"),
                ChangelogItem(NEW, "多账号一键切换：加密保存多个账号，无需重新输入密码"),
                ChangelogItem(NEW, "会话置顶、消息日期分隔、会话内搜索、每会话聊天背景、聊天信息聚合入口"),
                ChangelogItem(NEW, "QQ 式头像体系：查看 / 修改 / 历史头像，裁剪支持圆形遮罩与三分参考线"),
                ChangelogItem(NEW, "关于页新增历史更新内容"),
                ChangelogItem(IMPROVE, "图片/视频消息无边框、按原始比例显示，最大高度限制为屏幕 60%"),
                ChangelogItem(IMPROVE, "上传头像时显示加载动画，不再无反馈等待"),
                ChangelogItem(IMPROVE, "动态头像上限放宽至 5MB"),
                ChangelogItem(FIX, "修复已下载安装包后仍重复要求下载的问题（复用本地已下载包）"),
                ChangelogItem(FIX, "修复更新到新版本后仍提示更新的问题：本地版本号改用 PackageManager.longVersionCode，仅在服务端 versionCode 更大时提示"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.17.0",
            versionCode = 27,
            date = "2026-09-24",
            items = listOf(
                ChangelogItem(NEW, "主题色预设、按时间自动深浅色、字号、消息密度、动画偏好"),
                ChangelogItem(NEW, "头像历史与服务端存储、上传前 1:1 裁切、GIF/WebP 动态头像"),
                ChangelogItem(NEW, "快捷回复、免打扰时段、通知声音/震动、12/24 小时制"),
                ChangelogItem(FIX, "修复 AI 会话首次发送时流式回复被历史回填覆盖、需重进页面才显示的问题"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.15.0",
            versionCode = 25,
            date = "2026-09-13",
            items = listOf(
                ChangelogItem(NEW, "AI 会话复用聊天列表的共享元素展开动画；停止生成、重新生成、/ 命令面板"),
                ChangelogItem(NEW, "全局列表顶部栏随滚动收起；多处列表与按钮动画"),
                ChangelogItem(IMPROVE, "AI 空状态引导与长按复制"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.13.0",
            versionCode = 23,
            date = "2026-09-13",
            items = listOf(
                ChangelogItem(NEW, "AI 机器人可见性（私有 / 公开 / 指定用户）"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.12.1",
            versionCode = 22,
            date = "2026-09-13",
            items = listOf(
                ChangelogItem(NEW, "/reset 与 /compact 命令，自动上下文压缩"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.12.0",
            versionCode = 21,
            date = "2026-09-13",
            items = listOf(
                ChangelogItem(NEW, "AI 流式输出与机器人配置面板"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.11.0",
            versionCode = 20,
            date = "2026-09-13",
            items = listOf(
                ChangelogItem(NEW, "账号隔离的 Room 缓存、Keystore 字段加密、消息幂等与重试恢复"),
                ChangelogItem(NEW, "WebSocket 连接代际、文件大小/分片校验与 EXIF 去敏"),
                ChangelogItem(NEW, "通知隐私与后台更新下载"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.2.0",
            versionCode = 12,
            date = "2026-09-11",
            items = listOf(
                ChangelogItem(NEW, "应用内检查更新与自动安装"),
                ChangelogItem(NEW, "应用内消息横幅、启动品牌加载动画"),
            ),
        ),
        ChangelogVersion(
            versionName = "2.1.0",
            versionCode = 10,
            date = "2026-08-10",
            items = listOf(
                ChangelogItem(NEW, "JWT 加密存储、消息乐观发送与重试、已读闭环、消息操作（回复/复制/撤回）"),
                ChangelogItem(NEW, "群内 @ 补全、Markdown 原生渲染、图片内联预览与音视频流播"),
                ChangelogItem(NEW, "本地通知与前台服务、跨重启断点续传"),
            ),
        ),
    )
}
