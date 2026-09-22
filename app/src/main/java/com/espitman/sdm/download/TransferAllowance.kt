package com.espitman.sdm.download

fun interface TransferAllowance {
    fun isAllowed(): Boolean
}

class MutableTransferAllowance(
    initiallyAllowed: Boolean = false,
) : TransferAllowance {
    @Volatile
    private var allowed: Boolean = initiallyAllowed

    override fun isAllowed(): Boolean = allowed

    fun setAllowed(value: Boolean) {
        allowed = value
    }
}
