package io.github.nathankewley.jocr

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.nathankewley.jocr.ocr.OcrEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The app's preferences, shared with the settings screen, which writes them under the same keys. */
class Settings(context: Context) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	val renshuuApiKey: String?
		get() = prefs.getString(KEY_RENSHUU_API_KEY, null)?.trim()?.ifEmpty { null }

	/** The recogniser photos are read with, or null until one has been chosen (see [EngineSetup]). */
	var ocrEngine: OcrEngine?
		get() = prefs.getString(KEY_OCR_ENGINE, null)?.let(OcrEngine::fromId)
		set(value) = prefs.edit { putString(KEY_OCR_ENGINE, value?.id) }

	val isFuriganaEnabled: Boolean
		get() = prefs.getBoolean(KEY_FURIGANA, true)

	val isTranslationEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRANSLATION, true)

	/** Whether a translation waits for a tap, so the text can be read first and checked after. */
	val isTranslationHidden: Boolean
		get() = prefs.getBoolean(KEY_TRANSLATION_HIDDEN, true)

	val isRenshuuPresenceEnabled: Boolean
		get() = prefs.getBoolean(KEY_RENSHUU_PRESENCE, true)

	/** The list words were last added to, offered first next time. */
	var renshuuListKey: String?
		get() = prefs.getString(KEY_RENSHUU_LIST, null)
		set(value) = prefs.edit { putString(KEY_RENSHUU_LIST, value) }

	/** [read] now and again every time [key] changes. */
	fun <T> observe(key: String, read: Settings.() -> T): Flow<T> = callbackFlow {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
			if (changed == key) {
				trySend(read())
			}
		}
		prefs.registerOnSharedPreferenceChangeListener(listener)
		send(read())
		awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}.distinctUntilChanged()

	companion object {

		const val KEY_RENSHUU_API_KEY = "renshuu_api_key"
		const val KEY_OCR_ENGINE = "ocr_engine"
		const val KEY_FURIGANA = "jp_furigana"
		const val KEY_TRANSLATION = "jp_translation"
		const val KEY_TRANSLATION_HIDDEN = "jp_translation_hidden"
		const val KEY_RENSHUU_PRESENCE = "renshuu_presence"
		const val KEY_RENSHUU_LIST = "renshuu_list"
	}
}
