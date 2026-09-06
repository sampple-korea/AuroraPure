/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import picocli.CommandLine

class CliPolicyTest {
    @Test
    fun `every subcommand shows help without required arguments`() {
        listOf("search", "info", "variants", "download", "verify", "history", "config", "doctor")
            .forEach { command ->
                val output = StringWriter()
                val errors = StringWriter()
                val exitCode = CommandLine(RootCommand())
                    .setOut(PrintWriter(output))
                    .setErr(PrintWriter(errors))
                    .execute(command, "--help")
                assertEquals("$command help must exit successfully", 0, exitCode)
                assertTrue(output.toString().contains("Usage: aurora-pure $command"))
                assertTrue(errors.toString().isBlank())
            }
    }

    @Test
    fun `package input accepts package and official Play links only`() {
        assertEquals("com.example.app", parsePackageName("com.example.app"))
        assertEquals(
            "com.example.app",
            parsePackageName("https://play.google.com/store/apps/details?id=com.example.app&hl=en")
        )
        assertEquals("com.example.app", parsePackageName("market://details?id=com.example.app"))
        assertEquals(null, parsePackageName("https://example.com/?id=com.example.app"))
        assertEquals(null, parsePackageName("not a package"))
    }

    @Test
    fun `universal profile matrix includes every ABI and standard DPI`() {
        val profiles = DeviceProfiles.matrix(
            ArchitectureMode.UNIVERSAL,
            DensityMode.parse("all"),
            listOf(36, 31)
        )
        assertEquals(4 * 7 * 2, profiles.size)
        assertEquals(AbiVariant.entries.toSet(), profiles.map { it.abi }.toSet())
        assertEquals(DensityMode.standard.toSet(), profiles.map { it.densityDpi }.toSet())
        assertEquals(setOf(31, 36), profiles.map { it.sdkVersion }.toSet())
    }

    @Test
    fun `device profiles advertise all languages and the exact requested target`() {
        AbiVariant.entries.forEach { abi ->
            val profile = DeliveryProfile(abi, 213, 31)
            val properties = DeviceProfiles.properties(profile)
            assertEquals(abi.platforms.joinToString(","), properties.getProperty("Platforms"))
            assertEquals("213", properties.getProperty("Screen.Density"))
            assertEquals("31", properties.getProperty("Build.VERSION.SDK_INT"))
            assertTrue(properties.getProperty("Locales").contains("ko_KR"))
            assertTrue(properties.getProperty("Locales").contains("zh_CN"))
            assertTrue(properties.getProperty("Locales").contains("ja_JP"))
        }
        assertTrue(DeviceProfiles.allPlayLocales.size > 150)
    }

    @Test
    fun `resume policy only appends an exact partial response`() {
        assertEquals(
            CliResumePolicy.Decision.APPEND,
            CliResumePolicy.decide(100, 500, 206, "bytes 100-499/500")
        )
        assertEquals(
            CliResumePolicy.Decision.REJECT,
            CliResumePolicy.decide(100, 500, 206, "bytes 0-499/500")
        )
        assertEquals(CliResumePolicy.Decision.RESTART, CliResumePolicy.decide(100, 500, 200, null))
        assertEquals(CliResumePolicy.Decision.COMPLETE, CliResumePolicy.decide(500, 500, 416, null))
        assertEquals(CliResumePolicy.Decision.REJECT, CliResumePolicy.decide(499, 500, 416, null))
    }

    @Test
    fun `configuration round trip validates and does not contain session data`() {
        val root = Files.createTempDirectory("aurora-pure-config-test-")
        try {
            val path = root.resolve("config.json")
            val store = ConfigStore(path)
            val expected = CliConfig(
                language = "ko",
                parallelism = 6,
                outputDirectory = root.resolve("output").toString()
            )
            store.save(expected)
            assertEquals(expected, store.load())
            val serialized = Files.readString(path)
            assertFalse(serialized.contains("authToken", true))
            assertFalse(serialized.contains("downloadUrl", true))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `apks verifier rejects metadata entries`() {
        val file = Files.createTempFile("aurora-pure-invalid-", ".apks")
        try {
            ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("base.apk"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("download-info.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
            val error = runCatching { ArchiveVerifier.verify(file) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("only root-level APK"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `all four translations contain every English message key`() {
        fun properties(resource: String): Properties = Properties().apply {
            requireNotNull(CliPolicyTest::class.java.getResourceAsStream(resource)).use(::load)
        }
        val english = properties("/messages.properties").stringPropertyNames()
        listOf("/messages_ko.properties", "/messages_ja.properties", "/messages_zh_CN.properties")
            .forEach { resource ->
                assertEquals(english, properties(resource).stringPropertyNames())
            }
    }
}
