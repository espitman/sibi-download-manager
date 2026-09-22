package com.espitman.sdm.domain

enum class DownloadFailure(val label: String) {
    NETWORK_LOSS("Network lost"),
    TIMEOUT("Timed out"),
    EXPIRED_LINK("Link expired"),
    INSUFFICIENT_STORAGE("Not enough storage"),
    TRANSIENT_HTTP("Temporary server error"),
    HTTP_ERROR("HTTP error"),
    OTHER("Download failed");

    companion object {
        private val HTTP_STATUS = Regex("""(?i)\bHTTP\s+(\d{3})\b""")

        fun classify(errorText: String?): DownloadFailure {
            val text = errorText.orEmpty()
            val httpCode = HTTP_STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull()
            when (httpCode) {
                401, 403, 410 -> return EXPIRED_LINK
                408, 429 -> return TRANSIENT_HTTP
                in 500..599 -> return TRANSIENT_HTTP
                in 100..399, in 400..499 -> return HTTP_ERROR
            }
            val lowered = text.lowercase()
            if (containsTlsPhrase(lowered)) return OTHER
            if (containsTimeoutPhrase(lowered)) return TIMEOUT
            if (containsInsufficientStoragePhrase(lowered)) return INSUFFICIENT_STORAGE
            if (containsNetworkLossPhrase(lowered)) return NETWORK_LOSS
            return OTHER
        }

        private fun containsTimeoutPhrase(lowered: String): Boolean =
            "timeout" in lowered ||
                "timed out" in lowered ||
                "time out" in lowered

        private fun containsInsufficientStoragePhrase(lowered: String): Boolean =
            "enospc" in lowered ||
                "no space left" in lowered ||
                "insufficient storage" in lowered ||
                "not enough storage" in lowered ||
                "not enough space" in lowered

        private fun containsTlsPhrase(lowered: String): Boolean =
            "sslhandshake" in lowered ||
                "ssl handshake" in lowered ||
                "sslpeerunverified" in lowered ||
                "certificateexception" in lowered ||
                "certpathvalidator" in lowered ||
                "trust anchor" in lowered ||
                "certificate pinning" in lowered ||
                "chain validation failed" in lowered ||
                "handshake failed" in lowered ||
                "handshake_failure" in lowered ||
                "unrecognized ssl" in lowered ||
                "plaintext connection" in lowered ||
                "cleartext communication" in lowered ||
                "cleartext http traffic" in lowered

        private fun containsNetworkLossPhrase(lowered: String): Boolean =
            "unknownhost" in lowered ||
                "unknown host" in lowered ||
                "unable to resolve" in lowered ||
                "no address associated" in lowered ||
                "nodename nor servname" in lowered ||
                "failed to connect" in lowered ||
                "failed connecting" in lowered ||
                "connection refused" in lowered ||
                "connection reset" in lowered ||
                "connection abort" in lowered ||
                "software caused connection abort" in lowered ||
                "network unreachable" in lowered ||
                "network is unreachable" in lowered ||
                "host unreachable" in lowered ||
                "broken pipe" in lowered ||
                "network i/o" in lowered ||
                "i/o error" in lowered ||
                "ioexception" in lowered ||
                "socketexception" in lowered ||
                "econnreset" in lowered ||
                "econnrefused" in lowered ||
                "econnaborted" in lowered ||
                "enetunreach" in lowered ||
                "enetdown" in lowered ||
                "ehostunreach" in lowered ||
                "unexpected end of stream" in lowered ||
                "stream was reset" in lowered
    }
}
