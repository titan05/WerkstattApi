package com.livewallpaper.video

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Ergebnis eines Downloads. */
sealed class UrlImportResult {
    data class Success(val item: VideoItem) : UrlImportResult()
    data class Failure(val error: Error) : UrlImportResult()

    enum class Error { NETWORK, FORBIDDEN, NOT_FOUND, NOT_A_VIDEO, TOO_LARGE, CANCELLED }
}

/**
 * Holt Videos in den privaten App-Speicher - aus einer ausgewaehlten Datei oder
 * von einer direkten Adresse. Dadurch funktioniert der Hintergrund auch dann
 * noch, wenn das Original spaeter verschwindet oder das Geraet offline ist.
 */
object VideoImporter {

    private const val TAG = "VideoImporter"
    private const val THUMB_WIDTH = 480
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_STEP = 512L * 1024L

    /** Obergrenze fuer Downloads: 500 MB. */
    const val MAX_BYTES = 500L * 1024L * 1024L

    private const val USER_AGENT = "VideoHintergrund/1.5 (Android)"

    // --- Datei auswaehlen -----------------------------------------------------

    fun import(context: Context, uri: Uri): VideoItem? {
        val id = UUID.randomUUID().toString()
        val displayName = queryName(context, uri) ?: "Video"
        val extension = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 } ?: "mp4"
        val target = File(Storage.videoDir(context), "$id.$extension")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Kopieren fehlgeschlagen", e)
            target.delete()
            return null
        }

        return finish(context, id, target, displayName)
    }

    // --- Von einer Adresse laden ---------------------------------------------

    /**
     * Laedt eine direkt erreichbare Videodatei. Videoportale wie YouTube liefern
     * keine solche Datei aus - solche Adressen werden vorher von
     * [VideoUrlRules] abgefangen und gar nicht erst hier hereingereicht.
     */
    fun importFromUrl(
        context: Context,
        url: String,
        onProgress: (loadedBytes: Long, totalBytes: Long) -> Unit,
        isCancelled: () -> Boolean,
        displayName: String? = null
    ): UrlImportResult {
        val id = UUID.randomUUID().toString()
        var connection: HttpURLConnection? = null
        var target: File? = null

        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "video/*;q=0.9,*/*;q=0.5")
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "Server antwortet mit $status")
                // Genaue Rueckmeldung: 403 heisst meist Schutz gegen direktes
                // Verlinken, da hilft ein anderer Hinweis als bei 404.
                return UrlImportResult.Failure(
                    when (status) {
                        401, 403 -> UrlImportResult.Error.FORBIDDEN
                        404, 410 -> UrlImportResult.Error.NOT_FOUND
                        else -> UrlImportResult.Error.NETWORK
                    }
                )
            }

            val contentType = connection.contentType
            val announcesVideo = contentType?.lowercase()?.startsWith("video/") == true
            if (!announcesVideo && !VideoUrlRules.looksLikeVideoFile(url)) {
                return UrlImportResult.Failure(UrlImportResult.Error.NOT_A_VIDEO)
            }

            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_BYTES) {
                return UrlImportResult.Failure(UrlImportResult.Error.TOO_LARGE)
            }

            target = File(
                Storage.videoDir(context),
                "$id." + VideoUrlRules.extensionFor(url, contentType)
            )

            var loaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) {
                            return failAndClean(target, UrlImportResult.Error.CANCELLED)
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        loaded += count
                        if (loaded > MAX_BYTES) {
                            return failAndClean(target, UrlImportResult.Error.TOO_LARGE)
                        }
                        if (loaded - lastReported >= PROGRESS_STEP) {
                            lastReported = loaded
                            onProgress(loaded, declaredLength)
                        }
                    }
                }
            }

            if (loaded == 0L) return failAndClean(target, UrlImportResult.Error.NOT_A_VIDEO)
            onProgress(loaded, declaredLength)

            val name = displayName ?: VideoUrlRules.fileNameFrom(url) ?: "Video"
            val item = finish(context, id, target, name)
                ?: return UrlImportResult.Failure(UrlImportResult.Error.NOT_A_VIDEO)
            return UrlImportResult.Success(item)
        } catch (e: Exception) {
            Log.w(TAG, "Download fehlgeschlagen", e)
            target?.delete()
            return UrlImportResult.Failure(UrlImportResult.Error.NETWORK)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun failAndClean(target: File, error: UrlImportResult.Error): UrlImportResult {
        target.delete()
        return UrlImportResult.Failure(error)
    }

    // --- Gemeinsamer Abschluss ------------------------------------------------

    /**
     * Liest die Metadaten der geladenen Datei, legt das Vorschaubild an und
     * verwirft alles, was sich gar nicht als Video lesen laesst.
     */
    private fun finish(
        context: Context,
        id: String,
        target: File,
        displayName: String
    ): VideoItem? {
        if (target.length() == 0L) {
            target.delete()
            return null
        }

        val (durationMs, aspect) = readMetadata(target)
        if (durationMs <= 0L && aspect <= 0f) {
            // Weder Laufzeit noch Bildgroesse lesbar - das spielt auch nicht ab.
            Log.w(TAG, "Datei ist kein abspielbares Video: $displayName")
            target.delete()
            return null
        }

        val item = VideoItem(
            id = id,
            name = displayName.substringBeforeLast('.').ifBlank { displayName },
            fileName = target.name,
            durationMs = durationMs,
            aspect = aspect
        )
        writeThumbnail(context, target, item)
        return item
    }

    /** Liefert Dauer in ms und das gedrehte Seitenverhaeltnis. */
    private fun readMetadata(file: File): Pair<Long, Float> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toFloatOrNull() ?: 0f
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toFloatOrNull() ?: 0f
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val aspect = when {
                width <= 0f || height <= 0f -> 0f
                rotation == 90 || rotation == 270 -> height / width
                else -> width / height
            }
            duration to aspect
        } catch (e: Exception) {
            Log.w(TAG, "Metadaten nicht lesbar", e)
            0L to 0f
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeThumbnail(context: Context, video: File, item: VideoItem) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return
            val scale = THUMB_WIDTH.toFloat() / frame.width.coerceAtLeast(1)
            val thumb = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                frame
            }
            FileOutputStream(Storage.thumbFile(context, item)).use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (thumb !== frame) thumb.recycle()
            frame.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Vorschaubild fehlgeschlagen", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}
