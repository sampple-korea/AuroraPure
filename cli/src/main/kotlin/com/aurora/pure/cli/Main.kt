/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import com.aurora.gplayapi.data.models.PlayFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Help.Ansi
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Parameters
import picocli.CommandLine.ScopeType

@Command(
    name = "aurora-pure",
    mixinStandardHelpOptions = true,
    version = ["Aurora Pure CLI 1.2.1"],
    description = ["Interactive, download-only Google Play APK client."],
    subcommands = [
        SearchCommand::class,
        InfoCommand::class,
        VariantsCommand::class,
        DownloadCommand::class,
        VerifyCommand::class,
        HistoryCommand::class,
        ConfigCommand::class,
        DoctorCommand::class
    ]
)
class RootCommand : Callable<Int> {
    @Option(
        names = ["--language"],
        description = ["UI language: auto, en, zh, ja, or ko"],
        scope = ScopeType.INHERIT
    )
    var language: String? = null

    private val configStore = ConfigStore()
    val config: CliConfig by lazy { configStore.load() }
    val messages: Messages by lazy { Messages(language ?: config.language) }
    val history: HistoryStore by lazy { HistoryStore() }
    val gateway: PlayGateway by lazy {
        PlayGateway(CliPaths.cacheDirectory, messages.locale)
    }

    override fun call(): Int = InteractiveWizard(this).run()

    fun saveConfig(value: CliConfig) = configStore.save(value)
    fun configPath(): Path = configStore.path
    fun outputDirectory(override: String? = null): Path = override
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?: config.outputDirectory.takeIf(String::isNotBlank)?.let(Path::of)
        ?: CliPaths.defaultOutputDirectory
}

class DeliveryOptions {
    @Option(names = ["-a", "--architecture"], description = ["Optional scan filter: universal, both, 64, 32, arm64, arm32, x86_64, or x86; default scans all"])
    var architecture: String? = null

    @Option(names = ["-d", "--density"], description = ["Optional scan filter: current, all, a named density, or an exact DPI number; default scans all standard DPI"])
    var density: String? = null

    @Option(names = ["--current-dpi"], description = ["DPI used by the 'current' density mode"])
    var currentDpi: Int? = null

    @Option(names = ["--android-api"], description = ["Highest Android API to probe (21-36)"])
    var androidApi: Int? = null

    fun resolve(): ResolvedDeliveryOptions {
        val dpi = currentDpi ?: DensityMode.DEFAULT_DPI
        require(dpi in 72..1000) { "DPI must be between 72 and 1000" }
        val api = androidApi ?: PlayGateway.CURRENT_ANDROID_API
        require(api in PlayGateway.MIN_ANDROID_API..PlayGateway.CURRENT_ANDROID_API) {
            "Android API must be between ${PlayGateway.MIN_ANDROID_API} and ${PlayGateway.CURRENT_ANDROID_API}"
        }
        return ResolvedDeliveryOptions(
            architecture = ArchitectureMode.parse(architecture ?: ArchitectureMode.UNIVERSAL.cliName),
            density = DensityMode.parse(density ?: "all", dpi),
            maxSdk = api
        )
    }
}

data class ResolvedDeliveryOptions(
    val architecture: ArchitectureMode,
    val density: DensityMode,
    val maxSdk: Int
)

@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search Google Play by app name."]
)
class SearchCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Parameters(arity = "1..*", paramLabel = "QUERY") lateinit var query: Array<String>
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        status(root.messages["status.searching"])
        val results = runBlockingCli { root.gateway.search(query.joinToString(" ")) }
        if (json) println(GSON.toJson(results)) else printApps(results, root.messages)
        return if (results.isEmpty()) 4 else 0
    }
}

