package com.espitman.sdm.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserPreferencesStoreTest {
    @Test fun legacyPrivateByDefaultIsResetWithoutDroppingRegularHistoryOrTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("sdm_browser_migration_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val history = listOf(
            BrowserHistoryEntry("https://example.com/kept", "Kept", 42L),
        )
        val session = BrowserPersistedSession(
            tabs = listOf(BrowserTab("tab-4", "Example", "https://example.com", isPrivate = false)),
            activeId = "tab-4",
            nextTabOrdinal = 5,
        )
        preferences.edit()
            .putBoolean("private_by_default", true)
            .putBoolean("block_trackers", true)
            .putString("history_v1", encodeBrowserHistory(history))
            .putString("session_v1", encodeBrowserSession(session.tabs, session.activeId, session.nextTabOrdinal))
            .commit()

        val store = BrowserPreferencesStore(preferences)
        val read = store.read()
        assertFalse(read.privateByDefault)
        assertTrue(read.blockTrackers)
        assertEquals(history, store.readHistory())
        assertEquals(listOf("tab-4"), store.readSession()?.tabs?.map { it.id })
        assertFalse(store.readSession()!!.tabs.single().isPrivate)

        store.write(read.copy(privateByDefault = true))
        assertTrue(store.read().privateByDefault)
        assertEquals(history, store.readHistory())
    }
}
