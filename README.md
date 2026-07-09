# KZAgent — Kotlin AI Coding Agent (MVP)

**KZAgent** 是一个用 **Kotlin/JVM** 构建的轻量级 AI 编程助手 CLI 工具。它通过调用 **DeepSeek API**（兼容 OpenAI 格式）的推理能力，结合一组本地文件操作和命令执行工具，在终端中实现类似 Cursor / Claude Code 的代码辅助体验。

---

## 📋 目录

- [项目概述](#项目概述)
- [快速开始](#快速开始)
- [使用方式](#使用方式)
- [核心架构](#核心架构)
- [工具列表](#工具列表)
- [安全机制](#安全机制)
- [会话持久化](#会话持久化)
- [项目结构](#项目结构)
- [配置参考](#配置参考)
- [技术栈](#技术栈)

---

## 项目概述

KZAgent 是一个**最小化可行产品（MVP）**，核心思想是让大语言模型（LLM）通过工具调用（Tool Calling）与本地开发环境交互：

1. **用户提问** → CLI 将问题发送给 DeepSeek 模型
2. **模型推理** → 模型决定直接回答或调用工具
3. **工具执行** → Agent 在本地执行模型选择的工具（读文件、搜索、编辑等）
4. **结果反馈** → 工具执行结果返回给模型，继续推理
5. **输出答案** → 模型给出最终回答

整个过程最多支持 **20 轮工具调用**，防止无限循环。

---

## 快速开始

### 1. 配置 API Key

在项目根目录创建 `local.properties` 文件（已加入 `.gitignore`，不会提交到仓库）：

```properties
deepseek.api.key=sk-xxxxxxxxxxxxxxxx
deepseek.model=deepseek-v4-flash
deepseek.base.url=https://api.deepseek.com
```

也可以通过环境变量提供：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

### 2. 运行

```bash
# 单次提问
./gradlew run --args="ask \"列出当前项目文件\""

# 交互式多轮对话
./gradlew run --args="chat"

# 携带初始问题的交互式对话
./gradlew run --args="chat \"分析一下项目架构\""
```

---

## 使用方式

### `ask` — 单次提问模式

执行一次提问，模型经过多轮工具调用后给出最终答案，然后退出。

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

## 核心架构

### 工作流程

```
用户输入
    │
    ▼
┌──────────────────┐
│  CodingAgent      │  ◄── 核心循环（最多 20 轮）
│  运行推理 → 检查   │
│  工具调用 → 执行   │
│  结果 → 继续推理   │
└──────────────────┘
    │
    ├── DeepSeekClient  ────► DeepSeek API
    │
    └── LocalTools
         ├── list_files     (只读，无需审批)
         ├── read_file      (只读，无需审批)
         ├── search_text    (只读，无需审批)
         ├── replace_in_file (文件编辑，无需审批)
         └── run_command    (需终端确认，阻止危险命令)
```

### 主要组件

| 组件 | 文件 | 职责 |
|------|------|------|
| **CodingAgent** | `agent/CodingAgent.kt` | Agent 核心循环：调度模型推理与工具执行 |
| **PromptBuilder** | `agent/PromptBuilder.kt` | 构建系统提示词（定义 Agent 行为规则） |
| **DeepSeekClient** | `llm/DeepSeekClient.kt` | 调用 DeepSeek Chat Completions API |
| **SessionWriter** | `agent/SessionWriter.kt` | 将会话记录写入 `.kagent/sessions/` |
| **SessionReader** | `agent/SessionReader.kt` | 从磁盘读取历史会话，支持断点续聊 |
| **AppConfigLoader** | `config/AppConfig.kt` | 从 `local.properties` / 环境变量加载配置 |
| **LocalTools** | `tools/LocalTools.kt` | 5 个本地工具的注册与实现 |
| **PathGuard** | `tools/PathGuard.kt` | 路径安全守卫，防止逃逸出工作区 |
| **ApprovalPolicy** | `tools/Approval.kt` | 审批策略接口及终端确认实现 |

---

## 工具列表

Agent 可以通过以下工具与本地文件系统交互：

| 工具名称 | 需要审批 | 功能描述 |
|-----------|:--------:|----------|
| `list_files` | ❌ | 列出工作区目录结构（深度 1–8，最多 500 项） |
| `read_file` | ❌ | 读取文本文件中指定行范围（最多 500 行） |
| `search_text` | ❌ | 在文本文件中大小写不敏感搜索子串（最多 200 个结果） |
| `replace_in_file` | ❌ | 替换文件中唯一出现的文本，或创建新文件 |
| `run_command` | ✅ | 在终端执行 shell 命令，需用户输入 `yes` 确认 |

### 工具设计原则

- **只读工具**（`list_files` / `read_file` / `search_text`）直接执行，无需人工干预
- **文件编辑工具**（`replace_in_file`）不要求审批，支持快速迭代编辑
  - 已有文件：精确匹配并替换唯一出现的目标文本
  - 新文件：直接创建
- **命令执行**（`run_command`）必须经用户确认，且有严格的安全校验

---

## 安全机制

### 🔒 路径安全（PathGuard）

所有文件操作路径都会被**归一化**和**校验**，确保不会逃逸出工作区根目录。无论是绝对路径还是包含 `../` 的相对路径，都会被拦截。

### 🚫 敏感路径保护

自动拒绝访问以下敏感文件/目录：

- `.env`、`local.properties`（API Key 配置文件）
- 密钥文件、密码文件等

### 🛡️ 命令安全校验

`run_command` 执行前会进行多重安全检查：

1. **用户确认**：必须输入 `yes` 才能执行
2. **危险命令拦截**：阻止 `rm`、`sudo`、`chmod`、`dd`、`shutdown` 等危险命令
3. **路径逃逸拦截**：阻止 `../` 和重定向到绝对路径
4. **敏感引用拦截**：阻止命令中引用密钥文件
5. **超时控制**：默认 30 秒超时，可配 1–120 秒
6. **禁止多行命令**：防止注入复杂脚本

### 🔑 API Key 脱敏

所有错误消息中的 API Key 都会被自动替换为 `***REDACTED***`，防止密钥在日志或终端中泄露。

---

## 会话持久化

每次交互都会被自动记录到 `.kagent/sessions/` 目录下的 JSONL 格式文件中：

```
.kagent/
└── sessions/
    └── session-2025-01-01T120000Z.jsonl
```

每条记录包含：时间戳、消息角色、内容、工具调用信息等。`chat` 模式启动时会自动加载最近一次会话，实现**断点续聊**。

---

## 项目结构

```
src/main/kotlin/com/kzagent/kagent/
├── agent/
│   ├── CodingAgent.kt      # Agent 核心循环
│   ├── PromptBuilder.kt    # 系统提示词构建
│   ├── SessionReader.kt    # 会话历史读取
│   └── SessionWriter.kt    # 会话历史写入
├── cli/
│   └── Main.kt             # 入口：ask / chat 命令
├── config/
│   └── AppConfig.kt        # 配置加载与密钥脱敏
├── llm/
│   ├── DeepSeekClient.kt   # DeepSeek API 客户端
│   └── Messages.kt         # 消息模型定义
└── tools/
    ├── Tool.kt             # 工具定义、注册表、JSON Schema 构建
    ├── LocalTools.kt       # 5 个本地工具实现
    ├── Approval.kt         # 审批策略（终端确认 / 始终批准 / 始终拒绝）
    └── PathGuard.kt        # 路径安全守卫
```

---

## 配置参考

### `local.properties`

```properties
deepseek.api.key=sk-xxxxxxxxxxxxxxxx    # DeepSeek API Key（必填）
deepseek.model=deepseek-v4-flash        # 模型名称（可选，默认 deepseek-v4-flash）
deepseek.base.url=https://api.deepseek.com  # API 地址（可选）
```

### 环境变量

```bash
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx    # 优先级高于 local.properties
```

---

## 技术栈

| 技术 | 用途 |
|------|------|
| **Kotlin 1.9.25** + **JVM 11+** | 开发语言与运行时 |
| **Gradle** | 构建工具 |
| **kotlinx-coroutines** | 异步编程（协程） |
| **kotlinx-serialization** | JSON 序列化/反序列化 |
| **Java HttpClient** | 调用 DeepSeek API |
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

