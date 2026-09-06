/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

import java.util.Properties
import java.util.TimeZone

object DeviceProfiles {
    val allPlayLocales: List<String> = ALL_PLAY_LOCALES
        .lineSequence()
        .flatMap { it.splitToSequence(',') }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    fun matrix(
        architecture: ArchitectureMode,
        density: DensityMode,
        sdkVersions: List<Int>
    ): List<DeliveryProfile> = architecture.variants.flatMap { abi ->
        density.densities.flatMap { dpi ->
            sdkVersions.distinct().map { sdk -> DeliveryProfile(abi, dpi, sdk) }
        }
    }

    fun properties(profile: DeliveryProfile): Properties {
        val stream = requireNotNull(
            DeviceProfiles::class.java.getResourceAsStream(
                "/device-profiles/${profile.abi.profileResource}"
            )
        ) { "Bundled GPlayApi device profile is missing: ${profile.abi.profileResource}" }
        return Properties().apply {
            stream.use(::load)
            setProperty("Platforms", profile.abi.platforms.joinToString(","))
            setProperty("Screen.Density", profile.densityDpi.toString())
            setProperty("Build.VERSION.SDK_INT", profile.sdkVersion.toString())
            setProperty("Build.VERSION.RELEASE", androidRelease(profile.sdkVersion))
            setProperty("Locales", allPlayLocales.joinToString(","))
            setProperty("TimeZone", TimeZone.getDefault().id)
            setProperty(
                "UserReadableName",
                "Aurora Pure ${profile.abi.label} ${profile.densityDpi}dpi API ${profile.sdkVersion}"
            )
        }
    }

    fun androidRelease(api: Int): String = when (api) {
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
        else -> api.toString()
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
