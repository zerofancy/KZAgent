# Skill 安装与加载 — 设计 Spec

## 1. 目标与范围

让 KZAgent 支持**用户级、跨项目**的可复用能力包（skill），可以安装、启用 / 禁用、并在会话中按需加载。



* **项目级**规则已经由 `AGENTS.md` 承担（workspace 内、根目录全量注入）。

* **用户级** skill 放在应用数据目录（`{AppDataDir}/skills/`），不绑定单个项目，CLI 和桌面端共用。

* 本次只做 **SKILL.md 文本指令**的发现与按需加载，**不**做自动执行脚本钩子、不做在线 skill 市场。脚本执行仍走现有 `run_command` + 审批。

* **安装 / 卸载 skill 本身不写专门的 Kotlin 代码**，而是由内置的 `skill-installer` skill 告诉模型怎么做，模型用现有工具完成。

非目标：



* 不引入插件 / 动态代码加载机制（不改 classpath、不加载 jar）。

* skill 内的脚本不自动运行，不提供 install/uninstall 生命周期钩子。

* 不做 skill 之间的依赖解析。



***

## 2. 概念

一个 skill 是一个自包含目录，最小结构：



```
my-skill/

├── SKILL.md            # 必需：frontmatter + 操作指引正文

├── scripts/            # 可选：脚本（模型用 run\_command 显式调用）

├── templates/          # 可选：模板文件

└── reference/          # 可选：参考文档
```

**SKILL.md 格式：**



```
\---

name: pdf

description: 处理所有 PDF 相关任务：读取、创建、编辑、转换

when\_to\_use: 用户上传 PDF、要求生成/填写 PDF 时

version: "1.0.0"

source: https://github.com/example/kzagent-skills

\---

\# PDF Skill

具体操作指引……模型在调用 read\_skill("pdf") 后才会看到这部分。
```



* frontmatter 用 YAML，至少有 `name` 和 `description`；`when_to_use`、`version`、`source` 可选。

* `name` 只能是小写字母、数字、连字符，作为工具调用和目录名的唯一标识。

* frontmatter 用于**注入系统提示的目录摘要**；正文用于**按需读取**，不常驻上下文。



***

## 3. 存储位置



```
{AppDataDir}/

├── sessions/

├── config.json

└── skills/                  # 用户级 skill 根目录（agent 用 run\_command 往这里装）

&#x20;   ├── pdf/

&#x20;   │   ├── SKILL.md

&#x20;   │   └── scripts/...

&#x20;   └── ppt/

&#x20;       └── SKILL.md
```



* `{AppDataDir}` 沿用现有 `AppDataDir`（Windows: `%APPDATA%\kzagent`，macOS: `~/Library/Application Support/kzagent`，Linux: `~/.config/kzagent`）。

* 每个 skill 一个子目录，目录名 = frontmatter 的 `name`。

* 安装 / 卸载由内置 `skill-installer` skill 驱动模型完成，框架不维护专门的安装代码。



***

## 4. 安装机制（由内置 skill 驱动，不写专门安装代码）

框架**不**写 `SkillInstaller.kt`、不写 `kza skills install/uninstall` 子命令。安装、卸载、列出 skill 的能力本身是一个内置 skill：



* 内置 skill 名为 `skill-installer`，打包在 `src/main/resources/builtin-skills/skill-installer/SKILL.md`；

* 启动时和用户 skill 一起被发现、注入摘要，`read_skill("skill-installer")` 可读全文；

* 模型拿到操作步骤后，用现有 `run_command` 完成实际操作（复制目录、`git clone`、删除），走现有审批模式；

* 不做 zip URL 下载，不做自动安装钩子。

系统提示在 skill 摘要部分**附带 skills 根目录的绝对路径**，让模型知道往哪里装、从哪里卸：



```
User skills are installed under: {AppDataDir}/skills/

Built-in skills are read-only.

Newly installed skills only appear in the list after a new session starts.
```

`skill-installer/SKILL.md` 正文大致告诉模型：



1. **本地目录来源**：用 `run_command` 把源目录整体复制到 `{skills_dir}/<name>/`；

2. **Git 来源**：用 `run_command` 执行 `git clone --depth 1` 到临时目录，再复制含 `SKILL.md` 的子目录到 `{skills_dir}/<name>/`；

