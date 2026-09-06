/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
}

val signingPropertiesFile = file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use(::load)
    }
}

fun Properties.signingSecret(name: String): String {
    getProperty(name)?.takeIf(String::isNotBlank)?.let { return it }
    val secretFile = getProperty("${name}_FILE")
        ?.takeIf(String::isNotBlank)
        ?: error("Missing $name or ${name}_FILE in signing.properties")
    return rootProject.file(secretFile).readText().trim()
        .takeIf(String::isNotBlank)
        ?: error("Signing secret file for $name is empty")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

android {
    namespace = "com.aurora.pure"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aurora.pure"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "UPSTREAM_VERSION", "\"4.8.3\"")
        buildConfigField("String", "UPSTREAM_COMMIT", "\"e9be2c8293e02cc362d603df6b12b019fdb849f2\"")
    }

    signingConfigs {
        if (signingPropertiesFile.exists()) {
            create("release") {
                keyAlias = signingProperties.getProperty("KEY_ALIAS")
                keyPassword = signingProperties.signingSecret("KEY_PASSWORD")
                storeFile = rootProject.file(signingProperties.getProperty("STORE_FILE"))
                storePassword = signingProperties.signingSecret("STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-process:${libs.versions.lifecycle.get()}")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.squareup.okhttp)
    implementation(libs.auroraoss.gplayapi)
    implementation("com.android.tools.build:apksig:9.2.0")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}
