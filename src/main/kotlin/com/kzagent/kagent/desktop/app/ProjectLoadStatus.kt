package com.kzagent.kagent.desktop.app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.desktop.SessionManager
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.Text
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Quarantined records remain visible and retryable instead of silently disappearing. */
@Composable
internal fun ProjectLoadStatus(state: DesktopSessionUiState, manager: SessionManager, scope: CoroutineScope, initialWorkspace: Path) {
    val hasIssues = manager.loadIssues.isNotEmpty() || state.sessionLoadError != null || state.projectActionError != null
    if (!hasIssues) return
    var details by remember { mutableStateOf(false) }
    var retrying by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (state.sessionLoadError != null) "项目或会话加载失败" else "项目数据提示（${manager.loadIssues.size} 条加载诊断）",
            color = FluentTheme.colors.system.critical)
        (state.sessionLoadError ?: state.projectActionError)?.let {
            Text(SecretRedactor.redact(it), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(disabled = retrying || state.draft.submitting || manager.sessions.any { it.isBusy }, onClick = {
                if (!retrying) {
                    retrying = true
                    scope.launch {
                        try {
                            val firstLoad = !manager.initialized
                            if (firstLoad) manager.loadSessions(initialWorkspace) else manager.retryLoadIssues()
                            if (firstLoad) state.draft.resolveWorkspace(initialWorkspace, manager.projects)
                            state.sessionLoadError = null
                            state.projectActionError = null
                        } catch (error: CancellationException) { throw error }
                        catch (error: Exception) { state.sessionLoadError = SecretRedactor.redact(error.message ?: error.toString()) }
                        finally { retrying = false }
                    }
                }
            }) { Text(if (retrying) "重试中…" else "重试加载") }
            Button(onClick = { details = !details }) { Text(if (details) "收起详情" else "查看详情") }
        }
        if (details) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 16.dp)) {
                    (listOfNotNull(state.sessionLoadError, state.projectActionError) + manager.loadIssues).forEach {
                        Text(SecretRedactor.redact(it), style = FluentTheme.typography.caption)
                    }
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
}
