package com.kzagent.kagent.skill

import com.kzagent.kagent.config.SkillsConfig
import com.kzagent.kagent.config.SecretRedactor
import com.kzagent.kagent.tools.TextFileCodec
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

enum class SkillOrigin { BUILTIN, USER, EXTRA }

data class SkillEntry(
    val id: String,
    val source: SkillMdSource?,
    val origin: SkillOrigin,
    val directory: Path? = null,
    val manifest: SkillManifest? = null,
    val diagnostic: String? = null,
    val disabled: Boolean = false,
    val globallyEnabled: Boolean = true,
    val shadowedBy: String? = null,
) {
    val active get() = manifest != null && diagnostic == null && !disabled && globallyEnabled && shadowedBy == null
    val status get() = when {
        diagnostic != null -> "加载失败"
        shadowedBy != null -> "被同名项覆盖"
        !globallyEnabled -> "全局已禁用"
        disabled -> "已禁用"
        else -> "已启用"
    }
    fun readContent(): String = when (val value = source) {
        is SkillMdSource.FileSystem -> TextFileCodec.read(value.path).text
        is SkillMdSource.Classpath -> SkillCatalog::class.java.getResourceAsStream(value.resource)
            ?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("资源不存在")
        null -> error("此条目没有 SKILL.md")
    }
}

/** Discovery is independent of activation so disabled and broken installations remain manageable. */
object SkillCatalog {
    fun scan(root: Path, config: SkillsConfig): List<SkillEntry> {
        val entries = mutableListOf<SkillEntry>()
        val resource = "/builtin-skills/skill-installer/SKILL.md"
        val builtin = SkillCatalog::class.java.getResourceAsStream(resource)?.use { String(it.readBytes(), Charsets.UTF_8) }
        if (builtin != null) entries += SkillEntry(resource, SkillMdSource.Classpath(resource), SkillOrigin.BUILTIN,
            manifest = SkillFrontmatterParser.parse(builtin))
        val roots = linkedMapOf(root.toAbsolutePath().normalize() to SkillOrigin.USER)
        config.extraDirectories.forEach { value ->
            try { roots.putIfAbsent(Path.of(value).toAbsolutePath().normalize(), SkillOrigin.EXTRA) }
            catch (e: Exception) { entries += SkillEntry(value, null, SkillOrigin.EXTRA, diagnostic = "无效目录路径") }
        }
        roots.forEach { (path, origin) ->
            try {
                if (!Files.exists(path) && origin == SkillOrigin.USER) return@forEach
                Files.list(path).use { stream ->
                    stream.filter { Files.isDirectory(it) }.sorted().forEach { dir ->
                        val file = dir.resolve("SKILL.md")
                        try {
                            val manifest = SkillFrontmatterParser.parse(TextFileCodec.read(file).text)
                            require(manifest != null && Regex("^[a-z0-9][a-z0-9-]*$").matches(manifest.name)) { "SKILL.md 元数据无效" }
                            entries += SkillEntry(file.toString(), SkillMdSource.FileSystem(file), origin, dir, manifest)
                        } catch (e: Exception) {
                            entries += SkillEntry(file.toString(), SkillMdSource.FileSystem(file), origin, dir,
                                diagnostic = SecretRedactor.redact(e.message ?: "读取失败"))
                        }
                    }
                }
            } catch (e: Exception) {
                entries += SkillEntry(path.toString(), null, origin, diagnostic = SecretRedactor.redact(e.message ?: "目录不可读"))
            }
        }
        val seen = mutableMapOf<String, String>()
        return entries.map { entry ->
            val name = entry.manifest?.name
            entry.copy(disabled = name in config.disabled, globallyEnabled = config.enabled,
                shadowedBy = name?.let { seen.putIfAbsent(it.lowercase(), entry.id) })
        }
    }

    fun canUninstall(root: Path, entry: SkillEntry): Boolean = entry.origin == SkillOrigin.USER &&
        entry.directory?.toAbsolutePath()?.normalize()?.parent == root.toAbsolutePath().normalize()

    fun uninstall(root: Path, entry: SkillEntry) {
        require(canUninstall(root, entry)) { "仅允许卸载默认目录的用户 skill" }
        val base = root.toAbsolutePath().normalize()
        val target = requireNotNull(entry.directory).toAbsolutePath().normalize()
        // Real-path equality also rejects Windows junctions and aliases, not just symbolic links.
        require(base.toRealPath() == base && target.toRealPath() == target && !Files.isSymbolicLink(target)) { "不能删除链接目录" }
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(dir.toRealPath() == dir && dir.startsWith(target)) { "拒绝越界或链接目录" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
