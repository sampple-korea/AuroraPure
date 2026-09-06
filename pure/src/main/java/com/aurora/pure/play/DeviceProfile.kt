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
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryProfile
import java.util.Properties
import java.util.TimeZone

object DeviceProfile {
    /**
     * Locales advertised by Aurora's bundled GPlayApi device profiles. Google Play can only
     * return language splits that an app actually publishes, but none are filtered to the UI
     * language by Aurora Pure.
     */
    val allPlayLocales: List<String> = ALL_PLAY_LOCALES
        .lineSequence()
        .flatMap { line -> line.splitToSequence(',') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    fun deliveryProfiles(
        choice: ArchitectureChoice,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
    ): List<DeliveryProfile> {
        val firstAbi = supportedAbis.firstOrNull().orEmpty()
        val x86Family = firstAbi.startsWith("x86")
        return choice.variants.map { variant ->
            val platforms = when (variant) {
                ArchitectureVariant.BIT_64 -> {
                    if (x86Family) listOf("x86_64") else listOf("arm64-v8a")
                }

                ArchitectureVariant.BIT_32 -> {
                    if (x86Family) listOf("x86") else listOf("armeabi-v7a", "armeabi")
                }
            }
            DeliveryProfile(variant, platforms)
        }
    }

    fun properties(context: Context, profile: DeliveryProfile): Properties = Properties().apply {
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
            (configuration.navigation != Configuration.NAVIGATION_NONAV).toString()
        )

        val metrics = context.resources.displayMetrics
        setProperty("Screen.Density", metrics.densityDpi.toString())
        setProperty("Screen.Width", metrics.widthPixels.toString())
        setProperty("Screen.Height", metrics.heightPixels.toString())
        setProperty("Platforms", profile.platforms.joinToString(","))
        setProperty(
            "Features",
            context.packageManager.systemAvailableFeatures.mapNotNull { it.name }.joinToString(",")
        )
        setProperty("Locales", allPlayLocales.joinToString(","))
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

    fun description(profiles: List<DeliveryProfile>): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE)
        profiles.forEach { profile ->
            append(" · ").append(profile.variant.bitness).append("-bit (")
            append(profile.platforms.joinToString(", ")).append(')')
        }
    }

    private const val ALL_PLAY_LOCALES = """
        af,am,ar,ar_AE,ar_IL,ar_XB,as,as_IN,ast,az,az_AZ,be,be_BY,bg,bg_BG,bn,bn_BD,bn_IN,
        bs,bs_BA,ca,ca_ES,cs,cs_CZ,da,da_DK,de,de_AT,de_CH,de_DE,el,el_GR,en,en_AU,en_CA,
        en_DI,en_GB,en_IE,en_IN,en_NZ,en_PH,en_US,en_XA,en_XC,en_ZA,en_ZG,eo,es,es_419,es_ES,
        es_US,et,et_EE,eu,eu_ES,fa,fa_IR,fi,fi_FI,fil,fil_PH,fr,fr_BE,fr_CA,fr_CH,fr_FR,ga,
        ga_IE,gl,gl_ES,gu,gu_IN,he,hi,hi_IN,hr,hr_HR,hu,hu_HU,hy,hy_AM,ia,in,in_ID,is,is_IS,
        it,it_IT,iw,iw_IL,ja,ja_JP,ka,ka_GE,kab,kk,kk_KZ,km,km_KH,kmr,kn,kn_IN,ko,ko_KR,
        ky,ky_KG,lo,lo_LA,lt,lt_LT,lv,lv_LV,mk,mk_MK,ml,ml_IN,mn,mn_MN,mr,mr_IN,ms,ms_MY,
        my,my_MM,my_ZG,nb,nb_NO,ne,ne_NP,nl,nl_BE,nl_NL,or,or_IN,pa,pa_IN,pl,pl_PL,pl_SP,
        pt,pt_BR,pt_PT,ro,ro_RO,ru,ru_RU,sc,si,si_LK,sk,sk_SK,sl,sl_SI,so,sq,sq_AL,sr,
        sr_Latn,sr_RS,sv,sv_SE,sw,ta,ta_IN,te,te_IN,tg,tg_TJ,th,th_TH,tk,tk_TM,tr,tr_TR,
        uk,uk_UA,ur,ur_PK,uz,uz_UZ,vi,vi_VN,yue,zh_CN,zh_HK,zh_TW,zu
    """
}
