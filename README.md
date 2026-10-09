# rtcomm · Android 客户端

rtcomm 即时通讯客户端的 Android 前端，使用 Jetpack Compose 构建。

## 功能

- **会话与群聊**：文本、图片、视频、语音、文件消息；引用、编辑、撤回、转发、合并转发、多选；群名与群头像设置。
- **实时消息**：WebSocket 长连接 + 前台服务；FCM 推送兜底。
- **AI 助手**：多机器人、流式回复、图片/文件输入、深度思考。
- **自定义表情**：添加本地图片或 GIF，发出后按原始比例缩放显示。
- **Markdown 渲染**：消息气泡、AI 回复、引用、列表预览与搜索结果。
- **个性化**：主题色、深色 / AMOLED、字体与字号、聊天背景、气泡圆角、消息密度。
- **离线缓存**：Room 加密持久化；偏好本地保存并可同步服务端。

## 技术栈

Kotlin · Jetpack Compose (Material 3) · Navigation Compose · Room · OkHttp · Gson · Coil · Media3 · Firebase Messaging

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk = 34`）。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug     # debug
./gradlew assembleRelease   # release（R8 混淆 + 资源缩减，需配置 release 签名）
```

> 首次启动需在登录页填写服务器地址；源码不内置任何默认服务器地址。

## 签名

- **debug**：默认使用本机 `~/.android/debug.keystore`。
- **release**：必须在 `local.properties`（已被 `.gitignore` 忽略，请勿提交）或环境变量中提供正式 keystore；**未配置时 `assembleRelease` 会失败**（不会回退到 debug 签名）。

```properties
rtcomm.release.keystore=/absolute/path/to/release.keystore
rtcomm.release.storePassword=...
rtcomm.release.keyAlias=...
rtcomm.release.keyPassword=...
```

对应环境变量：`RTCOMM_RELEASE_KEYSTORE`、`RTCOMM_RELEASE_STORE_PASSWORD`、`RTCOMM_RELEASE_KEY_ALIAS`、`RTCOMM_RELEASE_KEY_PASSWORD`。

## 测试

```bash
./gradlew testDebugUnitTest
```

包含纯逻辑单测，以及 Robolectric + Roborazzi 截图回归测试。

## 许可证

MIT，见 [LICENSE](LICENSE)。
