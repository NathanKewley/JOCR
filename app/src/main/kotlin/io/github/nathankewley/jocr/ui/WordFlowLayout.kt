package io.github.nathankewley.jocr.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import kotlin.math.max

/**
 * Lays words out like text: left to right, wrapping onto a new line when the row is full, with each
 * row's words aligned on their bottom edge so the kanji line up whether or not a word has furigana.
 *
 * Material's ChipGroup does the wrapping but its FlowLayout is internal API, and chips are a single
 * line of text, which leaves nowhere to put the reading.
 */
class WordFlowLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

	private val spacing = (resources.displayMetrics.density * 2).toInt()

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
		var x = 0
		var rowHeight = 0
		var height = 0
		var widest = 0
		for (child in visibleChildren()) {
			measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
			if (x > 0 && x + child.measuredWidth > maxWidth) {
				height += rowHeight + spacing
				x = 0
				rowHeight = 0
			}
			x += child.measuredWidth + spacing
			widest = max(widest, x - spacing)
			rowHeight = max(rowHeight, child.measuredHeight)
		}
		height += rowHeight
		val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
			MeasureSpec.getSize(widthMeasureSpec)
		} else {
			widest + paddingLeft + paddingRight
		}
		setMeasuredDimension(width, resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec))
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		val maxWidth = r - l - paddingLeft - paddingRight
		val rows = ArrayList<MutableList<View>>()
		var x = 0
		for (child in visibleChildren()) {
			if (rows.isEmpty() || (x > 0 && x + child.measuredWidth > maxWidth)) {
				rows += ArrayList<View>()
				x = 0
			}
			rows.last() += child
			x += child.measuredWidth + spacing
		}
		var y = paddingTop
		for (row in rows) {
			val rowHeight = row.maxOf { it.measuredHeight }
			var left = paddingLeft
			for (child in row) {
				val top = y + rowHeight - child.measuredHeight
				child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
				left += child.measuredWidth + spacing
			}
			y += rowHeight + spacing
		}
	}

	private fun visibleChildren(): Sequence<View> = (0 until childCount).asSequence()
		.map { getChildAt(it) }
		.filterNot { it.isGone }
}
