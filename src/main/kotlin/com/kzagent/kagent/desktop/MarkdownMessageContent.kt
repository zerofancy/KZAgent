package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun shouldRenderMarkdown(role: String): Boolean = role == "user" || role == "assistant"

@Composable
internal fun MessageContent(
    message: DisplayMessage,
    workspace: Path,
) {
    if (!shouldRenderMarkdown(message.role)) {
        SelectionContainer {
            Text(
                message.content,
                style = when (message.role) {
                    "tool_call" -> MaterialTheme.typography.bodyMedium
                    "tool_result" -> MaterialTheme.typography.bodySmall
                    else -> MaterialTheme.typography.bodyLarge
                },
            )
        }
        return
    }

    val escapedContent = remember(message.content) { escapeUnsupportedMarkdown(message.content) }
    val markdownState = rememberMarkdownState(escapedContent)
    val imageAltByLink = remember(message.content) { extractImageAltByLink(message.content) }
    val imageTransformer = remember(workspace, imageAltByLink) {
        WorkspaceImageTransformer(workspace, imageAltByLink)
    }
    val components = markdownComponents(
        codeBlock = {
            MarkdownHighlightedCodeBlock(
                content = it.content,
                node = it.node,
                style = it.typography.code,
                showHeader = true,
            )
        },
        codeFence = {
            MarkdownHighlightedCodeFence(
                content = it.content,
                node = it.node,
                style = it.typography.code,
                showHeader = true,
            )
        },
        image = { SafeBlockMarkdownImage(it, workspace) },
        inlineImage = { SafeInlineMarkdownImage(it, workspace, imageAltByLink) },
        checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
    )

    SelectionContainer {
        Markdown(
            markdownState = markdownState,
            modifier = Modifier.fillMaxWidth(),
            components = components,
            imageTransformer = imageTransformer,
            error = {
                Text(
                    message.content,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
        )
    }
}

/**
 * Escapes markdown syntax for features that the renderer doesn't handle correctly,
 * so they display as raw text rather than garbled output.
 *
 * Currently covers:
 * - Inline math: $...$ → \$...\$
 * - Block math:  $$...$$ → \$\$...\$\$
 * - Footnote references: [^n] → \[^n]
 * - Footnote definitions: [^n]: → \[^n]:
 */
private val unsupportedMarkdownRegex = Regex(
    pattern = """(?<!\\)\$\$|(?<!\\)\$|\[(\^[^]]+)]""",
)

internal fun escapeUnsupportedMarkdown(text: String): String {
    return unsupportedMarkdownRegex.replace(text) { match ->
        when {
            match.value == "\$\$" -> "\\\$\\\$"
            match.value == "\$"   -> "\\\$"
            match.value.startsWith("[^") -> {
                "\\[" + match.groupValues[1] + "]"
            }
            else -> match.value
        }
    }
}

@Composable
private fun SafeBlockMarkdownImage(
    model: MarkdownComponentModel,
    workspace: Path,
) {
    val referenceLinkHandler = LocalReferenceLinkHandler.current
    val link = model.node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)
        ?.getUnescapedTextInNode(model.content)
        ?: model.node.findReferencedImageLink(model.content, referenceLinkHandler::find)
    val alt = model.node.findImageAlt(model.content)
    val resolved = remember(workspace, link) { link?.let { resolveMarkdownImageSource(workspace, it) } }

    if (resolved == null) {
        ImageFallback(alt)
        return
    }

    SubcomposeAsyncImage(
        model = resolved.model,
        contentDescription = alt,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
        contentScale = ContentScale.Fit,
        loading = { ImageLoadingPlaceholder(Modifier.fillMaxWidth().height(120.dp)) },
        error = { ImageFallback(alt) },
    )
}

@Composable
private fun SafeInlineMarkdownImage(
    model: MarkdownComponentModel,
    workspace: Path,
    imageAltByLink: Map<String, String>,
) {
    val link = model.content
    val alt = imageAltByLink[link]
    val resolved = remember(workspace, link) { resolveMarkdownImageSource(workspace, link) }

    if (resolved == null) {
        ImageFallback(alt, compact = true)
        return
    }

    SubcomposeAsyncImage(
        model = resolved.model,
        contentDescription = alt,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        loading = { ImageLoadingPlaceholder(Modifier.fillMaxSize()) },
        error = { ImageFallback(alt, compact = true) },
    )
}

@Composable
private fun ImageLoadingPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "图片加载中…",
            modifier = Modifier.padding(4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImageFallback(alt: String?, compact: Boolean = false) {
    Text(
        text = alt?.takeIf { it.isNotBlank() } ?: "图片无法加载",
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        maxLines = if (compact) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

internal sealed interface ResolvedMarkdownImage {
    val model: Any

    data class Https(val uri: URI) : ResolvedMarkdownImage {
        override val model: Any = uri.toString()
    }

    data class Local(val path: Path) : ResolvedMarkdownImage {
        override val model: Any = path.toFile()
    }
}

internal fun resolveMarkdownImageSource(workspace: Path, rawLink: String): ResolvedMarkdownImage? {
    val link = rawLink.trim().removeSurrounding("<", ">")
    if (link.isBlank()) return null

    val uri = runCatching { URI(link) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (scheme != null) {
        return when (scheme) {
            "https" -> uri.takeIf { !it.host.isNullOrBlank() }?.let(ResolvedMarkdownImage::Https)
            "file" -> runCatching { Path.of(uri) }.getOrNull()?.let { resolveLocalImage(workspace, it) }
            else -> null
        }
    }

    val localPath = try {
        Path.of(link)
    } catch (_: InvalidPathException) {
        return null
    }
    return resolveLocalImage(workspace, localPath)
}

private fun resolveLocalImage(workspace: Path, imagePath: Path): ResolvedMarkdownImage.Local? {
    val workspaceRoot = runCatching { workspace.toRealPath() }.getOrNull() ?: return null
    val candidate = if (imagePath.isAbsolute) imagePath else workspaceRoot.resolve(imagePath)
    val normalized = candidate.toAbsolutePath().normalize()
    if (!normalized.startsWith(workspaceRoot)) return null

    val realPath = runCatching { normalized.toRealPath() }.getOrNull() ?: return null
    if (!realPath.startsWith(workspaceRoot) || !Files.isRegularFile(realPath)) return null
    return ResolvedMarkdownImage.Local(realPath)
}

private class WorkspaceImageTransformer(
    private val workspace: Path,
    private val imageAltByLink: Map<String, String>,
) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        val resolved = remember(workspace, link) { resolveMarkdownImageSource(workspace, link) } ?: return null
        return Coil3ImageTransformerImpl.transform(resolved.model.toString())
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size = Coil3ImageTransformerImpl.intrinsicSize(painter)

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        val altLength = imageAltByLink[link]?.length ?: 0
        val width = (altLength * 8f + 24f).coerceIn(32f, 200f)
        return PlaceholderConfig(Size(width, 32f))
    }
}

private fun ASTNode.findChildOfTypeRecursive(type: IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == type) return child
        child.findChildOfTypeRecursive(type)?.let { return it }
    }
    return null
}

private fun ASTNode.findImageAlt(content: String): String? {
    val node = findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
        ?: findChildOfTypeRecursive(MarkdownElementTypes.LINK_LABEL)
        ?: return null
    return node.getUnescapedTextInNode(content).trim('[', ']').trim().takeIf { it.isNotEmpty() }
}

private fun ASTNode.findReferencedImageLink(
    content: String,
    findReference: (String) -> String?,
): String? {
    val reference = findChildOfTypeRecursive(MarkdownElementTypes.FULL_REFERENCE_LINK)
        ?: findChildOfTypeRecursive(MarkdownElementTypes.SHORT_REFERENCE_LINK)
        ?: return null
    val label = reference.findChildOfTypeRecursive(MarkdownElementTypes.LINK_LABEL)
        ?.getUnescapedTextInNode(content)
        ?: return null
    return findReference(label)?.takeIf { it.isNotEmpty() }
}

private val inlineImageRegex = Regex(
    pattern = """!\[([^]]*)]\(\s*(?:<([^>]+)>|([^\s)]+))(?:\s+[^)]*)?\s*\)""",
)

internal fun extractImageAltByLink(markdown: String): Map<String, String> = buildMap {
    inlineImageRegex.findAll(markdown).forEach { match ->
        val alt = match.groupValues[1].trim()
        val link = match.groupValues[2].ifBlank { match.groupValues[3] }.trim()
        if (link.isNotBlank()) put(link, alt)
    }
}
