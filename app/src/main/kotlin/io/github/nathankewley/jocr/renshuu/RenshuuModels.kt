package io.github.nathankewley.jocr.renshuu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

data class RenshuuWord(
	val id: Long,
	val kanji: String,
	val kana: String,
	val isCommon: Boolean,
	val typeOfSpeech: String,
	val markers: List<String>,
	val definitions: List<String>,
	/** The user's schedules and lists that already hold this word; empty when the API did not say. */
	val inLists: List<RenshuuList>,
) {

	/** The form to show big: the kanji spelling when the word has one. */
	val headword: String
		get() = kanji.ifEmpty { kana }
}

data class RenshuuKanji(
	val kanji: String,
	val definition: String,
	val onyomi: String,
	val kunyomi: String,
	val jlpt: String,
	val strokeCount: Int,
	val radical: String,
	val radicalName: String,
	val parts: List<Pair<String, String>>,
)

/**
 * Somewhere a word can be added: one of the user's vocabulary schedules, or a list they made.
 *
 * Schedules are what renshuu's own app shows as the user's word lists - the ones a learner adds
 * words to, e.g. one per manga. The API's "lists" are only the lists the user created themselves,
 * which for most people is the single default Studypad, so both are offered.
 */
data class RenshuuList(
	val id: String,
	val title: String,
	val isSchedule: Boolean,
) {

	/** Stable across sessions, and unambiguous - a schedule and a list can share an id. */
	val key: String
		get() = (if (isSchedule) "sched:" else "list:") + id
}

enum class AddToListResult { ADDED, ALREADY_PRESENT }

/** The API key was refused. Distinct so the app can point at the setting instead of retrying. */
class RenshuuAuthException(message: String?) : RuntimeException(message)

class RenshuuApiException(message: String?, val code: Int) : RuntimeException(message)

/*
 * Field-by-field parsing rather than @Serializable classes: the OpenAPI document is the only
 * description of these responses, and a single field whose real type differs from it (an id sent as
 * a string, a flag as 0/1) would otherwise fail the whole lookup instead of losing one detail.
 */

internal fun JsonObject.toWord(): RenshuuWord? {
	val id = long("id") ?: return null
	return RenshuuWord(
		id = id,
		kanji = string("kanji_full"),
		kana = string("hiragana_full"),
		isCommon = bool("is_common"),
		typeOfSpeech = string("typeofspeech"),
		markers = stringList("markers"),
		definitions = stringList("def"),
		inLists = listOf(true, false).flatMap { isSchedule ->
			(obj("presence")?.get(if (isSchedule) "scheds" else "lists") as? JsonArray).orEmpty()
				.mapNotNull { it as? JsonObject }
				.filter { it.bool("hasWord") }
				.mapNotNull { entry ->
					val id = entry.primitive(if (isSchedule) "sched_id" else "list_id")?.content ?: return@mapNotNull null
					RenshuuList(id = id, title = entry.string("name"), isSchedule = isSchedule)
				}
		},
	)
}

internal fun JsonObject.toKanji(): RenshuuKanji = RenshuuKanji(
	kanji = string("kanji"),
	definition = string("definition"),
	onyomi = string("onyomi"),
	kunyomi = string("kunyomi"),
	jlpt = string("jlpt"),
	strokeCount = long("scount")?.toInt() ?: 0,
	radical = string("radical"),
	radicalName = string("radical_name"),
	parts = (get("parts") as? JsonArray).orEmpty()
		.mapNotNull { it as? JsonObject }
		.map { it.string("piece") to it.string("definition") }
		.filter { it.first.isNotEmpty() },
)

/** The vocabulary schedules from `GET /schedule`, in renshuu's own order. */
internal fun JsonObject.toVocabSchedules(): List<RenshuuList> =
	(get("schedules") as? JsonArray).orEmpty()
		.mapNotNull { it as? JsonObject }
		.filter { it.string("booktype") == TERMTYPE_VOCAB }
		.mapNotNull { schedule ->
			val id = schedule.primitive("id")?.content ?: return@mapNotNull null
			RenshuuList(id = id, title = schedule.string("name"), isSchedule = true)
		}

/**
 * The vocabulary lists the user made, from `GET /lists` - less the Studypad, the scratch list
 * renshuu gives every account, which is not somewhere anyone files the words from a manga.
 */
internal fun JsonObject.toVocabLists(): List<RenshuuList> =
	(get("termtype_groups") as? JsonArray).orEmpty()
		.mapNotNull { it as? JsonObject }
		.filter { it.string("termtype") == TERMTYPE_VOCAB }
		.flatMap { termtype ->
			(termtype["groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
		}
		.flatMap { group ->
			(group["lists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { list ->
				val id = list.primitive("list_id")?.content ?: return@mapNotNull null
				val title = list.string("title")
				if (title.equals(STUDYPAD, ignoreCase = true)) return@mapNotNull null
				RenshuuList(id = id, title = title, isSchedule = false)
			}
		}

private const val TERMTYPE_VOCAB = "vocab"
private const val STUDYPAD = "Studypad"

internal fun JsonObject.primitive(key: String): JsonPrimitive? = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

internal fun JsonObject.string(key: String): String = primitive(key)?.content.orEmpty()

internal fun JsonObject.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.content.toLongOrNull() }

internal fun JsonObject.bool(key: String): Boolean {
	val p = primitive(key) ?: return false
	return p.booleanOrNull ?: (p.longOrNull?.let { it != 0L } ?: (p.content == "yes"))
}

/** An array of strings, or a lone string where one was expected. */
internal fun JsonObject.stringList(key: String): List<String> = when (val e: JsonElement? = get(key)) {
	is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
	is JsonPrimitive -> if (e is JsonNull || e.content.isEmpty()) emptyList() else listOf(e.content)
	else -> emptyList()
}.filter { it.isNotBlank() }
