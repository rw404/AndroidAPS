package app.aaps.ui.food.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max

/** Decodes only a granted local image, applies its orientation, and bounds memory. */
internal object FoodRecognitionImageLoader {

    fun decode(context: Context, uri: Uri, maxEdge: Int = 2200): Bitmap {
        require(uri.scheme == "content" || uri.scheme == "file") { "Only local image URIs are supported" }
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toDouble() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        if (image.config == Bitmap.Config.ARGB_8888) return image
        return try {
            checkNotNull(image.copy(Bitmap.Config.ARGB_8888, false))
        } finally {
            image.recycle()
        }
    }

    fun copy(bitmap: Bitmap, maxEdge: Int = 2200): Bitmap {
        require(!bitmap.isRecycled) { "Image has been recycled" }
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        val scale = maxEdge.toDouble() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        return try {
            checkNotNull(scaled.copy(Bitmap.Config.ARGB_8888, false))
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }
}
