# SPDX-FileCopyrightText: 2026 Aurora Pure contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# GPlayApi uses generated protobuf and Kotlin serialization metadata.
-keep class com.aurora.gplayapi.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
