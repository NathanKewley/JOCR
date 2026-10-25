package io.github.nathankewley.jocr

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.github.nathankewley.jocr.nlp.JapaneseTokenizer
import io.github.nathankewley.jocr.ocr.PhotoDecoder
import io.github.nathankewley.jocr.ocr.PhotoTextRecognizer
import io.github.nathankewley.jocr.renshuu.RenshuuRepository
import io.github.nathankewley.jocr.translate.JapaneseTranslator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the app's few long-lived objects. Small enough that a DI framework would be more code than
 * it saves: everything is created on first use and lives as long as the process.
 */
class JocrApp : Application() {

	val settings by lazy { Settings(this) }
	val decoder by lazy { PhotoDecoder(contentResolver) }
	val recognizer by lazy { PhotoTextRecognizer() }
	val tokenizer by lazy { JapaneseTokenizer() }
	val translator by lazy { JapaneseTranslator() }
	val renshuu by lazy { RenshuuRepository(httpClient, settings) }

	private val httpClient by lazy {
		OkHttpClient.Builder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.build()
	}

	override fun onCreate() {
		super.onCreate()
		DynamicColors.applyToActivitiesIfAvailable(this)
	}
}
