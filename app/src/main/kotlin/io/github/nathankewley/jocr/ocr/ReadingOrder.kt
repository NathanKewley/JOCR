package io.github.nathankewley.jocr.ocr

/**
 * Puts the blocks of text in a photo into the order they are read.
 *
 * Text is read in tiers: the blocks are grouped into rows - a block joins the current row when it
 * overlaps it vertically by at least half of its own height - and the rows are read top to bottom.
 * Within a row, vertical text (a manga page, a novel, a menu set in columns) is read from the
 * rightmost block, horizontal text (a sign, a label, a screenshot) from the leftmost. Panel borders
 * are not detected, so an odd layout can still come out in the wrong order; it is the same text
 * either way, only listed differently.
 */
object ReadingOrder {

	fun sort(blocks: List<OcrBlock>): List<OcrBlock> = sortBy(
		items = blocks,
		top = { it.bounds.top },
		bottom = { it.bounds.bottom },
		left = { it.bounds.left },
		right = { it.bounds.right },
		// Whichever way most of the text runs decides for the whole photo: a manga page with one
		// horizontal caption is still read right to left.
		isRightToLeft = blocks.count { it.isVertical } * 2 > blocks.size,
	)

	// Plain ints rather than a Rect so this can be unit tested without an Android runtime.
	internal fun <T> sortBy(
		items: List<T>,
		top: (T) -> Int,
		bottom: (T) -> Int,
		left: (T) -> Int,
		right: (T) -> Int,
		isRightToLeft: Boolean,
	): List<T> {
		if (items.size < 2) {
			return items
		}
		val rows = ArrayList<Row<T>>()
		for (item in items.sortedBy(top)) {
			val row = rows.lastOrNull()
			val height = bottom(item) - top(item)
			if (row != null && row.overlapOf(top(item), bottom(item)) * 2 >= height) {
				row.add(item, top(item), bottom(item))
			} else {
				rows += Row<T>().also { it.add(item, top(item), bottom(item)) }
			}
		}
		return rows.flatMap { row ->
			if (isRightToLeft) row.items.sortedByDescending(right) else row.items.sortedBy(left)
		}
	}

	private class Row<T> {

		val items = ArrayList<T>()
		private var top = Int.MAX_VALUE
		private var bottom = Int.MIN_VALUE

		fun add(item: T, itemTop: Int, itemBottom: Int) {
			items += item
			top = minOf(top, itemTop)
			bottom = maxOf(bottom, itemBottom)
		}

		fun overlapOf(itemTop: Int, itemBottom: Int): Int =
			(minOf(bottom, itemBottom) - maxOf(top, itemTop)).coerceAtLeast(0)
	}
}
