package io.github.nathankewley.jocr.renshuu

import androidx.collection.LruCache
import io.github.nathankewley.jocr.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.HttpURLConnection

/**
 * The parts of the renshuu.org API the app uses: dictionary lookups and the user's vocabulary
 * lists.
 *
 * Every call counts against a daily allowance (2000 at the time of writing), so lookups happen only
 * when a word is tapped, never for a whole page up front, and answers are kept for the session -
 * the same 私 and だ turn up in every photo.
 */
class RenshuuRepository(
	private val httpClient: OkHttpClient,
	private val settings: Settings,
) {

	private val wordCache = LruCache<String, List<RenshuuWord>>(WORD_CACHE_SIZE)
	private val kanjiCache = LruCache<String, RenshuuKanji>(KANJI_CACHE_SIZE)
	private val presenceCache = LruCache<Long, List<RenshuuList>>(WORD_CACHE_SIZE)

	@Volatile
	private var listsCache: List<RenshuuList>? = null

	val isConfigured: Boolean
		get() = settings.renshuuApiKey != null

	suspend fun searchWords(query: String): List<RenshuuWord> {
		wordCache[query]?.let { return it }
		val url = url("word", "search").addQueryParameter("value", query).build()
		val words = (get(url)["words"] as? JsonArray).orEmpty()
			.mapNotNull { (it as? JsonObject)?.toWord() }
			.rankFor(query)
		wordCache.put(query, words)
		return words
	}

	/**
	 * Names of the user's schedules, and library lists, holding the word. Search results leave this
	 * out, so it costs a lookup of its own - a second API call for every word tapped. Lists the user
	 * made themselves, such as Studypad, are not reported by renshuu at all.
	 */
	suspend fun getPresence(wordId: Long): List<RenshuuList> {
		presenceCache[wordId]?.let { return it }
		val words = (get(url("word", wordId.toString()).build())["words"] as? JsonArray).orEmpty()
		val result = words.firstNotNullOfOrNull { (it as? JsonObject)?.toWord() }?.inLists.orEmpty()
		presenceCache.put(wordId, result)
		return result
	}

	suspend fun getKanji(kanji: String): RenshuuKanji {
		kanjiCache[kanji]?.let { return it }
		val result = get(url("kanji", kanji).build()).toKanji()
		kanjiCache.put(kanji, result)
		return result
	}

	/** Everywhere a word can be added: vocabulary schedules first, then the user's own lists. */
	suspend fun getVocabLists(forceRefresh: Boolean = false): List<RenshuuList> {
		if (!forceRefresh) {
			listsCache?.let { return it }
		}
		val schedules = get(url("schedule").build()).toVocabSchedules()
		val lists = get(url("lists").build()).toVocabLists()
		return (schedules + lists).also { listsCache = it }
	}

	suspend fun addWordToList(wordId: Long, list: RenshuuList): AddToListResult {
		val url = url("word", wordId.toString())
			.addQueryParameter(if (list.isSchedule) "sched_id" else "list_id", list.id)
			.build()
		val request = Request.Builder()
			.url(url)
			.put(ByteArray(0).toRequestBody())
			.authorized()
			.build()
		httpClient.newCall(request).await().use { response ->
			return when (response.code) {
				HttpURLConnection.HTTP_CONFLICT -> AddToListResult.ALREADY_PRESENT
				in 200..299 -> {
					// What the word was in is now out of date; the next lookup asks again.
					wordCache.evictAll()
					presenceCache.remove(wordId)
					AddToListResult.ADDED
				}

				else -> throw response.toError()
			}
		}
	}

	/** Checks [apiKey] against the profile endpoint and returns the account's name. */
	suspend fun checkApiKey(apiKey: String): String {
		val profile = get(url("profile").build(), apiKey)
		return profile.string("real_name")
	}

	fun clearCache() {
		wordCache.evictAll()
		presenceCache.evictAll()
		kanjiCache.evictAll()
		listsCache = null
	}

	private suspend fun get(url: HttpUrl, apiKey: String? = null): JsonObject {
		val request = Request.Builder().url(url).get().authorized(apiKey).build()
		httpClient.newCall(request).await().use { response ->
			if (!response.isSuccessful) {
				throw response.toError()
			}
			return json.parseToJsonElement(response.body.string()) as? JsonObject
				?: throw RenshuuApiException("Unexpected response", response.code)
		}
	}

	private fun Request.Builder.authorized(apiKey: String? = null): Request.Builder {
		val key = apiKey ?: settings.renshuuApiKey ?: throw RenshuuAuthException(null)
		return header("Authorization", "Bearer $key")
	}

	private fun Response.toError(): Exception {
		val message = runCatching {
			(json.parseToJsonElement(body.string()) as? JsonObject)?.let {
				it.string("error").ifEmpty { it.string("message") }
			}
		}.getOrNull()?.ifEmpty { null }
		return when (code) {
			HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> RenshuuAuthException(message)
			else -> RenshuuApiException(message ?: this.message, code)
		}
	}

	// Path segments, not string templates: a kanji in the path has to be percent-encoded.
	private fun url(vararg segments: String): HttpUrl.Builder = BASE_URL.toHttpUrl().newBuilder().apply {
		segments.forEach { addPathSegment(it) }
	}

	private companion object {

		const val BASE_URL = "https://api.renshuu.org/v1"
		const val WORD_CACHE_SIZE = 500
		const val KANJI_CACHE_SIZE = 200

		val json = Json { ignoreUnknownKeys = true }
	}
}

/**
 * Exact matches first.
 *
 * A search for 見る also returns 見る目 and 見る見る; the word that was actually on the page belongs at
 * the top, with common words ahead of rare spellings after that.
 */
internal fun List<RenshuuWord>.rankFor(query: String): List<RenshuuWord> = sortedWith(
	compareByDescending<RenshuuWord> { it.kanji == query || it.kana == query }
		.thenByDescending { it.isCommon },
)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
	cont.invokeOnCancellation { cancel() }
	enqueue(
		object : Callback {
			override fun onResponse(call: Call, response: Response) {
				cont.resume(response) { _, value, _ -> value.close() }
			}

			override fun onFailure(call: Call, e: IOException) {
				cont.resumeWith(Result.failure(e))
			}
		},
	)
}
