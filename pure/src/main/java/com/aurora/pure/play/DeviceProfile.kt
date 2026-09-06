/*
 * This file is based on Aurora Store's NativeDeviceInfoProvider.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.play

import android.content.Context
import android.content.res.Resources
import android.os.Build
import com.aurora.gplayapi.R as GPlayApiR
import com.aurora.pure.data.ArchitectureChoice
import com.aurora.pure.data.ArchitectureVariant
import com.aurora.pure.data.DeliveryProfile
import com.aurora.pure.data.DensityChoice
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
        densityChoice: DensityChoice = DensityChoice.CURRENT,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
        currentDensityDpi: Int = Resources.getSystem().displayMetrics.densityDpi,
        sdkVersions: List<Int> = listOf(Build.VERSION.SDK_INT)
    ): List<DeliveryProfile> {
        val firstAbi = supportedAbis.firstOrNull().orEmpty()
        val x86Family = firstAbi.startsWith("x86")
        val variants = when (choice) {
            ArchitectureChoice.UNIVERSAL -> listOf(
                ArchitectureVariant.ARM_64,
                ArchitectureVariant.ARM_32,
                ArchitectureVariant.X86_64,
                ArchitectureVariant.X86
            )
            ArchitectureChoice.BOTH -> if (x86Family) {
                listOf(ArchitectureVariant.X86_64, ArchitectureVariant.X86)
            } else {
                listOf(ArchitectureVariant.ARM_64, ArchitectureVariant.ARM_32)
            }
            ArchitectureChoice.BIT_64 -> listOf(
                if (x86Family) ArchitectureVariant.X86_64 else ArchitectureVariant.ARM_64
            )
            ArchitectureChoice.BIT_32 -> listOf(
                if (x86Family) ArchitectureVariant.X86 else ArchitectureVariant.ARM_32
            )
        }
        return variants.flatMap { variant ->
            densityChoice.resolve(currentDensityDpi).flatMap { densityDpi ->
                sdkVersions.distinct().map { sdkVersion ->
                DeliveryProfile(variant, variant.platforms, densityDpi, sdkVersion)
                }
            }
        }
    }

    fun properties(context: Context, profile: DeliveryProfile): Properties = Properties().apply {
        // Foreign ABI discovery needs a coherent reference device, rather than a native
        // ARM fingerprint with only its Platforms field changed. These profiles ship in
        // the pinned GPlayApi AAR and are then narrowed to the exact ABI/DPI/API probe.
        context.resources.openRawResource(profileResource(profile.variant)).use(::load)
        setProperty(
            "UserReadableName",
            "Aurora Pure ${profile.variant.archiveDirectory} ${profile.densityDpi}dpi API ${profile.sdkVersion}"
        )
        setProperty("Build.VERSION.SDK_INT", profile.sdkVersion.toString())
        setProperty("Build.VERSION.RELEASE", androidRelease(profile.sdkVersion))
        setProperty("Screen.Density", profile.densityDpi.toString())
        setProperty("Platforms", profile.platforms.joinToString(","))
        setProperty("Locales", allPlayLocales.joinToString(","))
        setProperty("TimeZone", TimeZone.getDefault().id)
    }

    private fun profileResource(variant: ArchitectureVariant): Int = when (variant) {
        ArchitectureVariant.ARM_64 -> GPlayApiR.raw.gplayapi_px_9a
        ArchitectureVariant.ARM_32 -> GPlayApiR.raw.gplayapi_rm_5_pro
        ArchitectureVariant.X86_64,
        ArchitectureVariant.X86 -> GPlayApiR.raw.gplayapi_google_kiwi_x86_64
    }

    fun description(profiles: List<DeliveryProfile>): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" · Android ").append(Build.VERSION.RELEASE)
        append(" · ")
        append(profiles.map(DeliveryProfile::primaryAbi).distinct().joinToString(", "))
        append(" · ")
        append(profiles.map(DeliveryProfile::densityDpi).distinct().joinToString(", "))
        append(" dpi")
        append(" · API ")
        append(profiles.map(DeliveryProfile::sdkVersion).distinct().joinToString(", "))
    }

    fun androidRelease(sdkVersion: Int): String = when (sdkVersion) {
        21, 22 -> "5"
        23 -> "6"
        24, 25 -> "7"
        26, 27 -> "8"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31, 32 -> "12"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> sdkVersion.toString()
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
