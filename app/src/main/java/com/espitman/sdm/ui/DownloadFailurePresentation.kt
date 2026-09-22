package com.espitman.sdm.ui

import com.espitman.sdm.domain.DownloadFailure
import com.espitman.sdm.domain.ErrorReportSanitizer

internal const val FAILED_DOWNLOAD_ERROR_DETAIL_MAX_LENGTH = ErrorReportSanitizer.MAX_LENGTH

internal fun failedDownloadCardLabel(errorText: String?): String =
    DownloadFailure.classify(errorText).label

internal fun sanitizePersistedErrorDetail(
    errorText: String?,
    maxLength: Int = FAILED_DOWNLOAD_ERROR_DETAIL_MAX_LENGTH,
): String = ErrorReportSanitizer.sanitize(errorText, maxLength)

internal fun failedDownloadLastError(errorText: String?): String {
    val label = failedDownloadCardLabel(errorText)
    val detail = sanitizePersistedErrorDetail(errorText)
    return if (detail.isEmpty()) label else "$label · $detail"
}
