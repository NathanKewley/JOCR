package io.github.nathankewley.jocr.ocr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlin.math.max
import kotlin.math.roundToInt

/** Loads a photo from the camera, the gallery or a share into something the recogniser can read. */
class PhotoDecoder(private val resolver: ContentResolver) {

	/**
	 * The photo, upright, in software memory and no bigger than [MAX_SIDE] on its long side.
	 *
	 * ImageDecoder rather than BitmapFactory: it applies the EXIF rotation a camera writes instead
	 * of turning the pixels, and it reads the HEIC photos newer phones take. Software, because a
	 * hardware bitmap can be drawn but not cropped, scaled or read, and the recogniser does all three.
	 * A 50 megapixel photo is cut down on decode: the recogniser reads at most 2000 pixels across
	 * anyway, and at full size it would not fit in memory.
	 */
	suspend fun decode(uri: Uri): Bitmap = runInterruptible(Dispatchers.IO) {
		val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
			decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
			val side = max(info.size.width, info.size.height)
			if (side > MAX_SIDE) {
				val k = MAX_SIDE.toFloat() / side
				decoder.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
			}
		}
		// HDR photos and some PNGs decode to other configs; ML Kit and the ink map want plain ARGB.
		if (bitmap.config == Bitmap.Config.ARGB_8888) {
			bitmap
		} else {
			bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
		}
	}

	/** A copy small enough to show on screen without holding the full photo twice. */
	fun preview(bitmap: Bitmap): Bitmap {
		val side = max(bitmap.width, bitmap.height)
		if (side <= MAX_PREVIEW_SIDE) {
			return bitmap.copy(Bitmap.Config.ARGB_8888, false)
		}
		val k = MAX_PREVIEW_SIDE.toFloat() / side
		return bitmap.scale((bitmap.width * k).roundToInt(), (bitmap.height * k).roundToInt())
	}

	private companion object {

		const val MAX_SIDE = 3200
		const val MAX_PREVIEW_SIDE = 1600
	}
}
