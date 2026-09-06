# SPDX-FileCopyrightText: 2026 Aurora Pure contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# GPlayApi uses generated protobuf and Kotlin serialization metadata.
-keep class com.aurora.gplayapi.** { *; }

# Aurora Pure reads every split APK's signed binary AndroidManifest.xml before
# export. Keep the parser implementation used by ApkManifestReader in release
# builds; it is an internal apksig class and otherwise has no reflective roots.
-keep class com.android.apksig.internal.apk.AndroidBinXmlParser { *; }
-keep class com.android.apksig.internal.apk.AndroidBinXmlParser$* { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
