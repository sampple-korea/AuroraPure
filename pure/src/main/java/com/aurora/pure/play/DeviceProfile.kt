/*
 * This file is based on Aurora Store's NativeDeviceInfoProvider.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

object DeviceProfile {
    fun properties(context: Context): Properties = Properties().apply {
        setProperty("UserReadableName", "${Build.MANUFACTURER} ${Build.MODEL}")
        setProperty("Build.HARDWARE", Build.HARDWARE.orEmpty())
        setProperty("Build.RADIO", Build.getRadioVersion() ?: "unknown")
        setProperty("Build.FINGERPRINT", Build.FINGERPRINT.orEmpty())
        setProperty("Build.BRAND", Build.BRAND.orEmpty())
        setProperty("Build.DEVICE", Build.DEVICE.orEmpty())
        setProperty("Build.VERSION.SDK_INT", Build.VERSION.SDK_INT.toString())
        setProperty("Build.VERSION.RELEASE", Build.VERSION.RELEASE.orEmpty())
        setProperty("Build.MODEL", Build.MODEL.orEmpty())
        setProperty("Build.MANUFACTURER", Build.MANUFACTURER.orEmpty())
        setProperty("Build.PRODUCT", Build.PRODUCT.orEmpty())
        setProperty("Build.ID", Build.ID.orEmpty())
        setProperty("Build.BOOTLOADER", Build.BOOTLOADER.orEmpty())

        val configuration = context.resources.configuration
        setProperty("TouchScreen", configuration.touchscreen.toString())
        setProperty("Keyboard", configuration.keyboard.toString())
        setProperty("Navigation", configuration.navigation.toString())
        setProperty("ScreenLayout", (configuration.screenLayout and 15).toString())
        setProperty(
            "HasHardKeyboard",
            (configuration.keyboard == Configuration.KEYBOARD_QWERTY).toString()
        )
        setProperty(
            "HasFiveWayNavigation",
            (configuration.navigation == Configuration.NAVIGATIONHIDDEN_YES).toString()
        )

        val metrics = context.resources.displayMetrics
        setProperty("Screen.Density", metrics.densityDpi.toString())
        setProperty("Screen.Width", metrics.widthPixels.toString())
        setProperty("Screen.Height", metrics.heightPixels.toString())
        setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
        setProperty(
            "Features",
            context.packageManager.systemAvailableFeatures.mapNotNull { it.name }.joinToString(",")
        )
        setProperty(
            "Locales",
            (0 until Locale.getDefault().let { 1 }).map { Locale.getDefault().toString() }
                .joinToString(",")
        )
        setProperty(
            "SharedLibraries",
            context.packageManager.systemSharedLibraryNames?.joinToString(",").orEmpty()
        )
        val activityManager = context.getSystemService(ActivityManager::class.java)
        setProperty("GL.Version", activityManager?.deviceConfigurationInfo?.reqGlEsVersion?.toString() ?: "0")
        setProperty("GL.Extensions", "")

        setProperty("Client", "android-google")
        setProperty("GSF.version", "203019037")
        setProperty("Vending.version", "82151710")
        setProperty("Vending.versionString", "21.5.17-21 [0] [PR] 326734551")
        setProperty("Roaming", "mobile-notroaming")
        setProperty("TimeZone", TimeZone.getDefault().id)
        setProperty("CellOperator", "310")
        setProperty("SimOperator", "38")
    }

    fun description(): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE)
        append(" · ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI")
    }
}
