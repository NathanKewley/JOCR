package io.github.nathankewley.jocr

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.github.nathankewley.jocr.nlp.JapaneseTokenizer
import io.github.nathankewley.jocr.ocr.MangaOcrReader
import io.github.nathankewley.jocr.ocr.MlKitTextRecognizer
import io.github.nathankewley.jocr.ocr.ModelStore
import io.github.nathankewley.jocr.ocr.ModelsMissingException
import io.github.nathankewley.jocr.ocr.OcrEngine
import io.github.nathankewley.jocr.ocr.PaddleTextRecognizer
import io.github.nathankewley.jocr.ocr.PhotoDecoder
import io.github.nathankewley.jocr.ocr.TextRecognizer
import io.github.nathankewley.jocr.renshuu.RenshuuRepository
import io.github.nathankewley.jocr.translate.JapaneseTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the app's few long-lived objects. Small enough that a DI framework would be more code than
 * it saves: everything is created on first use and lives as long as the process.
 */
class JocrApp : Application() {

	val settings by lazy { Settings(this) }
	val decoder by lazy { PhotoDecoder(contentResolver) }
	val tokenizer by lazy { JapaneseTokenizer() }
	val translator by lazy { JapaneseTranslator() }
	val renshuu by lazy { RenshuuRepository(httpClient, settings) }
	val models by lazy { ModelStore(this, httpClient) }
	val engineSetup by lazy { EngineSetup(models, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

	private val mlKitRecognizer by lazy { MlKitTextRecognizer() }
	private val paddleRecognizer by lazy { PaddleTextRecognizer(models) }
	private val paddleMangaRecognizer by lazy { PaddleTextRecognizer(models, MangaOcrReader(models)) }

	/**
	 * The recogniser chosen in settings, looked up per photo so a change applies to the next one.
	 * Throws [ModelsMissingException] until one has been chosen and its models are on the phone.
	 */
	val recognizer: TextRecognizer
		get() = when (settings.ocrEngine?.takeIf(models::isReady)) {
			OcrEngine.PADDLE_MANGA -> paddleMangaRecognizer
			OcrEngine.PADDLE -> paddleRecognizer
			OcrEngine.MLKIT -> mlKitRecognizer
			null -> throw ModelsMissingException()
		}

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
