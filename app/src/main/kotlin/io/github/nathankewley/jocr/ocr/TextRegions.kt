package io.github.nathankewley.jocr.ocr

/** A plain rectangle, so the layout code can be unit tested without android.graphics. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {

	val width: Int get() = right - left
	val height: Int get() = bottom - top

	fun union(other: Box) = Box(
		minOf(left, other.left),
		minOf(top, other.top),
		maxOf(right, other.right),
		maxOf(bottom, other.bottom),
	)

	fun inflate(by: Int) = Box(left - by, top - by, right + by, bottom + by)

	fun intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Groups the fragments the recogniser found into speech bubbles.
 *
 * ML Kit has no notion of vertical Japanese. It reports each column of a bubble as a separate block,
 * or worse reads a narrow bubble across its columns, so one bubble arrives as several unrelated
 * fragments. Fragments of one bubble lie within about half a character of each other while separate
 * bubbles are much further apart, so fragments closer than that are joined, transitively, into one
 * region.
 */
object TextRegions {

	/** Two fragments closer than this many character sizes belong to the same bubble. */
	private const val JOIN_DISTANCE = 0.5f

	fun <T> cluster(items: List<T>, charSize: Int, box: (T) -> Box): List<List<T>> {
		val reach = maxOf(1, (charSize * JOIN_DISTANCE / 2).toInt())
		val boxes = items.map { box(it).inflate(reach) }
		// Union-find over the "touches" relation.
		val parent = IntArray(items.size) { it }
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
				if (boxes[i].intersects(boxes[j])) {
					parent[find(i)] = find(j)
				}
			}
		}
		return items.indices.groupBy(::find).values.map { group -> group.map { items[it] } }
	}
}
