# KZAgent 开发约定

## 沟通

- 始终使用中文回复用户和描述计划。
- 先检查仓库中的真实实现，再对代码行为作出判断。

## 项目架构

- 本项目使用 Kotlin 2.4、JVM 17、Compose Desktop，以及支持 DeepSeek/OpenRouter 的 OpenAI-compatible Chat Completions API。
- CLI 与桌面端共享 `AgentRuntimeFactory`、`CodingAgent` 和本地工具实现；共同能力应放在共享运行时中，不要复制两套逻辑。
- 新增或修改 `AgentMessage` 类型时，必须同步检查 API 序列化、JSONL 会话读写、上下文压缩、token 估算和桌面消息展示。
- 文件和目录处理必须保持 Windows、macOS 与 Linux 兼容，优先使用 `java.nio.file.Path`，不要硬编码平台路径分隔符。

## 桌面 UI 风格

- 桌面端以 Compose Fluent UI 为主视觉体系。应用外壳、导航、弹出层和常规交互控件应优先使用 `io.github.composefluent` 提供的组件，并从 `FluentTheme` 获取颜色、排版和形状，不要自行硬编码一套近似 Fluent 的样式。
- 新增或修改对话框时统一使用 Fluent `ContentDialog` 或 `FluentDialog`；禁止新增 Material3 `AlertDialog`。标题、正文、按钮和输入框也应优先使用 Fluent `Text`、`Button`、`AccentButton` 和 `TextField`，保持同一弹窗内部的视觉体系一致。
- 页面层级和容器应优先使用 Fluent 的 `Mica`、`Layer`、卡片及语义化背景/描边颜色；图标优先选用 Fluent Icons。强调、危险、禁用、悬停和选中状态必须使用主题提供的语义颜色，确保亮色与暗色主题均清晰可辨。
- Material3 仅保留给 Markdown 渲染器及 Compose Fluent 暂无可用替代的存量复杂控件。确需使用时必须置于 `KZAgentFluentTheme` 的双主题桥接下，并保证颜色、字号、圆角、间距和交互状态与周围 Fluent UI 协调；不得在新界面中引入孤立的 Material 风格区域。
- 修改 UI 前先检查项目中同类 Fluent 组件的现有用法，并对照项目锁定版本的 Compose Fluent API；不要依据其他版本或 WinUI API 猜测参数和行为。

## 桌面 UI 适配

- 桌面界面必须适配窗口缩放和不同内容长度，优先组合使用 `weight`、`fillMaxSize`、`fillMaxWidth` 与 `heightIn`，避免依赖只适合单一窗口尺寸的固定布局。
- 所有可滚动区域都必须提供可见、可拖拽的对应滚动条；滚动内容与滚动条必须共享同一个 `ScrollState` 或 `LazyListState`，并预留空间避免滚动条遮挡内容。
- 保持鼠标滚轮、触控板、滚动条拖拽、键盘焦点和快捷键行为一致；修改输入控件时同时验证 Enter、组合键和多行输入。
- UI 修改应在 Windows、macOS 和 Linux 的 Compose Desktop 行为差异下保持合理退化，并为可提取的交互判断补充单元测试。

## 资源生命周期

- 拥有非 daemon 线程或线程池的资源（如 OkHttpClient 的异步 Dispatcher）必须在应用退出前显式关闭，否则会阻止 JVM 自动退出。
- 新增持有网络客户端、线程池、文件锁、ServerSocket 或 WatchService 等资源的类，必须实现 `AutoCloseable`，并将关闭入口串联到以下位置之一：
  - 桌面端：根 composable 的 `DisposableEffect.onDispose`。
  - CLI 端：`main` 或 `runCli` 中的 `use` 块。
  - 共享运行时：`AgentRuntime`，由 `SessionManager.close()` 与 CLI 的 `use` 统一调用。
- 不要在切换 runtime、删除会话或 invalidate 时仅丢弃引用而不关闭旧资源。
- OkHttp 异步请求应优先使用 `suspendCancellableCoroutine` + `call.cancel()` 以响应取消；关闭客户端时按顺序执行 `dispatcher.cancelAll()` → `dispatcher.executorService.shutdown()` → `connectionPool.evictAll()` → `cache?.close()`。

## 安全与修改

- 对复杂逻辑、非显然的约束和重要设计取舍适当添加注释，说明“为什么这样做”；不要用注释逐行复述显而易见的代码。
- 不得削弱工作区路径边界、敏感路径保护、密钥脱敏或命令审批机制。
- 不得读取、输出或提交 API Key 等密钥。
- 不要提交 `local.properties`、`.kagent/`、`build/`、`.gradle/` 或其他生成产物。
- 修改行为或公开使用方式时，同步更新 README 和相关测试。

## 验证

- Windows 使用 `.\gradlew.bat test`。
- macOS 和 Linux 使用 `./gradlew test`。
- 提交代码前运行完整测试；测试应覆盖正常路径、失败路径、会话恢复和上下文压缩等受影响行为。
