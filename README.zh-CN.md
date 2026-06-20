# Vertex Proxy Android APK 构建版

本仓库是一个 Android APK 构建工程，用来把本地 Vertex/Gemini 代理封装成手机上的本地服务。安装后，其他 Android agent 可以通过 OpenAI 兼容接口访问本机代理。

本 Android 构建版基于本地源项目 `E:\project\vertex-master` 制作。

## APK 信息

- APK 文件：`vertex-proxy-local-proxy.apk`
- 包名：`com.local.vproxy`
- 当前版本：`0.17-preserve-nodes`
- API Base URL：`http://127.0.0.1:2156/v1`
- 管理后台：`http://127.0.0.1:2156/admin/`

APK 不内置默认 API Key，也不内置默认代理节点。首次启动后，管理员密码、API Key、节点、模型和运行配置会保存在 App 私有目录中。

## 数字签名声明

此 APK 使用本构建仓库中的本地 Android debug keystore 签名，不是上游项目发布签名，也不是 Google Play / 正式发布签名。

- Keystore 文件：`debug.keystore`
- 签名别名：`androiddebugkey`
- 证书所有者 / 来源：`CN=Android Debug, O=Android, C=US`
- 创建时间：`2026-06-20 12:48:43 CST`
- SHA1：`4A:D1:FE:99:5B:47:B5:27:C0:8E:1E:41:06:41:43:ED:A8:DB:C5:B3`
- SHA256：`9D:89:11:EC:86:2C:1A:C6:2E:A4:F6:84:D6:CF:2F:1D:94:2B:72:74:AA:12:EF:F7:38:6B:99:04:1D:C8:72:D6`

请把此 APK 视为本地测试构建。如果要公开分发，建议使用你自己的正式 release keystore 重新签名。

## 基本使用方法

1. 在 Android 手机上安装 `vertex-proxy-local-proxy.apk`。
2. 打开 `Vertex Proxy` App。
3. 输入管理员密码，点击“保存并启动服务”。
4. 按系统提示允许通知、电池优化白名单、精确闹钟等权限。
5. 如果手机系统后台限制较强，还需要在系统设置中给此 App 开启：
   - 允许后台运行
   - 自启动
   - 电池策略选择“无限制”或“不优化”
   - 后台任务锁定，避免被清理
6. 用浏览器打开管理后台：
   `http://127.0.0.1:2156/admin/`
7. 在管理后台添加 API Key。
8. 在外部 agent 中填写：
   - Base URL：`http://127.0.0.1:2156/v1`
   - API Key：管理后台创建的 Key
   - 模型示例：`gemini-3.5-flash`

## 推荐先导入代理节点

强烈建议先导入可用代理节点，再进行模型调用测试。很多手机网络环境无法直接稳定访问 Google / reCAPTCHA 相关地址，如果不配置节点，可能会出现超时、无响应或必须切回日志页才继续的现象。

推荐流程：

1. 打开管理后台的节点 / 代理页面。
2. 导入订阅链接或节点列表。
3. 启用可用节点。
4. 建议开启并发节点池：
   - `parallel_pool_enabled`: `true`
   - `parallel_pool_size`: `4`
5. 在管理后台测试节点可用性，删除或禁用失败节点。
6. 再测试 `/v1/models` 和一条简短聊天请求。

如果手机网络不能直连 Google 相关端点，不建议依赖空的 `proxy_url`。

## 后台运行说明

此 APK 为了尽量适配激进的 Android 后台冻结策略，加入了以下保活手段：

- 前台服务
- wakelock
- watchdog 闹钟
- 静音 media playback keepalive
- 开机后自动尝试拉起服务

但不同厂商系统限制不同。如果仍然出现“只有打开日志页才继续响应”的情况，请优先检查系统后台权限，而不是只看 App 内设置。

## 节点和配置保存

- 重启服务不会清空订阅节点和并发节点池。
- App 不会在启动时清理 `nodes.json`。
- APK 包内不包含 `nodes.json` 或默认 API Key。
- 健康检查和模型列表轮询日志已静默，避免后台刷屏和额外 I/O。

## 常见端点

- 管理后台：`http://127.0.0.1:2156/admin/`
- OpenAI 兼容 Base URL：`http://127.0.0.1:2156/v1`
- 模型列表：`http://127.0.0.1:2156/v1/models`
- 健康检查：`http://127.0.0.1:2156/health`

## 本地构建

在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

构建脚本会完成以下工作：

1. 将 Go 代理编译为 Android arm64 native library。
2. 编译 Android Java 包装层。
3. 打包 APK。
4. 使用 `debug.keystore` 签名。
5. 输出 `vertex-proxy-local-proxy.apk`。
