package com.example.data.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioManagerNeuralCacheTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var manager: AudioManager
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = AudioManager(context, scope)
        cacheDir = File(context.cacheDir, "tts_disk_cache").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun wav(name: String, bytes: Int, modifiedAgoMs: Long): File =
        File(cacheDir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - modifiedAgoMs)
        }

    @Test
    fun fileNamesAreStableAndDependOnVoiceSpeedAndText() {
        val a = manager.neuralTtsFile("female_mainao", 0.95f, "आं नोंखौ")
        assertEquals(a, manager.neuralTtsFile("female_mainao", 0.95f, "  आं नोंखौ  ")) // trimmed text
        assertNotEquals(a, manager.neuralTtsFile("male_birphung", 0.95f, "आं नोंखौ"))
        assertNotEquals("speed must take part in the name", a, manager.neuralTtsFile("female_mainao", 1.1f, "आं नोंखौ"))
        assertNotEquals(a, manager.neuralTtsFile("female_mainao", 0.95f, "आं नोंखौ।"))
        assertTrue(a.name.matches(Regex("tts_neural_[0-9a-f]{16}\\.wav")))
        assertEquals(cacheDir.absolutePath, a.parentFile!!.absolutePath)
    }

    @Test
    fun textsWithTheSameJavaHashCodeNoLongerCollide() {
        // "Aa" and "BB" are the classic String.hashCode() collision; the old file name used hashCode().
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(manager.neuralTtsFile("female_mainao", 1f, "Aa"), manager.neuralTtsFile("female_mainao", 1f, "BB"))
    }

    @Test
    fun localAudioNeedsToExistAndBeMoreThanAHeader() {
        assertFalse(manager.isLocalAudioUsable(File(cacheDir, "missing.wav").absolutePath))
        assertFalse(manager.isLocalAudioUsable(wav("tts_neural_tiny.wav", 44, 0).absolutePath))
        assertTrue(manager.isLocalAudioUsable(wav("tts_neural_ok.wav", 4000, 0).absolutePath))
    }

    @Test
    fun trimKeepsTheNewestFilesAndOnlyTouchesOfflineResults() {
        for (i in 0 until 30) wav("tts_neural_%02d.wav".format(i), 1000, modifiedAgoMs = (30 - i) * 1000L) // i=29 is newest
        val online = wav("tts_female_mainao_123.wav", 1000, 10_000_000L) // an online-cache entry: must survive
        manager.trimNeuralTtsCache(maxFiles = 24)
        val remaining = cacheDir.listFiles()!!.map { it.name }.filter { it.startsWith("tts_neural_") }.sorted()
        assertEquals(24, remaining.size)
        assertEquals("tts_neural_06.wav", remaining.first()) // 0..5 (the oldest six) were removed
        assertEquals("tts_neural_29.wav", remaining.last())
        assertTrue(online.exists())
    }

    @Test
    fun trimEnforcesTheByteBudgetOldestFirst() {
        for (i in 0 until 5) wav("tts_neural_b$i.wav", 1_000_000, modifiedAgoMs = (5 - i) * 1000L)
        manager.trimNeuralTtsCache(maxFiles = 24, maxBytes = 3_500_000L)
        val remaining = cacheDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("tts_neural_b2.wav", "tts_neural_b3.wav", "tts_neural_b4.wav"), remaining)
    }

    @Test
    fun abandonedPartFilesAreRemovedButARunningSynthesisIsLeftAlone() {
        val stale = wav("tts_neural_x.wav.part", 500, modifiedAgoMs = 30 * 60_000L)
        val running = wav("tts_neural_y.wav.part", 500, modifiedAgoMs = 5_000L)
        manager.trimNeuralTtsCache()
        assertFalse(stale.exists())
        assertTrue(running.exists())
    }

    @Test
    fun clearingTheTtsCacheAlsoRemovesOfflineResults() {
        wav("tts_neural_keep.wav", 2000, 0)
        manager.clearTtsCache()
        assertFalse(File(cacheDir, "tts_neural_keep.wav").exists())
    }
}
