package io.github.nathankewley.jocr.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRegionsTest {

	private data class Frag(val text: String, val box: Box)

	private fun frag(text: String, left: Int, top: Int, right: Int, bottom: Int) = Frag(text, Box(left, top, right, bottom))

	private fun cluster(charSize: Int, vararg frags: Frag) =
		TextRegions.cluster(frags.toList(), charSize) { it.box }
			.map { group -> group.map { it.text }.sorted().joinToString("+") }
			.toSet()

	/** The column blocks ML Kit returned for a synthetic test page on a real phone. */
	@Test
	fun columnsOfOneBubbleAreJoined() {
		val result = cluster(
			40,
			frag("見たの", 187, 165, 223, 328),
			frag("何の映画を", 237, 162, 275, 372),
			frag("本当に", 289, 162, 326, 305),
			frag("食べている", 626, 912, 666, 1123),
			frag("行きました", 671, 129, 714, 412),
			frag("彼はご飯を", 680, 912, 719, 1125),
			frag("映画を見に", 729, 143, 765, 353),
			frag("昨日友達と", 778, 142, 819, 398),
		)
		assertEquals(
			setOf("何の映画を+本当に+見たの", "映画を見に+昨日友達と+行きました", "彼はご飯を+食べている"),
			result,
		)
	}

	/** A narrow bubble read across its columns, from a real manga page: rows a line apart. */
	@Test
	fun rowsOfMisreadBubbleAreJoined() {
		val result = cluster(
			44,
			frag("なすサ", 1177, 519, 1340, 564),
			frag("Sつっイ", 1188, 569, 1332, 611),
			frag("げト", 1240, 616, 1330, 661),
			frag("えウ", 1240, 666, 1333, 711),
			frag("開いた", 694, 744, 730, 859),
		)
		assertEquals(setOf("Sつっイ+えウ+げト+なすサ", "開いた"), result)
	}

	@Test
	fun distantFragmentsStaySeparate() {
		val result = cluster(40, frag("右", 800, 100, 840, 400), frag("左", 600, 100, 640, 400))
		assertEquals(setOf("右", "左"), result)
	}
}
