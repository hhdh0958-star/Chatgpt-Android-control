# ChatGPT Android Native Agent

这是为 Android 开发的实验性本地 Agent。它通过 Android 无障碍服务观察官方 ChatGPT 普通聊天，并在手机上执行已支持的操作。项目使用独立 APK，官方 ChatGPT 客户端无需重新签名。文件操作受 Android 目录授权约束，工具在普通应用权限下运行。

## 当前进度

- **0.24.5 已在 OPPO Reno12 上验证 QQ 搜索和结果回传。** 在官方 ChatGPT 普通聊天发送 `Search QQ for NativeAgentQA`，手机打开 QQ 搜索、填入并读回 `NativeAgentQA`，随后自动返回原聊天提交执行结果。这个流程验证到搜索词输入，尚未验证读取匹配项、选择联系人或发送 QQ 消息。
- 要单独打开 QQ，可以在普通聊天发送「打开 QQ」。0.24.1 已在真机验证 QQ 进入前台并返回 ChatGPT。普通聊天中的这些请求由手机上的无障碍服务识别和执行，ChatGPT 可以在原对话中根据执行回执答复。
- 已有手机本地文件、Python、虚拟 Bash、JavaScript ES 模块、Git，以及计算器、秒表页和倒计时操作。任意应用启动、指定闹钟、QQ 消息发送和自动打完一局斗地主仍在范围之外。

[下载 0.24.5 与本系列历史 APK](https://github.com/hhdh0958-star/Chatgpt-Android-control/releases/tag/v0.24.5) · [逐版本说明](CHANGELOG.md) · [APK 归档的 SHA-256](SHA256SUMS.txt)

## 普通聊天中的工具调用边界

当前由 Android 侧的 `ChatProbe` 创建 `FileSession`，并绑定任务 ID 和步数。没有本地会话时，聊天观察逻辑不会分发工具。已有执行器核对命令、权限和任务状态。执行结果以 `NA_RESULT` 聊天消息回填。部分固定手机指令由本机识别用户已发送的原句并执行，属于客户端功能。

要由普通 ChatGPT Android 会话主动调用，需要 OpenAI 为该会话提供经过授权的工具声明，并由官方客户端将该次调用交给同机 Agent，再以同一调用 ID 回传结果。Android 上的本地 Binder、Intent 或 HTTP 监听器只能提供接收端。官方自定义 MCP 连接面向网页端，ChatGPT 无法直接连接手机上的本地 MCP。独立的 OpenAI API 工具定义适用于由开发者自己发起的 API 会话。 [ChatGPT 的自定义 MCP 文档](https://help.openai.com/en/articles/12584461-connecting-custom-mcp-servers-in-chatgpt) · [API 工具调用文档](https://developers.openai.com/api/docs/guides/function-calling)

Reno12 上已核对官方 ChatGPT Android 1.2026.321 与 Agent 0.22.4 的安装信息。Android 公布的已安装组件中未找到可供普通聊天注册和调用 Native Agent 的入口。无障碍节点能证明文字来自 ChatGPT 窗口，但不携带服务端工具授权或调用 ID。后续验收需要查到由 ChatGPT 发出的真实调用事件，并在原会话收到同一调用 ID 的执行结果。 [Android 无障碍事件文档](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)

## 构建与安装

使用 JDK 17、Android API 36 的 `android.jar`、Android SDK Build Tools 和 PowerShell。输出路径建议使用 ASCII 字符。

```powershell
.\probe\build.ps1 -AndroidJar $androidJar -BuildTools $buildTools -OutputDirectory $outputDirectory
.\probe\build.ps1 -AndroidJar $androidJar -BuildTools $buildTools -OutputDirectory $outputDirectory -QqPrototype
.\test-device.ps1 -R8Jar $r8Jar -AndroidJar $androidJar
```

用 MT 管理器安装生成的 APK，并在手机设置中开启 **Native Agent Probe** 无障碍服务。首次自行构建会生成本地签名。覆盖已有安装时需要与原安装保持签名一致。依赖许可证和声明随对应源码目录保留。

使用本次发布的 APK 时，请在 MT 管理器中安装 `native-agent-0.24.5-qq-prototype.apk`，再在系统无障碍设置里开启 **Native Agent QQ Prototype**。原型使用独立包名 `dev.nativeagent.qqprototype`，可以与原版 Agent 并行安装。仅开启其中一个无障碍服务，避免两套服务同时响应。要停用原型，关闭它的无障碍服务即可。
