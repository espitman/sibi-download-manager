package com.espitman.sdm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCapacityTest {
    @Test
    fun knownCapacityDerivesUsedWhenAvailableDoesNotExceedTotal() {
        val capacity = StorageCapacity.from(totalBytes = 128L, availableBytes = 32L)
        assertEquals(128L, capacity.totalBytes)
        assertEquals(32L, capacity.availableBytes)
        assertEquals(96L, capacity.usedBytes)
        assertFalse(capacity.isUnknown)
    }

    @Test
    fun availableOnlyDoesNotInventTotalOrUsed() {
        val capacity = StorageCapacity.from(totalBytes = null, availableBytes = 40L)
        assertNull(capacity.totalBytes)
        assertEquals(40L, capacity.availableBytes)
        assertNull(capacity.usedBytes)
        assertFalse(capacity.isUnknown)
    }

    @Test
    fun missingOrNegativeValuesAreUnknownRatherThanGuessed() {
        assertSame(StorageCapacity.Unknown, StorageCapacity.from(null, null))
        assertTrue(StorageCapacity.from(totalBytes = -1L, availableBytes = -8L).isUnknown)
        val inaccurate = StorageCapacity.from(totalBytes = 10L, availableBytes = 11L)
        assertEquals(10L, inaccurate.totalBytes)
        assertEquals(11L, inaccurate.availableBytes)
        assertNull(inaccurate.usedBytes)
    }

    @Test
    fun remainingAndSufficientUseOverflowSafeNonnegativeArithmetic() {
        assertEquals(0L, StorageBytes.remaining(-4L, 2L))
        assertEquals(0L, StorageBytes.remaining(8L, 8L))
        assertEquals(0L, StorageBytes.remaining(8L, 9L))
        assertEquals(3L, StorageBytes.remaining(8L, 5L))
        assertEquals(0L, StorageBytes.remaining(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, StorageBytes.remaining(Long.MAX_VALUE, 0L))
        assertEquals(Long.MAX_VALUE, StorageBytes.used(Long.MAX_VALUE, 0L))
        assertEquals(0L, StorageBytes.used(Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(StorageBytes.used(-1L, 0L))
        assertTrue(StorageBytes.hasSufficient(8L, 8L))
        assertTrue(StorageBytes.hasSufficient(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(StorageBytes.hasSufficient(7L, 8L))
        assertFalse(StorageBytes.hasSufficient(-1L, 0L))
        assertTrue(StorageBytes.hasSufficient(0L, 0L))
    }

    @Test
    fun rootMatchesTreeDocumentIdOrRootId() {
        assertTrue(rootMatchesTreeDocument("root", "sdm-test-root", "root"))
        assertTrue(rootMatchesTreeDocument("sdm-test-root", "sdm-test-root", "root"))
        assertFalse(rootMatchesTreeDocument("other", "sdm-test-root", "root"))
        assertFalse(rootMatchesTreeDocument("", "root", "root"))
    }

    @Test
    fun rootMatchesExternalStorageDescendantTreesWithoutBroadPrefixes() {
        assertTrue(rootMatchesTreeDocument("primary:", "primary", "primary:"))
        assertTrue(rootMatchesTreeDocument("primary:Download", "primary", "primary:"))
        assertTrue(rootMatchesTreeDocument("primary:Download/SDM", "primary", "primary:"))
        assertTrue(rootMatchesTreeDocument("root/Download", "sdm-test-root", "root"))
        assertFalse(rootMatchesTreeDocument("primary2:Download", "primary", "primary:"))
        assertFalse(rootMatchesTreeDocument("primary-alt:Download", "primary", "primary:"))
        assertFalse(rootMatchesTreeDocument("home:Download", "primary", "primary:"))
        assertFalse(isSameDocumentOrDescendant("primary:Download", "primary:Down"))
        assertFalse(rootMatchesTreeDocument("primary:Downloads", "home", "primary:Download"))
        assertFalse(isSameDocumentOrDescendant("primary:Download", ""))
        assertFalse(isSameDocumentOrDescendant("primary:Download", null))
    }
}
