package io.github.nathankewley.jocr.ui

import android.graphics.Bitmap
import android.graphics.Rect
import io.github.nathankewley.jocr.nlp.JapaneseWord
import io.github.nathankewley.jocr.renshuu.RenshuuKanji
import io.github.nathankewley.jocr.renshuu.RenshuuList
import io.github.nathankewley.jocr.renshuu.RenshuuWord

/** One block of text, recognised and split into words. */
data class OcrBlockModel(
	val id: Int,
	val text: String,
	val words: List<JapaneseWord>,
	/** Where the block is, in the pixels of the photo that was read (see [PhotoPreview.sourceWidth]). */
	val bounds: Rect,
)

/** The photo as shown on screen: a smaller copy than was read, so blocks are scaled to fit it. */
data class PhotoPreview(
	val bitmap: Bitmap,
	val sourceWidth: Int,
	val sourceHeight: Int,
)

sealed interface OcrStatus {

	/** No photo yet. */
	data object Idle : OcrStatus

	data class Working(val phase: Phase) : OcrStatus

	data class Failed(val error: Throwable) : OcrStatus

	data class Done(val blockCount: Int) : OcrStatus

	enum class Phase { LOADING_PHOTO, RECOGNIZING, ANALYZING }
}

sealed interface TranslationState {

	data object Disabled : TranslationState

	data class Pending(val isDownloadingModel: Boolean) : TranslationState

	data class Done(val text: String) : TranslationState

	data object Failed : TranslationState
}

sealed interface WordDetails {

	val word: JapaneseWord

	data class Loading(override val word: JapaneseWord) : WordDetails

	/** Renshuu is not set up: the offline reading is all there is to show, plus a way to the setting. */
	data class NoApiKey(override val word: JapaneseWord) : WordDetails

	data class Failed(override val word: JapaneseWord, val error: Throwable) : WordDetails

	data class Loaded(
		override val word: JapaneseWord,
		val entries: List<RenshuuWord>,
		val kanji: KanjiDetails?,
		val addingIds: Set<Long>,
	) : WordDetails
}

sealed interface KanjiDetails {

	val kanji: String

	data class Loading(override val kanji: String) : KanjiDetails

	data class Loaded(override val kanji: String, val details: RenshuuKanji) : KanjiDetails

	data class Failed(override val kanji: String, val error: Throwable) : KanjiDetails
}

/** A row in the list: a block plus everything opened on it. */
data class OcrBlockItem(
	val block: OcrBlockModel,
	val translation: TranslationState,
	val isTranslationRevealed: Boolean,
	val selectedWordIndex: Int,
	val details: WordDetails?,
	val isFuriganaVisible: Boolean,
)

data class OcrContent(
	val status: OcrStatus,
	val items: List<OcrBlockItem>,
	val isAllRevealed: Boolean,
	/** False when translations show straight away, so there is nothing to reveal. */
	val isRevealable: Boolean,
)

data class ListChoice(
	val word: RenshuuWord,
	val lists: List<RenshuuList>,
	val selectedListKey: String?,
)

sealed interface OcrMessage {

	data class Added(val word: String, val list: String) : OcrMessage

	data class AlreadyInList(val word: String, val list: String) : OcrMessage

	data object NoLists : OcrMessage
}
