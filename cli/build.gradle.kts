/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.aurora.pure"
version = "1.4.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    applicationName = "aurora-pure"
    mainClass = "com.aurora.pure.cli.MainKt"
    // Windows consoles and pipes otherwise fall back to a legacy code page, which turns every
    // Korean, Japanese, and Chinese message the CLI ships into question marks.
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8"
    )
}

// The version used to be typed into Main.kt and into all four message bundles, so `--version` and
// the wizard banner kept reporting an older release than the one being built. It is generated from
// the single value above instead.
val generatedVersionDirectory = layout.buildDirectory.dir("generated/version")

val generateVersionResource by tasks.registering {
    val version = providers.provider { project.version.toString() }
    val output = generatedVersionDirectory
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("aurora-pure-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=${version.get()}\n")
    }
}

val gplayApiAar by configurations.creating
val extractedGplayApi = layout.buildDirectory.dir("generated/gplayapi")

val extractGplayApi by tasks.registering(Sync::class) {
    from({ gplayApiAar.map(::zipTree) }) {
        include("classes.jar", "res/raw/*.properties")
    }
    into(extractedGplayApi)
}

dependencies {
    gplayApiAar("com.auroraoss:gplayapi:${libs.versions.gplayapi.get()}@aar")
    implementation(files(extractedGplayApi.map { it.file("classes.jar") }))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:${libs.versions.kotlin.get()}")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.protobuf:protobuf-javalite:4.34.0")
    implementation(libs.squareup.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.android.tools.build:apksig:9.2.0")
    implementation("info.picocli:picocli:4.7.7")
    testImplementation(libs.junit)
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(extractGplayApi)
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(extractGplayApi)
}

tasks.processResources {
    dependsOn(extractGplayApi)
    from(extractedGplayApi.map { it.dir("res/raw") }) {
        into("device-profiles")
    }
    from(generateVersionResource)
}

tasks.test {
    useJUnit()
}

tasks.named<Zip>("distZip") {
    archiveFileName.set("aurora-pure-cli-${project.version}.zip")
}

tasks.named<Tar>("distTar") {
    archiveFileName.set("aurora-pure-cli-${project.version}.tar")
}
