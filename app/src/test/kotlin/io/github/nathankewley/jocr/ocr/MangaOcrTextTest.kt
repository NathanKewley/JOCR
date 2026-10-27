package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaOcrTextTest {

	@Test
	fun dotsBecomeEllipses() {
		assertEquals("え……うそでしょ？", MangaOcrText.clean("え......うそでしょ?"))
		assertEquals("全然ダメだった…", MangaOcrText.clean("全然ダメだった..."))
		assertEquals("待って…", MangaOcrText.clean("待って・・"))
	}

	@Test
	fun asciiBecomesFullWidthAndSpacesGo() {
		assertEquals("ＮＨＫの２５日！", MangaOcrText.clean("NHK の 25日!"))
	}

	@Test
	fun ordinaryRunsAreNotLoops() {
		assertFalse(MangaOcrText.isLooping(listOf(2) + List(10) { 50 }))
		assertFalse(MangaOcrText.isLooping(listOf(2, 60, 61, 60, 61, 60, 61)))
	}

	@Test
	fun longRunsAreLoops() {
		assertTrue(MangaOcrText.isLooping(listOf(2) + List(24) { 50 }))
		assertTrue(MangaOcrText.isLooping(listOf(2) + List(6) { listOf(70, 71, 72, 73) }.flatten()))
	}

	/** Readings of the same bubble, from the test pages. */
	@Test
	fun aCloserReadingIsBelieved() {
		assertFalse(MangaOcrText.isImplausible("ちょっと待って！財布を忘れた", "ちつと待て財布忘れた"))
		assertFalse(MangaOcrText.isImplausible("早く逃げろ！", "く逃けろ"))
	}

	@Test
	fun anInventedReadingIsNot() {
		assertTrue(MangaOcrText.isImplausible("２０１８明日の試験や２０１７年１２月１２日、ちょっと待って！財布を忘れた", "明日の試験自信ある?"))
		assertTrue(MangaOcrText.isImplausible("", "明日"))
	}
}
