package io.github.nathankewley.jocr.ocr

import kotlin.math.abs
import kotlin.math.min

/**
 * Black and white pixels of a crop of the page: true where there is ink.
 */
class InkMap(val width: Int, val height: Int, private val ink: BooleanArray) {

	init {
		require(ink.size == width * height)
	}

	operator fun get(x: Int, y: Int): Boolean = ink[y * width + x]
}

/**
 * Finds the columns of a vertically set bubble and the characters in each, so they can be laid out
 * again as horizontal lines the recogniser can actually read.
 *
 * Works from projection profiles: a column is a band of x positions with ink in it, a character a
 * band of rows with ink within its column. [charSize] - the size of one character, in pixels - is
 * what tells a gap between columns from the gap inside a glyph like 川, and a furigana column from
 * a column of text.
 */
object VerticalTextLayout {

	/** Gaps narrower than this, in characters, are inside a glyph, not between columns. */
	private const val COLUMN_GAP = 0.2f

	/** Ink a position needs, in characters' height, to count as part of a column of text. */
	private const val MIN_COLUMN_INK = 0.15f

	/** Columns narrower than this, in characters, are furigana or noise. */
	private const val MIN_COLUMN_WIDTH = 0.55f

	/**
	 * A gap is only bridged while the column stays narrower than this, in characters. Furigana sits
	 * as close to its column as the strokes of a glyph sit to each other; what tells them apart is
	 * that a glyph is one character wide and a glyph plus its furigana is half as wide again.
	 */
	private const val MAX_COLUMN_WIDTH = 1.3f

	/**
	 * Blobs of ink bigger than this, in characters, are not part of a glyph. Generous, so that two
	 * characters run together by a smudged scan still count as text.
	 */
	private const val MAX_BLOB_WIDTH = 1.8f
	private const val MAX_BLOB_HEIGHT = 3f

	/** Where a cut between characters may fall, in pitches from the top of the character. */
	private const val MIN_CELL_PITCH = 0.6f
	private const val MAX_CELL_PITCH = 1.3f

	/** A stroke longer than this, in characters, and thinner than [MAX_LINE_THICKNESS] is a line of the artwork. */
	private const val MIN_LINE_LENGTH = 1.5f
	private const val MAX_LINE_THICKNESS = 0.35f

	/**
	 * The ink that could be text: everything else - the bubble's outline, lines of the artwork
	 * around it, and anything cut off by the edge of the crop - removed.
	 *
	 * Works on connected blobs of ink. A glyph is made of blobs no bigger than itself, while an
	 * outline or a stroke of the artwork is one long blob, so size alone tells them apart. Blobs
	 * touching the edge are dropped too: the crop is taken generously around the text, so whatever
	 * reaches its edge is the scenery beyond the bubble.
	 */
	fun textOnly(ink: InkMap, charSize: Int): InkMap {
		val w = ink.width
		val h = ink.height
		val maxWidth = charSize * MAX_BLOB_WIDTH
		val maxHeight = charSize * MAX_BLOB_HEIGHT
		val keep = BooleanArray(w * h)
		val seen = BooleanArray(w * h)
		val stack = IntArray(w * h)
		val blob = IntArray(w * h)
		for (start in 0 until w * h) {
			if (seen[start] || !ink[start % w, start / w]) continue
			var top = 0
			var size = 0
			var minX = w
			var maxX = -1
			var minY = h
			var maxY = -1
			stack[top++] = start
			seen[start] = true
			while (top > 0) {
				val p = stack[--top]
				blob[size++] = p
				val px = p % w
				val py = p / w
				minX = minOf(minX, px)
				maxX = maxOf(maxX, px)
				minY = minOf(minY, py)
				maxY = maxOf(maxY, py)
				for (dy in -1..1) for (dx in -1..1) {
					val nx = px + dx
					val ny = py + dy
					if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
					val n = ny * w + nx
					if (!seen[n] && ink[nx, ny]) {
						seen[n] = true
						stack[top++] = n
					}
				}
			}
			val touchesEdge = minX == 0 || minY == 0 || maxX == w - 1 || maxY == h - 1
			val bw = maxX - minX + 1
			val bh = maxY - minY + 1
			val isTextSized = bw <= maxWidth && bh <= maxHeight
			// A panel border or the stroke of a bubble's tail: as thin as a ー but far longer.
			val isLine = (bh > charSize * MIN_LINE_LENGTH && bw < charSize * MAX_LINE_THICKNESS) ||
				(bw > charSize * MIN_LINE_LENGTH && bh < charSize * MAX_LINE_THICKNESS)
			if (isTextSized && !isLine && !touchesEdge) {
				for (i in 0 until size) keep[blob[i]] = true
			}
		}
		return InkMap(w, h, keep)
	}

