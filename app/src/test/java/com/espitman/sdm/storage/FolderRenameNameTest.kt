package com.espitman.sdm.storage

import org.junit.Assert.*
import org.junit.Test

class FolderRenameNameTest {
    @Test fun acceptsUnicodeAndTrimsWithoutChangingTheFilename() {
        assertEquals("ویدئوهای من", FolderRenameCoordinator.validName("  ویدئوهای من  "))
    }
    @Test fun rejectsTraversalSeparatorsControlCharactersAndStagingDirectory() {
        listOf("", " ", ".", "..", "a/b", "a\\b", "a:b", "bad?name", "bad*name", "bad|name", "trailing.", "a\u0000b", ".sdm-saf-staging", "a".repeat(121)).forEach {
            try { FolderRenameCoordinator.validName(it); fail("Accepted invalid name: $it") } catch (_: IllegalArgumentException) { }
        }
    }
}