3. **校验**：目标目录必须有 `SKILL.md`，frontmatter 的 `name` 要和目录名一致；

4. **完成提示**：告诉用户新 skill 在下次新建 / 重建会话后才会出现在摘要里（摘要是启动时快照）；

5. **列出 / 卸载**：`ls` 列出 `{skills_dir}/`，卸载用 `run_command` 删除整个子目录（走审批）。

框架只负责：内置 skill 从 classpath 加载、用户 skill 从文件系统加载、摘要注入、`read_skill` 读正文。其余全是模型行为。



***

## 5. 加载机制

### 5.1 启动时：目录摘要注入

在 `AgentRuntimeFactory.create()` 里，于构建 `PromptBuilder` 之前：



1. 从 classpath 加载内置 skill（`builtin-skills/*/SKILL.md`）；

2. 扫描 `{AppDataDir}/skills/*/SKILL.md`；

3. 解析每个 SKILL.md 的 frontmatter；

4. 过滤掉 `config.json` 里 `disabled` 的 skill；

5. 生成**紧凑摘要**传给 `PromptBuilder`。

注入系统提示的格式（接在现有 `userPrompt` 和 `AGENTS.md` 之后）：



```
\## Installed skills

The following user-level skills are available. When the task matches a skill's \`when\_to\_use\`, call the \`read\_skill\` tool with its name to load full instructions before proceeding. Do not guess from the summary alone.

User skills are installed under: {AppDataDir}/skills/

Built-in skills are read-only.

Newly installed skills only appear in the list after a new session starts.

\- skill-installer (v1.0.0): 安装、卸载、列出用户级 skill。when: 用户要求安装/添加/移除/列出 skill。

\- pdf (v1.0.0): 处理所有 PDF 相关任务（读取、创建、编辑、转换）。when: 用户上传 PDF、要求生成/填写 PDF。

\- ppt (v1.2.0): 飞书幻灯片创建和编辑。when: 创建演示文稿。
```

摘要只带 name、version、description、when\_to\_use，**不带正文**，避免每个 skill 的全文常驻上下文。

### 5.2 按需：read\_skill 工具

新增工具，第一版只读 `SKILL.md` 正文：



| 工具名          | 参数             | 积分 | 说明                          |
| ------------ | -------------- | -- | --------------------------- |
| `read_skill` | `name: string` | 1  | 读取指定 skill 的 SKILL.md 全文并返回 |



* 未找到、已禁用时返回错误（不泄露路径细节）。

* 不提供列文件 / 读子文件的工具（第一版）。模型要读脚本 / 模板时，用现有 `read_file`，但路径必须经 PathGuard 放行 ——skill 文件在 `{AppDataDir}/skills/` 下，**不在 workspace 内**，所以 `read_file` 默认读不到。

> **第二版可选**
>
> ：加一个 
>
> `read_skill_file(name, path)`
>
>  工具，把读取限制在 skill 目录内（类似 PathGuard 但根是 skill 目录），让模型能读脚本和模板而不用改 
>
> `read_file`
>
>  的 workspace 边界。



***

## 6. 配置

`config.json` 顶层新增可选字段：



```
{

&#x20; "skills": {

&#x20;   "enabled": true,

&#x20;   "disabled": \["old-pdf"],

&#x20;   "extraDirectories": \[]

&#x20; }

}
```



| 字段                        | 默认     | 说明                                                  |
| ------------------------- | ------ | --------------------------------------------------- |
| `skills.enabled`          | `true` | 全局开关。false 时不扫描、不注入、不注册 read\_skill                 |
| `skills.disabled`         | `[]`   | 要跳过的 skill name 列表（内置 skill-installer 不受此开关影响，始终可用） |
| `skills.extraDirectories` | `[]`   | 额外的 skill 搜索根目录（高级用户指向仓库）                           |

对应 Kotlin 数据类加在 `AppConfig.kt`：



```
data class SkillsConfig(

&#x20;   val enabled: Boolean = true,

&#x20;   val disabled: List\<String> = emptyList(),

&#x20;   val extraDirectories: List\<String> = emptyList(),

)
```

`AppConfig` 加 `val skills: SkillsConfig = SkillsConfig()`。`AppConfigDto` 同步加字段，保证旧配置文件缺字段时能正常反序列化。



***

