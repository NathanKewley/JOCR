package io.github.nathankewley.jocr.nlp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real analyser and dictionary, so it checks the grouping rules against IPADIC's labels. */
class JapaneseTokenizerTest {

	private val tokenizer = JapaneseTokenizer()

	@Test
	fun conjugatedVerbIsOneWord() = runTest {
		val words = tokenizer.tokenize("昨日、友達と映画を見に行きました。")
		val surfaces = words.map { it.surface }
		assertTrue(surfaces.toString(), "行きました" in surfaces)
		assertEquals("行く", words.first { it.surface == "行きました" }.lookup)
		assertEquals("ともだち", words.first { it.surface == "友達" }.reading)
	}

	@Test
	fun teIruFormIsOneWord() = runTest {
		val words = tokenizer.tokenize("彼はご飯を食べている")
		val word = words.first { it.surface.startsWith("食べ") }
		assertEquals("食べている", word.surface)
		assertEquals("食べる", word.lookup)
	}

	@Test
	fun punctuationIsNotLookupable() = runTest {
		val words = tokenizer.tokenize("なに！？")
		assertTrue(words.filter { it.surface == "！" }.none { it.isLookupable })
	}
}
