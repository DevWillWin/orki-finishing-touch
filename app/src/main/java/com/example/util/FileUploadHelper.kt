package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

data class AttachedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fileType: String, // "image", "pdf", "txt"
    val base64Data: String? = null,
    val textContent: String? = null
) {
    val formattedSize: String
        get() = formatFileSize(sizeBytes)
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

object FileUploadHelper {

    suspend fun processUri(context: Context, uri: Uri): AttachedFile? = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        var fileName = "attachment"
        var fileSize = 0L

        // Retrieve file metadata from ContentResolver
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: "attachment"
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {}

        val resolvedMime = contentResolver.getType(uri) ?: when {
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".txt", ignoreCase = true) || fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".json", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }

        val fileType = when {
            resolvedMime.startsWith("image/") || fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true) ||
                    fileName.endsWith(".webp", ignoreCase = true) -> "image"
            resolvedMime == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> "pdf"
            resolvedMime.startsWith("text/") || fileName.endsWith(".txt", ignoreCase = true) ||
                    fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".json", ignoreCase = true) ||
                    fileName.endsWith(".csv", ignoreCase = true) -> "txt"
            else -> "txt"
        }

        when (fileType) {
            "image" -> {
                try {
                    val bytes = readAndCompressImage(context, uri)
                    if (bytes != null) {
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val actualSize = if (fileSize > 0) fileSize else bytes.size.toLong()
                        AttachedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = "image/jpeg",
                            sizeBytes = actualSize,
                            fileType = "image",
                            base64Data = base64
                        )
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            "pdf" -> {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    val bytes = inputStream?.use { it.readBytes() }
                    if (bytes != null) {
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val actualSize = if (fileSize > 0) fileSize else bytes.size.toLong()
                        AttachedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = "application/pdf",
                            sizeBytes = actualSize,
                            fileType = "pdf",
                            base64Data = base64
                        )
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            "txt" -> {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(uri)
                    val text = inputStream?.bufferedReader()?.use { it.readText() }
                    if (text != null) {
                        val truncatedText = if (text.length > 50_000) text.substring(0, 50_000) + "\n...[truncated]" else text
                        val actualSize = if (fileSize > 0) fileSize else text.toByteArray().size.toLong()
                        AttachedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = "text/plain",
                            sizeBytes = actualSize,
                            fileType = "txt",
                            textContent = truncatedText
                        )
                    } else null
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            else -> null
        }
    }

    private fun readAndCompressImage(context: Context, uri: Uri): ByteArray? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return null
        inputStream.close()

        val maxDimension = 1280
        val width = originalBitmap.width
        val height = originalBitmap.height
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val scale = maxDimension.toFloat() / max(width, height)
            val newWidth = (width * scale).toInt()
            val newHeight = (height * scale).toInt()
            Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
        } else {
            originalBitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return outputStream.toByteArray()
    }
}
