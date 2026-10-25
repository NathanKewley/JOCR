package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingOrderTest {

	private data class Box(val name: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

	private fun sort(vararg boxes: Box, isRightToLeft: Boolean = true) = ReadingOrder.sortBy(
		items = boxes.toList(),
		top = { it.top },
		bottom = { it.bottom },
		left = { it.left },
		right = { it.right },
		isRightToLeft = isRightToLeft,
	).map { it.name }

	@Test
	fun verticalRowIsReadRightToLeft() {
		val result = sort(
			Box("left", 0, 10, 100, 200),
			Box("right", 300, 0, 400, 180),
			Box("middle", 150, 20, 250, 210),
		)
		assertEquals(listOf("right", "middle", "left"), result)
	}

	@Test
	fun horizontalRowIsReadLeftToRight() {
		val result = sort(
			Box("left", 0, 10, 100, 200),
			Box("right", 300, 0, 400, 180),
			Box("middle", 150, 20, 250, 210),
			isRightToLeft = false,
		)
		assertEquals(listOf("left", "middle", "right"), result)
	}

	@Test
	fun rowsAreReadTopToBottom() {
		val result = sort(
			Box("bottom-right", 300, 500, 400, 700),
			Box("top-left", 0, 0, 100, 200),
			Box("bottom-left", 0, 520, 100, 680),
			Box("top-right", 300, 30, 400, 190),
		)
		assertEquals(listOf("top-right", "top-left", "bottom-right", "bottom-left"), result)
	}

	@Test
	fun slightOverlapStartsNewRow() {
		// The lower block pokes 20px into the first row; it still belongs to the second tier.
		val result = sort(
			Box("upper-left", 0, 0, 100, 200),
			Box("lower-right", 300, 180, 400, 400),
		)
		assertEquals(listOf("upper-left", "lower-right"), result)
	}
}
