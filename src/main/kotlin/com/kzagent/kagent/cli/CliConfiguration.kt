package com.kzagent.kagent.cli

import com.kzagent.kagent.config.AppConfig
import com.kzagent.kagent.config.AppConfigLoader
import com.kzagent.kagent.config.ConfigWriter
import com.kzagent.kagent.config.MissingProviderConfigurationException
import com.kzagent.kagent.config.ModelSelection
import com.kzagent.kagent.config.ProviderConfig
import com.kzagent.kagent.config.ProviderKind
import java.net.URI

/** Only missing credentials trigger setup; malformed existing files must not be overwritten. */
internal fun ensureCliConfiguration(
    load: () -> AppConfig = { AppConfigLoader.load() },
    save: (AppConfig) -> Unit = { ConfigWriter.save(it) },
    readInput: (secret: Boolean) -> String? = ::readConfigurationInput,
    output: (String) -> Unit = { println(it) },
) {
    try {
        load()
        return
    } catch (_: MissingProviderConfigurationException) {
        output("尚未配置模型服务，请完成首次设置（输入 /cancel 或结束输入可退出，不保存）。")
    }

    fun input(prompt: String, default: String = "", secret: Boolean = false): String {
        while (true) {
            output(if (default.isEmpty()) prompt else "$prompt [$default]")
            val value = readInput(secret)?.trim()
                ?: throw IllegalStateException("设置已取消，未保存配置。")
            if (value == "/cancel") throw IllegalStateException("设置已取消，未保存配置。")
            if (value.isNotEmpty()) return value
            if (default.isNotEmpty()) return default
            output("此项不能为空，请重新输入。")
        }
    }

    val kinds = ProviderKind.entries
    kinds.forEachIndexed { index, kind -> output("${index + 1}. ${kind.displayName}") }
    var kind: ProviderKind? = null
    while (kind == null) {
        kind = input("选择 Provider 编号：", "1").toIntOrNull()
            ?.let { kinds.getOrNull(it - 1) }
        if (kind == null) output("请选择列表中的编号。")
    }
    val selectedKind = kind
    val id = when (selectedKind) {
        ProviderKind.DEEPSEEK -> "deepseek"
        ProviderKind.OPENROUTER -> "openrouter"
        ProviderKind.MIMOCODE -> "mimocode"
        ProviderKind.OPENAI_COMPATIBLE -> "custom"
    }
    var baseUrl: String
    while (true) {
        baseUrl = input("API Base URL：", selectedKind.defaultBaseUrl).trimEnd('/')
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        if (uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
            && uri.userInfo == null && uri.query == null && uri.fragment == null) break
        output("请输入有效的 HTTP(S) 服务地址，不包含凭据、查询参数或片段。")
    }
    val defaultModel = when (selectedKind) {
        ProviderKind.DEEPSEEK -> AppConfig.DEFAULT_MODEL
        ProviderKind.MIMOCODE -> AppConfig.DEFAULT_MIMO_MODEL
        else -> ""
    }
    val modelId = input("模型 ID：", defaultModel)
    val apiKey = input("API Key：", secret = true)
    val config = AppConfig(
        providers = listOf(ProviderConfig(id, selectedKind.displayName, selectedKind, apiKey, baseUrl)),
        defaultModel = ModelSelection(id, modelId),
    )
    // Persist only after every field is complete; cancellation leaves the configuration untouched.
    save(config)
    output("配置已保存，继续执行当前命令。")
}

private fun readConfigurationInput(secret: Boolean): String? {
    val console = System.console()
    if (secret && console != null) {
        val password = console.readPassword() ?: return null
        return try {
            String(password)
        } finally {
            password.fill('\u0000')
        }
    }
    if (secret) println("当前运行环境无法隐藏输入，API Key 可能在终端回显；可输入 /cancel 退出。")
    return if (console != null) console.readLine() else readlnOrNull()
}
