package com.example.data.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * Manages the optional offline Bodo Neural TTS Voice Pack.
 *
 * Keeps the base APK compact by maintaining the ~123 MB neural model as a separate verified voice
 * pack stored in app-private storage.
 *
 * Integrity model: the download URLs are pinned to an immutable Hugging Face *commit* (not the moving
 * `main` ref) whose `model.onnx` has the pinned size and SHA-256 below (checked against the Hub's
 * LFS metadata). The digest is computed while streaming, and files are installed only after every
 * check passed, so a failed or truncated re-download can never replace a working pack.
 */
class BodoVoicePackManager(
    private val baseDirProvider: () -> File,
    private val sources: Sources = Sources.DEFAULT
) {

    constructor(context: Context) : this({ context.filesDir })

    /** Where to download from and what to expect; overridable for tests. */
    class Sources(
        val modelUrl: String,
        val tokensUrl: String,
        val modelSize: Long,
        val modelSha256: String,
        val tokensSize: Long,
        val minFreeBytes: Long = EXTRA_FREE_SPACE_BYTES
    ) {
        companion object {
            val DEFAULT = Sources(
                modelUrl = MODEL_DOWNLOAD_URL,
                tokensUrl = TOKENS_DOWNLOAD_URL,
                modelSize = EXPECTED_MODEL_SIZE,
                modelSha256 = EXPECTED_MODEL_SHA256,
                tokensSize = EXPECTED_TOKENS_SIZE
            )
        }
    }

    companion object {
        const val VOICE_PACK_DIR_NAME = "bodo_tts_pack"
        const val MODEL_FILE_NAME = "model.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
        const val GUARD_FILE_NAME = "engine_guard"

        /** Immutable Hub revision of MatiasLin/sherpa-onnx-vits-rasa-13 that the constants below describe. */
        const val PINNED_REVISION = "b5afd4e991e3bdd6195528de650e2ed219d47506"

        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/$PINNED_REVISION/model.onnx"
        const val TOKENS_DOWNLOAD_URL =
            "https://huggingface.co/MatiasLin/sherpa-onnx-vits-rasa-13/resolve/$PINNED_REVISION/tokens.txt"

        // Verified against the Hub tree API (LFS oid / size) for the pinned revision.
        const val EXPECTED_MODEL_SHA256 =
            "4473f8d0b6f738196c1626bbf337cda99b34749b638bb7234c4cb8e1992bf804"
        const val EXPECTED_MODEL_SIZE = 123338635L
        const val EXPECTED_TOKENS_SIZE = 9556L

        /** Headroom on top of the download itself (temp file + tokens + filesystem slack). */
        const val EXTRA_FREE_SPACE_BYTES = 64L * 1024 * 1024

        private const val MIN_MODEL_BYTES = 100_000_000L
        private const val MIN_TOKENS_BYTES = 5000L
    }

    private val rootDir: File get() = baseDirProvider()

    /** Pure accessor: nothing is created until files are actually written. */
    val voicePackDir: File
        get() = File(rootDir, VOICE_PACK_DIR_NAME)

    val modelFile: File
        get() = File(voicePackDir, MODEL_FILE_NAME)

    val tokensFile: File
        get() = File(voicePackDir, TOKENS_FILE_NAME)

    /** Crash-sentinel location used by the engine (see [EngineGuard]). */
    val guardFile: File
        get() = File(voicePackDir, GUARD_FILE_NAME)

    /**
     * Cheap structural check (two `stat` calls): both files exist and have plausible sizes.
     * Content integrity is enforced at download time and again by the engine when loading.
     */
    fun isVoicePackInstalled(): Boolean {
        val model = modelFile
        val tokens = tokensFile
        return model.isFile && model.length() > MIN_MODEL_BYTES && tokens.isFile && tokens.length() > MIN_TOKENS_BYTES
    }

    /**
     * Full verification of the installed model (reads the whole file; call off the main thread).
     * The tokens file is validated structurally by the engine (symbol table parse), not by hash.
     */
    fun verifyChecksums(): Boolean {
        val model = modelFile
        if (!model.isFile || model.length() != sources.modelSize) return false
        return computeSha256(model).equals(sources.modelSha256, ignoreCase = true)
    }

    /** Installs or links the voice pack from local files. */
    fun installFromLocalFiles(srcModel: File, srcTokens: File): Boolean {
        return try {
            voicePackDir.mkdirs()
            srcModel.copyTo(modelFile, overwrite = true)
            srcTokens.copyTo(tokensFile, overwrite = true)
            isVoicePackInstalled()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Downloads and verifies the neural model and token vocabulary with progress reporting. Existing
     * files are replaced only after both downloads verified. Cancellation-aware.
     */
    suspend fun downloadVoicePack(
        onProgress: (progress: Float, statusMessage: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = voicePackDir
        val tokensTemp = File(dir, "$TOKENS_FILE_NAME.downloading")
        val modelTemp = File(dir, "$MODEL_FILE_NAME.downloading")
        var installed = false
        try {
            dir.mkdirs()
            val needed = sources.modelSize + sources.tokensSize + sources.minFreeBytes
            if (dir.usableSpace in 1 until needed) {
                return@withContext Result.failure(
                    IOException("Not enough free storage: about ${needed / (1024 * 1024)} MB are needed.")
                )
            }

            onProgress(0.02f, "Downloading Bodo vocabulary (tokens.txt)...")
            downloadVerified(sources.tokensUrl, tokensTemp, sources.tokensSize, expectedSha256 = null) { _, _ -> }

            val modelMb = sources.modelSize / (1024 * 1024)
            onProgress(0.05f, "Downloading Bodo Neural TTS model ($modelMb MB)...")
            downloadVerified(sources.modelUrl, modelTemp, sources.modelSize, sources.modelSha256) { bytesRead, totalBytes ->
                val progress = 0.05f + (bytesRead.toFloat() / totalBytes.coerceAtLeast(1L).toFloat()) * 0.90f
                onProgress(
                    progress.coerceIn(0.05f, 0.95f),
                    "Downloading model: ${bytesRead / (1024 * 1024)}MB / ${totalBytes / (1024 * 1024)}MB..."
                )
            }

            onProgress(0.97f, "Installing verified files...")
            replaceFile(tokensTemp, tokensFile)
            replaceFile(modelTemp, modelFile)
            installed = true
            guardFile.delete() // a fresh pack starts with a clean crash marker

            onProgress(1.0f, "Bodo Neural Voice Pack installed successfully!")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (!installed) {
                tokensTemp.delete()
                modelTemp.delete()
            }
        }
    }

    fun deleteVoicePack(): Boolean {
        return try {
            var deleted = true
            if (modelFile.exists()) deleted = deleted && modelFile.delete()
            if (tokensFile.exists()) deleted = deleted && tokensFile.delete()
            guardFile.delete()
            deleted
        } catch (_: Exception) {
            false
        }
    }

    private fun replaceFile(temp: File, target: File) {
        if (target.exists() && !target.delete()) throw IOException("Could not replace ${target.name}")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    /**
     * Streams [sourceUrl] into [tempFile], verifying exact size, Content-Length (when announced) and,
     * if given, SHA-256 on the fly. On any mismatch the temp file is deleted and an IOException is thrown.
     */
    private suspend fun downloadVerified(
        sourceUrl: String,
        tempFile: File,
        expectedSize: Long,
        expectedSha256: String?,
        onChunk: (bytesRead: Long, totalBytes: Long) -> Unit
    ) {
        val conn = java.net.URI(sourceUrl).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        var ok = false
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} while downloading ${File(java.net.URI(sourceUrl).path).name}")
            }
            val announced = conn.contentLengthLong
            if (announced >= 0 && announced != expectedSize) {
                throw IOException("Unexpected download size ($announced bytes, expected $expectedSize).")
            }
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    var totalRead = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        digest?.update(buffer, 0, n)
                        totalRead += n
                        if (totalRead > expectedSize) throw IOException("Download is larger than expected.")
                        onChunk(totalRead, expectedSize)
                    }
                }
            }
            if (tempFile.length() != expectedSize) {
                throw IOException("Download is incomplete (${tempFile.length()} of $expectedSize bytes). Please retry.")
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("Integrity check failed: the downloaded file does not match the expected checksum.")
                }
            }
            ok = true
        } finally {
            conn.disconnect()
            if (!ok) tempFile.delete()
        }
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(65536)
            while (true) {
                val n = fis.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
