package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.download.SubmissionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDownloadLinksTest {
    @Test
    fun reportsProgressAndCancellationDoesNotSubmitRemainingLinks() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        val attempted = mutableListOf<String>()
        try {
            submitDownloadLinks(listOf("one", "two", "three"), onProgress = { done, total -> progress += done to total }) {
                attempted += it
                if (it == "two") throw kotlinx.coroutines.CancellationException("Closed")
                SubmissionResult.Success(Download(url = "https://example.com/one", fileName = "one", createdAtEpochMillis = 1))
            }
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(listOf("one", "two"), attempted)
            assertEquals(listOf(1 to 3), progress)
        }
    }
    @Test
    fun eachEpisodeLineKeepsItsOwnSignedQueryString() {
        val first = "https://example.com/Ted.Lasso.S01E01.mkv?md5=a_b-c&u=146599&expires=1790232138"
        val second = "https://example.com/Ted.Lasso.S01E02.mkv?md5=d_e-f&u=146599&expires=1790232138"

        assertEquals(
            ParsedDownloadLinks(listOf(first, second), emptyList()),
            parseDownloadLinks("\n $first \r\n\n$second\n"),
        )
    }

    @Test
    fun copiedMarkdownLinksAreUnescapedWithoutChangingUrls() {
        val result = parseDownloadLinks(
            "[Episode 1](https://example.com/E01.mkv?md5=a\\_b\\&u=1)\n" +
                "[Episode 2](https://example.com/E02.mkv?md5=c_d\\&u=2)",
        )

        assertEquals(
            listOf(
                "https://example.com/E01.mkv?md5=a_b&u=1",
                "https://example.com/E02.mkv?md5=c_d&u=2",
            ),
            result.urls,
        )
        assertEquals(emptyList<String>(), result.invalidLines)
    }

    @Test
    fun invalidLineDoesNotDiscardValidLinesOrDuplicateEntries() {
        val url = "http://example.com/episode.mkv"
        val result = parseDownloadLinks("$url\nnot a URL\n$url")

        assertEquals(listOf(url, url), result.urls)
        assertEquals(listOf("not a URL"), result.invalidLines)
    }

    @Test
    fun batchContinuesAfterIndividualSubmissionFailure() = runBlocking {
        val urls = listOf("https://example.com/1.mkv", "https://example.com/2.mkv", "https://example.com/3.mkv")
        val attempted = mutableListOf<String>()

        val outcome = submitDownloadLinks(urls) { url ->
            attempted += url
            if (url == urls[1]) SubmissionResult.Failure("Server unavailable")
            else SubmissionResult.Success(
                Download(url = url, fileName = url.substringAfterLast('/'), createdAtEpochMillis = 1L),
            )
        }

        assertEquals(urls, attempted)
        assertEquals(2, outcome.added)
        assertEquals(listOf(urls[1]), outcome.failedUrls)
        assertEquals("Server unavailable", outcome.firstFailure)
    }
}
