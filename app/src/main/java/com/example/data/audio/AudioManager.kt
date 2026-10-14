package com.example.data.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

data class AudioPlayerState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val activeTtsText: String? = null
)

class AudioManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordFile: File? = null

    private var amplitudeJob: Job? = null
    private var playbackProgressJob: Job? = null

    private val _amplitudeFlow = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    private val _playerState = MutableStateFlow(AudioPlayerState())
    val playerState: StateFlow<AudioPlayerState> = _playerState.asStateFlow()

    private var onAudioCompletedCallback: (() -> Unit)? = null

    // Cache of text to resolved audio URL
    val ttsUrlCache = mutableMapOf<String, String>()

    // Persistent disk cache for synthesized audio. Do not touch storage while the first frame is
    // being built; the directory is created only when a TTS cache operation is requested.
    private val ttsDiskCacheDir by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(context.cacheDir, "tts_disk_cache").apply { mkdirs() }
    }

    fun getCachedTtsPath(text: String, voice: String): String? {
        val key = "${voice}_${text.trim().hashCode()}"
        val file = File(ttsDiskCacheDir, "tts_$key.wav")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun saveTtsToDiskCache(text: String, voice: String, audioData: String): String? {
        return try {
            val key = "${voice}_${text.trim().hashCode()}"
            val file = File(ttsDiskCacheDir, "tts_$key.wav")
            val commaIdx = audioData.indexOf(',')
            val b64Data = if (commaIdx != -1) audioData.substring(commaIdx + 1) else audioData
            val bytes = Base64.decode(b64Data, Base64.DEFAULT)
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearTtsCache() {
        try {
            ttsUrlCache.clear()
            ttsDiskCacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("tts_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startRecording(
        autoSilenceDetection: Boolean = false,
        onSilenceDetected: (() -> Unit)? = null
    ): Boolean {
        return try {
            stopPlayback()
            val outputFile = File(context.cacheDir, "orki_voice_${System.currentTimeMillis()}.m4a")
            recordFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder

            startAmplitudePolling(autoSilenceDetection, onSilenceDetected)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanupRecorder()
            false
        }
    }

    private fun startAmplitudePolling(
        autoSilenceDetection: Boolean,
        onSilenceDetected: (() -> Unit)?
    ) {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch(Dispatchers.Default) {
            var speechStarted = false
            var silenceStartTime = 0L
            val silenceThreshold = 1600 // Sensitive threshold for early speech detection
            val silenceDurationMs = 460L // Snappy auto-turnaround (under half second)
            val minSpeechMs = 300L
            val recordStartTime = System.currentTimeMillis()

            while (isActive && mediaRecorder != null) {
                val amp = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (_: Exception) { 0 }

                // Normalized 0.0 to 1.0 (clamped)
                val normalized = (amp / 26000f).coerceIn(0f, 1f)
                _amplitudeFlow.value = normalized

                if (autoSilenceDetection && onSilenceDetected != null) {
                    val now = System.currentTimeMillis()
                    if (amp > silenceThreshold) {
                        speechStarted = true
                        silenceStartTime = 0L
                    } else if (speechStarted && (now - recordStartTime > minSpeechMs)) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = now
                        } else if (now - silenceStartTime >= silenceDurationMs) {
                            // Silence duration met - send speech immediately
                            launch(Dispatchers.Main) {
                                onSilenceDetected()
                            }
                            break
                        }
                    }
                }
                delay(40)
            }
        }
    }

    fun stopRecording(): File? {
        amplitudeJob?.cancel()
        amplitudeJob = null
        _amplitudeFlow.value = 0f

        val file = recordFile
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (_: Exception) {}
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
        }
        return file
    }

    fun fileToBase64(file: File): String? {
        return try {
            if (!file.exists() || file.length() < 500) return null
            val bytes = ByteArray(file.length().toInt())
            FileInputStream(file).use { it.read(bytes) }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        amplitudeJob?.cancel()
        amplitudeJob = null
        _amplitudeFlow.value = 0f
    }

    private var currentTempPlaybackFile: File? = null

    fun setPlayerLoading(text: String? = null) {
        stopPlayback()
        _playerState.value = AudioPlayerState(
            isVisible = true,
            isPlaying = false,
            isLoading = true,
            currentPositionMs = 0,
            durationMs = 0,
            activeTtsText = text
        )
    }

    // Local Preloaded Audio Resource Playback (Instant 0-latency offline preview)
    fun playRawResource(
        resId: Int,
        ttsText: String? = null,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        onCompleted: (() -> Unit)? = null
    ) {
        stopPlayback()
        onAudioCompletedCallback = onCompleted

        _playerState.value = AudioPlayerState(
            isVisible = true,
            isPlaying = false,
            isLoading = true,
            currentPositionMs = 0,
            durationMs = 0,
            activeTtsText = ttsText
        )

        try {
            val player = MediaPlayer.create(context, resId) ?: run {
                handlePlaybackEnded()
                return
            }

            // Only apply playbackParams if pitch or speed is customized
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (pitch != 1.0f || speed != 1.0f)) {
                try {
                    val params = player.playbackParams
                    params.pitch = pitch.coerceIn(0.5f, 2.0f)
                    params.speed = speed.coerceIn(0.5f, 2.0f)
                    player.playbackParams = params
                } catch (pe: Exception) {
                    pe.printStackTrace()
                }
            }

            player.setOnCompletionListener {
                handlePlaybackEnded()
            }
            player.setOnErrorListener { _, _, _ ->
                handlePlaybackEnded()
                true
            }

            player.start()
            _playerState.value = _playerState.value.copy(
                isPlaying = true,
                isLoading = false,
                durationMs = player.duration
            )
            mediaPlayer = player
            startPlaybackProgressTracker()
        } catch (e: Exception) {
            e.printStackTrace()
            handlePlaybackEnded()
        }
    }

    // Audio Playback (supports http/https URLs, local file paths, and data:audio/*;base64,... URIs)
    fun playAudioUrl(
        url: String,
        ttsText: String? = null,
        pitch: Float = 1.0f,
        speed: Float = 1.0f,
        onCompleted: (() -> Unit)? = null
    ) {
        stopPlayback()
        onAudioCompletedCallback = onCompleted

        _playerState.value = AudioPlayerState(
            isVisible = true,
            isPlaying = false,
            isLoading = true,
            currentPositionMs = 0,
            durationMs = 0,
            activeTtsText = ttsText
        )

        try {
            val dataSource: String = if (url.startsWith("data:", ignoreCase = true)) {
                // Decode base64 data URI into temporary WAV cache file
                val commaIdx = url.indexOf(',')
                val b64Data = if (commaIdx != -1) url.substring(commaIdx + 1) else url
                val audioBytes = Base64.decode(b64Data, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "tts_stream_${System.currentTimeMillis()}.wav")
                tempFile.writeBytes(audioBytes)
                currentTempPlaybackFile = tempFile
                tempFile.absolutePath
            } else {
                url
            }

            val player = MediaPlayer().apply {
                setDataSource(dataSource)
                setOnPreparedListener { mp ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (pitch != 1.0f || speed != 1.0f)) {
                        try {
                            val params = mp.playbackParams
                            params.pitch = pitch.coerceIn(0.5f, 2.0f)
                            params.speed = speed.coerceIn(0.5f, 2.0f)
                            mp.playbackParams = params
                        } catch (pe: Exception) {
                            pe.printStackTrace()
                        }
                    }
                    mp.start()
                    _playerState.value = _playerState.value.copy(
                        isPlaying = true,
                        isLoading = false,
                        durationMs = mp.duration
                    )
                    startPlaybackProgressTracker()
                }
                setOnCompletionListener {
                    handlePlaybackEnded()
                }
                setOnErrorListener { _, _, _ ->
                    handlePlaybackEnded()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            e.printStackTrace()
            handlePlaybackEnded()
        }
    }

    private fun startPlaybackProgressTracker() {
        playbackProgressJob?.cancel()
        playbackProgressJob = scope.launch(Dispatchers.Main) {
            while (isActive && mediaPlayer != null) {
                try {
                    val mp = mediaPlayer
                    if (mp != null && mp.isPlaying) {
                        _playerState.value = _playerState.value.copy(
                            currentPositionMs = mp.currentPosition,
                            durationMs = mp.duration
                        )
                    }
                } catch (_: Exception) {}
                delay(200)
            }
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                _playerState.value = _playerState.value.copy(isPlaying = false)
            } else {
                mp.start()
                _playerState.value = _playerState.value.copy(isPlaying = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekToFraction(fraction: Float) {
        val mp = mediaPlayer ?: return
        try {
            val targetMs = (mp.duration * fraction.coerceIn(0f, 1f)).toInt()
            mp.seekTo(targetMs)
            _playerState.value = _playerState.value.copy(currentPositionMs = targetMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopPlayback() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        currentTempPlaybackFile?.delete()
        currentTempPlaybackFile = null
        _playerState.value = AudioPlayerState(isVisible = false)
    }

    private fun handlePlaybackEnded() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentTempPlaybackFile?.delete()
        currentTempPlaybackFile = null
        _playerState.value = AudioPlayerState(isVisible = false)
        onAudioCompletedCallback?.invoke()
        onAudioCompletedCallback = null
    }

    fun destroy() {
        cleanupRecorder()
        stopPlayback()
    }
}