## 7. 新增 Kotlin 组件

新文件放 `src/main/kotlin/com/kzagent/kagent/skill/`：



```
skill/

├── SkillManifest.kt       # data class + frontmatter 解析

├── SkillRegistry.kt       # 加载内置 skill + 扫描用户 skill，提供摘要和 find()

└── SkillTools.kt          # read\_skill 工具定义
```

内置 skill 放在 resources：



```
src/main/resources/builtin-skills/

└── skill-installer/

&#x20;   └── SKILL.md
```

### 7.1 SkillManifest



```
data class SkillManifest(

&#x20;   val name: String,

&#x20;   val description: String,

&#x20;   val whenToUse: String? = null,

&#x20;   val version: String? = null,

&#x20;   val source: String? = null,

)

data class Skill(

&#x20;   val manifest: SkillManifest,

&#x20;   val rootDir: Path?,        // 用户 skill 的目录；内置 skill 为 null

&#x20;   val skillMdSource: SkillMdSource,  // FILESYSTEM(path) 或 CLASSPATH(resource)

&#x20;   val builtin: Boolean = false,

)
```

frontmatter 解析第一版用简单正则（`---` 包裹的 YAML 键值对），不引入 SnakeYAML 依赖。只支持 `name: value` 单层标量，不支持嵌套 / 列表。

### 7.2 SkillRegistry



```
class SkillRegistry(private val skills: List\<Skill>) {

&#x20;   fun summaries(skillsDir: Path): String   // 拼成注入 PromptBuilder 的 Markdown，附 skillsDir 路径

&#x20;   fun find(name: String): Skill?

&#x20;   fun readSkillMd(skill: Skill): String    // FILESYSTEM 用 TextFileCodec.read；CLASSPATH 用 classloader

}
```

加载逻辑：



* **内置 skill**：从 classpath 扫描 `builtin-skills/*/SKILL.md`，每个目录一个 skill，`builtin=true`；

* **用户 skill**：遍历 `{AppDataDir}/skills/` 和 `extraDirectories` 的一级子目录，找 `SKILL.md`，解析 frontmatter；

* 合并后过滤 `disabled`（但 `skill-installer` 始终保留）；

* 解析失败的目录跳过并记日志（不阻断启动）。

### 7.3 SkillTools



```
fun SkillRegistry.readSkillTool(): ToolDefinition = ToolDefinition(

&#x20;   name = "read\_skill",

&#x20;   description = "Load the full instructions of an installed user-level skill by name. Call this when a task matches an installed skill's trigger; do not guess from the summary.",

&#x20;   parameters = objectSchema(

&#x20;       properties = mapOf("name" to stringSchema("Skill name from the installed skills list.")),

&#x20;       required = listOf("name"),

&#x20;   ),

&#x20;   requiresApproval = false,

&#x20;   cost = 1,

) { args ->

&#x20;   // find(name) → readSkillMd → ToolResult.ok

}
```



***

## 8. 集成点（改动现有文件）

### 8.1 `AgentRuntimeFactory.kt`

在创建 `LocalTools` 附近：



```
val skillsDir = AppDataDir.ensureSkillsDir()

val skillRegistry = if (config.skills.enabled) {

&#x20;   SkillRegistry.load(skillsDir, config.skills)

} else null

val skillSummaries = skillRegistry?.summaries(skillsDir).orEmpty()

val localTools = LocalTools(...).registry()

val agent = CodingAgent(

&#x20;   ...

&#x20;   tools = localTools + TodoTools(...).registry() + AskUserTools(...).registry() +

&#x20;       (skillRegistry?.readSkillTool()?.let { ToolRegistry(listOf(it)) } ?: ToolRegistry(emptyList())),

&#x20;   promptBuilder = PromptBuilder(

&#x20;       workspace = pathGuard.root,

&#x20;       userPrompt = config.userPrompt,

&#x20;       rootInstructions = rootInstructions,

&#x20;       skillSummaries = skillSummaries,   // 新增参数

&#x20;   ),

)
```

### 8.2 `PromptBuilder.kt`

构造函数加 `skillSummaries: String = ""`。在现有 `rootInstructions` 拼接之后追加：



```
.let {

&#x20;   if (skillSummaries.isBlank()) it else "\$it\n\n\$skillSummaries"

}
```

