package com.kzagent.kagent.tools

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal data class TextFile(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
)

/** Decodes common Windows text formats and preserves their charset and BOM on write. */
internal object TextFileCodec {
    private val UTF32_LE = Charset.forName("UTF-32LE")
    private val UTF32_BE = Charset.forName("UTF-32BE")
    private val GB18030 = Charset.forName("GB18030")
    private val WINDOWS_1252 = Charset.forName("windows-1252")

    fun read(path: Path): TextFile = decode(Files.readAllBytes(path), path.toString())

    fun decode(bytes: ByteArray, displayName: String): TextFile {
        val signatures = listOf(
            Signature(byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()), UTF32_BE),
            Signature(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00), UTF32_LE),
            Signature(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), StandardCharsets.UTF_8),
            Signature(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), StandardCharsets.UTF_16BE),
            Signature(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), StandardCharsets.UTF_16LE),
        )
        signatures.firstOrNull { bytes.startsWith(it.bytes) }?.let { signature ->
            val payload = bytes.copyOfRange(signature.bytes.size, bytes.size)
            return TextFile(decodeStrict(payload, signature.charset, displayName), signature.charset, signature.bytes)
        }

        val charset = when {
            canDecode(bytes, StandardCharsets.UTF_8) -> StandardCharsets.UTF_8
            canDecode(bytes, GB18030) -> GB18030
            else -> WINDOWS_1252
        }
        return TextFile(decodeStrict(bytes, charset, displayName), charset, byteArrayOf())
    }

    fun write(path: Path, file: TextFile) {
        val encoded = file.charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(file.text))
        val content = ByteArray(file.bom.size + encoded.remaining())
        file.bom.copyInto(content)
        encoded.get(content, file.bom.size, encoded.remaining())
        Files.write(path, content)
    }

    private fun canDecode(bytes: ByteArray, charset: Charset): Boolean =
        runCatching { decodeStrict(bytes, charset, "file") }.isSuccess

    private fun decodeStrict(bytes: ByteArray, charset: Charset, displayName: String): String = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Cannot decode text file $displayName as ${charset.name()}.")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private data class Signature(val bytes: ByteArray, val charset: Charset)
}
