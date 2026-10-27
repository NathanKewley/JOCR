package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CtcDecoderTest {

	private val chars = listOf("", "日", "本", "語")

	/** One row per step, the likeliest entry of [chars] given [p] and the rest sharing what is left. */
	private fun steps(p: Float, vararg best: Int): FloatArray {
		val probs = FloatArray(best.size * chars.size) { (1 - p) / (chars.size - 1) }
		best.forEachIndexed { step, c -> probs[step * chars.size + c] = p }
		return probs
	}

	@Test
	fun repeatsAreCountedOnceAndGapsDropped() {
		val result = CtcDecoder.decode(steps(0.9f, 0, 1, 1, 0, 2, 2, 2, 0, 0, 3), 10, chars)
		assertEquals("日本語", result.text)
		assertEquals(0.9f, result.confidence, 1e-6f)
	}

	@Test
	fun aGapSeparatesTheSameCharacterTwice() {
		assertEquals("日日", CtcDecoder.decode(steps(0.9f, 1, 0, 1), 3, chars).text)
	}

	@Test
	fun nothingReadIsEmpty() {
		val result = CtcDecoder.decode(steps(0.9f, 0, 0, 0), 3, chars)
		assertEquals("", result.text)
		assertEquals(0f, result.confidence, 0f)
	}
}
