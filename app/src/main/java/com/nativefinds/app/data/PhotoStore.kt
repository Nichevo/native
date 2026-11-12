package com.nativefinds.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Where photos live: a temporary capture file, then a permanent, upright, size-limited copy. */
class PhotoStore(private val context: Context) {
    private val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
    private val captureDir = File(context.cacheDir, "capture").apply { mkdirs() }

    /** A fresh file + content URI for the camera app to write into. */
    fun newCaptureTarget(): Pair<File, Uri> {
        val f = File(captureDir, "capture-${System.currentTimeMillis()}.jpg")
        return f to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    /** GPS embedded in a gallery photo, if the system left it in. */
    fun readExifLocation(uri: Uri): Pair<Double, Double>? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).latLong?.let { it[0] to it[1] }
        }
    }.getOrNull()

    /**
     * Copies an image into permanent storage, rotated upright and scaled to at most [maxSide] px.
     * Returns the new file.
     */
    fun importPhoto(uri: Uri, maxSide: Int = 2048): File {
        val resolver = context.contentResolver
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not read the photo")

        val upright = rotate(decoded, orientation)
        val scaled = scaleDown(upright, maxSide)
        val out = File(photosDir, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return out
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    fun clearCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun loadBitmap(path: String, maxSide: Int): Bitmap {
            val bmp = BitmapFactory.decodeFile(path) ?: error("Could not read $path")
            return scaleDown(bmp, maxSide)
        }

        fun jpegBytes(path: String, maxSide: Int, quality: Int = 85): ByteArray {
            val bmp = loadBitmap(path, maxSide)
            return ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it); it.toByteArray() }
        }

        fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
            val longest = maxOf(bmp.width, bmp.height)
            if (longest <= maxSide) return bmp
            val f = maxSide.toFloat() / longest
            return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        }

        private fun rotate(bmp: Bitmap, orientation: Int): Bitmap {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                else -> return bmp
            }
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
    }
}
