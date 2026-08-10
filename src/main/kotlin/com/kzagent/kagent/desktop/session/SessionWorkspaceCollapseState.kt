package com.kzagent.kagent.desktop

import com.kzagent.kagent.config.AppDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

@Serializable
internal data class SessionWorkspaceCollapseState(
    val expandedGroups: Map<String, Boolean> = emptyMap(),
)

private val sessionWorkspaceCollapseJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun defaultSessionWorkspaceCollapseFile(): Path =
    AppDataDir.appDir().resolve("session-group-collapses.json")

internal fun sessionWorkspaceKey(workspace: Path): String {
    val workspacePath = workspace.toAbsolutePath().normalize().toString()
    return if (System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")) {
        workspacePath.lowercase(Locale.getDefault())
    } else {
        workspacePath
    }
}

internal suspend fun loadSessionWorkspaceExpandState(
    stateFile: Path = defaultSessionWorkspaceCollapseFile(),
): Map<String, Boolean> = withContext(Dispatchers.IO) {
    runCatching {
        if (!Files.exists(stateFile)) {
            return@runCatching emptyMap<String, Boolean>()
        }
        val content = Files.readString(stateFile, StandardCharsets.UTF_8)
        sessionWorkspaceCollapseJson.decodeFromString<SessionWorkspaceCollapseState>(content).expandedGroups
    }.getOrDefault(emptyMap())
}

internal suspend fun saveSessionWorkspaceExpandState(
    expandedGroups: Map<String, Boolean>,
    stateFile: Path = defaultSessionWorkspaceCollapseFile(),
) {
    withContext(Dispatchers.IO) {
        runCatching {
            stateFile.parent?.let(Files::createDirectories)
            Files.writeString(
                stateFile,
                sessionWorkspaceCollapseJson.encodeToString(SessionWorkspaceCollapseState(expandedGroups)),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }
}
