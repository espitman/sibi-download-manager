package com.espitman.sdm.ui

import org.junit.Assert.assertEquals
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
        assertEquals(BrowserPrivacyPolicy.Private, BrowserPrivacyPolicy.forTab(true))
    }

    @Test fun regularTabsDoNotClaimPrivateSessionCleanup() {
        val policy = BrowserPrivacyPolicy.Regular
        assertTrue(policy.persistHistory)
        assertTrue(policy.persistCache)
        assertFalse(policy.clearCookiesAtSessionEnd)
        assertFalse(policy.clearWebStorageAtSessionEnd)
        assertTrue(initialBrowserTabs().tabs.none { it.isPrivate })
        assertEquals(BrowserPrivacyPolicy.Regular, BrowserPrivacyPolicy.forTab(false))
    }
}
