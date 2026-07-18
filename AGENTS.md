# KZAgent 开发约定

## 沟通

- 始终使用中文回复用户和描述计划。
- 先检查仓库中的真实实现，再对代码行为作出判断。

## 项目架构

- 本项目使用 Kotlin 2.4、JVM 17、Compose Desktop 和 DeepSeek Chat Completions API。
- CLI 与桌面端共享 `AgentRuntimeFactory`、`CodingAgent` 和本地工具实现；共同能力应放在共享运行时中，不要复制两套逻辑。
- 新增或修改 `AgentMessage` 类型时，必须同步检查 API 序列化、JSONL 会话读写、上下文压缩、token 估算和桌面消息展示。
- 文件和目录处理必须保持 Windows、macOS 与 Linux 兼容，优先使用 `java.nio.file.Path`，不要硬编码平台路径分隔符。

## 桌面 UI 适配

- 桌面界面必须适配窗口缩放和不同内容长度，优先组合使用 `weight`、`fillMaxSize`、`fillMaxWidth` 与 `heightIn`，避免依赖只适合单一窗口尺寸的固定布局。
- 所有可滚动区域都必须提供可见、可拖拽的对应滚动条；滚动内容与滚动条必须共享同一个 `ScrollState` 或 `LazyListState`，并预留空间避免滚动条遮挡内容。
- 保持鼠标滚轮、触控板、滚动条拖拽、键盘焦点和快捷键行为一致；修改输入控件时同时验证 Enter、组合键和多行输入。
- UI 修改应在 Windows、macOS 和 Linux 的 Compose Desktop 行为差异下保持合理退化，并为可提取的交互判断补充单元测试。

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
