/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.text.MessageFormat
import java.time.Instant
import java.util.Locale
import java.util.ResourceBundle

data class CliConfig(
    var language: String = "auto",
    var parallelism: Int = DownloadEngine.DEFAULT_PARALLELISM,
    var outputDirectory: String = ""
) {
    fun validate(): CliConfig = apply {
        require(language in setOf("auto", "en", "ko", "ja", "zh", "zh-CN")) {
            "language must be auto, en, zh, ja, or ko"
        }
        require(parallelism in 1..DownloadEngine.MAX_PARALLELISM) {
            "parallelism must be between 1 and ${DownloadEngine.MAX_PARALLELISM}"
        }
    }
}

data class HistoryEntry(
    val completedAt: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val outputPath: String,
    val outputSha256: String,
    val outputBytes: Long,
    val apkCount: Int,
    val profiles: List<String>
)

object CliPaths {
    private val userHome: Path = Path.of(
        System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: "."
    ).toAbsolutePath().normalize()
    private val windows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

    val configDirectory: Path by lazy {
        when {
            windows -> envPath("APPDATA")?.resolve("AuroraPure")
            else -> envPath("XDG_CONFIG_HOME")?.resolve("aurora-pure")
        } ?: userHome.resolve(if (windows) "AppData/Roaming/AuroraPure" else ".config/aurora-pure")
    }
    val cacheDirectory: Path by lazy {
        when {
            windows -> envPath("LOCALAPPDATA")?.resolve("AuroraPure/Cache")
            else -> envPath("XDG_CACHE_HOME")?.resolve("aurora-pure")
        } ?: userHome.resolve(if (windows) "AppData/Local/AuroraPure/Cache" else ".cache/aurora-pure")
    }
    val dataDirectory: Path by lazy {
        when {
            windows -> envPath("LOCALAPPDATA")?.resolve("AuroraPure/Data")
            else -> envPath("XDG_DATA_HOME")?.resolve("aurora-pure")
        } ?: userHome.resolve(if (windows) "AppData/Local/AuroraPure/Data" else ".local/share/aurora-pure")
    }
    val defaultOutputDirectory: Path by lazy { userHome.resolve("Downloads/AuroraPure") }

    private fun envPath(name: String): Path? = System.getenv(name)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
}

class ConfigStore(private val file: Path = CliPaths.configDirectory.resolve("config.json")) {
    fun load(): CliConfig {
        if (!Files.isRegularFile(file)) return CliConfig()
        return runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use {
                GSON.fromJson(it, CliConfig::class.java)
            }.validate()
        }.getOrElse { throw IllegalArgumentException("Invalid CLI configuration: ${it.message}", it) }
    }

    fun save(config: CliConfig) {
        config.validate()
        atomicWrite(file, GSON.toJson(config))
    }

    val path: Path get() = file
}

class HistoryStore(private val file: Path = CliPaths.dataDirectory.resolve("history.json")) {
    fun list(): List<HistoryEntry> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use {
                GSON.fromJson<List<HistoryEntry>>(it, HISTORY_TYPE) ?: emptyList()
            }
        }.getOrElse { throw IllegalArgumentException("Invalid CLI history: ${it.message}", it) }
    }

    @Synchronized
    fun add(plan: DownloadPlan, result: DownloadResult) {
        val entry = HistoryEntry(
            completedAt = Instant.now().toString(),
            packageName = plan.packageName,
            versionName = plan.versionName,
            versionCode = plan.versionCode,
            outputPath = result.path.toAbsolutePath().normalize().toString(),
            outputSha256 = ArtifactCache.digest(result.path),
            outputBytes = result.bytes,
            apkCount = result.apkCount,
            profiles = plan.profiles.map(DeliveryProfile::id).distinct()
        )
        atomicWrite(file, GSON.toJson((list() + entry).takeLast(MAX_HISTORY)))
    }

    fun clear() {
        Files.deleteIfExists(file)
    }

    val path: Path get() = file

    companion object {
        private const val MAX_HISTORY = 500
        private val HISTORY_TYPE = object : TypeToken<List<HistoryEntry>>() {}.type
    }
}

class Messages(language: String) {
    val code: String = normalizedLanguage(language)
    val locale: Locale = when (code) {
        "ko" -> Locale.KOREAN
        "ja" -> Locale.JAPANESE
        "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.ENGLISH
    }
    private val bundle = ResourceBundle.getBundle("messages", locale)

    operator fun get(key: String): String = bundle.getString(key)

    fun text(key: String, vararg arguments: Any): String =
        MessageFormat(bundle.getString(key), locale).format(arguments)

    companion object {
        fun normalizedLanguage(value: String?): String {
            val requested = value?.trim()?.takeIf(String::isNotBlank)
            val source = if (requested == null || requested.equals("auto", true)) {
                Locale.getDefault().language
            } else {
                requested
            }
            return when (source.lowercase(Locale.ROOT).replace('_', '-')) {
                "ko", "ko-kr" -> "ko"
                "ja", "ja-jp" -> "ja"
                "zh", "zh-cn", "zh-hans", "zh-sg" -> "zh"
                else -> "en"
            }
        }
    }
}

internal val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

internal fun atomicWrite(path: Path, content: String) {
    Files.createDirectories(path.parent)
    val partial = path.resolveSibling(".${path.fileName}.partial")
    Files.writeString(partial, content + "\n", StandardCharsets.UTF_8)
    runCatching {
        Files.setPosixFilePermissions(
            partial,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        )
    }
    try {
        Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
