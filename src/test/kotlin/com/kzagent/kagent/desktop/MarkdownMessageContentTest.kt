package com.kzagent.kagent.desktop

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownMessageContentTest {
    @Test
    fun onlyConversationMessagesRenderAsMarkdown() {
        assertTrue(shouldRenderMarkdown("user"))
        assertTrue(shouldRenderMarkdown("assistant"))
        assertFalse(shouldRenderMarkdown("tool_call"))
        assertFalse(shouldRenderMarkdown("tool_result"))
        assertFalse(shouldRenderMarkdown("system"))
    }

    @Test
    fun resolvesHttpsAndWorkspaceLocalImages() {
        val workspace = Files.createTempDirectory("kagent-markdown-images")
        val nestedImage = workspace.resolve("images").createDirectories().resolve("preview.png").createFile()

        val https = assertIs<ResolvedMarkdownImage.Https>(
            resolveMarkdownImageSource(workspace, "https://example.com/preview.svg")
        )
        assertEquals("https://example.com/preview.svg", https.uri.toString())

        val relative = assertIs<ResolvedMarkdownImage.Local>(
            resolveMarkdownImageSource(workspace, "images/preview.png")
        )
        assertEquals(nestedImage.toRealPath(), relative.path)

        val absolute = assertIs<ResolvedMarkdownImage.Local>(
            resolveMarkdownImageSource(workspace, nestedImage.toString())
        )
        assertEquals(nestedImage.toRealPath(), absolute.path)

        val fileUri = assertIs<ResolvedMarkdownImage.Local>(
            resolveMarkdownImageSource(workspace, nestedImage.toUri().toString())
        )
        assertEquals(nestedImage.toRealPath(), fileUri.path)
    }

    @Test
    fun rejectsUnsafeOrUnavailableImageSources() {
        val workspace = Files.createTempDirectory("kagent-markdown-images")
        val outside = Files.createTempFile("kagent-outside-image", ".png")

        assertNull(resolveMarkdownImageSource(workspace, "http://example.com/image.png"))
        assertNull(resolveMarkdownImageSource(workspace, "data:image/png;base64,AAAA"))
        assertNull(resolveMarkdownImageSource(workspace, "ftp://example.com/image.png"))
        assertNull(resolveMarkdownImageSource(workspace, "https:///missing-host.png"))
        assertNull(resolveMarkdownImageSource(workspace, "missing.png"))
        assertNull(resolveMarkdownImageSource(workspace, outside.toString()))
        assertNull(resolveMarkdownImageSource(workspace, "../${outside.fileName}"))
        assertNull(resolveMarkdownImageSource(workspace, "."))
    }

    @Test
    fun rejectsSymlinkThatEscapesWorkspaceWhenSupported() {
        val workspace = Files.createTempDirectory("kagent-markdown-images")
        val outside = Files.createTempFile("kagent-outside-image", ".png")
        val symlink = workspace.resolve("escaped.png")
        val created = runCatching { Files.createSymbolicLink(symlink, outside) }.isSuccess
        if (created) {
            assertNull(resolveMarkdownImageSource(workspace, symlink.fileName.toString()))
        }
    }

    @Test
    fun extractsAltTextForInlineImages() {
        val markdown = """
            ![架构图](images/architecture.png)
            Inline ![status badge](<https://example.com/status badge.svg> "Status") text.
        """.trimIndent()

        assertEquals(
            mapOf(
                "images/architecture.png" to "架构图",
                "https://example.com/status badge.svg" to "status badge",
            ),
            extractImageAltByLink(markdown),
        )
    }

    @Test
    fun escapeUnsupportedMarkdownEscapesMathAndFootnotes() {
        val input = """
            行内公式：${'$'}E = mc^2$
            块级公式：
            $$
            \sum_{i=1}^{n} i = \frac{n(n+1)}{2}
            $$
            这是脚注[^1]和另一个脚注[^label]。
            
            [^1]: 这是脚注定义
        """.trimIndent()

        val escaped = escapeUnsupportedMarkdown(input)

        assertTrue(escaped.contains("""\${'$'}E = mc^2\$"""))
        assertTrue(escaped.contains("""\$\$"""))
        assertTrue(escaped.contains("""\[^1]"""))
        assertTrue(escaped.contains("""\[^label]"""))
        // Dollar signs that were already escaped should stay escaped
        assertFalse(escaped.contains("""\\\$"""))
    }

    @Test
    fun alreadyEscapedDollarSignsArePreserved() {
        val input = """Already escaped: \${'$'}100 and \${'$'}\${'$'}200\${'$'}\${'$'}"""
        val escaped = escapeUnsupportedMarkdown(input)
        // Should not double-escape
        assertEquals(input, escaped)
    }

    @Test
    fun normalTextIsUnchanged() {
        val input = "This is **bold** and *italic* with `code` and [link](url)"
        val escaped = escapeUnsupportedMarkdown(input)
        assertEquals(input, escaped)
    }
}
