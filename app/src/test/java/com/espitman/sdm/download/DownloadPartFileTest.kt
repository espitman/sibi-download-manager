package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DownloadPartFileTest {

    @Test
    fun destinationAndResolvedFilenameDeriveTheSamePartPath() {
        val directory = File("/downloads")
        val destination = File(directory, "archive.zip")
        val fromDestination = DownloadPartFile.forDestination(destination)
        val fromName = DownloadPartFile.forResolvedFilename(directory, "archive.zip")
        assertEquals(fromName, fromDestination)

        val hash = MessageDigest.getInstance("SHA-256")
            .digest("archive.zip".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(".sdm-$hash.part", fromDestination.name)
        assertEquals(directory, fromDestination.parentFile)
        assertTrue(fromDestination.name.startsWith(".sdm-") && fromDestination.name.endsWith(".part"))
    }

    @Test
    fun collidingResolvedNamesStayDeterministicForResume() {
        val directory = File("/downloads")
        val first = DownloadPartFile.forResolvedFilename(directory, "report (1).pdf")
        val second = DownloadPartFile.forResolvedFilename(directory, "report (1).pdf")
        assertEquals(first, second)
        assertEquals(
            DownloadPartFile.forDestination(File(directory, "report (1).pdf")),
            first,
        )
    }
}
