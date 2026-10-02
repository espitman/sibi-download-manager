package com.espitman.sdm.download

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpConnectionTelemetryTest {
    @Test fun parallelStreamsRemainIndependentAcrossDownloadsAndRepeatedClose() {
        val streams = (1..32).map { HttpConnectionTelemetry.track("parallel", ByteArrayInputStream(byteArrayOf(1))) }
        val other = HttpConnectionTelemetry.track("other", ByteArrayInputStream(byteArrayOf(2)))
        try {
            assertEquals(32, HttpConnectionTelemetry.active.value["parallel"])
            streams.first().close()
            streams.first().close()
            assertEquals(31, HttpConnectionTelemetry.active.value["parallel"])
            assertEquals(1, HttpConnectionTelemetry.active.value["other"])
        } finally {
            streams.forEach { it.close() }
            other.close()
        }
        assertNull(HttpConnectionTelemetry.active.value["parallel"])
        assertNull(HttpConnectionTelemetry.active.value["other"])
    }

    @Test fun failedTransferAndFailedCloseReleaseTheConnection() {
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { throw IOException("close failed") }
        }
        try {
            HttpConnectionTelemetry.track("failed", input).use { throw IOException("transfer failed") }
        } catch (_: IOException) { }
        assertNull(HttpConnectionTelemetry.active.value["failed"])
    }
}
