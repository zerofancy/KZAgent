package com.kzagent.kagent.tools

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextFileCodecTest {
    @Test
    fun plainUtf8DoesNotResolveOptionalCharsets() {
        val resolvedNames = mutableListOf<String>()

        val decoded = TextFileCodec.decode(
            bytes = "root instructions".toByteArray(StandardCharsets.UTF_8),
            displayName = "AGENTS.md",
        ) { name ->
            resolvedNames += name
            error("Unexpected charset lookup: $name")
        }

        assertEquals("root instructions", decoded.text)
        assertEquals(StandardCharsets.UTF_8, decoded.charset)
        assertContentEquals(byteArrayOf(), decoded.bom)
        assertEquals(emptyList(), resolvedNames)
    }

    @Test
    fun bomInputResolvesOnlyItsMatchingCharset() {
        val utf32 = Charset.forName("UTF-32LE")
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
        val resolvedNames = mutableListOf<String>()

        val decoded = TextFileCodec.decode(
            bytes = bom + "文本".toByteArray(utf32),
            displayName = "utf32.txt",
        ) { name ->
            resolvedNames += name
            Charset.forName(name)
        }

        assertEquals("文本", decoded.text)
        assertEquals(utf32, decoded.charset)
        assertContentEquals(bom, decoded.bom)
        assertEquals(listOf("UTF-32LE"), resolvedNames)
    }

    @Test
    fun failedOptionalLookupDoesNotPoisonLaterUtf8Reads() {
        assertFailsWith<IllegalStateException> {
            TextFileCodec.decode(
                bytes = byteArrayOf(0x81.toByte()),
                displayName = "legacy.txt",
            ) { error("charset provider unavailable") }
        }

        assertEquals(
            "still readable",
            TextFileCodec.decode(
                "still readable".toByteArray(StandardCharsets.UTF_8),
                "AGENTS.md",
            ).text,
        )
    }
}
