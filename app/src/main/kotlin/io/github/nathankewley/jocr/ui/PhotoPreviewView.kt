package io.github.nathankewley.jocr.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * The photo with a box around every block of text that was read, the one being read in the list
 * highlighted.
 *
 * [focusBlock] zooms in on one block - the one being read in the list - and the photo can also be
 * pinched and dragged. A tap on a box reports that block; a tap anywhere else zooms back out.
 */
class PhotoPreviewView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : View(context, attrs) {

	var onBlockClick: ((blockId: Int) -> Unit)? = null

	private var preview: PhotoPreview? = null
	private var blocks: List<OcrBlockModel> = emptyList()
	private var focusedBlockId = -1

	// The view as a window onto the photo: photo pixel (x, y) is drawn at (x * scale + tx, y * scale + ty).
	private var scale = 1f
	private var tx = 0f
	private var ty = 0f
	private var animator: ValueAnimator? = null

	private val density = resources.displayMetrics.density
	private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val colorPrimary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
	private val colorTertiary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)

	private val matrix = Matrix()
	private val photoRect = RectF()

	/** The blocks' boxes as last drawn, in view pixels, in the order of [blocks]. Reused between draws. */
	private val boxes = ArrayList<RectF>()

	private val gestures = GestureDetector(
		context,
		object : GestureDetector.SimpleOnGestureListener() {

			override fun onDown(e: MotionEvent): Boolean = true

			override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
				animator?.cancel()
				moveTo(scale, tx - dx, ty - dy)
				return true
			}

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				val hit = blockAt(e.x, e.y)
				if (hit != null) {
					performClick()
					onBlockClick?.invoke(hit.id)
				} else {
					focusBlock(-1)
				}
				return true
			}
		},
	)

	private val scaleGestures = ScaleGestureDetector(
		context,
		object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

			override fun onScale(detector: ScaleGestureDetector): Boolean {
				animator?.cancel()
				val fit = fitScale()
				val newScale = (scale * detector.scaleFactor).coerceIn(fit, fit * MAX_PINCH_ZOOM)
				// Zoom about the point between the fingers, so what is under them stays there.
				val k = newScale / scale
				moveTo(newScale, detector.focusX - (detector.focusX - tx) * k, detector.focusY - (detector.focusY - ty) * k)
				return true
			}
		},
	)

	fun bind(preview: PhotoPreview?, blocks: List<OcrBlockModel>) {
		val isNewPhoto = preview?.bitmap !== this.preview?.bitmap
		this.preview = preview
		this.blocks = blocks
		if (isNewPhoto) {
			animator?.cancel()
			focusedBlockId = -1
			applyTarget(animate = false)
		}
		if (blocks.none { it.id == focusedBlockId }) {
			focusedBlockId = -1
		}
		invalidate()
	}

	/** Zooms in on block [blockId], or out to the whole photo for -1. */
	fun focusBlock(blockId: Int) {
		if (preview == null) return
		focusedBlockId = if (blocks.any { it.id == blockId }) blockId else -1
		applyTarget(animate = true)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		animator?.cancel()
		applyTarget(animate = false)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val preview = preview ?: return
		matrix.setScale(scale, scale)
		matrix.postTranslate(tx, ty)
		canvas.save()
		canvas.concat(matrix)
		// The preview bitmap is a smaller copy; drawn over the photo's full size, it lines up with the blocks.
		photoRect.set(0f, 0f, preview.sourceWidth.toFloat(), preview.sourceHeight.toFloat())
		canvas.drawBitmap(preview.bitmap, null, photoRect, bitmapPaint)
		canvas.restore()
		layOutBoxes()
		// The focused box last, so its colour is the one on a line it shares with a neighbour.
		var focused = -1
		for (i in blocks.indices) {
			if (blocks[i].id == focusedBlockId) {
				focused = i
			} else {
				drawBox(canvas, boxes[i], isFocused = false)
			}
		}
		if (focused >= 0) {
			drawBox(canvas, boxes[focused], isFocused = true)
		}
	}

	private fun drawBox(canvas: Canvas, box: RectF, isFocused: Boolean) {
		boxPaint.color = if (isFocused) colorTertiary else colorPrimary
		boxPaint.alpha = if (isFocused) 255 else UNFOCUSED_ALPHA
		boxPaint.strokeWidth = (if (isFocused) 3f else 1.5f) * density
		canvas.drawRoundRect(box, 4 * density, 4 * density, boxPaint)
	}

	/**
	 * Works out [boxes]: each block's bounds on screen, a little clear of its text, and kept from
	 * overlapping the boxes of the blocks around it.
	 */
	private fun layOutBoxes() {
		// Padding in screen pixels, so the gap around the text is the same at any zoom.
		val padding = BOX_PADDING_DP * density
		while (boxes.size < blocks.size) {
			boxes += RectF()
		}
		blocks.forEachIndexed { i, block ->
			boxes[i].set(block.bounds)
			matrix.mapRect(boxes[i])
			boxes[i].inset(-padding, -padding)
		}
		for (i in blocks.indices) {
			for (j in i + 1 until blocks.size) {
				separate(boxes[i], boxes[j])
			}
		}
	}

	/**
	 * Two boxes that overlap - lines set close together, a caption beside a picture - are cut back
	 * to the midpoint between them, so they share one line instead of crossing. Overlapping the
	 * short way means one is above the other, so they are cut apart vertically; the long way, side
	 * by side. A block wholly inside another overlaps both ways and is left as it is.
	 */
	private fun separate(a: RectF, b: RectF) {
		if (!RectF.intersects(a, b)) return
		val overlapX = min(a.right, b.right) - max(a.left, b.left)
		val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
		if (overlapY <= overlapX && overlapY < min(a.height(), b.height()) / 2) {
			val upper = if (a.centerY() <= b.centerY()) a else b
			val lower = if (upper === a) b else a
			val middle = (upper.bottom + lower.top) / 2
			upper.bottom = middle
			lower.top = middle
		} else if (overlapX < overlapY && overlapX < min(a.width(), b.width()) / 2) {
			val left = if (a.centerX() <= b.centerX()) a else b
			val right = if (left === a) b else a
			val middle = (left.right + right.left) / 2
			left.right = middle
			right.left = middle
		}
	}

	@SuppressLint("ClickableViewAccessibility") // performClick is called on a tap; the blocks are also a list
	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (preview == null) {
			return super.onTouchEvent(event)
		}
		scaleGestures.onTouchEvent(event)
		gestures.onTouchEvent(event)
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}

	/**
	 * The block whose box, as last drawn, is under the finger. The smallest when there are several:
	 * a block inside another's box is the one meant.
	 */
	private fun blockAt(x: Float, y: Float): OcrBlockModel? = blocks.indices
		.filter { it < boxes.size && boxes[it].contains(x, y) }
		.minByOrNull { boxes[it].width() * boxes[it].height() }
		?.let { blocks[it] }

	/** Moves to the focused block, or the whole photo. */
	private fun applyTarget(animate: Boolean) {
		val preview = preview ?: return
		if (width == 0 || height == 0) return
		val block = blocks.find { it.id == focusedBlockId }
		val fit = fitScale()
		val (toScale, cx, cy) = if (block == null) {
			Triple(fit, preview.sourceWidth / 2f, preview.sourceHeight / 2f)
		} else {
			// Some of the photo around the block stays in view, so it is clear where in the photo it is.
			val b = block.bounds
			val s = min(width / (b.width() * FOCUS_MARGIN), height / (b.height() * FOCUS_MARGIN))
			Triple(s.coerceIn(fit, fit * MAX_FOCUS_ZOOM), b.exactCenterX(), b.exactCenterY())
		}
		val toX = width / 2f - cx * toScale
		val toY = height / 2f - cy * toScale
		animator?.cancel()
		if (!animate) {
			moveTo(toScale, toX, toY)
			return
		}
		val fromScale = scale
		val fromX = tx
		val fromY = ty
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = ANIMATION_MS
			interpolator = FastOutSlowInInterpolator()
			addUpdateListener {
				val t = it.animatedValue as Float
				moveTo(fromScale + (toScale - fromScale) * t, fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)
			}
			start()
		}
	}

	/**
	 * Sets the view onto the photo, kept so the photo never leaves a gap it could fill: centred along
	 * a side where it is smaller than the view, edge to edge where it is bigger.
	 */
	private fun moveTo(newScale: Float, newX: Float, newY: Float) {
		val preview = preview ?: return
		scale = newScale
		tx = clamp(newX, preview.sourceWidth * newScale, width.toFloat())
		ty = clamp(newY, preview.sourceHeight * newScale, height.toFloat())
		invalidate()
	}

	private fun clamp(offset: Float, content: Float, view: Float): Float =
		if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0f)

	private fun fitScale(): Float {
		val preview = preview ?: return 1f
		return min(width.toFloat() / preview.sourceWidth, height.toFloat() / preview.sourceHeight)
			.let { max(it, Float.MIN_VALUE) }
	}

	private companion object {

		/** The block takes up this fraction less than the whole view, in each direction. */
		const val FOCUS_MARGIN = 1.4f

		/** How far a focused block is enlarged at most, relative to the whole photo fitting the view. */
		const val MAX_FOCUS_ZOOM = 6f
		const val MAX_PINCH_ZOOM = 10f
		const val ANIMATION_MS = 300L

		/** Boxes other than the focused one are faint: they show where text was found, no more. */
		const val UNFOCUSED_ALPHA = 140

		/** Space between a block's text and the box drawn around it. */
		const val BOX_PADDING_DP = 2f
	}
}
