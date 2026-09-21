package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadResumePartTest {
    @Test
    fun missingDestinationFailsSafely() {
        val missing = DownloadResumePart.resolve(null)
        check(missing is DownloadResumePart.Result.Failed)
        assertTrue(missing.error.contains("Destination"))
        assertTrue(DownloadResumePart.resolve("   ") is DownloadResumePart.Result.Failed)
    }

    @Test
    fun missingOrEmptyPartFailsWithoutCreatingAReadyFile() {
        val dest = File(System.getProperty("java.io.tmpdir"), "sdm_resume_part_${System.nanoTime()}/file.bin")
        dest.parentFile!!.mkdirs()
        try {
            val missing = DownloadResumePart.resolve(dest.absolutePath)
            check(missing is DownloadResumePart.Result.Failed)
            assertEquals("Incomplete download part is missing", missing.error)

            val part = DownloadPartFile.forDestination(dest)
            part.writeBytes(ByteArray(0))
            val empty = DownloadResumePart.resolve(dest.absolutePath)
            check(empty is DownloadResumePart.Result.Failed)
            assertEquals("Incomplete download part is empty", empty.error)

            part.writeBytes(byteArrayOf(1, 2, 3))
            val ready = DownloadResumePart.resolve(dest.absolutePath)
            check(ready is DownloadResumePart.Result.Ready)
            assertEquals(part.canonicalFile, ready.file.canonicalFile)
        } finally {
            DownloadPartFile.forDestination(dest).delete()
            dest.parentFile?.delete()
        }
    }
}
