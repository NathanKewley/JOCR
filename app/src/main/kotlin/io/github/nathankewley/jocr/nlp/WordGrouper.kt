package io.github.nathankewley.jocr.nlp

/** One morpheme as the analyser returns it, with IPADIC's part-of-speech labels. */
data class Morpheme(
	val surface: String,
	val baseForm: String?,
	val reading: String?,
	val pos: String,
	val pos2: String,
)

/**
 * A word as the learner sees it on screen.
 *
 * @param surface what is written on the page, e.g. 食べていた
 * @param lookup the dictionary form to search for, e.g. 食べる
 * @param reading the surface in hiragana, or null when it is already all kana or unknown
 */
data class JapaneseWord(
	val surface: String,
	val lookup: String,
	val reading: String?,
	val partOfSpeech: PartOfSpeech,
) {

	val isLookupable: Boolean
		get() = partOfSpeech != PartOfSpeech.SYMBOL
}

enum class PartOfSpeech {
	NOUN, VERB, ADJECTIVE, ADVERB, PARTICLE, AUXILIARY, CONJUNCTION, INTERJECTION, PREFIX, SYMBOL, OTHER,
}

/**
 * Rebuilds words out of morphemes.
 *
 * The analyser cuts at every morpheme, so 食べていた arrives as 食べ / て / い / た. Shown like that a
 * learner has to tap four times and gets the dictionary entry for the particle て along the way,
 * so conjugation tails are put back onto the verb or adjective they belong to, and a prefix such as
 * the polite お is joined to the noun after it (お茶 is in the dictionary; お on its own is not
 * what anyone wants to look up).
 */
object WordGrouper {

	fun group(morphemes: List<Morpheme>): List<JapaneseWord> {
		val words = ArrayList<Builder>(morphemes.size)
		var pendingPrefix: Morpheme? = null
		for (m in morphemes) {
			val last = words.lastOrNull()
			when {
				pendingPrefix == null && last != null && last.isInflectable && m.isConjugationTail() -> last.append(m)
				m.pos == POS_PREFIX -> {
					pendingPrefix?.let { words += Builder(it) }
					pendingPrefix = m
				}

				else -> {
					val builder = Builder(pendingPrefix ?: m)
					if (pendingPrefix != null) {
						builder.append(m, isHead = true)
						pendingPrefix = null
					}
					words += builder
				}
			}
		}
		pendingPrefix?.let { words += Builder(it) }
		return words.map { it.build() }
	}

	private fun Morpheme.isConjugationTail(): Boolean = when (pos) {
		POS_AUXILIARY -> true
		// 食べている, 食べてしまう: the いる and しまう are helpers, not verbs in their own right.
		POS_VERB -> pos2 == POS2_DEPENDENT || pos2 == POS2_SUFFIX
		POS_ADJECTIVE -> pos2 == POS2_DEPENDENT
		POS_PARTICLE -> pos2 == POS2_CONJUNCTIVE_PARTICLE && surface in CONJUNCTIVE_TE
		else -> false
	}

	private class Builder(first: Morpheme) {

		private val parts = arrayListOf(first)
		private var head: Morpheme = first

		val isInflectable: Boolean
			get() = head.pos == POS_VERB || head.pos == POS_ADJECTIVE || head.pos == POS_AUXILIARY

		fun append(m: Morpheme, isHead: Boolean = false) {
			parts += m
			if (isHead) {
				head = m
			}
		}

		fun build(): JapaneseWord {
			val surface = parts.joinToString("") { it.surface }
			val hasPrefix = parts.first() !== head
			val lookup = when {
				// お茶: the dictionary has the prefixed form, so search for what was written.
				head.pos == POS_VERB || head.pos == POS_ADJECTIVE -> head.baseForm ?: head.surface
				hasPrefix -> parts.first().surface + head.surface
				else -> head.surface
			}
			val reading = if (parts.all { it.reading != null }) {
				parts.joinToString("") { it.reading.orEmpty() }.katakanaToHiragana()
			} else {
				null
			}
			return JapaneseWord(
				surface = surface,
				lookup = lookup,
				// Furigana over kana is clutter: only kept when there is something to read.
				reading = reading?.takeIf { surface.any { it.isKanji() } && it != surface },
				partOfSpeech = head.partOfSpeech(),
			)
		}
	}

	private fun Morpheme.partOfSpeech(): PartOfSpeech = when (pos) {
		POS_NOUN -> PartOfSpeech.NOUN
		POS_VERB -> PartOfSpeech.VERB
		POS_ADJECTIVE, POS_ADJECTIVAL -> PartOfSpeech.ADJECTIVE
		POS_ADVERB -> PartOfSpeech.ADVERB
		POS_PARTICLE -> PartOfSpeech.PARTICLE
		POS_AUXILIARY -> PartOfSpeech.AUXILIARY
		POS_CONJUNCTION -> PartOfSpeech.CONJUNCTION
		POS_INTERJECTION -> PartOfSpeech.INTERJECTION
		POS_PREFIX -> PartOfSpeech.PREFIX
		POS_SYMBOL -> PartOfSpeech.SYMBOL
		else -> PartOfSpeech.OTHER
	}

	// IPADIC part-of-speech labels
	private const val POS_NOUN = "名詞"
	private const val POS_VERB = "動詞"
	private const val POS_ADJECTIVE = "形容詞"
	private const val POS_ADJECTIVAL = "連体詞"
	private const val POS_ADVERB = "副詞"
	private const val POS_PARTICLE = "助詞"
	private const val POS_AUXILIARY = "助動詞"
	private const val POS_CONJUNCTION = "接続詞"
	private const val POS_INTERJECTION = "感動詞"
	private const val POS_PREFIX = "接頭詞"
	private const val POS_SYMBOL = "記号"
	private const val POS2_DEPENDENT = "非自立"
	private const val POS2_SUFFIX = "接尾"
	private const val POS2_CONJUNCTIVE_PARTICLE = "接続助詞"
	private val CONJUNCTIVE_TE = setOf("て", "で")
}

fun String.katakanaToHiragana(): String = buildString(length) {
	for (c in this@katakanaToHiragana) {
		append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
	}
}

fun Char.isKanji(): Boolean = this in '一'..'鿿' || this in '㐀'..'䶿' || this == '々'