@Command(
    name = "info",
    mixinStandardHelpOptions = true,
    description = ["Show app metadata without downloading."]
)
class InfoCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Parameters(index = "0", paramLabel = "PACKAGE_OR_PLAY_URL") lateinit var input: String
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        val packageName = requireNotNull(parsePackageName(input)) {
            "info requires a package name or Google Play app URL"
        }
        status(root.messages["status.details"])
        val app = runBlockingCli { root.gateway.details(packageName) }
        if (json) println(GSON.toJson(app)) else printApp(app, root.messages)
        return 0
    }
}

@Command(
    name = "variants",
    aliases = ["plan"],
    mixinStandardHelpOptions = true,
    description = ["Discover and list actual ABI × DPI × Android delivery combinations."]
)
class VariantsCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Parameters(index = "0", paramLabel = "PACKAGE_OR_PLAY_URL") lateinit var input: String
    @Mixin lateinit var delivery: DeliveryOptions
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        val packageName = requireNotNull(parsePackageName(input)) {
            "variants requires a package name or Google Play app URL"
        }
        val options = delivery.resolve()
        val variants = discover(root, packageName, options, quiet = json)
        if (json) println(GSON.toJson(variants)) else printVariants(variants, root.messages)
        return 0
    }
}

@Command(
    name = "download",
    mixinStandardHelpOptions = true,
    description = ["Resolve all language splits, download, verify, and save APK files."]
)
class DownloadCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Parameters(index = "0", paramLabel = "PACKAGE_OR_PLAY_URL") lateinit var input: String
    @Mixin lateinit var delivery: DeliveryOptions
    @Option(names = ["--variant"], description = ["universal, combined, exact variant ID, or displayed 1-based number"])
    var variant: String? = null
    @Option(names = ["-o", "--output"], description = ["Output directory"])
    var output: String? = null
    @Option(names = ["-j", "--parallel"], description = ["Parallel APK downloads (1-8)"])
    var parallelism: Int? = null
    @Option(names = ["-y", "--yes"], description = ["Accept the selected plan without a confirmation prompt"])
    var yes = false
    @Option(names = ["--json"], description = ["Print the final result as JSON; progress remains on stderr"])
    var json = false

    override fun call(): Int {
        val packageName = requireNotNull(parsePackageName(input)) {
            "download requires a package name or Google Play app URL"
        }
        val options = delivery.resolve()
        val variants = discover(root, packageName, options, quiet = json)
        if (!json) printVariants(variants, root.messages)
        val selected = selectVariant(variants, variant, yes, root.messages)
        status(root.messages["status.languages"])
        val plan = runBlockingCli {
            root.gateway.resolvePlan(packageName, options.architecture, options.density, selected)
        }
        if (!json) printPlan(plan, root.messages)
        if (!yes && !confirm(root.messages)) return 5

        val count = parallelism ?: root.config.parallelism
        require(count in 1..DownloadEngine.MAX_PARALLELISM) {
            "Parallel downloads must be between 1 and ${DownloadEngine.MAX_PARALLELISM}"
        }
        val engine = DownloadEngine(
            cacheRoot = CliPaths.cacheDirectory,
            http = root.gateway.http,
            parallelism = count,
            cachedArtifact = root.gateway::cachedArtifact
        )
        val shutdownHook = Thread(engine::cancel, "aurora-pure-cancel")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val printer = ProgressPrinter(plan, root.messages, enabled = !json)
        val result = try {
            runBlockingCli {
                engine.download(plan, root.outputDirectory(output), printer::update)
            }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            printer.finish()
        }
        status(root.messages["status.verifying"])
        root.history.add(plan, result)
        if (json) {
            println(GSON.toJson(mapOf(
                "path" to result.path.toAbsolutePath().normalize().toString(),
                "packageName" to plan.packageName,
                "versionName" to plan.versionName,
                "versionCode" to plan.versionCode,
                "apkCount" to result.apkCount,
                "bytes" to result.bytes,
                "sha256" to ArtifactCache.digest(result.path),
                "allDeclaredLanguages" to plan.requestedLocales.size,
                "profiles" to plan.profiles.map(DeliveryProfile::id)
            )))
        } else {
            println(root.messages.text("status.saved", result.path.toAbsolutePath().normalize()))
        }
        return 0
    }
}

