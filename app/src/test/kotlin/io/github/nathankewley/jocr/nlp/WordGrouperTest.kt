package io.github.nathankewley.jocr.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordGrouperTest {

	@Test
	fun verbKeepsItsConjugationTail() {
		// 食べていた -> 食べ / て / い / た
		val words = WordGrouper.group(
			listOf(
				Morpheme("食べ", "食べる", "タベ", "動詞", "自立"),
				Morpheme("て", "て", "テ", "助詞", "接続助詞"),
				Morpheme("い", "いる", "イ", "動詞", "非自立"),
				Morpheme("た", "た", "タ", "助動詞", "*"),
			),
		)
		assertEquals(1, words.size)
		with(words.single()) {
			assertEquals("食べていた", surface)
			assertEquals("食べる", lookup)
			assertEquals("たべていた", reading)
			assertEquals(PartOfSpeech.VERB, partOfSpeech)
		}
	}

	@Test
	fun particlesStaySeparate() {
		// 本を読む
		val words = WordGrouper.group(
			listOf(
				Morpheme("本", "本", "ホン", "名詞", "一般"),
				Morpheme("を", "を", "ヲ", "助詞", "格助詞"),
				Morpheme("読む", "読む", "ヨム", "動詞", "自立"),
			),
		)
		assertEquals(listOf("本", "を", "読む"), words.map { it.surface })
		assertEquals(listOf("ほん", null, "よむ"), words.map { it.reading })
	}

	@Test
	fun prefixJoinsFollowingNoun() {
		val words = WordGrouper.group(
			listOf(
				Morpheme("お", "お", "オ", "接頭詞", "名詞接続"),
				Morpheme("茶", "茶", "チャ", "名詞", "一般"),
				Morpheme("を", "を", "ヲ", "助詞", "格助詞"),
			),
		)
		assertEquals(listOf("お茶", "を"), words.map { it.surface })
		assertEquals("お茶", words.first().lookup)
		assertEquals("おちゃ", words.first().reading)
	}

	@Test
	fun kanaOnlyWordHasNoFurigana() {
		val words = WordGrouper.group(listOf(Morpheme("すごい", "すごい", "スゴイ", "形容詞", "自立")))
		assertNull(words.single().reading)
	}

	@Test
	fun unknownWordFallsBackToSurface() {
		val words = WordGrouper.group(listOf(Morpheme("ドドド", null, null, "名詞", "一般")))
		with(words.single()) {
			assertEquals("ドドド", lookup)
			assertNull(reading)
		}
	}
}
