package com.kzagent.kagent.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import coil3.compose.SubcomposeAsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
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
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR
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
        table = { ScrollableMarkdownTable(it) },
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
 * Keeps a wide table inside the message width while exposing the complete table through a
 * visible, draggable horizontal scrollbar. Cells wrap instead of using the renderer's default
 * single-line ellipsis, so scrolling never leaves hidden cell text inaccessible.
 */
@Composable
private fun ScrollableMarkdownTable(model: MarkdownComponentModel) {
    val tableMaxWidth = LocalMarkdownDimens.current.tableMaxWidth
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableCornerSize = LocalMarkdownDimens.current.tableCornerSize
    val tableBackground = LocalMarkdownColors.current.tableBackground
    val columnsCount = remember(model.node) {
        model.node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
    }
    val rowsCount = remember(model.node) {
        model.node.children.count { it.type == ROW } + 1
    }
    val columnWidths = rememberMarkdownTableColumnWidths(model, columnsCount, tableCellWidth)
    val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width }
    val horizontalScrollState = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportWidth = if (tableMaxWidth == Dp.Unspecified) maxWidth else minOf(maxWidth, tableMaxWidth)
        val scrollable = shouldScrollMarkdownTable(tableWidth, viewportWidth)
        val containerWidth = minOf(tableWidth, viewportWidth)
        Column(
            modifier = Modifier
                .width(containerWidth)
                .background(tableBackground, RoundedCornerShape(tableCornerSize))
                .semantics {
                    collectionInfo = CollectionInfo(rowCount = rowsCount, columnCount = columnsCount)
                },
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .requiredWidth(tableWidth),
            ) {
                MarkdownTableRows(model, columnWidths)
            }
            if (scrollable) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(horizontalScrollState),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberMarkdownTableColumnWidths(
    model: MarkdownComponentModel,
    columnsCount: Int,
    minimumWidth: Dp,
): List<Dp> {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val tableRows = remember(model.node) {
        model.node.children.filter { it.type == HEADER || it.type == ROW }
    }

    return remember(model.content, model.typography.table, tableRows, textMeasurer, density, cellPadding, minimumWidth) {
        List(columnsCount) { columnIndex ->
            val measuredWidth = tableRows.maxOfOrNull { row ->
                val cell = row.children.filter { it.type == CELL }.getOrNull(columnIndex)
                    ?: return@maxOfOrNull 0.dp
                val style = if (row.type == HEADER) {
                    model.typography.table.copy(fontWeight = FontWeight.Bold)
                } else {
                    model.typography.table
                }
                val textWidthPx = textMeasurer.measure(
                    text = cell.getUnescapedTextInNode(model.content).trim('|', ' '),
                    style = style,
                    softWrap = false,
                    maxLines = 1,
                ).size.width
                with(density) { textWidthPx.toDp() }
            } ?: 0.dp
            constrainMarkdownTableColumnWidth(
                measuredTextWidth = measuredWidth,
                horizontalPadding = cellPadding,
                minimumWidth = minimumWidth,
                maximumWidth = 480.dp,
            )
        }
    }
}

@Composable
private fun MarkdownTableRows(model: MarkdownComponentModel, columnWidths: List<Dp>) {
    var rowIndex = 1
    model.node.children.forEach { child ->
        when (child.type) {
            HEADER -> MarkdownAdaptiveTableRow(
                content = model.content,
                row = child,
                columnWidths = columnWidths,
                style = model.typography.table,
                rowIndex = 0,
                isHeader = true,
            )
            ROW -> {
                MarkdownAdaptiveTableRow(
                    content = model.content,
                    row = child,
                    columnWidths = columnWidths,
                    style = model.typography.table,
                    rowIndex = rowIndex,
                    isHeader = false,
                )
                rowIndex++
            }
            TABLE_SEPARATOR -> MarkdownDivider()
        }
    }
}

@Composable
private fun MarkdownAdaptiveTableRow(
    content: String,
    row: ASTNode,
    columnWidths: List<Dp>,
    style: TextStyle,
    rowIndex: Int,
    isHeader: Boolean,
) {
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val tableWidth = columnWidths.fold(0.dp) { total, width -> total + width }
    Row(
        modifier = Modifier.requiredWidth(tableWidth).height(IntrinsicSize.Max),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.children.filter { it.type == CELL }.forEachIndexed { columnIndex, cell ->
            Column(
                modifier = Modifier
                    .width(columnWidths.getOrElse(columnIndex) { 160.dp })
                    .padding(cellPadding)
                    .semantics {
                        if (isHeader) heading()
                        collectionItemInfo = CollectionItemInfo(
                            rowIndex = rowIndex,
                            rowSpan = 1,
                            columnIndex = columnIndex,
                            columnSpan = 1,
                        )
                    },
            ) {
                MarkdownTableBasicText(
                    content = content,
                    cell = cell,
                    style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

internal fun shouldScrollMarkdownTable(
    tableWidth: Dp,
    viewportWidth: Dp,
): Boolean = tableWidth > viewportWidth

internal fun constrainMarkdownTableColumnWidth(
    measuredTextWidth: Dp,
    horizontalPadding: Dp,
    minimumWidth: Dp,
    maximumWidth: Dp,
): Dp = (measuredTextWidth + horizontalPadding * 2).coerceIn(minimumWidth, maximumWidth)

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
    // Compare canonical paths so macOS aliases such as /var -> /private/var do
    // not reject a file that is genuinely inside the workspace. The boundary
    // remains enforced after symlink resolution, so escaping symlinks stay blocked.
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
