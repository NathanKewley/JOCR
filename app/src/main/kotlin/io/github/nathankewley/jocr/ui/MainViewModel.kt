package io.github.nathankewley.jocr.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.nathankewley.jocr.JocrApp
import io.github.nathankewley.jocr.Settings
import io.github.nathankewley.jocr.nlp.isKanji
import io.github.nathankewley.jocr.renshuu.AddToListResult
import io.github.nathankewley.jocr.renshuu.RenshuuList
import io.github.nathankewley.jocr.renshuu.RenshuuWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class MainViewModel(app: Application) : AndroidViewModel(app) {

	private val jocr = app as JocrApp
	private val decoder = jocr.decoder
	private val recognizer = jocr.recognizer
	private val tokenizer = jocr.tokenizer
	private val translator = jocr.translator
	private val renshuu = jocr.renshuu
	private val settings = jocr.settings

	private val _preview = MutableStateFlow<PhotoPreview?>(null)
	val preview: StateFlow<PhotoPreview?> = _preview.asStateFlow()

	val onChooseList = MutableSharedFlow<ListChoice>(extraBufferCapacity = 1)
	val onMessage = MutableSharedFlow<OcrMessage>(extraBufferCapacity = 1)
	private val _onError = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
	val onError: SharedFlow<Throwable> = _onError

	/** The photo to read, with a counter so the same photo can be asked for again. */
	private val request = MutableStateFlow<Pair<Uri, Int>?>(null)
	private val status = MutableStateFlow<OcrStatus>(OcrStatus.Idle)
	private val blocks = MutableStateFlow<List<OcrBlockModel>>(emptyList())
	private val revealed = MutableStateFlow<Set<Int>>(emptySet())
	private val selection = MutableStateFlow<Selection?>(null)

	/** Keyed by the Japanese text, so a line seen in an earlier photo is not translated twice. */
	private val translations = MutableStateFlow<Map<String, TranslationState>>(emptyMap())

	private var lookupJob: Job? = null
	private var kanjiJob: Job? = null

	/** Failures of background work the UI did not wait on: reported, never a crash. */
	private val errorHandler = CoroutineExceptionHandler { _, error ->
		Log.w(TAG, error)
		_onError.tryEmit(error)
	}

	val content: StateFlow<OcrContent> = combine(
		combine(status, blocks, translations, ::Triple),
		revealed,
		selection,
		settings.observe(Settings.KEY_FURIGANA) { isFuriganaEnabled },
		settings.observe(Settings.KEY_TRANSLATION_HIDDEN) { isTranslationHidden },
	) { (status, blocks, translations), revealed, selection, isFurigana, isHidden ->
		OcrContent(
			status = status,
			items = blocks.map { block ->
				val selected = selection?.takeIf { it.blockId == block.id }
				OcrBlockItem(
					block = block,
					translation = translations[block.text] ?: TranslationState.Disabled,
					isTranslationRevealed = !isHidden || block.id in revealed,
					selectedWordIndex = selected?.wordIndex ?: -1,
					details = selected?.details,
					isFuriganaVisible = isFurigana,
				)
			},
			isAllRevealed = !isHidden || (blocks.isNotEmpty() && blocks.all { it.id in revealed }),
			isRevealable = isHidden,
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, EMPTY_CONTENT)

	init {
		viewModelScope.launch(Dispatchers.Default) {
			// A new photo cancels the reading of the last one.
			request.collectLatest { request ->
				if (request != null) {
					processPhoto(request.first)
				}
			}
		}
	}

	fun open(uri: Uri) {
		request.update { (uri to ((it?.second ?: 0) + 1)) }
	}

	fun reload() {
		translations.update { map -> map.filterValues { it is TranslationState.Done } }
		request.value?.let { open(it.first) }
	}

	fun toggleTranslation(blockId: Int) {
		revealed.update { if (blockId in it) it - blockId else it + blockId }
	}

	fun toggleAllTranslations() {
		val all = blocks.value.mapTo(HashSet()) { it.id }
		revealed.update { if (it.containsAll(all)) emptySet() else all }
	}

	fun onWordClick(blockId: Int, wordIndex: Int) {
		val current = selection.value
		lookupJob?.cancel()
		kanjiJob?.cancel()
		if (current != null && current.blockId == blockId && current.wordIndex == wordIndex) {
			selection.value = null // a second tap folds the details away again
			return
		}
		val word = blocks.value.find { it.id == blockId }?.words?.getOrNull(wordIndex) ?: return
		if (!renshuu.isConfigured) {
			selection.value = Selection(blockId, wordIndex, WordDetails.NoApiKey(word))
			return
		}
		selection.value = Selection(blockId, wordIndex, WordDetails.Loading(word))
		lookupJob = viewModelScope.launch(Dispatchers.Default) {
			val details = runCatchingCancellable {
				// The dictionary form first; when the analyser's guess finds nothing (a name, slang,
				// an OCR slip), what was actually written.
				val entries = renshuu.searchWords(word.lookup).ifEmpty {
					if (word.surface != word.lookup) renshuu.searchWords(word.surface) else emptyList()
				}
				WordDetails.Loaded(word, entries.take(MAX_ENTRIES), kanji = null, addingIds = emptySet())
			}.getOrElse { error ->
				Log.w(TAG, error)
				WordDetails.Failed(word, error)
			}
			updateDetails(blockId, wordIndex) { details }
			val best = (details as? WordDetails.Loaded)?.entries?.firstOrNull()
			if (best != null && settings.isRenshuuPresenceEnabled) {
				// Only for the best match, and after the definitions are already showing: it is a
				// second call against the daily allowance and should not hold the answer up.
				val lists = runCatchingCancellable { renshuu.getPresence(best.id) }
					.onFailure { Log.w(TAG, it) }
					.getOrNull()
				lists?.forEach { markInList(best.id, it) }
			}
		}
	}

	fun onKanjiClick(kanji: String) {
		val current = selection.value ?: return
		val loaded = current.details as? WordDetails.Loaded ?: return
		kanjiJob?.cancel()
		if (loaded.kanji?.kanji == kanji) {
			updateDetails(current.blockId, current.wordIndex) { (it as WordDetails.Loaded).copy(kanji = null) }
			return
		}
		updateDetails(current.blockId, current.wordIndex) {
			(it as WordDetails.Loaded).copy(kanji = KanjiDetails.Loading(kanji))
		}
		kanjiJob = viewModelScope.launch(Dispatchers.Default) {
			val result = runCatchingCancellable {
				KanjiDetails.Loaded(kanji, renshuu.getKanji(kanji))
			}.getOrElse { KanjiDetails.Failed(kanji, it) }
			updateDetails(current.blockId, current.wordIndex) {
				(it as? WordDetails.Loaded)?.takeIf { d -> d.kanji?.kanji == kanji }?.copy(kanji = result) ?: it
			}
		}
	}

	fun retryLookup() {
		val current = selection.value ?: return
		selection.value = null
		onWordClick(current.blockId, current.wordIndex)
	}

	fun onAddToListClick(word: RenshuuWord) {
		viewModelScope.launch(Dispatchers.Default + errorHandler) {
			val lists = renshuu.getVocabLists()
			if (lists.isEmpty()) {
				onMessage.emit(OcrMessage.NoLists)
			} else {
				onChooseList.emit(ListChoice(word, lists, settings.renshuuListKey))
			}
		}
	}

	fun addToList(word: RenshuuWord, list: RenshuuList) {
		settings.renshuuListKey = list.key
		setAdding(word.id, true)
		viewModelScope.launch(Dispatchers.Default + errorHandler) {
			try {
				val result = renshuu.addWordToList(word.id, list)
				onMessage.emit(
					when (result) {
						AddToListResult.ADDED -> OcrMessage.Added(word.headword, list.title)
						AddToListResult.ALREADY_PRESENT -> OcrMessage.AlreadyInList(word.headword, list.title)
					},
				)
				markInList(word.id, list)
			} finally {
				setAdding(word.id, false)
			}
		}
	}

	private suspend fun processPhoto(uri: Uri) {
		revealed.value = emptySet()
		selection.value = null
		blocks.value = emptyList()
		val result = try {
			status.value = OcrStatus.Working(OcrStatus.Phase.LOADING_PHOTO)
			val bitmap = decoder.decode(uri)
			val ocr = try {
				// The old preview is left to the garbage collector, not recycled: the screen may
				// still be drawing it.
				_preview.value = PhotoPreview(decoder.preview(bitmap), bitmap.width, bitmap.height)
				status.value = OcrStatus.Working(OcrStatus.Phase.RECOGNIZING)
				// The dictionary loads on another thread while the recogniser runs.
				viewModelScope.launch(Dispatchers.Default) { runCatchingCancellable { tokenizer.warmUp() } }
				recognizer.recognize(bitmap)
			} finally {
				bitmap.recycle()
			}
			status.value = OcrStatus.Working(OcrStatus.Phase.ANALYZING)
			ocr.mapIndexed { index, block ->
				OcrBlockModel(id = index, text = block.text, words = tokenizer.tokenize(block.text), bounds = block.bounds)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, e)
			status.value = OcrStatus.Failed(e)
			return
		} catch (e: OutOfMemoryError) {
			status.value = OcrStatus.Failed(e)
			return
		}
		blocks.value = result
		status.value = OcrStatus.Done(result.size)
		translate(result)
	}

	/**
	 * Translates the blocks one at a time, top of the photo first, so the one most likely to be
	 * revealed is ready soonest. Runs inside the photo's job: opening another photo stops it.
	 */
	private suspend fun translate(blocks: List<OcrBlockModel>) {
		if (!settings.isTranslationEnabled) {
			return
		}
		val todo = blocks.map { it.text }.distinct().filter { translations.value[it] !is TranslationState.Done }
		if (todo.isEmpty()) {
			return
		}
		val isDownloading = runCatchingCancellable { translator.isModelDownloadNeeded() }.getOrDefault(false)
		translations.update { map -> map + todo.associateWith { TranslationState.Pending(isDownloading) } }
		for (text in todo) {
			val state = runCatchingCancellable {
				TranslationState.Done(translator.translate(text))
			}.getOrElse { error ->
				Log.w(TAG, error)
				TranslationState.Failed
			}
			translations.update { it + (text to state) }
		}
	}

	private fun updateDetails(blockId: Int, wordIndex: Int, transform: (WordDetails) -> WordDetails) {
		selection.update { sel ->
			if (sel != null && sel.blockId == blockId && sel.wordIndex == wordIndex) {
				sel.copy(details = transform(sel.details))
			} else {
				sel
			}
		}
	}

	private fun setAdding(wordId: Long, isAdding: Boolean) {
		val sel = selection.value ?: return
		updateDetails(sel.blockId, sel.wordIndex) { details ->
			if (details is WordDetails.Loaded) {
				details.copy(addingIds = if (isAdding) details.addingIds + wordId else details.addingIds - wordId)
			} else {
				details
			}
		}
	}

	private fun markInList(wordId: Long, list: RenshuuList) {
		val sel = selection.value ?: return
		updateDetails(sel.blockId, sel.wordIndex) { details ->
			if (details is WordDetails.Loaded) {
				details.copy(
					entries = details.entries.map { entry ->
						if (entry.id == wordId && entry.inLists.none { it.key == list.key }) {
							entry.copy(inLists = entry.inLists + list)
						} else {
							entry
						}
					},
				)
			} else {
				details
			}
		}
	}

	private data class Selection(
		val blockId: Int,
		val wordIndex: Int,
		val details: WordDetails,
	)

	companion object {

		private const val TAG = "JOCR"
		private const val MAX_ENTRIES = 3
		private val EMPTY_CONTENT = OcrContent(OcrStatus.Idle, emptyList(), isAllRevealed = false, isRevealable = false)

		/** The kanji in a word, each once, in the order they are written: the "parts" to break it into. */
		fun kanjiOf(text: String): List<String> = text.filter { it.isKanji() && it != '々' }
			.map { it.toString() }
			.distinct()
	}
}

/** [runCatching] that lets cancellation through, so a cancelled job stops instead of carrying on. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
	Result.success(block())
} catch (e: CancellationException) {
	throw e
} catch (e: Exception) {
	Result.failure(e)
}
