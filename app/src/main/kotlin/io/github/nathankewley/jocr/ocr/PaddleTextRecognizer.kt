package io.github.nathankewley.jocr.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.core.graphics.scale
import io.github.nathankewley.jocr.ocr.TextDetection.isVertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads the Japanese text off a photo with PaddleOCR (the PP-OCRv5 mobile models), run by ONNX
 * Runtime.
 *
 * Two models: a detector that finds every line of text, whichever way it runs, and a recogniser
 * that reads one line at a time. Unlike ML Kit's, this recogniser was trained on vertical text: a
 * column turned a quarter to lie on its side is read as it is, so there is no cutting columns into
 * characters. It was also trained on photos of signs and menus, not just print and screenshots.
 *
 * With a [blockReader], blocks of vertical text - speech bubbles, mostly - are read again by
 * manga-ocr, which reads them far better: Paddle's line reader drops small kana and punctuation in
 * columns, and reads furigana as text. Horizontal text stays Paddle's, which manga-ocr reads badly.
 *
 * The models are downloaded when the recogniser is chosen (see [ModelStore]).
 */
class PaddleTextRecognizer(
	private val models: ModelStore,
	private val blockReader: MangaOcrReader? = null,
) : TextRecognizer {

	private val detector by lazy { Onnx.session(models.file(Models.PADDLE_DETECTOR)) }
	private val reader by lazy { Onnx.session(models.file(Models.PADDLE_READER)) }

	private val chars by lazy {
		// The recogniser's first output is "no character", and its last the space, which the
		// character list leaves out.
		listOf("") + PaddleConfig.characters(models.file(Models.PADDLE_READER_CONFIG).readText()) + " "
	}

	override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> = withContext(Dispatchers.Default) {
		val lines = ArrayList<Line>()
		for (tile in tiles(bitmap.width, bitmap.height)) {
			val part = if (tile.height() == bitmap.height) {
				bitmap
			} else {
				Bitmap.createBitmap(bitmap, 0, tile.top, tile.width(), tile.height())
			}
			try {
				readTile(part, tile.top, lines)
			} finally {
				if (part !== bitmap) {
					part.recycle()
				}
			}
		}
		val blocks = toBlocks(lines).map { block ->
			currentCoroutineContext().ensureActive()
			if (blockReader != null && block.isVertical) readAgain(bitmap, block, blockReader) else block
		}
		arrange(blocks)
	}

	/** [block] as [reader] reads it, unless that reading is too far from Paddle's to be believed. */
	private fun readAgain(page: Bitmap, block: OcrBlock, reader: MangaOcrReader): OcrBlock {
		val bounds = block.bounds
		// A margin around the text, as manga-ocr saw bubbles in training.
		val margin = (min(bounds.width(), bounds.height()) * BLOCK_MARGIN).roundToInt()
		val left = max(bounds.left - margin, 0)
		val top = max(bounds.top - margin, 0)
		val crop = Bitmap.createBitmap(
			page, left, top,
			min(bounds.right + margin, page.width) - left,
			min(bounds.bottom + margin, page.height) - top,
		)
		val text = try {
			reader.read(crop)
		} finally {
			if (crop !== page) {
				crop.recycle()
			}
		}
		return if (MangaOcrText.isImplausible(text, block.text)) block else block.copy(text = text)
	}

	private suspend fun readTile(bitmap: Bitmap, offsetY: Int, out: MutableList<Line>) {
		for (box in TextDetection.joinColumns(detect(bitmap))) {
			// Reading a line takes a moment; a new photo should not wait for the whole page.
			currentCoroutineContext().ensureActive()
			val text = read(bitmap, box).text.trim()
			if (text.isNotEmpty()) {
				out += Line(Box(box.left, box.top + offsetY, box.right, box.bottom + offsetY), text)
			}
		}
	}

	/** The lines of text in [bitmap], in its pixels. */
	private fun detect(bitmap: Bitmap): List<Box> {
		val k = min(1f, MAX_DETECT_SIDE.toFloat() / max(bitmap.width, bitmap.height))
		// The detector halves its input five times: both sides must be multiples of 32.
		val w = max((bitmap.width * k / 32).roundToInt(), 1) * 32
		val h = max((bitmap.height * k / 32).roundToInt(), 1) * 32
		val input = bitmap.scale(w, h)
		val prob = try {
			run(detector, tensor(input, DETECT_MEAN, DETECT_STD))
		} finally {
			if (input !== bitmap) {
				input.recycle()
			}
		}
		val sx = bitmap.width.toFloat() / w
		val sy = bitmap.height.toFloat() / h
		return TextDetection.boxes(prob, w, h).map { box ->
			Box(
				(box.left * sx).roundToInt(),
				(box.top * sy).roundToInt(),
				min((box.right * sx).roundToInt(), bitmap.width),
				min((box.bottom * sy).roundToInt(), bitmap.height),
			)
		}
	}

	/** The text in [box] of [page], read as one line. */
	private fun read(page: Bitmap, box: Box): CtcDecoder.Result {
		// A column is turned a quarter anticlockwise to lie as a line with its top at the left: the
		// way the recogniser was trained to see vertical text.
		val matrix = if (box.isVertical()) Matrix().apply { setRotate(-90f) } else null
		val crop = Bitmap.createBitmap(page, box.left, box.top, box.width, box.height, matrix, true)
		val line = crop.scale(max(ceil(READ_HEIGHT.toFloat() * crop.width / crop.height).toInt(), 1), READ_HEIGHT)
		val probs = try {
			run(reader, tensor(line, READ_MEAN, READ_STD))
		} finally {
			if (line !== crop) {
				line.recycle()
			}
			if (crop !== page) {
				crop.recycle()
			}
		}
		return CtcDecoder.decode(probs, probs.size / chars.size, chars)
	}

	/**
	 * [bitmap] as both models want it: channels first, normalised, and in BGR order because Paddle
	 * trained on images loaded by OpenCV. The means and deviations are in that order too.
	 */
	private fun tensor(bitmap: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
		val w = bitmap.width
		val h = bitmap.height
		val plane = w * h
		val pixels = IntArray(plane)
		bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
		val data = FloatArray(plane * 3)
		for (i in 0 until plane) {
			val p = pixels[i]
			data[i] = ((p and 0xff) / 255f - mean[0]) / std[0]
			data[plane + i] = ((p shr 8 and 0xff) / 255f - mean[1]) / std[1]
			data[plane * 2 + i] = ((p shr 16 and 0xff) / 255f - mean[2]) / std[2]
		}
		return OnnxTensor.createTensor(Onnx.env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong()))
	}

	/** Runs [model] on [input], which it closes, and returns its only output flattened. */
	private fun run(model: OrtSession, input: OnnxTensor): FloatArray = input.use {
		model.run(mapOf(INPUT to input)).use { result ->
			val buffer = (result[0] as OnnxTensor).floatBuffer
			FloatArray(buffer.remaining()).also(buffer::get)
		}
	}

	/**
	 * Lines into blocks. Columns of vertical text close enough to touch are one speech bubble or
	 * paragraph, read right to left, and a squarish box among them - a column only a character or
	 * two long - belongs with them; furigana beside them do not. Horizontal lines stay a block each,
	 * for [Paragraphs] to join.
	 */
	private fun toBlocks(lines: List<Line>): List<OcrBlock> {
		val (horizontal, rest) = lines.partition { it.box.width >= it.box.height * HORIZONTAL_RATIO }
		val blocks = horizontal.mapTo(ArrayList()) { it.toBlock() }
		val columns = rest.filter { it.box.isVertical() }
		if (columns.isEmpty()) {
			return blocks + rest.map { it.toBlock() }
		}
		for (region in TextRegions.cluster(rest, medianWidth(columns)) { it.box }) {
			if (region.none { it.box.isVertical() }) {
				blocks += region.map { it.toBlock() }
				continue
			}
			val text = TextDetection.withoutFurigana(region, box = { it.box }, text = { it.text })
			blocks += OcrBlock(
				text = text.sortedByDescending { it.box.right }.joinToString("") { it.text },
				bounds = region.map { it.box }.reduce(Box::union).toRect(),
				isVertical = true,
				charSize = medianWidth(region),
			)
		}
		return blocks
	}

	private fun medianWidth(lines: List<Line>): Int = lines.map { it.box.width }.sorted()[lines.size / 2]

	private fun Line.toBlock() = OcrBlock(
		text = text,
		bounds = box.toRect(),
		isVertical = box.isVertical(),
		charSize = if (box.isVertical()) box.width else TextDetection.charSizeOf(box, text),
	)

	private fun Box.toRect() = Rect(left, top, right, bottom)

	private class Line(val box: Box, val text: String)

	private companion object {

		const val INPUT = "x"

		/**
		 * The detector's input is at most this big on its long side. PaddleOCR reads at full size; on a
		 * phone that is seconds per photo, and text small enough to need it is too small to study.
		 */
		const val MAX_DETECT_SIDE = 1600

		/** The recogniser reads lines scaled to this height. */
		const val READ_HEIGHT = 48

		/** The margin around a block given to manga-ocr, as a part of the block's shorter side. */
		const val BLOCK_MARGIN = 0.1f

		/** A box at least this many times wider than tall is a line of horizontal text. */
		const val HORIZONTAL_RATIO = 1.5f

		val DETECT_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
		val DETECT_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
		val READ_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
		val READ_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
	}
}

/** The parts of PaddleOCR's model config the app needs, apart so they can be unit tested. */
internal object PaddleConfig {

	/**
	 * The reader's `character_dict`: one character per `  - ` line, quoted the YAML way when it is
	 * punctuation. This is all of the YAML there is to read, so no parser for the rest of it.
	 */
	fun characters(yaml: String): List<String> {
		val lines = yaml.lines()
		val start = lines.indexOf("  character_dict:") + 1
		check(start > 0) { "No character_dict in the recogniser config" }
		return lines.drop(start).takeWhile { it.startsWith("  - ") }.map { line ->
			val value = line.removePrefix("  - ")
			if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
				value.substring(1, value.length - 1).replace("''", "'")
			} else {
				value
			}
		}
	}
}
