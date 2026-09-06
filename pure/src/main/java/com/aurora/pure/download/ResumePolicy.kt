/*
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.download

internal object ResumePolicy {
    enum class Decision {
        APPEND,
        RESTART,
        REJECT
    }

    fun decide(offset: Long, status: Int, contentRange: String?): Decision {
        require(offset > 0) { "Resume offset must be positive" }
        if (status == 200) return Decision.RESTART
        if (status != 206) return Decision.REJECT
        val start = Regex("^bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE)
            .find(contentRange.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        return if (start == offset) Decision.APPEND else Decision.REJECT
    }
}
