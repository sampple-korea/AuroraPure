# SPDX-FileCopyrightText: 2026 Aurora Pure contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# GPlayApi uses generated protobuf and Kotlin serialization metadata.
-keep class com.aurora.gplayapi.** { *; }

# APK verification is a security boundary. apksig passes the real-APK JVM
# integration test before shrinking, while aggressive member optimization can
# make v3 verification report a false failure on-device. Preserve the complete
# implementation so release builds behave identically to the verified fixture.
-keep class com.android.apksig.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
