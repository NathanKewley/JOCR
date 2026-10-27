package io.github.nathankewley.jocr.ocr

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads one block of Japanese text - a speech bubble, a caption - with manga-ocr
 * (kha-white/manga-ocr-base), run by ONNX Runtime.
 *
 * A vision transformer looks at the whole block at once and a language model writes out its text,
 * a character at a time, so it reads vertical columns, small kana and punctuation that PaddleOCR's
 * line reader drops, and leaves furigana out. It was trained on manga, and squashes whatever it is
 * given into a square: it reads bubbles very well but long lines of horizontal text badly, inventing
 * fluent sentences that are not there. So it is only given blocks of vertical text (see
 * [PaddleTextRecognizer]).
 *
 * Slow and big: 460 MB of models, downloaded when the recogniser is chosen (see [ModelStore]), and
 * on a phone around a second a bubble.
 */
class MangaOcrReader(private val models: ModelStore) {

	private val encoder by lazy { Onnx.session(models.file(Models.MANGA_ENCODER)) }
	private val decoder by lazy { Onnx.session(models.file(Models.MANGA_DECODER)) }
	private val vocab by lazy { models.file(Models.MANGA_VOCAB).readLines() }

	/** The text in [image], a crop around one block. Blocking, for a second or so. */
	fun read(image: Bitmap): String {
		val input = image.scale(SIZE, SIZE)
		val pixels = try {
			tensor(input)
		} finally {
			if (input !== image) {
				input.recycle()
			}
		}
		return pixels.use { encoder.run(mapOf(PIXELS to it)) }.use { encoded ->
			val tokens = generate(encoded[0] as OnnxTensor)
			MangaOcrText.clean(tokens.filter { it >= FIRST_TEXT_TOKEN }.joinToString("") { vocab[it] })
		}
	}

	/**
	 * The tokens of the text, chosen greedily: the likeliest next one each time. manga-ocr's own beam
	 * search, with its rule against repeating three tokens, read worse in testing - it cuts ……
	 * short - and took twice as long.
	 */
	private fun generate(encoded: OnnxTensor): List<Int> {
		val tokens = mutableListOf(START)
		while (tokens.size < MAX_TOKENS && !MangaOcrText.isLooping(tokens)) {
			val ids = LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() })
			val next = OnnxTensor.createTensor(Onnx.env, ids, longArrayOf(1, tokens.size.toLong())).use { input ->
				decoder.run(mapOf(TOKENS to input, ENCODED to encoded)).use { result ->
					// Scores for every position; only the last one's, for the next token, matter.
					val logits = (result[0] as OnnxTensor).floatBuffer
					val size = logits.remaining() / tokens.size
					val offset = (tokens.size - 1) * size
					var best = 0
					for (i in 1 until size) {
						if (logits[offset + i] > logits[offset + best]) {
							best = i
						}
					}
					best
				}
			}
			if (next == END) {
				break
			}
			tokens += next
		}
		return tokens.drop(1)
	}

	/** [bitmap] as manga-ocr wants it: grey, in three identical channels, scaled to -1..1. */
	private fun tensor(bitmap: Bitmap): OnnxTensor {
		val plane = SIZE * SIZE
		val pixels = IntArray(plane)
		bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
		val data = FloatArray(plane * 3)
		for (i in 0 until plane) {
			val p = pixels[i]
			// The same weights as PIL's convert("L"), which manga-ocr was trained through.
			val grey = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
			val value = grey / 127.5f - 1f
			data[i] = value
			data[plane + i] = value
			data[plane * 2 + i] = value
		}
		return OnnxTensor.createTensor(Onnx.env, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()))
	}

	private companion object {

		const val PIXELS = "pixel_values"
		const val TOKENS = "input_ids"
		const val ENCODED = "encoder_hidden_states"

		/** The model's input is a square this many pixels across. */
		const val SIZE = 224

		/** [CLS] starts the text and [SEP] ends it; tokens below 5 are these and the other markers. */
		const val START = 2
		const val END = 3
		const val FIRST_TEXT_TOKEN = 5

		/** As in manga-ocr. A bubble never comes near it; a model stuck in a loop would. */
		const val MAX_TOKENS = 300
	}
}

/** The plain-text side of [MangaOcrReader], apart so it can be unit tested. */
internal object MangaOcrText {

	/** Dots, or an ellipsis manga-ocr has written as dots. */
	private val DOTS = Regex("[・.…]{2,}|…")

	/** Two readings this different - as a part of the longer - disagree about what the text is. */
	private const val MAX_DISAGREEMENT = 0.75f

	/** Loops of up to this many tokens are looked for... */
	private const val MAX_LOOP = 8

	/** ...and one this many tokens long, or repeated this many times, is a loop. */
	private const val MAX_RUN = 24
	private const val MIN_REPEATS = 6

	/**
	 * manga-ocr's own clean-up of what the model writes, except that it is given its ellipses back:
	 * no spaces, and letters, digits and ASCII punctuation full width, as Japanese text sets them.
	 */
	fun clean(text: String): String {
		val dotted = DOTS.replace(text.filterNot(Char::isWhitespace)) { match ->
			val dots = match.value.sumOf { if (it == '…') 3 else 1 }
			"…".repeat(max(1, (dots / 3f).roundToInt()))
		}
		return dotted.map { if (it in '!'..'~') it + 0xFEE0 else it }.joinToString("")
	}

	/**
	 * Whether [tokens] end in the same few tokens over and over: the model stuck in a loop rather than
	 * reading. A run as long as ーーーー or ドキドキ is text; one a page long is not.
	 */
	fun isLooping(tokens: List<Int>): Boolean = (1..MAX_LOOP).any { period ->
		val repeats = max(MIN_REPEATS, MAX_RUN / period)
		val length = period * repeats
		tokens.size >= length && (tokens.size - length until tokens.size - period).all { tokens[it] == tokens[it + period] }
	}

	/**
	 * Whether manga-ocr's reading of a block cannot be trusted next to PaddleOCR's [check]: when the
	 * two have little in common, manga-ocr has most likely invented text. In testing it was almost
	 * always PaddleOCR that was wrong when they differed at all, so only a wide gap counts.
	 */
	fun isImplausible(reading: String, check: String): Boolean {
		val a = Normalizer.normalize(reading, Normalizer.Form.NFKC)
		val b = Normalizer.normalize(check, Normalizer.Form.NFKC)
		if (a.isEmpty() || b.isEmpty()) {
			return a.isEmpty()
		}
		return distance(a, b).toFloat() / max(a.length, b.length) > MAX_DISAGREEMENT
	}

	private fun distance(a: String, b: String): Int {
		var previous = IntArray(b.length + 1) { it }
		for (i in 1..a.length) {
			val current = IntArray(b.length + 1)
			current[0] = i
			for (j in 1..b.length) {
				val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
				current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
			}
			previous = current
		}
		return previous[b.length]
	}
}
