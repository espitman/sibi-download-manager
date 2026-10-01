package com.espitman.sdm.domain

import java.math.BigDecimal
import java.math.RoundingMode

object IndividualSpeedLimit {
    /** Decimal byte units, matching SDM's global speed limit. Reject overflow and sub-byte rates. */
    fun parse(value: String, megabytes: Boolean): Long? = try {
        BigDecimal(value.trim()).multiply(BigDecimal(if (megabytes) 1_000_000 else 1_000))
            .setScale(0, RoundingMode.DOWN).longValueExact().takeIf { it > 0 }
    } catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
}
