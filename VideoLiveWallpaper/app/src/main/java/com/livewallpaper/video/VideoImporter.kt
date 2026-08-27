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
import java.util.UUID

/**
 * Kopiert ausgewaehlte Videos in den privaten App-Speicher. Dadurch funktioniert
 * der Hintergrund auch dann noch, wenn die Originaldatei spaeter verschoben oder
 * die Berechtigung fuer den Ordner entzogen wird.
 */
object VideoImporter {

    private const val TAG = "VideoImporter"
    private const val THUMB_WIDTH = 480

    fun import(context: Context, uri: Uri): VideoItem? {
        val id = UUID.randomUUID().toString()
        val displayName = queryName(context, uri) ?: "Video"
        val extension = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 } ?: "mp4"
        val target = File(Storage.videoDir(context), "$id.$extension")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                }
            } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Kopieren fehlgeschlagen", e)
            target.delete()
            return null
        }

        if (target.length() == 0L) {
            target.delete()
            return null
        }

        val meta = readMetadata(target)
        val item = VideoItem(
            id = id,
            name = displayName.substringBeforeLast('.').ifBlank { displayName },
            fileName = target.name,
            durationMs = meta.first,
            aspect = meta.second
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