	/** Columns of the bubble, right to left - the order they are read in. */
	fun columns(ink: InkMap, charSize: Int): List<IntRange> {
		val inkPerColumn = inkPerColumn(ink)
		val cores = textColumns(inkPerColumn, charSize)
		// The threshold trims the edges of glyphs made of horizontal strokes - 二, 三, に - so each
		// column is widened back to a full character around its centre, but never past halfway to
		// the next column.
		return cores.mapIndexed { i, core ->
			val center = (core.first + core.last) / 2
			val floor = cores.getOrNull(i - 1)?.let { (it.last + core.first) / 2 + 1 } ?: 0
			val ceiling = cores.getOrNull(i + 1)?.let { (core.last + it.first) / 2 } ?: (ink.width - 1)
			minOf(core.first, maxOf(center - charSize * 11 / 20, floor))..maxOf(core.last, minOf(center + charSize * 11 / 20, ceiling))
		}.reversed()
	}

	private fun inkPerColumn(ink: InkMap) = IntArray(ink.width) { x ->
		var count = 0
		for (y in 0 until ink.height) {
			if (ink[x, y]) count++
		}
		count
	}

	/** The positions with real ink in them, left to right, before widening. */
	private fun textColumns(inkPerColumn: IntArray, charSize: Int): List<IntRange> {
		// A position counts as part of a column only with real ink in it. A dashed rule or a strip
		// of screentone crossing the bubble puts a few pixels in every position, which would
		// otherwise bridge the gaps between the columns and run them all together.
		val minInk = maxOf(1, (charSize * MIN_COLUMN_INK).toInt())
		val hasInk = BooleanArray(inkPerColumn.size) { inkPerColumn[it] >= minInk }
		return bands(hasInk, maxGap = (charSize * COLUMN_GAP).toInt(), maxWidth = (charSize * MAX_COLUMN_WIDTH).toInt())
			.filter { it.last - it.first + 1 >= charSize * MIN_COLUMN_WIDTH }
	}

	/**
	 * The characters of one column, top to bottom, each as tight around its ink as it goes.
	 *
	 * Printed vertical text sits on a fixed pitch of about one character, so the column is cut every
	 * pitch, at the blank row nearest to where the cut should fall - or, when the characters touch,
	 * at the row with the least ink. Following the pitch rather than the gaps is what keeps the two
	 * strokes of 二 together and a small っ apart from the character after it.
	 */
	fun cells(ink: InkMap, column: IntRange, charSize: Int): List<Box> {
		val inkPerRow = IntArray(ink.height) { y -> column.count { x -> ink[x, y] } }
		val firstRow = inkPerRow.indexOfFirst { it > 0 }
		if (firstRow == -1) {
			return emptyList()
		}
		val lastRow = inkPerRow.indexOfLast { it > 0 }
		val pitch = (column.last - column.first + 1).coerceIn(charSize * 4 / 5, charSize * 6 / 5)
		val rows = ArrayList<IntRange>()
		var top = firstRow
		while (top <= lastRow) {
			val earliest = top + (pitch * MIN_CELL_PITCH).toInt()
			if (earliest > lastRow) {
				rows += top..lastRow
				break
			}
			val target = top + pitch
			val window = earliest..min(top + (pitch * MAX_CELL_PITCH).toInt(), lastRow + 1)
			val cut = window.filter { it > lastRow || inkPerRow[it] == 0 }.minByOrNull { abs(it - target) }
				?: window.minWith(compareBy<Int> { inkPerRow[it] }.thenBy { abs(it - target) })
			rows += top until cut
			top = (cut..lastRow).firstOrNull { inkPerRow[it] > 0 } ?: break
		}
		// Tight around the ink: a long-vowel mark is a thin vertical stroke, and only its own width -
		// not the column's - shows that it has to be turned to read as ー.
		return rows.mapNotNull { r ->
			val inkRows = r.filter { inkPerRow[it] > 0 }
			val xs = column.filter { x -> inkRows.any { y -> ink[x, y] } }
			if (xs.isEmpty()) null else Box(xs.first(), inkRows.first(), xs.last() + 1, inkRows.last() + 1)
		}
	}

	/** Runs of true, with gaps of up to [maxGap] falses bridged as long as a run stays within [maxWidth]. */
	private fun bands(values: BooleanArray, maxGap: Int, maxWidth: Int = Int.MAX_VALUE): List<IntRange> {
		val runs = ArrayList<IntRange>()
		var start = -1
		for (i in values.indices) {
			if (values[i] && start == -1) {
				start = i
			} else if (!values[i] && start != -1) {
				runs += start until i
				start = -1
			}
		}
		if (start != -1) runs += start until values.size
		val result = ArrayList<IntRange>(runs.size)
		for (run in runs) {
			val last = result.lastOrNull()
			if (last != null && run.first - last.last - 1 <= maxGap && run.last - last.first + 1 <= maxWidth) {
				result[result.lastIndex] = last.first..run.last
			} else {
				result += run
			}
		}
		return result
	}
}
