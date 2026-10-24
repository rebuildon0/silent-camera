package com.rebuildon0.silentcamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class MediaItem(
    val uri: Uri,
    val id: Long,
    val isVideo: Boolean,
    val dateAdded: Long,
    val durationMs: Long,
)

/** Photos and videos this app saved under DCIM/SilentCamera. */
object MediaRepo {
    const val RELATIVE_DIR = "DCIM/SilentCamera"

    fun newValues(extension: String, mimeType: String): ContentValues {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SC_$stamp.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
        }
    }

    fun loadAll(context: Context): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, items)
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, items)
        return items.sortedWith(
            compareByDescending<MediaItem> { it.dateAdded }.thenByDescending { it.id }
        )
    }

    private fun query(context: Context, collection: Uri, isVideo: Boolean, out: MutableList<MediaItem>) {
        val projection = if (isVideo) {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.VideoColumns.DURATION)
        } else {
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
        }
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_DIR%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += MediaItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    isVideo = isVideo,
                    dateAdded = c.getLong(1),
                    durationMs = if (isVideo) c.getLong(2) else 0L,
                )
            }
        }
    }

    fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
    }.getOrNull()

    /** Decodes a photo scaled down so its longer side is at most [maxDim]. */
    fun decodePhoto(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxDim.toFloat() / max(w, h))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
    }.getOrNull()

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
