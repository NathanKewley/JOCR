package io.github.nathankewley.jocr.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the Japanese text off a photo, one [OcrBlock] per block of text (a speech bubble, a
 * paragraph, a sign), in reading order (see [ReadingOrder]).
 *
 * ML Kit only really reads horizontal text. It finds where the text in a photo is well enough, but
 * reads a vertical block either column by column as separate fragments or straight across the
 * columns, which is gibberish. So every region it finds is also re-read a second way - its columns
 * cut into characters and laid out again as horizontal lines (see [VerticalTextLayout]) - and the
 * reading the recogniser is more confident about wins. Horizontal titles and captions keep their
 * first reading; vertical dialogue takes the second.
 */
class PhotoTextRecognizer {

	// The bundled model, not the Play Services one: it works on devices without Google Play, and it
	// does not have to be downloaded the first time a photo is read.
	private val recognizer by lazy {
		TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
	}

	/** Reads [bitmap], which stays the caller's: it is not recycled here. */
	suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
		val blocks = ArrayList<OcrBlock>()
		for (tile in tiles(bitmap.width, bitmap.height)) {
			val part = if (tile.height() == bitmap.height) {
				bitmap
			} else {
				Bitmap.createBitmap(bitmap, 0, tile.top, tile.width(), tile.height())
			}
			try {
				recognizeTile(part, tile.top, blocks)
			} finally {
				if (part !== bitmap) {
					part.recycle()
				}
			}
		}
		val text = Paragraphs.join(blocks).filter { block ->
			// Page numbers, stray marks and lone sound effects: noise between the lines worth studying.
			block.text.count { it.isJapanese() } >= MIN_JAPANESE_CHARS
		}
		return ReadingOrder.sort(text)
	}

	private suspend fun recognizeTile(bitmap: Bitmap, offsetY: Int, out: MutableList<OcrBlock>) {
		val scale = when {
			// Downscaling a big photo keeps memory in check.
			bitmap.width > MAX_WIDTH -> MAX_WIDTH.toFloat() / bitmap.width
			// Upscaling a small image gives the recogniser more pixels per stroke. It cannot add detail,
			// but dense kanji in a low-resolution screenshot are read noticeably better at the larger size.
			bitmap.width < MIN_WIDTH -> MIN_WIDTH.toFloat() / bitmap.width
			else -> 1f
		}
		val input = if (scale != 1f) {
			bitmap.scale((bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt())
		} else {
			bitmap
		}
		try {
			val lines = recognizer.process(InputImage.fromBitmap(input, 0)).await()
				.textBlocks
				.flatMap { it.lines }
				.filter { it.boundingBox != null && it.text.isNotBlank() }
			if (lines.isEmpty()) {
				return
			}
			val regions = TextRegions.cluster(lines, charSizeOf(lines)) { it.box() }
			for (region in regions) {
				val bounds = region.map { it.box() }.reduce(Box::union)
				val original = readAsDetected(region)
				val reflowed = if (original.isVertical || region.looksLikeMisreadColumns()) {
					readAsVertical(input, region)
				} else {
					null
				}
				val best = if (reflowed != null && reflowed.confidence > original.confidence) reflowed else original
				out += OcrBlock(
					text = best.text,
					bounds = Rect(
						(bounds.left / scale).roundToInt(),
						(bounds.top / scale).roundToInt() + offsetY,
						(bounds.right / scale).roundToInt(),
						(bounds.bottom / scale).roundToInt() + offsetY,
					),
					isVertical = best.isVertical,
					charSize = (charSizeOf(region) / scale).roundToInt(),
				)
			}
		} finally {
			if (input !== bitmap) {
				input.recycle()
			}
		}
	}

	/**
	 * Whether ML Kit has read a vertical block across its columns. It comes out as several short
	 * rows - a column's worth of characters each - where genuine horizontal text, a title or a
	 * caption, is a few long lines.
	 */
	private fun List<Text.Line>.looksLikeMisreadColumns(): Boolean {
		val lengths = map { it.text.count { c -> !c.isWhitespace() } }.sorted()
		return lengths[lengths.size / 2] <= MAX_MISREAD_ROW_LENGTH
	}

	/** The region as ML Kit read it. Right for horizontal text, and for long clean columns. */
	private fun readAsDetected(lines: List<Text.Line>): Reading {
		val isVertical = lines.count { abs(abs(it.angle) - 90f) < 20f } * 2 > lines.size
		val ordered = if (isVertical) {
			// ML Kit lists a vertical block's columns left to right - backwards.
			lines.sortedByDescending { it.box().right }
		} else {
			lines.sortedWith(compareBy<Text.Line> { it.box().top }.thenBy { it.box().left })
		}
		return Reading(
			text = ordered.joinToString("") { it.text.filterNot(Char::isWhitespace) },
			confidence = ordered.weightedConfidence(),
			isVertical = isVertical,
		)
	}

	/**
	 * The region read as vertical text: its columns, right to left, each cut into characters that
	 * are laid out again left to right as one horizontal line, and the result read again.
	 */
	private suspend fun readAsVertical(page: Bitmap, lines: List<Text.Line>): Reading? {
		val charSize = charSizeOf(lines)
		if (charSize < MIN_CHAR_SIZE) {
			return null
		}
		// Generous around what ML Kit marked as text: it often misses the last characters of a
		// column, and those are exactly the ones a reading across the columns lost.
		val area = lines.map { it.box() }.reduce(Box::union)
		val crop = Box(
			max(area.left - charSize / 2, 0),
			max(area.top - charSize * 3 / 2, 0),
			min(area.right + charSize / 2, page.width),
			min(area.bottom + charSize * 3 / 2, page.height),
		)
		val pixels = IntArray(crop.width * crop.height)
		page.getPixels(pixels, 0, crop.width, crop.left, crop.top, crop.width, crop.height)
		val ink = VerticalTextLayout.textOnly(
			InkMap(crop.width, crop.height, BooleanArray(pixels.size) { pixels[it].luma() < INK_THRESHOLD }),
			charSize,
		)
		val columns = VerticalTextLayout.columns(ink, charSize).map { column ->
			VerticalTextLayout.cells(ink, column, charSize).map { cell ->
				Box(cell.left + crop.left, cell.top + crop.top, cell.right + crop.left, cell.bottom + crop.top)
			}
		}.filter { it.isNotEmpty() }
		if (columns.isEmpty()) {
			return null
		}
		val strip = layOutHorizontally(page, columns, charSize)
		val result = try {
			recognizer.process(InputImage.fromBitmap(strip, 0)).await()
		} finally {
			strip.recycle()
		}
		val readLines = result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
		if (readLines.isEmpty()) {
			return null
		}
		val lineHeight = (STRIP_CHAR_SIZE * STRIP_LINE_HEIGHT).roundToInt()
		val ordered = readLines.sortedWith(
			compareBy<Text.Line> { (it.box().top + it.box().bottom) / 2 / lineHeight }.thenBy { it.box().left },
		)
		return Reading(
			text = ordered.joinToString("") { it.text.filterNot(Char::isWhitespace) },
			confidence = ordered.weightedConfidence(),
			isVertical = true,
		)
	}

	/** Draws each column as one line of characters, at a size the recogniser reads well. */
	private fun layOutHorizontally(page: Bitmap, columns: List<List<Box>>, charSize: Int): Bitmap {
		val k = STRIP_CHAR_SIZE.toFloat() / charSize
		val gap = (STRIP_CHAR_SIZE * 0.15f).roundToInt()
		val lineHeight = (STRIP_CHAR_SIZE * STRIP_LINE_HEIGHT).roundToInt()
		val margin = STRIP_CHAR_SIZE
		val width = columns.maxOf { cells ->
			cells.sumOf { cell -> (cell.drawnWidth() * k).roundToInt() + gap }
		} + margin * 2
		val strip = createBitmap(width, lineHeight * columns.size + margin * 2)
		val canvas = Canvas(strip)
		canvas.drawColor(Color.WHITE)
		val paint = Paint(Paint.FILTER_BITMAP_FLAG)
		columns.forEachIndexed { index, cells ->
			// Lines start at the margin so the line index can be recovered from a line's centre.
			val centerY = margin + lineHeight * index + lineHeight / 2f
			var x = margin.toFloat()
			for (cell in cells) {
				val src = Rect(cell.left, cell.top, cell.right, cell.bottom)
				val w = cell.width * k
				val h = cell.height * k
				if (cell.isTurnedInVerticalText()) {
					// ー and ～ are turned a quarter in vertical text; turned back, they read as
					// themselves instead of as 1 or l.
					canvas.save()
					canvas.translate(x + h / 2, centerY)
					canvas.rotate(-90f)
					canvas.drawBitmap(page, src, RectF(-w / 2, -h / 2, w / 2, h / 2), paint)
					canvas.restore()
					x += h + gap
				} else {
					// Small kana and punctuation sit on the baseline in horizontal text. Centred, a small
					// っ is just a smaller つ; on the baseline it is the small one the recogniser knows.
					val isSmall = cell.width < charSize * SMALL_CHAR && cell.height < charSize * SMALL_CHAR
					val bottom = if (isSmall) centerY + STRIP_CHAR_SIZE / 2f else centerY + h / 2
					canvas.drawBitmap(page, src, RectF(x, bottom - h, x + w, bottom), paint)
					x += w + gap
				}
			}
		}
		return strip
	}

	private fun Box.isTurnedInVerticalText(): Boolean = height > width * 2

	private fun Box.drawnWidth(): Int = if (isTurnedInVerticalText()) height else width

	private fun charSizeOf(lines: List<Text.Line>): Int {
		// A line is one character thick whichever way it runs, so its short side is a character.
		val sizes = lines.map { min(it.box().width, it.box().height) }.sorted()
		return sizes[sizes.size / 2]
	}

	private fun List<Text.Line>.weightedConfidence(): Float {
		val total = sumOf { it.text.length }
		return if (total == 0) 0f else sumOf { (it.confidence * it.text.length).toDouble() }.toFloat() / total
	}

	private fun Text.Line.box(): Box = checkNotNull(boundingBox).let { Box(it.left, it.top, it.right, it.bottom) }

	private fun Int.luma(): Int = (Color.red(this) * 299 + Color.green(this) * 587 + Color.blue(this) * 114) / 1000

	/**
	 * Splits a tall image - a long screenshot, a webtoon strip - into screen-shaped slices.
	 *
	 * Fed whole, an 800x12000 strip is scaled down by the recogniser until the text is a few pixels
	 * high and nothing is read at all. A block cut in two by a slice boundary comes back as two
	 * blocks, which is a far smaller loss.
	 */
	private fun tiles(width: Int, height: Int): List<Rect> {
		val tileHeight = max(width * 2, MIN_TILE_HEIGHT)
		if (height <= tileHeight * 5 / 4) {
			return listOf(Rect(0, 0, width, height))
		}
		return (0 until height step tileHeight).map { top ->
			Rect(0, top, width, min(top + tileHeight, height))
		}
	}

	private class Reading(val text: String, val confidence: Float, val isVertical: Boolean)

	private companion object {

		const val MAX_WIDTH = 2000
		const val MIN_WIDTH = 1600
		const val MIN_TILE_HEIGHT = 1200
		const val MIN_JAPANESE_CHARS = 2

		/** Darker than this is ink. Speech bubbles and print are dark text on light, so this need not be clever. */
		const val INK_THRESHOLD = 140

		/** Rows this short, in characters, are what reading across a vertical block produces. */
		const val MAX_MISREAD_ROW_LENGTH = 4

		/** Text this small is a footnote or furigana; cutting it into characters only adds noise. */
		const val MIN_CHAR_SIZE = 12

		/** Characters smaller than this, in characters, both ways are small kana or punctuation. */
		const val SMALL_CHAR = 0.75f

		/** Character size in the re-laid-out strip, and line spacing as a multiple of it. */
		const val STRIP_CHAR_SIZE = 64
		const val STRIP_LINE_HEIGHT = 1.6f
	}
}

/** One block of text, with [bounds] and [charSize] in the pixels of the bitmap that was read. */
data class OcrBlock(
	val text: String,
	val bounds: Rect,
	val isVertical: Boolean,
	/** The size of one character: what tells a paragraph's next line from the next paragraph. */
	val charSize: Int,
)

fun Char.isJapanese(): Boolean = this in '぀'..'ヿ' || // hiragana, katakana
	this in '一'..'鿿' || // CJK unified ideographs
	this in '㐀'..'䶿' || // CJK extension A
	this == '々' // 々
