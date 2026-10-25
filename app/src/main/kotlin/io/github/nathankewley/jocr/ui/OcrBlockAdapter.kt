package io.github.nathankewley.jocr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import io.github.nathankewley.jocr.R
import io.github.nathankewley.jocr.databinding.ItemOcrBlockBinding
import io.github.nathankewley.jocr.databinding.ItemOcrEntryBinding
import io.github.nathankewley.jocr.databinding.ItemOcrWordBinding
import io.github.nathankewley.jocr.nlp.JapaneseWord
import io.github.nathankewley.jocr.nlp.PartOfSpeech
import io.github.nathankewley.jocr.renshuu.RenshuuAuthException
import io.github.nathankewley.jocr.renshuu.RenshuuKanji
import io.github.nathankewley.jocr.renshuu.RenshuuWord

interface OcrListener {

	/** A tap on the block itself, outside its words and translation. */
	fun onBlockClick(blockId: Int)

	fun onWordClick(blockId: Int, wordIndex: Int)

	fun onTranslationClick(blockId: Int)

	fun onKanjiClick(kanji: String)

	fun onAddToListClick(word: RenshuuWord)

	fun onRetryLookup()

	fun onOpenSettings()
}

class OcrBlockAdapter(
	private val listener: OcrListener,
) : ListAdapter<OcrBlockItem, OcrBlockAdapter.Holder>(DIFF) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
		ItemOcrBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false),
	)

	override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

	inner class Holder(
		private val binding: ItemOcrBlockBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		private val inflater = LayoutInflater.from(binding.root.context)

		fun bind(item: OcrBlockItem) {
			binding.root.setOnClickListener { listener.onBlockClick(item.block.id) }
			bindWords(item)
			bindTranslation(item)
			bindDetails(item)
		}

		private fun bindWords(item: OcrBlockItem) {
			val flow = binding.flowWords
			val words = item.block.words
			// Word views are kept between binds of the same block so selecting a word does not rebuild
			// the sentence; only a different block in this holder needs new ones.
			if (flow.tag != item.block) {
				flow.removeAllViews()
				words.forEach { word -> flow.addView(createWordView(flow, word)) }
				flow.tag = item.block
			}
			words.forEachIndexed { index, word ->
				val view = flow.getChildAt(index)
				view.isSelected = index == item.selectedWordIndex
				ItemOcrWordBinding.bind(view).textViewReading.isVisible = item.isFuriganaVisible && word.reading != null
				if (word.isLookupable) {
					view.setOnClickListener { listener.onWordClick(item.block.id, index) }
				}
			}
		}

		private fun createWordView(parent: ViewGroup, word: JapaneseWord): View {
			val wordBinding = ItemOcrWordBinding.inflate(inflater, parent, false)
			wordBinding.textViewSurface.text = word.surface
			wordBinding.textViewReading.text = word.reading
			// Grammar in a quieter colour: the eye goes to the words that carry the meaning, and the
			// particles are still there to read in place.
			if (word.partOfSpeech in GRAMMAR) {
				wordBinding.textViewSurface.setTextColor(
					MaterialColors.getColor(wordBinding.root, com.google.android.material.R.attr.colorOnSurfaceVariant),
				)
			}
			if (!word.isLookupable) {
				wordBinding.root.background = null
				wordBinding.root.isClickable = false
			}
			wordBinding.root.contentDescription = word.surface
			return wordBinding.root
		}

		private fun bindTranslation(item: OcrBlockItem) {
			val view = binding.textViewTranslation
			val res = view.resources
			val translation = item.translation
			view.isVisible = translation != TranslationState.Disabled
			view.setOnClickListener { listener.onTranslationClick(item.block.id) }
			val isHint = !item.isTranslationRevealed || translation !is TranslationState.Done
			view.text = when {
				!item.isTranslationRevealed -> res.getString(R.string.ocr_tap_to_reveal)
				translation is TranslationState.Done -> translation.text
				translation is TranslationState.Pending && translation.isDownloadingModel ->
					res.getString(R.string.ocr_downloading_translation_model)

				translation is TranslationState.Pending -> res.getString(R.string.ocr_translating)
				else -> res.getString(R.string.ocr_translation_failed)
			}
			view.setTextColor(
				MaterialColors.getColor(
					view,
					if (isHint) {
						com.google.android.material.R.attr.colorOnSurfaceVariant
					} else {
						com.google.android.material.R.attr.colorOnSurface
					},
				),
			)
			TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
				view,
				if (item.isTranslationRevealed) R.drawable.ic_eye_off else R.drawable.ic_eye,
				0, 0, 0,
			)
			TextViewCompat.setCompoundDrawableTintList(view, view.textColors)
		}

		private fun bindDetails(item: OcrBlockItem) {
			val container = binding.layoutDetails
			container.removeAllViews()
			val details = item.details
			container.isVisible = details != null
			when (details) {
				null -> Unit
				is WordDetails.Loading -> {
					addWordHeader(container, details.word)
					addText(container, R.string.ocr_looking_up)
				}

				is WordDetails.NoApiKey -> {
					addWordHeader(container, details.word)
					addText(container, R.string.renshuu_api_key_required)
					addButton(container, R.string.settings) { listener.onOpenSettings() }
				}

				is WordDetails.Failed -> {
					addWordHeader(container, details.word)
					if (details.error is RenshuuAuthException) {
						addText(container, R.string.renshuu_api_key_rejected)
						addButton(container, R.string.settings) { listener.onOpenSettings() }
					} else {
						addText(container, details.error.displayMessage(container.resources))
						addButton(container, R.string.try_again) { listener.onRetryLookup() }
					}
				}

				is WordDetails.Loaded -> bindEntries(container, details)
			}
		}

		private fun bindEntries(container: ViewGroup, details: WordDetails.Loaded) {
			if (details.entries.isEmpty()) {
				addWordHeader(container, details.word)
				addText(container, container.resources.getString(R.string.ocr_no_dictionary_entry, details.word.lookup))
				return
			}
			details.entries.forEachIndexed { index, entry ->
				val entryBinding = ItemOcrEntryBinding.inflate(inflater, container, false)
				bindEntry(entryBinding, entry, isAdding = entry.id in details.addingIds)
				if (index > 0) {
					// Alternatives are kept smaller so the best match still reads as the answer.
					entryBinding.textViewHeadword.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
				}
				container.addView(entryBinding.root)
			}
			val kanji = MainViewModel.kanjiOf(details.word.surface)
				.ifEmpty { MainViewModel.kanjiOf(details.entries.first().kanji) }
			if (kanji.isNotEmpty()) {
				addKanji(container, kanji, details.kanji)
			}
		}

		private fun bindEntry(entryBinding: ItemOcrEntryBinding, entry: RenshuuWord, isAdding: Boolean) {
			val res = entryBinding.root.resources
			entryBinding.textViewHeadword.text = entry.headword
			entryBinding.textViewKana.text = entry.kana.takeIf { it != entry.headword }
			entryBinding.textViewMeta.text = buildList {
				entry.typeOfSpeech.trim('(', ')', ' ').takeIf { it.isNotEmpty() }?.let(::add)
				addAll(entry.markers)
				if (entry.isCommon) add(res.getString(R.string.renshuu_common))
			}.joinToString(" · ")
			entryBinding.textViewMeta.isVisible = entryBinding.textViewMeta.text.isNotEmpty()
			entryBinding.textViewDefinitions.text = if (entry.definitions.size == 1) {
				entry.definitions.single()
			} else {
				entry.definitions.mapIndexed { i, d -> "${i + 1}. $d" }.joinToString("\n")
			}
			// How many lists already have it, on the button that adds it to another: which ones is in
			// the chooser behind it, where the answer is actually needed.
			entryBinding.buttonAdd.text = if (entry.inLists.isEmpty()) {
				res.getString(R.string.renshuu_add_to_list)
			} else {
				res.getString(R.string.renshuu_add_to_list_count, entry.inLists.size)
			}
			entryBinding.buttonAdd.isEnabled = !isAdding
			entryBinding.buttonAdd.setOnClickListener { listener.onAddToListClick(entry) }
		}

		private fun addKanji(container: ViewGroup, kanji: List<String>, selected: KanjiDetails?) {
			addText(container, R.string.ocr_kanji_breakdown).apply {
				setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
				setPadding(0, paddingTop + container.resources.getDimensionPixelSize(R.dimen.margin_small), 0, 0)
			}
			val group = ChipGroup(container.context)
			kanji.forEach { k ->
				group.addView(
					Chip(container.context).apply {
						text = k
						textSize = 18f
						isCheckable = true
						isChecked = selected?.kanji == k
						setOnClickListener { listener.onKanjiClick(k) }
					},
				)
			}
			container.addView(group)
			when (selected) {
				null -> Unit
				is KanjiDetails.Loading -> addText(container, R.string.ocr_looking_up)
				is KanjiDetails.Failed -> addText(container, selected.error.displayMessage(container.resources))
				is KanjiDetails.Loaded -> addText(container, formatKanji(container, selected.details))
			}
		}

		private fun formatKanji(container: ViewGroup, k: RenshuuKanji): String {
			val res = container.resources
			return buildList {
				add("${k.kanji} — ${k.definition}")
				if (k.onyomi.isNotEmpty()) add(res.getString(R.string.kanji_onyomi, k.onyomi))
				if (k.kunyomi.isNotEmpty()) add(res.getString(R.string.kanji_kunyomi, k.kunyomi))
				buildList {
					k.jlpt.takeIf { it.isNotEmpty() }?.let { add(res.getString(R.string.kanji_jlpt, it)) }
					if (k.strokeCount > 0) add(res.getQuantityString(R.plurals.kanji_strokes, k.strokeCount, k.strokeCount))
					if (k.radical.isNotEmpty()) {
						add(res.getString(R.string.kanji_radical, "${k.radical} ${k.radicalName}".trim()))
					}
				}.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let(::add)
				if (k.parts.isNotEmpty()) {
					add(res.getString(R.string.kanji_parts, k.parts.joinToString { (piece, def) -> "$piece $def".trim() }))
				}
			}.joinToString("\n")
		}

		/** The word as the analyser saw it, so there is something to read even without renshuu. */
		private fun addWordHeader(container: ViewGroup, word: JapaneseWord) {
			val text = buildString {
				append(word.surface)
				word.reading?.let { append("【").append(it).append("】") }
				if (word.lookup != word.surface) append("  → ").append(word.lookup)
			}
			addText(container, text).setTextAppearance(
				com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
			)
		}

		private fun addText(container: ViewGroup, textRes: Int): TextView =
			addText(container, container.resources.getString(textRes))

		private fun addText(container: ViewGroup, text: CharSequence): TextView {
			val view = TextView(container.context)
			view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
			view.setTextColor(MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface))
			view.text = text
			view.setPadding(0, container.resources.getDimensionPixelSize(R.dimen.margin_small), 0, 0)
			container.addView(view)
			return view
		}

		private fun addButton(container: ViewGroup, textRes: Int, onClick: () -> Unit) {
			val button = MaterialButton(
				container.context,
				null,
				com.google.android.material.R.attr.materialButtonTonalStyle,
			)
			button.setText(textRes)
			button.setOnClickListener { onClick() }
			container.addView(
				button,
				ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
			)
		}
	}

	private companion object {

		val GRAMMAR = setOf(PartOfSpeech.PARTICLE, PartOfSpeech.AUXILIARY, PartOfSpeech.SYMBOL)

		val DIFF = object : DiffUtil.ItemCallback<OcrBlockItem>() {

			override fun areItemsTheSame(oldItem: OcrBlockItem, newItem: OcrBlockItem) =
				oldItem.block.id == newItem.block.id && oldItem.block.text == newItem.block.text

			override fun areContentsTheSame(oldItem: OcrBlockItem, newItem: OcrBlockItem) = oldItem == newItem
		}
	}
}
