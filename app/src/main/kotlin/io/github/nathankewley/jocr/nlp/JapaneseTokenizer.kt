package io.github.nathankewley.jocr.nlp

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Splits a Japanese sentence into the words a learner would look up.
 *
 * Renshuu's dictionary search wants a word, not a sentence, and Japanese does not put spaces
 * between words - so this is what turns a block of text into something tappable.
 */
class JapaneseTokenizer {

	private val mutex = Mutex()

	@Volatile
	private var tokenizer: Tokenizer? = null

	suspend fun tokenize(text: String): List<JapaneseWord> = withContext(Dispatchers.Default) {
		WordGrouper.group(getTokenizer().tokenize(text).map { it.toMorpheme() })
	}

	/**
	 * The dictionary takes a second or two and tens of megabytes to load, so it is loaded while the
	 * photo is still being read and the wait overlaps with OCR instead of following it.
	 */
	suspend fun warmUp() {
		withContext(Dispatchers.Default) { getTokenizer() }
	}

	private suspend fun getTokenizer(): Tokenizer = tokenizer ?: mutex.withLock {
		tokenizer ?: Tokenizer().also { tokenizer = it }
	}

	private fun Token.toMorpheme() = Morpheme(
		surface = surface,
		baseForm = baseForm.takeUnless { it == UNKNOWN },
		reading = reading.takeUnless { it == UNKNOWN },
		pos = partOfSpeechLevel1,
		pos2 = partOfSpeechLevel2,
	)

	private companion object {

		const val UNKNOWN = "*"
	}
}
