# Kotlin Coding Agent MVP

Minimal Kotlin/JVM CLI coding agent that talks to DeepSeek's OpenAI-compatible API and can call a small set of local tools.

## Setup

Create an untracked `local.properties` file:

```properties
deepseek.api.key=your-key
deepseek.model=deepseek-v4-flash
deepseek.base.url=https://api.deepseek.com
```

You can also provide the key through `DEEPSEEK_API_KEY`.

## Usage

```bash
./gradlew run --args="ask \"列出当前项目文件\""
```

Read/search tools run directly. File replacement and shell command tools require terminal confirmation.

