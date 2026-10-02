package com.espitman.sdm.torrent

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorrentSafetyTest {
    @Test fun acceptsV1HexBase32AndV2Multihash() {
        assertTrue(TorrentMagnet.isValid("magnet:?xt=urn:btih:${"a".repeat(40)}"))
        assertTrue(TorrentMagnet.isValid("magnet:?xt=urn%3Abtih%3A${"A".repeat(32)}&dn=hello"))
        assertTrue(TorrentMagnet.isValid("magnet:?xt=urn:btmh:1220${"b".repeat(64)}"))
    }
    @Test fun rejectsMalformedOrMissingContentIdentity() {
        listOf("https://example.com", "magnet:?dn=hello", "magnet:?xt=urn:btih:hello", "magnet://host?xt=urn:btih:${"a".repeat(40)}", "magnet:?xt=urn:btih:${"a".repeat(40)}#fragment").forEach { assertFalse(it, TorrentMagnet.isValid(it)) }
    }
    @Test fun rejectsEscapingPathsAndPlatformAliases() {
        listOf("../outside", "root/../../outside", "/absolute", "C:/drive", "root\\file", "root//file", "root/./file", "root/../file", "root/\u0000file").forEach {
            assertThrows(IllegalArgumentException::class.java) { TorrentPaths.resolve(File("/safe"), it) }
        }
        assertEquals(File("/safe/root/file.bin"), TorrentPaths.resolve(File("/safe"), "root/file.bin"))
    }
    @Test fun downloadRecognizesTorrentWithoutAffectingHttpValidation() {
        val magnet = "magnet:?xt=urn:btih:${"a".repeat(40)}"
        assertTrue(com.espitman.sdm.domain.Download(url=magnet,fileName="files",createdAtEpochMillis=1).isTorrent)
        assertFalse(com.espitman.sdm.domain.Download(url="https://example.com/file",fileName="file",createdAtEpochMillis=1).isTorrent)
    }
    @Test fun portableBackupPreservesSelectionAndMagnetWithoutLocalPaths() {
        val magnet="magnet:?xt=urn:btih:${"a".repeat(40)}"
        val download=com.espitman.sdm.domain.Download(url=magnet,fileName="bundle",destinationPath="/private/phone",createdAtEpochMillis=1)
        val snapshot=com.espitman.sdm.data.DownloadBackupCodec.TorrentSnapshot(byteArrayOf(1,2,3),setOf(1,3),false)
        val text=com.espitman.sdm.data.DownloadBackupCodec.encode(listOf(download),null,1,torrents=mapOf(magnet to snapshot))
        assertFalse(text.contains("/private/phone"))
        val result=com.espitman.sdm.data.DownloadBackupCodec.decode(text)
        assertTrue(result.downloads.single().isTorrent)
        assertEquals(setOf(1,3),result.torrents[magnet]!!.selected)
        assertArrayEquals(snapshot.metadata,result.torrents[magnet]!!.metadata)
    }
    @Test fun linkExportIncludesTheMagnetIdentity() {
        val magnet="magnet:?xt=urn:btih:${"a".repeat(40)}"
        val row=com.espitman.sdm.domain.Download(url=magnet,fileName="bundle",createdAtEpochMillis=1)
        assertEquals(magnet,com.espitman.sdm.domain.LinkArchive.export(listOf(row)).trim())
    }

}
