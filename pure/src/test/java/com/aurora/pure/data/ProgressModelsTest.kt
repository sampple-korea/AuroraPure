/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressModelsTest {
    @Test
    fun discoveryFractionIsZeroBeforeTheTotalIsKnown() {
        assertEquals(0f, DiscoveryProgress().fraction, 0f)
        assertEquals(0f, DiscoveryProgress(pathsCompleted = 3, totalPaths = 0).fraction, 0f)
    }

    @Test
    fun discoveryFractionTracksCompletedPaths() {
        val progress = DiscoveryProgress(pathsCompleted = 7, totalPaths = 28)

        assertEquals(0.25f, progress.fraction, 0.0001f)
    }

    @Test
    fun discoveryFractionNeverExceedsOne() {
        val progress = DiscoveryProgress(pathsCompleted = 40, totalPaths = 28)

        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun downloadFractionIsZeroWithoutAKnownTotal() {
        assertEquals(0f, record(downloadedBytes = 500, totalBytes = 0).fraction, 0f)
    }

    @Test
    fun downloadFractionTracksTransferredBytes() {
        val record = record(downloadedBytes = 250, totalBytes = 1_000)

        assertEquals(0.25f, record.fraction, 0.0001f)
    }

    @Test
    fun remainingTimeNeedsBothARateAndAKnownTotal() {
        assertNull(record(downloadedBytes = 100, totalBytes = 1_000).secondsRemaining)
        assertNull(
            record(downloadedBytes = 100, totalBytes = 0, bytesPerSecond = 500).secondsRemaining
        )
    }

    @Test
    fun remainingTimeDividesTheOutstandingBytesByTheRate() {
        val record = record(downloadedBytes = 1_000, totalBytes = 6_000, bytesPerSecond = 500)

        assertEquals(10L, record.secondsRemaining)
    }

    @Test
    fun remainingTimeIsAbsentOnceEverythingIsTransferred() {
        val record = record(downloadedBytes = 6_000, totalBytes = 6_000, bytesPerSecond = 500)

        assertNull(record.secondsRemaining)
    }

    @Test
    fun remainingTimeIsAbsentRatherThanZeroInTheFinalSecond() {
        val record = record(downloadedBytes = 5_800, totalBytes = 6_000, bytesPerSecond = 500)

        assertNull(record.secondsRemaining)
    }

    @Test
    fun activeDownloadCountIgnoresFinishedTasks() {
        val state = PureUiState(
            records = listOf(
                record(status = TaskStatus.DOWNLOADING),
                record(status = TaskStatus.QUEUED),
                record(status = TaskStatus.COMPLETED),
                record(status = TaskStatus.CANCELLED),
                record(status = TaskStatus.PAUSED)
            )
        )

        assertEquals(2, state.activeDownloadCount)
    }

    private fun record(
        status: TaskStatus = TaskStatus.DOWNLOADING,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        bytesPerSecond: Long = 0
    ) = DownloadRecord(
        id = "task",
        packageName = "com.example.app",
        displayName = "Example",
        versionName = "1.0",
        versionCode = 1,
        planFingerprint = "fingerprint",
        checkedAt = 0,
        createdAt = 0,
        status = status,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond
    )
}
