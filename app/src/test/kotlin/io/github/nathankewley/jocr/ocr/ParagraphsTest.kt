package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphsTest {

	private data class Line(val name: String, val box: Box, val charSize: Int = 20)

	private fun join(vararg lines: Line) = Paragraphs.joinBy(lines.toList(), { it.box }, { it.charSize })
		.map { paragraph -> paragraph.map { it.name } }

	@Test
	fun linesOfAParagraphAreJoined() {
		// 20px text with 10px between lines, the second line wrapping a word off the first.
		val result = join(
			Line("a", Box(0, 0, 400, 20)),
			Line("b", Box(0, 30, 400, 50)),
			Line("c", Box(0, 60, 150, 80)),
		)
		assertEquals(listOf(listOf("a", "b", "c")), result)
	}

	@Test
	fun blankLineStartsNewParagraph() {
		val result = join(
			Line("a", Box(0, 0, 400, 20)),
			Line("b", Box(0, 30, 200, 50)),
			Line("c", Box(0, 100, 400, 120)),
		)
		assertEquals(listOf(listOf("a", "b"), listOf("c")), result)
	}

	@Test
	fun headlineIsNotJoinedToBodyText() {
		val result = join(
			Line("headline", Box(0, 0, 400, 32), charSize = 32),
			Line("body", Box(0, 40, 400, 60)),
		)
		assertEquals(listOf(listOf("headline"), listOf("body")), result)
	}

	@Test
	fun firstLineIndentIsTolerated() {
		val result = join(
			Line("indented", Box(20, 0, 400, 20)),
			Line("next", Box(0, 30, 400, 50)),
		)
		assertEquals(listOf(listOf("indented", "next")), result)
	}

	@Test
	fun sideBySideColumnsStaySeparate() {
		// A caption beside a thumbnail: two lines each, starting far apart.
		val result = join(
			Line("left1", Box(0, 0, 100, 20)),
			Line("right1", Box(200, 0, 400, 20)),
			Line("left2", Box(0, 30, 100, 50)),
			Line("right2", Box(200, 30, 400, 50)),
		)
		assertEquals(listOf(listOf("left1", "left2"), listOf("right1", "right2")), result)
	}
}
