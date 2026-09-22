package com.espitman.sdm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPrivacyPolicyTest {
    @Test fun privateLabelMatchesSessionBehavior() {
        val policy = BrowserPrivacyPolicy.Private
        assertFalse(policy.persistHistory)
        assertFalse(policy.persistCache)
        assertTrue(policy.clearCookiesAtSessionEnd)
        assertTrue(policy.clearWebStorageAtSessionEnd)
        assertTrue(initialBrowserTabs().tabs.all { it.isPrivate })
    }
}
