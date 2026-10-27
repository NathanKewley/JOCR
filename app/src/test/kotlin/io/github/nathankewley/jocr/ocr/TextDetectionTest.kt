package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDetectionTest {

	/** A [width] x [height] map with [value] inside each of [boxes] and nothing elsewhere. */
	private fun map(width: Int, height: Int, value: Float, vararg boxes: Box): FloatArray {
		val prob = FloatArray(width * height)
		for (box in boxes) {
			for (y in box.top until box.bottom) {
				for (x in box.left until box.right) {
					prob[y * width + x] = value
				}
			}
		}
		return prob
	}

	@Test
	fun eachPatchIsALineGrownBackOut() {
		val prob = map(100, 60, 0.9f, Box(10, 10, 50, 20), Box(10, 40, 90, 50))
		assertEquals(
			// Grown by area * 1.5 / perimeter: 40x10 by 6, 80x10 by 7.
			setOf(Box(4, 4, 56, 26), Box(3, 33, 97, 57)),
			TextDetection.boxes(prob, 100, 60).toSet(),
		)
	}

	@Test
	fun boxesStayInsideTheMap() {
		val prob = map(40, 20, 0.9f, Box(0, 0, 40, 10))
		assertEquals(listOf(Box(0, 0, 40, 16)), TextDetection.boxes(prob, 40, 20))
	}

	@Test
	fun unsureLinesAndSpecksAreDropped() {
		val unsure = map(100, 60, 0.5f, Box(10, 10, 50, 20))
		val speck = map(100, 60, 0.9f, Box(10, 10, 12, 30))
		assertEquals(emptyList<Box>(), TextDetection.boxes(unsure, 100, 60))
		assertEquals(emptyList<Box>(), TextDetection.boxes(speck, 100, 60))
	}

	/** The pieces the detector returned for a column broken at its comma: うん / 、行こう！ */
	@Test
	fun piecesOfAColumnAreJoined() {
		val result = TextDetection.joinColumns(listOf(Box(676, 206, 722, 318), Box(672, 298, 726, 537)))
		assertEquals(listOf(Box(672, 206, 726, 537)), result)
	}

	@Test
	fun neighbouringColumnsStaySeparate() {
		val columns = listOf(Box(733, 204, 787, 672), Box(790, 324, 850, 673), Box(795, 204, 845, 354))
		assertEquals(listOf(Box(733, 204, 787, 672), Box(790, 204, 850, 673)), TextDetection.joinColumns(columns))
	}

	@Test
	fun horizontalLinesAreNotJoined() {
		val lines = listOf(Box(10, 230, 390, 249), Box(12, 254, 288, 273))
		assertEquals(lines, TextDetection.joinColumns(lines))
	}

	@Test
	fun aWideLineUnderAColumnIsNotJoinedToIt() {
		val boxes = listOf(Box(100, 0, 140, 300), Box(0, 310, 240, 350))
		assertEquals(boxes, TextDetection.joinColumns(boxes))
	}

	/** A headline and the date under it, from a news screenshot: boxes nearly the same height. */
	@Test
	fun charSizeComesFromTheWidthPerCharacter() {
		assertEquals(16, TextDetection.charSizeOf(Box(18, 21, 379, 43), "【映像】和歌山市で道路が川のようにカラーコー"))
		assertEquals(11, TextDetection.charSizeOf(Box(8, 70, 119, 87), "2026年10月25日11:33"))
	}

	@Test
	fun charSizeIsNeverMoreThanTheHeight() {
		assertEquals(20, TextDetection.charSizeOf(Box(0, 0, 400, 20), "あ"))
		assertEquals(20, TextDetection.charSizeOf(Box(0, 0, 400, 20), ""))
	}

	@Test
	fun furiganaAreDropped() {
		val columns = listOf(
			"さいふ" to Box(530, 100, 545, 180),
			"ちょっと待って!" to Box(546, 40, 584, 400),
			"財布を忘れた" to Box(486, 40, 524, 300),
		)
		val result = TextDetection.withoutFurigana(columns, box = { it.second }, text = { it.first })
		assertEquals(listOf("ちょっと待って!", "財布を忘れた"), result.map { it.first })
	}

	@Test
	fun aShortColumnOfKanaIsNotFurigana() {
		val columns = listOf("え?" to Box(560, 40, 590, 120), "なんで" to Box(600, 40, 636, 200))
		assertEquals(columns, TextDetection.withoutFurigana(columns, box = { it.second }, text = { it.first }))
	}

	/** The five columns of a narration page, as the detector found them: one column in two pieces. */
	@Test
	fun wideBlocksAreCutIntoChunksOfColumns() {
		val columns = listOf(
			Box(900, 0, 950, 500), Box(840, 0, 890, 300), Box(845, 320, 885, 480),
			Box(780, 0, 830, 500), Box(720, 0, 770, 400), Box(660, 0, 710, 500),
		)
		val area = Box(650, -10, 960, 510)
		assertEquals(
			listOf(Box(835, -10, 960, 510), Box(715, -10, 835, 510), Box(650, -10, 715, 510)),
			TextDetection.columnChunks(area, columns, 2),
		)
	}

	@Test
	fun aNarrowBlockIsOneChunk() {
		val area = Box(0, 0, 100, 300)
		assertEquals(listOf(area), TextDetection.columnChunks(area, listOf(Box(50, 0, 95, 300), Box(5, 0, 45, 300)), 2))
	}

	/** A name caption from a manga page: おうたいし set over 王太子, and a separate title above both. */
	@Test
	fun furiganaOverHorizontalTextAreDropped() {
		val lines = listOf(
			"ファルサス王家" to Box(100, 100, 400, 140),
			"おうたいし" to Box(150, 150, 260, 170),
			"王太子" to Box(140, 168, 280, 210),
		)
		val result = TextDetection.withoutRubyLines(lines, box = { it.second }, text = { it.first })
		assertEquals(listOf("ファルサス王家", "王太子"), result.map { it.first })
	}

	@Test
	fun aSmallLineOfKanaAwayFromTheTextIsKept() {
		val lines = listOf("ねえ" to Box(100, 0, 160, 20), "王太子" to Box(100, 200, 240, 240))
		assertEquals(lines, TextDetection.withoutRubyLines(lines, box = { it.second }, text = { it.first }))
	}
}
