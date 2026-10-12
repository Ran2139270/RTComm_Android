# R8 保活规则（release 已开启 isMinifyEnabled/isShrinkResources）。

# ---- Gson：模型类靠反射反序列化 ----
# 只对「真正经由 Gson 反射读写」的 DTO 显式保活字段名与无参构造。
# 之前是 `-keepclassmembers class com.rtcomm.app.data.**`，会把 Api/AppState/AuthStore/
# FileTransfer/Room Entity 等实现类的私有字段名一并保活，R8 无从混淆、包体偏大。
# 名单来源：data/Models.kt 的全部顶层类 + AuthStore.SavedAccount（同样用 Gson 存账号）。
# 新增 DTO 时记得在此追加，否则 release 下字段名会被混淆、JSON 解析失败。
-keepclassmembers class com.rtcomm.app.data.AddMembersReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminConversation { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminConversationsResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminMessagesResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminStats { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminStatsResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminUser { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AdminUsersResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AiChatLog { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AiChatLogsPage { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AiContextInfo { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AiQuota { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AiStreamChunk { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AppUpdateInfo { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AuthLoginReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AuthLoginResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AvatarItem { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AvatarsResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.AvatarUpsertResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.BotConfig { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.BotDetail { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.BotDetailWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.BotSummary { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.BotsWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CallLog { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CallLogsWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ChatBotReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ChatBotResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CommandReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CommandResult { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CompactAiResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.Conversation { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ConversationMember { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ConversationsWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ConversationWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CreateBotReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CreateConversationReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CreateRedeemCodesResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CreateRoomReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.CreateRoomResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.Device { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.DevicesWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.DeviceWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.EditMessageReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ErrBody { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ErrDetail { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.FileInitResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.FileMeta { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.FileMetaWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.InviteCheckResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.InviteCode { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.InviteCodesResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.InviteStats { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.Message { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessageCursor { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessageFile { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessagePage { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessageSearchHit { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessageSearchResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.MessageWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.PreferencesResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ProviderPreset { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ProviderSettings { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ProvidersSettingsResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ProvidersWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.PublicUser { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ReadAllResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RedeemCode { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RedeemCodesResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RedeemResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RegisterDeviceReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RegisterReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RemoteCommand { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RemoteCommandsWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RemoteSession { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.RemoteSessionsWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ReplyMessage { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.ResetAiResp { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.SendMessageReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.SendState { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.SummariesWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.Summary { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.SummaryWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UpdateBotConfigReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UpdateBotVisibilityReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UpdateProfileReq { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UploadStatus { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UsersWrap { <init>(); <fields>; }
-keepclassmembers class com.rtcomm.app.data.UserWrap { <init>(); <fields>; }
# AuthStore 内嵌的「已保存账号」同样由 Gson 读写。
-keepclassmembers class com.rtcomm.app.data.AuthStore$SavedAccount { <init>(); <fields>; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- Kotlin 协程：缺失的可选诊断类无需告警 ----
-dontwarn kotlinx.coroutines.**
