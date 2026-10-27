package io.github.nathankewley.jocr.ocr

/**
 * Reads the text out of PaddleOCR's recogniser output.
 *
 * The recogniser slides along a line and gives, at every step, a probability for each character
 * it knows and for "no character" (CTC). The text is the likeliest character at each step, with
 * repeats of a character across neighbouring steps counted once and the gaps dropped.
 */
object CtcDecoder {

	class Result(val text: String, val confidence: Float)

	/**
	 * [probs] holds [steps] rows of one probability per entry of [chars], which starts with the
	 * "no character" entry.
	 */
	fun decode(probs: FloatArray, steps: Int, chars: List<String>): Result {
		val classes = chars.size
		require(probs.size == steps * classes) { "Expected $steps x $classes probabilities, got ${probs.size}" }
		val text = StringBuilder()
		var confidence = 0.0
		var count = 0
		var last = BLANK
		for (step in 0 until steps) {
			val row = step * classes
			var best = 0
			for (c in 1 until classes) {
				if (probs[row + c] > probs[row + best]) {
					best = c
				}
			}
			if (best != BLANK && best != last) {
				text.append(chars[best])
				confidence += probs[row + best]
				count++
			}
			last = best
		}
		return Result(text.toString(), if (count == 0) 0f else (confidence / count).toFloat())
	}

	private const val BLANK = 0
}
