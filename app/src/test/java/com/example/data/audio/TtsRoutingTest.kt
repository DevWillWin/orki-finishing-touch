package com.example.data.audio

import com.example.data.audio.TtsRouting.OfflineFailureAction
import com.example.data.audio.TtsRouting.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsRoutingTest {

    @Test
    fun explicitOfflineModeNeverRoutesOnline() {
        assertEquals(Plan.OFFLINE, TtsRouting.plan("offline", packInstalled = true))
        // pack missing: report it, do NOT fall through to the online service
        assertEquals(Plan.OFFLINE_PACK_MISSING, TtsRouting.plan("offline", packInstalled = false))
        for (installed in listOf(true, false)) {
            assertNotEquals(Plan.ONLINE, TtsRouting.plan("offline", installed))
        }
    }

    @Test
    fun explicitOfflineModeReportsFailuresInsteadOfFallingBack() {
        assertEquals(OfflineFailureAction.REPORT_ONLY, TtsRouting.onOfflineFailure("offline"))
    }

    @Test
    fun autoModePrefersOfflineWhenInstalledAndFallsBackVisiblyOnFailure() {
        assertEquals(Plan.OFFLINE, TtsRouting.plan("auto", packInstalled = true))
        assertEquals(Plan.ONLINE, TtsRouting.plan("auto", packInstalled = false))
        assertEquals(OfflineFailureAction.REPORT_AND_USE_ONLINE, TtsRouting.onOfflineFailure("auto"))
    }

    @Test
    fun onlineModeAndUnknownValuesNeverTouchTheOfflineEngine() {
        for (mode in listOf("online", "", "weird", "OFFLINE")) {
            for (installed in listOf(true, false)) {
                assertEquals("mode '$mode' installed=$installed", Plan.ONLINE, TtsRouting.plan(mode, installed))
            }
        }
    }

    @Test
    fun onlineCachesAreOnlyReplayedOutsideExplicitOfflineMode() {
        assertFalse(TtsRouting.mayUseOnlineCache("offline"))
        assertTrue(TtsRouting.mayUseOnlineCache("auto"))
        assertTrue(TtsRouting.mayUseOnlineCache("online"))
    }
}
