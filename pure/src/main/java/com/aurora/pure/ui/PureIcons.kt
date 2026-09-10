/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icons the interface actually uses, drawn here rather than pulled from the extended Material
 * icon artifact. Keeping them local means the download, transfer, and verification glyphs the app
 * leans on are always present and consistently weighted, with no extra dependency to ship.
 */
object PureIcons {
    val Search: ImageVector by icon(
        "search",
        "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 " +
            "5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 " +
            "0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
    )

    val Download: ImageVector by icon(
        "download",
        "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
    )

    val Tune: ImageVector by icon(
        "tune",
        "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 " +
            "4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z"
    )

    val Close: ImageVector by icon(
        "close",
        "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 " +
            "19 19 17.59 13.41 12z"
    )

    val Refresh: ImageVector by icon(
        "refresh",
        "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 " +
            "7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 " +
            "4.22 1.78L13 11h7V4l-2.35 2.35z"
    )

    val Check: ImageVector by icon(
        "check",
        "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"
    )

    val Pause: ImageVector by icon(
        "pause",
        "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
    )

    val Play: ImageVector by icon(
        "play",
        "M8 5v14l11-7z"
    )

    val Delete: ImageVector by icon(
        "delete",
        "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
    )

    val Share: ImageVector by icon(
        "share",
        "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11" +
            "c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7" +
            "L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 " +
            "2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 " +
            "2.92-2.92-1.31-2.92-2.92-2.92z"
    )

    val ExpandMore: ImageVector by icon(
        "expand_more",
        "M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z"
    )

    val Info: ImageVector by icon(
        "info",
        "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"
    )

    val Warning: ImageVector by icon(
        "warning",
        "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"
    )

    val Folder: ImageVector by icon(
        "folder",
        "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"
    )

    val Apps: ImageVector by icon(
        "apps",
        "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4z" +
            "m-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"
    )

    val Copy: ImageVector by icon(
        "copy",
        "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 " +
            "2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
    )

    val ArrowBack: ImageVector by icon(
        "arrow_back",
        "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
    )

    val OpenInNew: ImageVector by icon(
        "open_in_new",
        "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 " +
            "3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z"
    )

    val Shield: ImageVector by icon(
        "shield",
        "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 " +
            "14.17l6.59-6.59L18 9l-8 8z"
    )

    private fun icon(name: String, pathData: String) = lazy {
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black)
        ).build()
    }
}
