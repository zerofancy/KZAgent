# KZAgent — Kotlin AI Coding Agent (MVP)

**KZAgent** 是一个用 **Kotlin/JVM + Compose Desktop** 构建的轻量级 AI 编程助手。它通过调用 **DeepSeek API**（兼容 OpenAI 格式）的推理能力，结合本地文件、命令执行和静态网页获取工具，提供桌面聊天界面，并保留 `ask` / `chat` 命令行模式。

---

## 📋 目录

- [项目概述](#项目概述)
- [快速开始](#快速开始)
- [使用方式](#使用方式)
- [项目指令（AGENTS.md）](#项目指令agentsmd)
- [核心架构](#核心架构)
- [工具列表](#工具列表)
- [安全机制](#安全机制)
- [会话持久化](#会话持久化)
- [项目结构](#项目结构)
- [配置参考](#配置参考)
- [技术栈](#技术栈)

---

## 项目概述

KZAgent 的核心思想是让大语言模型（LLM）通过工具调用（Tool Calling）与本地开发环境交互：

1. **用户提问** → 桌面端或 CLI 将问题发送给 DeepSeek 模型
2. **模型推理** → 模型决定直接回答或调用工具
3. **工具执行** → Agent 在本地执行模型选择的工具（读文件、搜索、编辑等）
4. **结果反馈** → 工具执行结果返回给模型，继续推理
5. **输出答案** → 模型给出最终回答

工具调用采用**积分配额（Tool Quota）**控制：每个工具消耗不同积分（本地只读操作 1 分、`apply_patch` 2 分、`run_command` / `fetch_web_page` 5 分），初始配额 100 分；
配额偏低时模型会收到警告并自动扩容 50 分，不限扩容次数，从而灵活限制资源消耗而无需硬编码轮数上限。

---

## 快速开始

### 1. 配置 API Key

**方式一（推荐）：通过桌面应用内置设置面板配置。** 首次启动桌面应用时如未检测到 API Key，将自动打开设置面板；你也可以随时通过侧边栏的「⚙ 设置」按钮进入。在面板中可配置 API Key、Base URL、模型名称、上下文窗口大小和敏感路径保护等全部选项。

**方式二：手动创建配置文件。** 在用户配置目录创建 `kzagent/config.properties` 文件：

- Windows: `%APPDATA%\kzagent\config.properties`
- macOS: `~/Library/Application Support/kzagent/config.properties`
- Linux: `$XDG_CONFIG_HOME/kzagent/config.properties`，未设置时使用 `~/.config/kzagent/config.properties`

```properties
deepseek.api.key=sk-xxxxxxxxxxxxxxxx
deepseek.model=deepseek-v4-pro
deepseek.base.url=https://api.deepseek.com
```

也可以通过环境变量提供，且优先级高于配置文件：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

### 可选配置项

| 属性 | 默认值 | 说明 |
|------|:------:|------|
| `deepseek.model` | `deepseek-v4-pro` | 使用的 DeepSeek 模型名称 |
| `deepseek.base.url` | `https://api.deepseek.com` | API 端点地址（兼容 OpenAI 格式的均可） |
| `deepseek.sensitive.path.protection` | `false` | 敏感路径保护开关。开启后拦截对 `local.properties`、`.env` 等本地敏感配置文件的访问 |
| `deepseek.context.window.size` | `1000000` | 上下文窗口大小（tokens），用于计算压缩阈值 |
| `kzagent.approval.mode` | `auto` | 审批模式：`auto`、`manual` 或 `full` |

### 2. 运行

项目使用 Gradle 和 JVM 17+。如果系统默认 Java 不是 17+，请先设置 `JAVA_HOME`。

```bash
# 启动桌面应用
./gradlew run

# 单次提问
./gradlew run --args="ask \"列出当前项目文件\""

# 交互式多轮对话
./gradlew run --args="chat"

# 携带初始问题的交互式对话
./gradlew run --args="chat \"分析一下项目架构\""
```

### 3. 打包桌面应用

Windows 安装包使用固定的升级 UUID，并允许新构建的相同版本覆盖已安装版本：

```powershell
.\gradlew.bat packageReleaseExe
```

产物位于 `build/compose/binaries/main-release/exe/`。`packageReleaseExe` 生成的是安装器；安装后的应用程序位于安装目录中的 `KZAgent.exe`。发布新版本时仍应同步提升 `build.gradle.kts` 中的 `windowsPackageVersion`，相同版本覆盖仅用于修复构建或重复安装。

---

## 使用方式

### 桌面应用

无参数运行会启动桌面窗口：

```bash
./gradlew run
```

桌面端支持：

- 使用 Compose Fluent + Material 3 双主题桥接的 Fluent UI 桌面界面；完整 NavigationView 会在宽屏显示固定左栏、窄屏切换为紧凑浮层，并支持亮/暗主题
- 默认使用启动目录作为工作区，并可任意切换目录
- 加载最新 `.kagent/sessions/` 历史用于续聊，支持多会话管理
- 会话列表、消息历史、设置和审批详情等滚动区域均提供可拖拽的桌面滚动条
- Markdown 超宽表格提供独立、可拖拽的横向滚动条，单元格内容会换行完整展示
- NavigationView 底部提供**设置面板**入口；会话重命名和删除集中在各会话的更多菜单中
- 主聊天页顶部提供常驻的**审批模式下拉菜单**，可立即切换自动、手动或全部放行模式
- 启动时自动检测配置：如未设置 API Key 将**默认跳转到设置界面**
- 在状态栏显示模型请求、工具执行和审批状态
- 支持自动、手动和全部放行三种审批模式；高风险人工审批使用单独的警告弹窗
- 输入框使用 `Enter` 发送；macOS 使用 `Command + Enter` 换行，Windows 和 Linux 使用 `Ctrl + Enter` 换行
- macOS 上关闭主窗口后应用会继续驻留；再次点击 Dock 图标可恢复原窗口和会话，使用 `Command + Q` 可完全退出

### `ask` — 单次提问模式

执行一次提问，模型经过多轮工具调用后只输出最终答案，然后退出。

```bash
./gradlew run --args="ask \"搜索所有包含 TODO 的文件\""
```

### `chat` — 交互式对话模式

进入持续对话界面，可以连续追问。支持以下功能：

- **断点续聊**：启动时自动加载最近一次会话历史
- **多轮追问**：每次回答后可输入新的问题
- **退出**：输入空行或 `exit` / `quit` 结束对话

```
$ ./gradlew run --args="chat"
You: 介绍一下这个项目
Assistant:
...
You (empty to exit): 帮我优化一下代码
Assistant:
...
You (empty to exit):
Chat ended.
```

---

## 项目指令（AGENTS.md）

KZAgent 支持使用工作区中的 `AGENTS.md` 为 Agent 提供持久的项目约定：

- 每个 session 创建时读取工作区根目录的 `AGENTS.md`，并将完整内容固化到基础系统提示词中。根指令不会受上下文压缩影响，session 中途修改后需新建或重建 session 才会生效。
- 当 `read_file` 成功读取工作区子目录中的文件时，从工作区根目录下一层到目标文件父目录逐层发现 `AGENTS.md`，按浅到深顺序加载；越接近目标文件的规则优先级越高。经审批读取的工作区外文件不会触发外部 `AGENTS.md` 加载。
- 空的 `AGENTS.md` 会被忽略。KZAgent 不限制单个指令文件大小，也不会截断或自动压缩文件内容。
- 同一份非空子目录指令在两次上下文压缩之间只加载一次。压缩成功后会开启新的加载周期，再次读取相应目录时允许重新加载，即使旧指令仍在最近消息窗口中。
- 子目录指令会记录到 session JSONL，并作为系统消息发送给模型，但不会显示在桌面对话列表中。落入压缩摘要区的子目录指令会被丢弃。
- 当前仅识别规范文件名 `AGENTS.md`，不读取全局指令、`AGENTS.override.md` 或其他备用文件名。

示例：

```text
workspace/
├── AGENTS.md              # 整个 session 始终生效
└── src/
    ├── AGENTS.md          # 读取 src/ 下文件后加载
    └── feature/
        ├── AGENTS.md      # 读取此目录文件后在 src/ 规则之后加载
        └── Feature.kt
```

---

## 核心架构

### 工作流程

```
用户输入
    │
    ▼
┌──────────────────┐
│  CodingAgent      │  ◄── 核心循环（积分配额制，自动扩容）
│  运行推理 → 检查   │
│  工具调用 → 执行   │
│  结果 → 继续推理   │
└──────────────────┘
    │
    ├── DeepSeekClient  ────► DeepSeek API
    │
    └── LocalTools
         ├── list_files     (只读，无需审批)
         ├── read_file      (工作区内只读；外部或敏感文件按策略审批)
         ├── search_text    (只读，无需审批)
         ├── apply_patch    (Git 补丁编辑，无需审批)
         ├── run_command    (按自动 / 手动 / 全部放行策略审批)
         └── fetch_web_page (公开静态网页获取，无需审批)
```

### 主要组件

| 组件 | 文件 | 职责 |
|------|------|------|
| **CodingAgent** | `agent/CodingAgent.kt` | Agent 核心循环：调度模型推理与工具执行 |
| **AgentsInstructionsLoader** | `agent/AgentsInstructionsLoader.kt` | 加载根目录与子目录 `AGENTS.md` 项目指令 |
| **PromptBuilder** | `agent/PromptBuilder.kt` | 构建系统提示词（定义 Agent 行为规则） |
| **DeepSeekClient** | `llm/DeepSeekClient.kt` | 调用 DeepSeek Chat Completions API |
| **SessionWriter** | `agent/SessionWriter.kt` | 以 JSONL 格式将消息流写入会话文件 |
| **SessionReader** | `agent/SessionReader.kt` | 从会话文件读取历史消息，恢复对话上下文 |
| **DesktopApp** | `desktop/DesktopApp.kt` | Compose Desktop 桌面聊天界面 |
| **SessionManager** | `desktop/SessionManager.kt` | 桌面端多会话管理：新建、切换、重命名、删除 |
| **AppConfigLoader** | `config/AppConfig.kt` | 从用户配置文件 / 环境变量加载配置 |
| **LocalTools** | `tools/LocalTools.kt` | 本地及网页工具的统一注册 |
| **WebPageService** | `tools/WebPageService.kt` | 公网静态页面请求、SSRF 防护、解析与正文提取子代理 |
| **PathGuard** | `tools/PathGuard.kt` | 路径解析与工作区边界判断 |
| **ApprovalPolicy** | `tools/Approval.kt` | 通用审批策略、风险分析与专用审批 Agent |

---

## 工具列表

Agent 可以通过以下工具与工作区和公开网页交互：

| 工具名称 | 需要审批 | 功能描述 |
|-----------|:--------:|----------|
| `list_files` | ❌ | 列出工作区目录结构（深度 1–8，最多 500 项） |
| `read_file` | 条件 | 工作区内直接读取；外部或受保护文件按审批模式处理 |
| `search_text` | ❌ | 在文本文件中大小写不敏感搜索子串（最多 200 个结果） |
| `apply_patch` | ❌ | 用单个 Git unified diff 更新、创建或删除多个文件 |
| `run_command` | ✅ | 按全局审批模式执行带执行超时限制的 shell 命令 |
| `fetch_web_page` | ❌ | 获取公开静态 HTTP(S) 页面，返回请求元数据、Markdown 正文和最多 20 个关键链接 |

### 工具设计原则

- **只读工具**中 `list_files` / `search_text` 严格限制在工作区；`read_file` 可在审批后读取单个工作区外文件
- **文件编辑工具**（`apply_patch`）不要求审批，使用单个 `patch` 参数传递标准 Git unified diff
  - 可在一次调用中更新、创建或删除多个文件
  - 自动识别 UTF-8（含 BOM）、UTF-16、UTF-32、GB18030/GBK 和 Windows-1252
  - 编辑已有文件时保留原编码、BOM 与换行符风格
- **命令执行**（`run_command`）统一经过当前审批模式；风险分析用于决定自动判断或人工确认，不再直接剥夺用户授权执行的能力
- **网页获取**（`fetch_web_page`）仅接受单个公开 HTTP(S) URL。HTML 会先清理脚本、样式、表单和页面框架，再由一次无工具、无历史的专用子代理提取正文；完整 HTML 不会返回主 Agent

### 当前网页能力限制

`fetch_web_page` 只处理服务器直接返回的 HTML、纯文本、JSON、XML、RSS 和 Atom 内容。它不会执行 JavaScript，也不支持点击、滚动、Cookie、登录态、验证码、自定义请求头、二进制下载或内网页面。遇到 SPA 空壳或要求启用 JavaScript 的页面时，结果会包含能力限制警告。

---

## 安全机制

### 🔒 路径安全（PathGuard）

所有写操作、目录枚举和批量搜索路径都会被**归一化**和**校验**，确保不会逃逸出工作区根目录。`read_file` 是唯一例外：绝对路径、`../` 或符号链接指向的工作区外普通文件会进入统一审批流程；批准后只读取本次指定的行范围。

### 🚫 敏感路径保护

启用敏感路径保护后，以下文件会从直接读取改为按审批模式处理：

- `.env`、`local.properties`（历史本地敏感配置文件）
- 密钥文件、密码文件等

### 🛡️ 命令安全校验

`run_command` 执行前会识别危险程序、多行脚本、Shell 组合语法、路径逃逸和敏感引用，并按审批模式处理：

1. **自动审批（默认）**：明确安全的常见只读、构建和测试命令由静态规则放行；其他普通命令由无工具、无会话历史的专用审批 Agent 判断；高风险命令或 Agent 无法判断时转人工。
2. **手动审批**：所有命令和受保护读取都由用户逐次确认。
3. **全部放行**：视为持续授权，不再调用审批 Agent 或人工弹窗，并以当前应用用户权限执行。
4. **命令执行超时**：命令进程启动后默认最多运行 30 秒，可配置为 1–120 秒；这不是审批等待超时。

### 🌐 网页请求安全

`fetch_web_page` 在建立连接时检查 DNS 解析结果，拒绝 localhost、环回、私网、链路本地、组播和保留地址；每次重定向都会重新校验。请求最多跟随 5 次重定向、总耗时最多 30 秒，解压后的响应体最多 2 MiB，并拒绝二进制内容。网页内容始终视为不可信数据，正文提取子代理不能调用任何工具。

桌面端高风险审批必须点击“仍然执行”，Enter 不会批准；CLI 必须输入完整的 `yes`。

### 🔑 API Key 脱敏

所有错误消息中的 API Key 都会被自动替换为 `***REDACTED***`，防止密钥在日志或终端中泄露。

---

## 会话持久化

每次交互通过 **SessionWriter** 自动以 **JSONL 格式**（每行一条 JSON）写入会话文件，保存在平台应用数据目录中，按工作区隔离：

```
{AppDataDir}/
└── sessions/
    ├── {workspace1}-{sha256hash}/
    │   ├── session-{uuid}.jsonl
    │   ├── session-{uuid}.jsonl.name      # 用户自定义会话名
    │   └── session-{uuid}.jsonl.workspace  # 对应的工作区路径
    └── {workspace2}-{sha256hash}/
        └── ...
```

**每条记录格式：**

| 字段 | 说明 |
|------|------|
| `time` | ISO-8601 时间戳，记录消息写入时间 |
| `role` | 消息角色：`system`、`user`、`assistant`、`tool`、`summary`、`context_snapshot` 等 |
| `content` | 消息正文 |
| `tool_calls` | （仅 assistant）模型请求的工具调用列表，含 `id`、`name`、`arguments` |
| `tool_call_id` / `name` / `is_error` | （仅 tool）工具执行结果对应的调用 ID、工具名和错误标记 |
| `context_tokens` | （仅 assistant / context_snapshot）写入时的累计上下文 token 数 |

**启动恢复行为：**

| 模式 | 行为 |
|------|------|
| 桌面端 | 自动加载所有历史会话，按最近修改排序，默认激活最新会话；支持侧边栏**多会话管理**（新建、切换、重命名、删除） |
| CLI `chat` | 无初始问题时加载**最近一次会话历史**，实现断点续聊；带初始问题时从空白上下文开始 |
| CLI `ask` | **不加载历史**，每次独立执行 |

**上下文压缩：** 当上下文超过 80% 窗口大小时自动触发，旧消息被 LLM 总结后以 `context_snapshot` 行写入会话文件。恢复时以此行为界，清空之前的消息并替换为摘要。

**会话元数据：** 每个 `.jsonl` 文件旁可存在两个辅助文件 — `.name`（用户自定义会话名）和 `.workspace`（所属工作区路径），由桌面端 `SessionManager` 读写；CLI 不使用。

---

## 项目结构

```
src/main/kotlin/com/kzagent/kagent/
├── agent/
│   ├── AgentsInstructionsLoader.kt # 分层 AGENTS.md 加载
│   ├── CodingAgent.kt      # Agent 核心循环
│   ├── PromptBuilder.kt    # 系统提示词构建
│   ├── SessionReader.kt    # 会话历史读取
│   └── SessionWriter.kt    # 会话历史写入
├── cli/
│   └── Main.kt             # 入口：ask / chat 命令
├── desktop/
│   ├── DesktopApp.kt       # 桌面应用 UI（主界面、侧边栏、消息列表、审批弹窗）
│   ├── SessionManager.kt   # 多会话管理（新建、切换、改名、删除）
│   └── SettingsPanel.kt    # 设置面板（API Key、模型、URL 等 GUI 配置）
├── Main.kt                 # 根入口：无参数桌面；有参数 CLI
├── AgentRuntimeFactory.kt  # 共享运行时创建
├── config/
│   └── AppConfig.kt        # 配置加载（AppConfigLoader）、保存（ConfigWriter）与密钥脱敏
├── llm/
│   ├── DeepSeekClient.kt   # DeepSeek API 客户端
│   └── Messages.kt         # 消息模型定义
└── tools/
    ├── Tool.kt             # 工具定义、注册表、JSON Schema 构建
    ├── LocalTools.kt       # 本地及网页工具注册
    ├── Approval.kt         # 通用审批模式、风险分析、静态规则与审批 Agent
    ├── PathGuard.kt        # 路径安全守卫
    ├── TextFileCodec.kt    # 多编码文本文件读写（UTF-8/16/32、GBK、Windows-1252）
    ├── UnifiedPatch.kt     # Git unified diff 解析与应用引擎
    ├── ToolQuota.kt        # 工具调用配额系统（含自动扩容）
    └── WebPageService.kt   # 静态网页请求、解析、安全校验与正文提取
```

---

## 配置参考

### 用户配置文件 `kzagent/config.properties`

```properties
deepseek.api.key=sk-xxxxxxxxxxxxxxxx          # DeepSeek API Key（必填）
deepseek.model=deepseek-v4-pro                # 模型名称（可选）
deepseek.base.url=https://api.deepseek.com    # API 地址（可选）
deepseek.sensitive.path.protection=false      # 敏感路径保护（可选）
deepseek.context.window.size=1000000          # 上下文窗口 tokens（可选）
kzagent.approval.mode=auto                    # auto | manual | full（可选，默认 auto）
```

### 环境变量

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx    # 优先级高于 config.properties
```

---

## 技术栈

| 技术 | 用途 |
|------|------|
| **Kotlin 2.4.0** + **JVM 17+** | 开发语言与运行时 |
| **Compose Desktop 1.11.1** | 桌面应用 UI 与原生打包 |
| **Gradle** | 构建工具 |
| **kotlinx-coroutines** | 异步编程（协程） |
| **kotlinx-serialization** | JSON 序列化/反序列化 |
| **Java HttpClient** | 调用 DeepSeek API |
| **OkHttp 4.12.0** | 静态网页请求、重定向与压缩传输 |
| **Jsoup 1.22.2** | HTML/XML 解析、清理和链接解析 |
| **DeepSeek API** (OpenAI 兼容) | LLM 推理后端 |

---

## 开发

```bash
# 构建项目
./gradlew build

# 运行测试
./gradlew test

# 运行（需先配置 API Key）
./gradlew run --args="ask \"你好\""
```

---

## License

本项目为 MVP 阶段的开源工具。
