package io.github.nathankewley.jocr.renshuu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenshuuModelsTest {

	private fun parse(json: String) = Json.parseToJsonElement(json) as JsonObject

	@Test
	fun wordIsParsedTolerantly() {
		// An id as a string, a flag as 0/1 and a lone definition string instead of an array.
		val word = parse(
			"""{"id":"239","kanji_full":"此方","hiragana_full":"こちら","is_common":1,
			"def":"this way",
			"presence":{"scheds":[{"sched_id":8781570,"name":"少年漫画 (Frieren)","hasWord":1}],
			"lists":[{"list_id":10705000,"name":"Death Note - All Words","hasWord":1},{"list_id":1,"name":"Other","hasWord":0}]}}""",
		).toWord()!!
		assertEquals(239L, word.id)
		assertTrue(word.isCommon)
		assertEquals(listOf("this way"), word.definitions)
		assertEquals(listOf("sched:8781570", "list:10705000"), word.inLists.map { it.key })
		assertEquals(listOf("少年漫画 (Frieren)", "Death Note - All Words"), word.inLists.map { it.title })
	}

	/** Shaped like a real `GET /schedule` response: vocabulary schedules mixed in with the rest. */
	@Test
	fun onlyVocabSchedulesAreOffered() {
		val schedules = parse(
			"""{"schedules":[
			{"id":8781570,"name":"少年漫画 (Frieren)","booktype":"vocab","is_frozen":0},
			{"id":6505962,"name":"(N1) Advanced Grammar","booktype":"grammar","is_frozen":0},
			{"id":7917619,"name":"Manga Kanji","booktype":"kanji","is_frozen":0},
			{"id":5983860,"name":"Default (Core 2.3k)","booktype":"vocab","is_frozen":0}],
			"api_usage":{"calls_today":10,"daily_allowance":2000}}""",
		).toVocabSchedules()
		assertEquals(listOf("少年漫画 (Frieren)", "Default (Core 2.3k)"), schedules.map { it.title })
		assertEquals(listOf("sched:8781570", "sched:5983860"), schedules.map { it.key })
	}

	@Test
	fun ownVocabListsAreOffered() {
		val lists = parse(
			"""{"termtype_groups":[{"termtype":"vocab","groups":[
			{"group_title":"(not grouped)","lists":[{"list_id":42,"title":"Studypad","num_terms":3},
			{"list_id":43,"title":"Manga words","num_terms":3}]},
			{"group_title":"JLPT","lists":[{"list_id":"7","title":"N5"}]}]},
			{"termtype":"kanji","groups":[{"group_title":"x","lists":[{"list_id":1,"title":"K"}]}]}]}""",
		).toVocabLists()
		// The default Studypad is left out.
		assertEquals(listOf("Manga words", "N5"), lists.map { it.title })
		assertEquals(listOf("list:43", "list:7"), lists.map { it.key })
	}

	@Test
	fun exactMatchRanksFirst() {
		fun w(id: Long, kanji: String, common: Boolean) =
			RenshuuWord(id, kanji, "", common, "", emptyList(), emptyList(), emptyList())
		val ranked = listOf(w(1, "見る目", true), w(2, "見る", false), w(3, "見る見る", true)).rankFor("見る")
		assertEquals(listOf(2L, 1L, 3L), ranked.map { it.id })
	}
}
