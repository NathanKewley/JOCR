package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalTextLayoutTest {

	/** Builds an ink map from filled rectangles: a crude stand-in for glyphs. */
	private fun ink(width: Int, height: Int, vararg rects: Box): InkMap {
		val pixels = BooleanArray(width * height)
		for (r in rects) {
			for (y in r.top until r.bottom) for (x in r.left until r.right) pixels[y * width + x] = true
		}
		return InkMap(width, height, pixels)
	}

	private fun glyph(left: Int, top: Int, size: Int = 20) = Box(left, top, left + size, top + size)

	@Test
	fun columnsAreFoundRightToLeft() {
		val map = ink(
			100, 100,
			glyph(70, 0), glyph(70, 25), // right column
			glyph(40, 0), // middle
			glyph(10, 0), glyph(10, 25), glyph(10, 50), // left
		)
		assertEquals(3, VerticalTextLayout.columns(map, 20).size)
	}

	@Test
	fun furiganaColumnIsDropped() {
		// A 6px-wide column of furigana 4px to the right of a 20px column of text.
		val map = ink(60, 60, glyph(10, 0), glyph(10, 25), Box(34, 2, 40, 18))
		val columns = VerticalTextLayout.columns(map, 20)
		assertEquals(1, columns.size)
		assertEquals(true, columns.single().last < 34) // stops short of the furigana
	}

	@Test
	fun gapInsideGlyphDoesNotSplitColumn() {
		// 川: three strokes with 2px between them.
		val map = ink(40, 40, Box(5, 0, 9, 20), Box(11, 0, 15, 20), Box(17, 0, 25, 20))
		assertEquals(1, VerticalTextLayout.columns(map, 20).size)
	}

	@Test
	fun cellsSplitCharactersButNotStrokes() {
		// Column of: 二 (two strokes with a gap), a full glyph, and a small っ.
		val map = ink(
			30, 100,
			Box(5, 0, 25, 4), Box(5, 14, 25, 18), // 二
			glyph(5, 24), // a square glyph
			Box(8, 50, 22, 60), // small kana
		)
		val cells = VerticalTextLayout.cells(map, 5..24, 20)
		assertEquals(listOf(0 until 18, 24 until 44, 50 until 60), cells.map { it.top until it.bottom })
		assertEquals(listOf(5 until 25, 5 until 25, 8 until 22), cells.map { it.left until it.right })
	}

	@Test
	fun longVowelMarkIsTallAndNarrow() {
		val map = ink(30, 30, Box(13, 2, 17, 22))
		val cell = VerticalTextLayout.cells(map, 5..24, 20).single()
		assertEquals(Box(13, 2, 17, 22), cell)
	}

	@Test
	fun outlineAndEdgeBlobsAreRemoved() {
		val map = ink(
			100, 100,
			glyph(40, 30), // text
			Box(10, 5, 90, 8), // a long stroke of the bubble outline
			Box(0, 50, 6, 60), // something cut off by the edge of the crop
		)
		val text = VerticalTextLayout.textOnly(map, 20)
		assertEquals(true, text[45, 35])
		assertEquals(false, text[50, 6])
		assertEquals(false, text[2, 55])
		assertEquals(1, VerticalTextLayout.columns(text, 20).size)
	}

	@Test
	fun dashedRuleDoesNotJoinColumns() {
		// Two columns 6px apart under a 2px rule of dashes running across both and the gap.
		val map = ink(
			80, 100,
			Box(4, 2, 76, 4),
			glyph(50, 20), glyph(50, 45), glyph(50, 70),
			glyph(24, 20), glyph(24, 45), glyph(24, 70),
		)
		val columns = VerticalTextLayout.columns(map, 20)
		// Each widened to a full character around its centre, never past the middle of the gap.
		assertEquals(listOf(48..70, 22..44), columns)
	}

	@Test
	fun columnWidenedOverThinStrokes() {
		// 二 over 三: horizontal strokes only, so few pixels in any one position at the edges.
		val map = ink(
			40, 60,
			Box(8, 0, 32, 3), Box(10, 14, 30, 17),
			Box(8, 24, 32, 27), Box(12, 32, 28, 35), Box(6, 40, 34, 43),
		)
		val column = VerticalTextLayout.columns(map, 30).single()
		assertEquals(true, column.first <= 6 && column.last >= 33)
	}
}
