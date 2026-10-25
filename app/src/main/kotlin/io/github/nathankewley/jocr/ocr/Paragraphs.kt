package io.github.nathankewley.jocr.ocr

import android.graphics.Rect
import kotlin.math.abs

/**
 * Joins lines of horizontal text back into the paragraphs they were set in.
 *
 * ML Kit reports every line of a paragraph on its own, and [TextRegions] only joins fragments that
 * nearly touch, which lines with ordinary line spacing do not. Left as separate blocks, a word that
 * wraps is cut in two - 流れ込/み - and the half on the next line is looked up as a word of its own.
 * So a line is added to the paragraph above it when the two are set the same way: text of about
 * the same size, starting at about the same left edge, with no more than a line's gap between them.
 * A headline, a caption beside the text or a new paragraph after a blank line stays separate.
 *
 * Vertical text is left alone: its columns are already read as one block.
 */
object Paragraphs {

	/** At most this much blank space between two lines of one paragraph, in characters. */
	private const val MAX_LINE_GAP = 1f

	/** Lines of one paragraph start within this many characters of each other; covers an indent. */
	private const val MAX_LEFT_OFFSET = 1.5f

	/** Text sizes of one paragraph differ by at most this ratio. */
	private const val MAX_SIZE_RATIO = 1.3f

	fun join(blocks: List<OcrBlock>): List<OcrBlock> {
		val (horizontal, vertical) = blocks.partition { !it.isVertical }
		val joined = joinBy(horizontal, box = { it.bounds.toBox() }, charSize = { it.charSize }).map { lines ->
			if (lines.size == 1) {
				lines.single()
			} else {
				// No spaces between the lines: Japanese has none, and a wrapped word is one word again.
				lines.first().copy(
					text = lines.joinToString("") { it.text },
					bounds = Rect(lines.first().bounds).apply { lines.forEach { union(it.bounds) } },
				)
			}
		}
		return joined + vertical
	}

	/** Groups [items] into paragraphs, each top to bottom. Plain [Box]es so it can be unit tested. */
	internal fun <T> joinBy(items: List<T>, box: (T) -> Box, charSize: (T) -> Int): List<List<T>> {
		val paragraphs = ArrayList<MutableList<T>>()
		for (item in items.sortedBy { box(it).top }) {
			val b = box(item)
			val size = charSize(item).coerceAtLeast(1)
			// Of the paragraphs this line could continue, the one whose last line is nearest above it.
			val target = paragraphs.filter { paragraph ->
				val last = paragraph.last()
				val lastBox = box(last)
				val lastSize = charSize(last).coerceAtLeast(1)
				val left = paragraph.minOf { box(it).left }
				val gap = b.top - lastBox.bottom
				val sizeRatio = maxOf(size, lastSize).toFloat() / minOf(size, lastSize)
				// A little overlap is fine: line boxes are drawn generously around the glyphs.
				gap >= -size / 2 && gap <= size * MAX_LINE_GAP &&
					sizeRatio <= MAX_SIZE_RATIO &&
					abs(b.left - left) <= size * MAX_LEFT_OFFSET
			}.minByOrNull { b.top - box(it.last()).bottom }
			if (target != null) {
				target += item
			} else {
				paragraphs += mutableListOf(item)
			}
		}
		return paragraphs
	}

	private fun Rect.toBox() = Box(left, top, right, bottom)
}
