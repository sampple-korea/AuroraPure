/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "com.aurora.pure"
version = "1.2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    applicationName = "aurora-pure"
    mainClass = "com.aurora.pure.cli.MainKt"
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
}

tasks.test {
    useJUnit()
}