@Command(
    name = "verify",
    mixinStandardHelpOptions = true,
    description = ["Cryptographically verify an Aurora Pure .apk or APK-only .apks file."]
)
class VerifyCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Parameters(index = "0", paramLabel = "FILE") lateinit var input: Path
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        val result = ArchiveVerifier.verify(input.toAbsolutePath().normalize())
        if (json) {
            println(GSON.toJson(result))
        } else {
            println(root.messages.text("verify.ok", result.apkCount, input))
            result.packages.forEach { (packageName, versions) ->
                println("  $packageName · ${versions.joinToString()}")
            }
            println("  SHA-256 ${result.sha256}")
        }
        return 0
    }
}

@Command(
    name = "history",
    mixinStandardHelpOptions = true,
    description = ["List or clear local download history. No tokens or URLs are stored."]
)
class HistoryCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Option(names = ["--clear"], description = ["Clear history without deleting downloaded files"])
    var clear = false
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        if (clear) {
            root.history.clear()
            println(root.messages["status.history_cleared"])
            return 0
        }
        val entries = root.history.list()
        if (json) {
            println(GSON.toJson(entries))
        } else if (entries.isEmpty()) {
            println(root.messages["status.history_empty"])
        } else {
            println(root.messages["history.header"])
            entries.asReversed().forEach {
                println("${it.completedAt} | ${it.packageName} | ${it.versionName} (${it.versionCode}) | " +
                    "${it.apkCount} | ${formatBytes(it.outputBytes)} | ${it.outputPath}")
            }
        }
        return 0
    }
}

@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = ["Show or change persistent CLI defaults."]
)
class ConfigCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Option(names = ["--set"], paramLabel = "KEY=VALUE", description = ["Set a value; may be repeated"])
    var changes: Array<String> = emptyArray()
    @Option(names = ["--reset"], description = ["Reset all defaults"])
    var reset = false
    @Option(names = ["--json"], description = ["Print machine-readable JSON"])
    var json = false

    override fun call(): Int {
        val config = if (reset) CliConfig() else root.config.copy()
        changes.forEach { change ->
            val key = change.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            val value = change.substringAfter('=', "").trim()
            require(key.isNotBlank() && value.isNotBlank() && '=' in change) {
                "Configuration values use KEY=VALUE"
            }
            when (key) {
                "language" -> config.language = value
                "parallel", "parallelism" -> config.parallelism = value.toInt()
                "output", "output-directory" -> config.outputDirectory = Path.of(value).toString()
                else -> throw IllegalArgumentException("Unknown configuration key: $key")
            }
        }
        if (reset || changes.isNotEmpty()) {
            root.saveConfig(config)
            if (!json) println(root.messages.text("status.config_saved", root.configPath()))
        }
        if (json) println(GSON.toJson(config.validate())) else {
            println(root.messages["config.current"])
            println(GSON.toJson(config.validate()))
        }
        return 0
    }
}

@Command(
    name = "doctor",
    mixinStandardHelpOptions = true,
    description = ["Check Java, protocol library, directories, and optionally network metadata access."]
)
class DoctorCommand : Callable<Int> {
    @ParentCommand lateinit var root: RootCommand
    @Option(names = ["--online"], description = ["Also query public Google Play metadata"])
    var online = false

