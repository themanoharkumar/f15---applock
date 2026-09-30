package com.f15.applock

import com.f15.applock.domain.model.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelectionTest {

    private val sampleApps = listOf(
        InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp", isLocked = false),
        InstalledApp(packageName = "com.instagram.android", appName = "Instagram", isLocked = true),
        InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube", isLocked = false),
        InstalledApp(packageName = "com.sec.android.app.sbrowser", appName = "Samsung Internet", isLocked = true)
    )

    @Test
    fun searchByName_isCaseInsensitive() {
        val query = "what"
        val filtered = sampleApps.filter {
            it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("com.whatsapp", filtered[0].packageName)
    }

    @Test
    fun searchByPackageName_matchesCorrectly() {
        val query = "sbrowser"
        val filtered = sampleApps.filter {
            it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("Samsung Internet", filtered[0].appName)
    }

    @Test
    fun emptySearchQuery_returnsAllApps() {
        val query = ""
        val filtered = if (query.isBlank()) {
            sampleApps
        } else {
            sampleApps.filter {
                it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
        assertEquals(4, filtered.size)
    }

    @Test
    fun appModel_protectionStateToggle() {
        val app = InstalledApp(packageName = "com.example.app", appName = "Example", isLocked = false)
        assertFalse(app.isLocked)
        val toggled = app.copy(isLocked = true)
        assertTrue(toggled.isLocked)
    }
}
