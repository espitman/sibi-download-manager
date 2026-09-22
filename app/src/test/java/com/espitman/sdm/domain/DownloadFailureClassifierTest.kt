package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFailureClassifierTest {
    @Test
    fun http401403And410AreExpiredLinks() {
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify("HTTP 401: Unauthorized"))
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify("HTTP 403: Forbidden"))
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify("http 410: Gone"))
        assertEquals("Link expired", DownloadFailure.EXPIRED_LINK.label)
    }

    @Test
    fun http408429And5xxAreTransient() {
        assertEquals(DownloadFailure.TRANSIENT_HTTP, DownloadFailure.classify("HTTP 408: Request Timeout"))
        assertEquals(DownloadFailure.TRANSIENT_HTTP, DownloadFailure.classify("HTTP 429: Too Many Requests"))
        assertEquals(DownloadFailure.TRANSIENT_HTTP, DownloadFailure.classify("HTTP 500: Internal Server Error"))
        assertEquals(DownloadFailure.TRANSIENT_HTTP, DownloadFailure.classify("HTTP 503: Service Unavailable"))
        assertEquals(DownloadFailure.TRANSIENT_HTTP, DownloadFailure.classify("HTTP 599: Unknown"))
        assertEquals("Temporary server error", DownloadFailure.TRANSIENT_HTTP.label)
    }

    @Test
    fun otherHttpStatusesAreHttpError() {
        assertEquals(DownloadFailure.HTTP_ERROR, DownloadFailure.classify("HTTP 404: Not Found"))
        assertEquals(DownloadFailure.HTTP_ERROR, DownloadFailure.classify("HTTP 400: Bad Request"))
        assertEquals(DownloadFailure.HTTP_ERROR, DownloadFailure.classify("HTTP 416: Range Not Satisfiable"))
        assertEquals("HTTP error", DownloadFailure.HTTP_ERROR.label)
    }

    @Test
    fun timeoutPhrasesAreTimeoutWhenNotHttp408() {
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailure.classify("SocketTimeoutException: timeout"))
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailure.classify("failed: timed out"))
        assertEquals(DownloadFailure.TIMEOUT, DownloadFailure.classify("Read time out"))
        assertEquals("Timed out", DownloadFailure.TIMEOUT.label)
    }

    @Test
    fun storagePhrasesAreInsufficientStorage() {
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify("ENOSPC"))
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify("java.io.IOException: No space left on device"),
        )
        assertEquals(
            DownloadFailure.INSUFFICIENT_STORAGE,
            DownloadFailure.classify("Insufficient storage for destination"),
        )
        assertEquals("Not enough storage", DownloadFailure.INSUFFICIENT_STORAGE.label)
    }

    @Test
    fun dnsConnectResetAndNetworkIoAreNetworkLoss() {
        assertEquals(
            DownloadFailure.NETWORK_LOSS,
            DownloadFailure.classify("java.net.UnknownHostException: Unable to resolve host"),
        )
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("Failed to connect to example.com/1.2.3.4:443"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("Connection reset by peer"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("Network is unreachable"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("Software caused connection abort"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("unexpected end of stream"))
        assertEquals(DownloadFailure.NETWORK_LOSS, DownloadFailure.classify("I/O error while reading"))
        assertEquals("Network lost", DownloadFailure.NETWORK_LOSS.label)
    }

    @Test
    fun tlsHandshakeAndCertificateErrorsAreOtherNotNetworkLoss() {
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify(
                "javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor for certification path not found",
            ),
        )
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify("javax.net.ssl.SSLPeerUnverifiedException: Hostname not verified"),
        )
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify("Certificate pinning failure"),
        )
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify("javax.net.ssl.SSLException: Unrecognized SSL message, plaintext connection?"),
        )
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify(
                "javax.net.ssl.SSLHandshakeException: Read error: ssl=0x1: I/O error during system call, Connection reset by peer",
            ),
        )
        assertEquals("Download failed", DownloadFailure.OTHER.label)
    }

    @Test
    fun unrecognizedAndBlankErrorsAreOther() {
        assertEquals(DownloadFailure.OTHER, DownloadFailure.classify(null))
        assertEquals(DownloadFailure.OTHER, DownloadFailure.classify(""))
        assertEquals(DownloadFailure.OTHER, DownloadFailure.classify("Interrupted when the app process stopped"))
        assertEquals("Download failed", DownloadFailure.OTHER.label)
    }

    @Test
    fun httpStatusTakesPrecedenceOverTimeoutPhrase() {
        assertEquals(
            DownloadFailure.TRANSIENT_HTTP,
            DownloadFailure.classify("HTTP 408: timeout while waiting"),
        )
    }

    @Test
    fun tlsHandshakeTimedOutIsOtherNotTimeout() {
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify("SSLHandshakeException: SSL handshake timed out"),
        )
        assertEquals(
            DownloadFailure.OTHER,
            DownloadFailure.classify("javax.net.ssl.SSLHandshakeException: Read timed out"),
        )
        assertEquals(
            DownloadFailure.TIMEOUT,
            DownloadFailure.classify("SocketTimeoutException: timed out"),
        )
    }
}