    override fun call(): Int {
        val javaFeature = Runtime.version().feature()
        require(javaFeature >= 21) { "Java 21 or newer is required" }
        val model = PlayFile()
        val output = root.outputDirectory()
        listOf(CliPaths.configDirectory, CliPaths.cacheDirectory, CliPaths.dataDirectory, output)
            .forEach { Files.createDirectories(it) }
        require(listOf(CliPaths.configDirectory, CliPaths.cacheDirectory, CliPaths.dataDirectory, output)
            .all(Files::isWritable)) { "A required Aurora Pure directory is not writable" }
        println(root.messages.text("doctor.java", System.getProperty("java.version")))
        println(root.messages.text("doctor.protocol", model.javaClass.name))
        println(root.messages.text("doctor.config", root.configPath()))
        println(root.messages.text("doctor.cache", CliPaths.cacheDirectory))
        println(root.messages.text("doctor.output", output))
        if (online) {
            val app = runBlockingCli { root.gateway.details("com.android.chrome") }
            println("Online: ${app.packageName} · ${app.versionName}")
        }
        println(root.messages["doctor.ready"])
        return 0
    }
}

private class InteractiveWizard(private val root: RootCommand) {
    private val input = BufferedReader(InputStreamReader(System.`in`))
    private val text = root.messages

    fun run(): Int {
        println(text["app.title"])
        println(text["app.subtitle"])
        println()
        val raw = ask(text["prompt.app"])
        val app = resolveApp(raw)
        printApp(app, text)
        println()

        val options = DeliveryOptions().resolve()
        val variants = discover(root, app.packageName, options, quiet = false)
        printVariants(variants, text)
        val selected = promptVariant(variants, text, input)
        status(text["status.languages"])
        val plan = runBlockingCli {
            root.gateway.resolvePlan(
                app.packageName,
                options.architecture,
                options.density,
                selected
            )
        }
        printPlan(plan, text)
        if (!confirm(text, input)) return 5
        val defaultOutput = root.outputDirectory().toString()
        val output = ask(text.text("prompt.output", defaultOutput)).ifBlank { defaultOutput }
        val engine = DownloadEngine(
            CliPaths.cacheDirectory,
            root.gateway.http,
            root.config.parallelism,
            root.gateway::cachedArtifact
        )
        val hook = Thread(engine::cancel, "aurora-pure-cancel")
        Runtime.getRuntime().addShutdownHook(hook)
        val printer = ProgressPrinter(plan, text, enabled = true)
        val result = try {
            runBlockingCli { engine.download(plan, Path.of(output), printer::update) }
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            printer.finish()
        }
        root.history.add(plan, result)
        println(text.text("status.saved", result.path.toAbsolutePath().normalize()))
        return 0
    }

    private fun resolveApp(raw: String): AppInfo {
        val packageName = parsePackageName(raw)
        if (packageName != null) {
            status(text["status.details"])
            return runBlockingCli { root.gateway.details(packageName) }
        }
        status(text["status.searching"])
        val results = runBlockingCli { root.gateway.search(raw) }.take(20)
        require(results.isNotEmpty()) { text["status.no_results"] }
        printApps(results, text)
        val choice = ask(text.text("prompt.select_app", results.size)).toIntOrNull()
            ?.takeIf { it in 1..results.size }
            ?: throw IllegalArgumentException(text["error.input"])
        return results[choice - 1]
    }

    private fun ask(prompt: String): String {
        print(prompt)
        System.out.flush()
        return input.readLine() ?: throw IllegalArgumentException(text["error.input"])
    }
}

private fun discover(
    root: RootCommand,
    packageName: String,
    options: ResolvedDeliveryOptions,
    quiet: Boolean
): List<DeliveryVariant> {
    if (!quiet) status(root.messages["status.discovering"])
    val lastShown = AtomicLong(0)
    return runBlockingCli {
        root.gateway.discover(
            packageName,
            options.architecture,
            options.density,
            options.maxSdk
        ) { completed, profile ->
            if (!quiet) {
                val now = System.nanoTime()
                if (completed == 1 || now - lastShown.get() >= 250_000_000L) {
                    lastShown.set(now)
                    status(root.messages.text(
                        "status.probe",
                        completed,
                        profile.abi.label,
                        profile.densityDpi,
                        profile.sdkVersion
                    ))
                }
            }
        }
    }
}

