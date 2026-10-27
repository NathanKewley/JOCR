package io.github.nathankewley.jocr.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns PaddleOCR's text detector output into boxes around lines of text: the DB post-processing.
 *
 * The detector marks a shrunk core of every line - so lines set close together stay apart - as a
 * map of how likely each pixel is to be text. Each connected patch of it is a line, kept when the
 * detector is sure enough of it on average, and grown back out by the margin it was shrunk by.
 * Boxes are upright rectangles: text photographed at a slight angle gets a box around its whole
 * slant, which still reads well enough.
 */
object TextDetection {

	/** A pixel more likely than this to be text is part of a line. */
	private const val THRESHOLD = 0.3f

	/** A line the detector is less sure of than this, on average, is dropped. */
	private const val MIN_SCORE = 0.6f

	/** How far the detector's cores are grown back out: PaddleOCR's own setting. */
	private const val UNCLIP_RATIO = 1.5f

	/** Patches thinner than this, in map pixels, are specks. */
	private const val MIN_SIDE = 3

	/** A box at least this many times taller than wide is a column of vertical text. */
	private const val VERTICAL_RATIO = 1.5f

	/** Pieces of one column are centred within this many column widths of each other... */
	private const val MAX_CENTER_OFFSET = 0.35f

	/** ...are at most this many times as wide as each other... */
	private const val MAX_WIDTH_RATIO = 2f

	/** ...and at most this many column widths apart. */
	private const val MAX_COLUMN_GAP = 1f

	/** A column of kana less than half as wide as the others beside it is furigana. */
	private const val FURIGANA_WIDTH = 0.5f

	/** A detector box's margin around its text, as a part of its height. */
	private const val MARGIN = 0.3f

	/** The lines in [prob], a [width] x [height] map of text probabilities, in map pixels. */
	fun boxes(prob: FloatArray, width: Int, height: Int): List<Box> {
		val seen = BooleanArray(prob.size)
		// Every pixel is pushed at most once, so the stack never outgrows the map.
		val stack = IntArray(prob.size)
		val out = ArrayList<Box>()
		for (start in prob.indices) {
			if (seen[start] || prob[start] <= THRESHOLD) {
				continue
			}
			var left = width
			var top = height
			var right = -1
			var bottom = -1
			var sum = 0.0
			var count = 0
			var size = 0
			stack[size++] = start
			seen[start] = true
			while (size > 0) {
				val i = stack[--size]
				val x = i % width
				val y = i / width
				left = min(left, x)
				top = min(top, y)
				right = max(right, x)
				bottom = max(bottom, y)
				sum += prob[i]
				count++
				for (ny in max(y - 1, 0)..min(y + 1, height - 1)) {
					for (nx in max(x - 1, 0)..min(x + 1, width - 1)) {
						val j = ny * width + nx
						if (!seen[j] && prob[j] > THRESHOLD) {
							seen[j] = true
							stack[size++] = j
						}
					}
				}
			}
			val w = right - left + 1
			val h = bottom - top + 1
			if (min(w, h) < MIN_SIDE || sum / count < MIN_SCORE) {
				continue
			}
			val grow = (w.toFloat() * h * UNCLIP_RATIO / (2 * (w + h))).roundToInt()
			out += Box(
				max(left - grow, 0),
				max(top - grow, 0),
				min(right + 1 + grow, width),
				min(bottom + 1 + grow, height),
			)
		}
		return out
	}

	/**
	 * Joins the pieces of each column of vertical text into one box.
	 *
	 * The detector often breaks a column at punctuation or a wider gap between characters, and a
	 * piece read on its own is read worse - and cut up the column for the word splitter. So boxes
	 * one above the other, about as wide and centred on each other, are read as one.
	 */
	fun joinColumns(boxes: List<Box>): List<Box> {
		val parent = IntArray(boxes.size) { it }
		fun find(i: Int): Int {
			var x = i
			while (parent[x] != x) {
				parent[x] = parent[parent[x]]
				x = parent[x]
			}
			return x
		}
		for (i in boxes.indices) {
			for (j in i + 1 until boxes.size) {
				if (isSameColumn(boxes[i], boxes[j])) {
					parent[find(i)] = find(j)
				}
			}
		}
		return boxes.indices.groupBy(::find).values.map { group -> group.map { boxes[it] }.reduce(Box::union) }
	}

	private fun isSameColumn(a: Box, b: Box): Boolean {
		if (!a.isVertical() && !b.isVertical()) {
			return false
		}
		val wide = max(a.width, b.width)
		val narrow = min(a.width, b.width).coerceAtLeast(1)
		val gap = max(a.top, b.top) - min(a.bottom, b.bottom)
		return wide <= narrow * MAX_WIDTH_RATIO &&
			abs((a.left + a.right) - (b.left + b.right)) / 2f <= wide * MAX_CENTER_OFFSET &&
			gap <= wide * MAX_COLUMN_GAP
	}

	/**
	 * [columns] of one block of vertical text without its furigana: the narrow columns of kana
	 * beside the kanji they spell out. The recogniser reads them as more text - さいふ財布 - and they
	 * would be looked up as words of their own.
	 */
	fun <T> withoutFurigana(columns: List<T>, box: (T) -> Box, text: (T) -> String): List<T> {
		val widths = columns.map { box(it).width }.sorted()
		val width = widths[widths.size / 2]
		return columns.filterNot { column ->
			box(column).width < width * FURIGANA_WIDTH && text(column).all { it in '぀'..'ヿ' }
		}
	}

	/**
	 * The size of one character of [text], read as one horizontal line from [box].
	 *
	 * Not the box's height: the detector's boxes carry a margin of about a third of their height
	 * all round, which narrows the gaps between lines and evens out sizes - a headline and the
	 * smaller date under it come out nearly the same height, and [Paragraphs] would run them
	 * together. The line's width, less that margin, shared out over the characters read is much
	 * closer to the truth. Half-width letters and digits count as half a character.
	 */
	fun charSizeOf(box: Box, text: String): Int {
		val chars = text.sumOf { if (it.isFullWidth()) 1.0 else 0.5 }
		val size = ((box.width - box.height * 2 * MARGIN) / chars).roundToInt()
		return if (chars > 0 && size > 0) min(size, box.height) else box.height
	}

	private fun Char.isFullWidth(): Boolean = this >= '⺀' && this !in '｡'..'ￜ'

	fun Box.isVertical(): Boolean = height >= width * VERTICAL_RATIO
}
