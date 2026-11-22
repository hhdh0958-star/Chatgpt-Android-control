# ChatGPT Android Native Agent

这是为 Android 开发的实验性本地 Agent。它通过 Android 无障碍服务观察官方 ChatGPT 普通聊天，并在手机上执行已支持的操作。项目使用独立 APK，官方 ChatGPT 客户端无需重新签名。文件操作受 Android 目录授权约束，工具在普通应用权限下运行。

## 当前进度

- **0.22.4 真机验收。** 在 ChatGPT 原输入框键入 `Open QQ Search` 并发送后，Reno12 打开 QQ 搜索、核对页面，再回原聊天报告成功。中文「帮我打开 QQ 搜索」走相同入口，本轮真机实测英文。
- **0.23 开发构建。** 增加 QQ 搜索词输入和读回代码。构建与 Android 协议回归通过，实际 QQ 输入仍待验收。
- 已有手机本地文件、Python、虚拟 Bash、JavaScript ES 模块、Git，以及计算器、秒表页和倒计时操作。任意应用启动、指定闹钟、QQ 消息发送和自动打完一局斗地主仍在范围之外。

[逐版本说明](CHANGELOG.md) · [APK 归档的 SHA-256](SHA256SUMS.txt)

## 构建与安装

使用 JDK 17、Android API 36 的 `android.jar`、Android SDK Build Tools 和 PowerShell。输出路径建议使用 ASCII 字符。

```powershell
.\probe\build.ps1 -AndroidJar $androidJar -BuildTools $buildTools -OutputDirectory $outputDirectory
.\test-device.ps1 -R8Jar $r8Jar -AndroidJar $androidJar
```

用 MT 管理器安装生成的 APK，并在手机设置中开启 **Native Agent Probe** 无障碍服务。首次自行构建会生成本地签名。覆盖已有安装时需要与原安装保持签名一致。依赖许可证和声明随对应源码目录保留。
