package io.github.nathankewley.jocr.ocr

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nathankewley.jocr.JocrApp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Scores the recognisers against photos with known text. Not a pass/fail test: a report, to tell
 * whether a change to the OCR made it better.
 *
 * Each photo (.jpg or .png) comes with a .txt of the same name holding the text that should be
 * read, one block per line. Push them to the app's files, then run this test:
 *
 *     adb push pages/. /sdcard/Android/data/io.github.nathankewley.jocr.debug/files/ocr-eval/
 *
 * Recognisers whose models have not been downloaded in the app are left out.
 * The report is logged under the tag OcrEval and written to report.txt beside the photos.
 * For every block that should have been read it takes the closest block that was read, and
 * reports the character error rate (CER) between the two. Recall and precision count the
 * characters read regardless of order: low precision is noise read as text - sound effects,
 * furigana, scribbles.
 */
@RunWith(AndroidJUnit4::class)
class OcrEvalTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val dir = File(context.getExternalFilesDir(null), "ocr-eval")
	private val decoder = PhotoDecoder(context.contentResolver)
	private val report = StringBuilder()

	@Test
	fun evaluate() = runBlocking {
		val photos = dir.listFiles { f -> f.extension in setOf("jpg", "jpeg", "png") && truthOf(f).exists() }
			?.sortedBy { it.name }
			.orEmpty()
		assumeTrue("No photos with ground truth in $dir", photos.isNotEmpty())
		// Only the recognisers whose models have been downloaded in the app.
		val models = (context.applicationContext as JocrApp).models
		val recognizers = listOfNotNull(
			("paddle+manga" to PaddleTextRecognizer(models, MangaOcrReader(models))).takeIf { models.isReady(OcrEngine.PADDLE_MANGA) },
			("paddle" to PaddleTextRecognizer(models)).takeIf { models.isReady(OcrEngine.PADDLE) },
			"mlkit" to MlKitTextRecognizer(),
		)
		for ((name, recognizer) in recognizers) {
			// The first photo loads the models; it is not what a photo takes to read.
			read(recognizer, photos.first())
			val scores = photos.map { score(name, recognizer, it) }
			line(
				"== %s: %d photos, CER %.3f, exact %d/%d, recall %.3f, precision %.3f, %d ms per photo".format(
					Locale.ROOT, name, scores.size, scores.flatMap { it.cers }.average(),
					scores.sumOf { s -> s.cers.count { it == 0.0 } }, scores.sumOf { it.cers.size },
					scores.map { it.recall }.average(), scores.map { it.precision }.average(),
					scores.map { it.millis }.average().toLong(),
				),
			)
		}
		File(dir, "report.txt").writeText(report.toString())
	}

	private suspend fun score(engine: String, recognizer: TextRecognizer, photo: File): Score {
		val truth = truthOf(photo).readLines().map(::normalize).filter { it.isNotEmpty() }
		val start = SystemClock.elapsedRealtime()
		val read = read(recognizer, photo).map { normalize(it.text) }
		val millis = SystemClock.elapsedRealtime() - start
		val cers = truth.map { expected ->
			val best = read.minByOrNull { distance(expected, it) }.orEmpty()
			val cer = minOf(1.0, distance(expected, best).toDouble() / expected.length)
			if (cer > 0) {
				line("   %s %s: %.2f  %s  <-  %s".format(Locale.ROOT, engine, photo.nameWithoutExtension, cer, expected, best))
			}
			cer
		}
		val wanted = truth.joinToString("")
		val got = read.joinToString("")
		val overlap = overlap(wanted, got)
		val score = Score(cers, overlap.toDouble() / wanted.length, if (got.isEmpty()) 0.0 else overlap.toDouble() / got.length, millis)
		line(
			"%s %s: CER %.3f, exact %d/%d, recall %.3f, precision %.3f, %d ms".format(
				Locale.ROOT, engine, photo.nameWithoutExtension, cers.average(), cers.count { it == 0.0 }, cers.size,
				score.recall, score.precision, millis,
			),
		)
		return score
	}

	private suspend fun read(recognizer: TextRecognizer, photo: File): List<OcrBlock> {
		val bitmap = decoder.decode(Uri.fromFile(photo))
		return try {
			recognizer.recognize(bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	private fun truthOf(photo: File) = File(photo.parentFile, photo.nameWithoutExtension + ".txt")

	/** Full-width and half-width forms alike (！ and !), and no spaces: neither is what is being tested. */
	private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).filterNot(Char::isWhitespace)

	private fun distance(a: String, b: String): Int {
		var previous = IntArray(b.length + 1) { it }
		for (i in 1..a.length) {
			val current = IntArray(b.length + 1)
			current[0] = i
			for (j in 1..b.length) {
				val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
				current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
			}
			previous = current
		}
		return previous[b.length]
	}

	/** How many characters of [a] are also in [b], each counted as often as it is in both. */
	private fun overlap(a: String, b: String): Int {
		val counts = b.groupingBy { it }.eachCount().toMutableMap()
		return a.count { c -> (counts[c] ?: 0).let { if (it > 0) counts[c] = it - 1; it > 0 } }
	}

	private fun line(text: String) {
		Log.i(TAG, text)
		report.appendLine(text)
	}

	private class Score(val cers: List<Double>, val recall: Double, val precision: Double, val millis: Long)

	private companion object {

		const val TAG = "OcrEval"
	}
}