private fun selectVariant(
    variants: List<DeliveryVariant>,
    requested: String?,
    assumeYes: Boolean,
    messages: Messages
): DeliveryVariant {
    if (requested != null) {
        if (requested.equals("universal", true)) {
            return variants.firstOrNull(DeliveryVariant::universal)
                ?: throw IllegalArgumentException("No universal variant exists in the selected scope")
        }
        if (requested.equals("combined", true)) {
            return variants.firstOrNull(DeliveryVariant::aggregate)
                ?: throw IllegalArgumentException("No combined variant exists in the selected scope")
        }
        requested.toIntOrNull()?.let { index ->
            return variants.getOrNull(index - 1)
                ?: throw IllegalArgumentException(messages["error.variant"])
        }
        return variants.firstOrNull { it.id == requested }
            ?: throw IllegalArgumentException(messages["error.variant"])
    }
    if (assumeYes) throw IllegalArgumentException(messages["error.noninteractive"])
    return promptVariant(variants, messages, BufferedReader(InputStreamReader(System.`in`)))
}

private fun promptVariant(
    variants: List<DeliveryVariant>,
    messages: Messages,
    input: BufferedReader
): DeliveryVariant {
    print(messages.text("prompt.variant", variants.size))
    System.out.flush()
    val choice = input.readLine()?.trim()?.toIntOrNull()
        ?.takeIf { it in 1..variants.size }
        ?: throw IllegalArgumentException(messages["error.input"])
    return variants[choice - 1]
}

private fun confirm(messages: Messages, input: BufferedReader = BufferedReader(InputStreamReader(System.`in`))): Boolean {
    print(messages["prompt.confirm"])
    System.out.flush()
    return input.readLine()?.trim()?.lowercase(Locale.ROOT) !in setOf("n", "no", "아니요", "いいえ", "否")
}

private fun printApps(apps: List<AppInfo>, messages: Messages) {
    if (apps.isEmpty()) {
        println(messages["status.no_results"])
        return
    }
    apps.forEachIndexed { index, app ->
        println("${index + 1}. ${app.name} · ${app.developer}")
        println("   ${app.packageName}${if (app.versionName.isBlank()) "" else " · ${app.versionName}"}")
    }
}

private fun printApp(app: AppInfo, messages: Messages) {
    println(app.name)
    println("${messages["label.developer"]}: ${app.developer}")
    println("${messages["label.package"]}: ${app.packageName}")
    if (app.versionCode > 0) println("${messages["label.version"]}: ${app.versionName} (${app.versionCode})")
    if (app.description.isNotBlank()) println(app.description.replace(Regex("\\s+"), " ").take(500))
}

private fun printVariants(variants: List<DeliveryVariant>, messages: Messages) {
    println(messages["table.header"])
    variants.withIndex()
        .groupBy { it.value.versionCode }
        .toSortedMap(reverseOrder())
        .forEach { (versionCode, indexedVariants) ->
            println()
            println(messages.text("version.group", indexedVariants.first().value.versionName, versionCode))
            indexedVariants.forEach { indexedVariant ->
                val index = indexedVariant.index
                val variant = indexedVariant.value
                val version = "${variant.versionName} (${variant.versionCode})"
                val architecture = variant.architectures.joinToString("+")
                val android = "${DeviceProfiles.androidRelease(variant.minSdk)}+ (API ${variant.minSdk})"
                val density = variant.densities.joinToString(",")
                val tested = variant.androidApis.joinToString(",") { "${DeviceProfiles.androidRelease(it)}/$it" }
                val label = when {
                    variant.universal -> "${messages["label.universal"]}: "
                    variant.aggregate -> "${messages["label.combined"]}: "
                    else -> ""
                }
                println("${index + 1} | $label$version | $architecture | $android | $density | $tested | " +
                    "${variant.artifactCount} | ${formatBytes(variant.totalBytes)}")
                println("    id=${variant.id}")
                variant.profiles
                    .groupBy { it.abi.label to it.densityDpi }
                    .toSortedMap(compareBy<Pair<String, Int>>({ it.first }, { it.second }))
                    .forEach { (target, profiles) ->
                        val apis = profiles.map(DeliveryProfile::sdkVersion).distinct().sortedDescending()
                        println("    ${messages["label.actual_combination"]}: ${target.first} × ${target.second}dpi × " +
                            apis.joinToString(", ") { "Android ${DeviceProfiles.androidRelease(it)} / API $it" })
                    }
            }
        }
}