### 8.3 `AppConfig.kt`

加 `SkillsConfig` data class 和 `AppConfig.skills` 字段，`AppConfigDto` 同步。加载逻辑走现有 `AppConfigLoader.load()`，新字段缺省即默认值。

### 8.4 桌面 UI

第一版**不做** skill 管理 UI。用户要装 skill 直接对 agent 说 "帮我装个 pdf skill，从 xxx 目录来"，agent 调 `read_skill("skill-installer")` 后用 `run_command` 完成。后续如有需要再加只读列表。



***

## 9. 安全边界



1. **SKILL.md 正文是不可信数据**。和 `fetch_web_page` 一样，模型可以读取它作为操作指引，但运行时不执行其中的任何指令；模型仍受现有系统提示、审批模式、PathGuard 约束。

2. **skill 脚本不自动执行**。模型要用脚本必须显式调 `run_command`，走现有审批（自动 / 手动 / 全部放行）。

3. **skill 文件读取受边界限制**。第一版只有 `read_skill`（读 SKILL.md 正文），不开放 skill 目录内任意文件读取。第二版的 `read_skill_file` 必须把读取限制在 skill 目录内，不能 `../` 逃逸。

4. **安装由模型通过 run\_command 完成**，走现有审批。框架不执行 git clone，不读 clone 下来的任何钩子。

5. **不泄露路径**：工具返回和错误消息里只暴露 skill name。但系统提示里需要告诉模型 skills 根目录的绝对路径（否则模型不知道往哪装）—— 这个路径只出现在系统提示（模型可见），不出现在工具结果文本里。



***

## 10. 与 AGENTS.md 的关系



|         | AGENTS.md                      | Skill                     |
| ------- | ------------------------------ | ------------------------- |
| 作用域     | workspace 内（项目级）               | AppDataDir（用户级、跨项目）       |
| 加载时机    | 根目录启动时全量注入；子目录 read\_file 时懒加载 | 启动时只注入摘要；按需 read\_skill   |
| 生命周期    | 随项目走                           | 随用户配置走                    |
| 可携带脚本   | 否（纯文本）                         | 可选 scripts/templates/     |
| 安装 / 编辑 | 用户直接改文件                        | 内置 skill-installer 驱动模型安装 |

两者不冲突，PromptBuilder 按固定顺序拼接：基础规则 → userPrompt → AGENTS.md 根指令 → skill 摘要。



***

## 11. 验收标准



* [ ] 空 skills 目录时，系统提示末尾仍出现内置 `skill-installer` 的摘要。

* [ ] 模型在用户说 "帮我装个 skill" 时调用 `read_skill("skill-installer")`，返回安装步骤。

* [ ] 手动往 `{AppDataDir}/skills/pdf/` 放一个合法 SKILL.md，重启会话后摘要里出现 pdf。

* [ ] 模型调用 `read_skill("pdf")` 返回 SKILL.md 正文。

* [ ] `config.json` 里 `disabled: ["pdf"]` 后，该 skill 不出现在摘要、`read_skill` 报错。

* [ ] frontmatter 解析失败的 skill 目录被跳过，不阻断启动，日志有警告。

* [ ] `read_skill` 返回内容里不暴露 `{AppDataDir}` 绝对路径（路径只出现在系统提示摘要段）。

* [ ] 旧版 `config.json`（无 skills 字段）能正常加载，使用默认值。

* [ ] `.\gradlew.bat test` 全绿，含 skill 扫描、内置加载、frontmatter 解析、禁用过滤、read\_skill 工具的单元测试。

* [ ] Windows /macOS/ Linux 上 `{AppDataDir}/skills/` 路径正确创建。



***

## 12. 实施顺序建议



1. `SkillManifest` + frontmatter 解析 + 单元测试；

2. `SkillRegistry.load()`（内置 classpath + 用户文件系统）+ 过滤 + summaries ()；

3. 内置 `builtin-skills/skill-installer/SKILL.md`；

4. 挂到 `AgentRuntimeFactory` + `PromptBuilder`（含 skills 目录路径注入）；

5. `read_skill` 工具；

6. 端到端验证：空目录启动 → 手动放一个 skill → 重启看摘要 → 模型 read\_skill；

7. 文档：README 加 "Skills" 一节，说明内置 skill-installer 用法。