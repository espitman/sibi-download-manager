package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedLimitUnitsTest {
    @Test
    fun oneDecimalMegabytePerSecondIsOneMillionBytes() {
        assertEquals(1_000_000L, SpeedLimitUnits.bytesPerSecond(1f))
    }

    @Test
    fun tenAndThirtyUseDecimalMegabytesNotMebibytesOrMegabits() {
        assertEquals(10_000_000L, SpeedLimitUnits.bytesPerSecond(10f))
        assertEquals(30_000_000L, SpeedLimitUnits.bytesPerSecond(30f))
        assertTrue(SpeedLimitUnits.bytesPerSecond(1f) < 1_048_576L)
        assertTrue(SpeedLimitUnits.bytesPerSecond(1f) > 125_000L)
    }

    @Test
    fun fractionalUiValueKeepsDecimalScaling() {
        assertEquals(2_500_000L, SpeedLimitUnits.bytesPerSecond(2.5f))
    }

    @Test
    fun rejectsNonFiniteAndOutOfUiBounds() {
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(0f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(0.99f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(30.01f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(-1f) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { SpeedLimitUnits.bytesPerSecond(Float.NEGATIVE_INFINITY) }
    }
}