private fun printPlan(plan: DownloadPlan, messages: Messages) {
    println()
    println(messages["label.plan"])
    println("  ${messages["label.package"]}: ${plan.packageName}")
    println("  ${messages["label.version"]}: ${plan.versionName} (${plan.versionCode})")
    println("  ${messages["label.android"]}: ${DeviceProfiles.androidRelease(plan.minSdk)}+ (API ${plan.minSdk})")
    println("  ${messages["label.architecture"]}: ${plan.profiles.map { it.abi.label }.distinct().joinToString()}")
    println("  ${messages["label.dpi"]}: ${plan.profiles.map { it.densityDpi }.distinct().sorted().joinToString()} dpi")
    println("  ${messages["label.languages"]}: ${plan.requestedLocales.size}")
    println("  ${messages["label.files"]}: ${plan.uniqueArtifacts.size} · ${formatBytes(plan.totalBytes)}")
    if (plan.hasAdditionalData) println("  ⚠ ${messages["label.additional_data"]}")
}

private class ProgressPrinter(
    private val plan: DownloadPlan,
    private val messages: Messages,
    private val enabled: Boolean
) {
    private var lastNanos = 0L
    private var lastCompleted = -1

    @Synchronized
    fun update(progress: DownloadProgress) {
        if (!enabled) return
        val now = System.nanoTime()
        if (progress.completedFiles == lastCompleted && now - lastNanos < 250_000_000L) return
        lastNanos = now
        lastCompleted = progress.completedFiles
        status(messages.text(
            "status.downloading",
            progress.completedFiles,
            progress.totalFiles,
            formatBytes(progress.downloadedBytes),
            formatBytes(progress.totalBytes)
        ))
    }

    fun finish() {
        if (enabled && plan.uniqueArtifacts.isNotEmpty()) System.err.flush()
    }
}

internal fun parsePackageName(value: String): String? {
    val trimmed = value.trim()
    if (PACKAGE_NAME.matches(trimmed)) return trimmed
    val decoded = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    if (decoded.scheme !in setOf("https", "http", "market")) return null
    if (decoded.scheme in setOf("https", "http") && decoded.host !in setOf("play.google.com", "market.android.com")) {
        return null
    }
    val query = decoded.rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == "id" }
        ?.substringAfter('=', "")
        ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
    return query?.takeIf(PACKAGE_NAME::matches)
}

private fun status(message: String) = System.err.println(message)

private fun safeError(error: Throwable): String {
    val leaf = generateSequence(error) { it.cause }.last()
    return (leaf.message ?: leaf.javaClass.simpleName)
        .replace(URL_SECRET, "<redacted-url>")
        .replace(EMAIL, "<redacted-email>")
        .replace(Regex("(?i)(authToken|token|cookie)=?[^\\s,;]+"), "$1=<redacted>")
        .take(1000)
}

private fun <T> runBlockingCli(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

fun main(args: Array<String>) {
    val root = RootCommand()
    val command = CommandLine(root)
        .setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF))
        .setExecutionExceptionHandler { error, line, _ ->
            val message = runCatching { root.messages.text("error.prefix", safeError(error)) }
                .getOrElse { "Error: ${safeError(error)}" }
            line.err.println(message)
            2
        }
    val exitCode = command.execute(*args)
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")
private val URL_SECRET = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
