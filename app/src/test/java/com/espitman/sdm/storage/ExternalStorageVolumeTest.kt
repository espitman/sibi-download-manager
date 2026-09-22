package com.espitman.sdm.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalStorageVolumeTest {
    @Test
    fun volumeIdFromExternalStorageTreeDocumentIds() {
        assertEquals("primary", ExternalStorageVolume.volumeIdFromDocumentId("primary:Download/SDM-QA"))
        assertEquals("primary", ExternalStorageVolume.volumeIdFromDocumentId("primary:"))
        assertEquals("primary", ExternalStorageVolume.volumeIdFromDocumentId("primary"))
        assertEquals("1A2B-3C4D", ExternalStorageVolume.volumeIdFromDocumentId("1A2B-3C4D:DCIM"))
        assertNull(ExternalStorageVolume.volumeIdFromDocumentId(null))
        assertNull(ExternalStorageVolume.volumeIdFromDocumentId(" "))
        assertNull(ExternalStorageVolume.volumeIdFromDocumentId(":Download"))
        assertTrue(ExternalStorageVolume.isExternalStorageAuthority("com.android.externalstorage.documents"))
        assertFalse(ExternalStorageVolume.isExternalStorageAuthority("com.espitman.sdm.test.documents"))
    }

    @Test
    fun matchesPrimaryOrUuidWithoutBroadPrefixCollisions() {
        assertTrue(ExternalStorageVolume.matchesVolumeId("primary", isPrimary = true, uuid = null))
        assertTrue(ExternalStorageVolume.matchesVolumeId("PRIMARY", isPrimary = true, uuid = "ignored"))
        assertFalse(ExternalStorageVolume.matchesVolumeId("primary", isPrimary = false, uuid = "primary"))
        assertTrue(ExternalStorageVolume.matchesVolumeId("1A2B-3C4D", isPrimary = false, uuid = "1a2b-3c4d"))
        assertFalse(ExternalStorageVolume.matchesVolumeId("1A2B-3C4D", isPrimary = true, uuid = null))
        assertFalse(ExternalStorageVolume.matchesVolumeId("1A2B-3C4D", isPrimary = false, uuid = "FFFF-FFFF"))
        assertFalse(ExternalStorageVolume.matchesVolumeId("primary2", isPrimary = true, uuid = null))
    }

    @Test
    fun filesystemRootPrefersStorageVolumeDirectoryWhenPresent() {
        val volumeDir = File("/volume/primary")
        val legacyPrimary = File("/storage/emulated/0")
        assertEquals(
            volumeDir,
            ExternalStorageVolume.filesystemRoot(
                isPrimary = true,
                uuid = null,
                directoryFromVolume = volumeDir,
                primaryExternalDirectory = legacyPrimary,
                exists = { it == volumeDir },
            ),
        )
    }

    @Test
    fun filesystemRootMapsPrimaryAndRemovableVolumesWithoutApi30Directory() {
        val primary = File("/storage/emulated/0")
        assertEquals(
            primary,
            ExternalStorageVolume.filesystemRoot(
                isPrimary = true,
                uuid = null,
                directoryFromVolume = null,
                primaryExternalDirectory = primary,
                exists = { it == primary },
            ),
        )
        val uuid = "1A2B-3C4D"
        val mounted = File("/storage/$uuid")
        assertEquals(
            mounted,
            ExternalStorageVolume.filesystemRoot(
                isPrimary = false,
                uuid = uuid,
                directoryFromVolume = null,
                primaryExternalDirectory = primary,
                exists = { it.path == mounted.path },
            ),
        )
        assertNull(
            ExternalStorageVolume.filesystemRoot(
                isPrimary = false,
                uuid = "FFFF-FFFF",
                directoryFromVolume = null,
                primaryExternalDirectory = primary,
                exists = { false },
            ),
        )
    }
}
