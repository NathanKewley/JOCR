package io.github.nathankewley.jocr.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.nathankewley.jocr.R
import io.github.nathankewley.jocr.databinding.ActivityMainBinding
import io.github.nathankewley.jocr.renshuu.RenshuuAuthException
import io.github.nathankewley.jocr.renshuu.RenshuuWord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity(), OcrListener {

	private lateinit var binding: ActivityMainBinding
	private val viewModel by viewModels<MainViewModel>()

	private val blockAdapter = OcrBlockAdapter(this)
	private var shownBlocks: List<OcrBlockModel> = emptyList()

	/** Where the camera app is writing the photo. Kept across the app being killed while it is open. */
	private var pendingCapture: Uri? = null

	private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
		val uri = pendingCapture
		pendingCapture = null
		if (saved && uri != null) {
			viewModel.open(uri)
		}
	}

	private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
		uri?.let(viewModel::open)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)
		pendingCapture = savedInstanceState?.let { BundleCompat.getParcelable(it, STATE_CAPTURE, Uri::class.java) }

		binding.recyclerView.layoutManager = LinearLayoutManager(this)
		binding.recyclerView.adapter = blockAdapter
		binding.photo.onBlockClick = { blockId ->
			scrollToBlock(blockId)
			binding.photo.focusBlock(blockId)
		}
		binding.buttonRetry.setOnClickListener { viewModel.reload() }
		// A tapped word swaps the card's contents in place; the default cross-fade makes it blink.
		(binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

		binding.buttonCamera.setOnClickListener { takePhoto() }
		binding.buttonGallery.setOnClickListener {
			pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
		}
		binding.toolbar.setOnMenuItemClickListener { item ->
			when (item.itemId) {
				R.id.action_reveal_all -> viewModel.toggleAllTranslations()
				R.id.action_reread -> viewModel.reload()
				R.id.action_settings -> onOpenSettings()
				else -> return@setOnMenuItemClickListener false
			}
			true
		}

		// Insets go on top of the padding the layout already has, not in place of it.
		val emptyPadding = binding.layoutEmpty.paddingLeft
		val barPaddingH = binding.bottomBar.paddingLeft
		val barPaddingBottom = binding.bottomBar.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			binding.appBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
			binding.layoutContent.updatePadding(left = bars.left, right = bars.right)
			binding.layoutEmpty.updatePadding(left = emptyPadding + bars.left, right = emptyPadding + bars.right)
			binding.bottomBar.updatePadding(
				left = barPaddingH + bars.left,
				right = barPaddingH + bars.right,
				bottom = barPaddingBottom + bars.bottom,
			)
			WindowInsetsCompat.CONSUMED
		}

		observe(combine(viewModel.preview, viewModel.content, ::Pair)) { (preview, content) -> onContentChanged(preview, content) }
		observe(viewModel.onChooseList, ::showListChooser)
		observe(viewModel.onMessage, ::showMessage)
		observe(viewModel.onError, ::showError)

		if (savedInstanceState == null) {
			handleShare(intent)
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleShare(intent)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putParcelable(STATE_CAPTURE, pendingCapture)
	}

	/** An image shared from another app - a screenshot, a photo in a chat - read straight away. */
	private fun handleShare(intent: Intent?) {
		if (intent?.action != Intent.ACTION_SEND) {
			return
		}
		IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(viewModel::open)
	}

	private fun takePhoto() {
		// A new file every time: the last photo's file may still be the one on screen, and a camera
		// app that is cancelled can leave a half-written file behind.
		val dir = File(cacheDir, CAPTURE_DIR).apply { mkdirs() }
		dir.listFiles()?.forEach { it.delete() }
		val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
		val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
		pendingCapture = uri
		try {
			takePicture.launch(uri)
		} catch (_: ActivityNotFoundException) {
			pendingCapture = null
			Snackbar.make(binding.root, R.string.error_no_camera, Snackbar.LENGTH_LONG)
				.setAnchorView(binding.bottomBar)
				.show()
		}
	}

	private fun onContentChanged(preview: PhotoPreview?, content: OcrContent) {
		val blocks = content.items.map { it.block }
		val isNewPhoto = blocks != shownBlocks && blocks.isNotEmpty() && shownBlocks.isEmpty()
		shownBlocks = blocks
		binding.photo.bind(preview, blocks)
		blockAdapter.submitList(content.items) {
			if (isNewPhoto) {
				binding.recyclerView.scrollToPosition(0)
			}
		}
		val hasPhoto = preview != null || content.status != OcrStatus.Idle
		binding.layoutEmpty.isVisible = !hasPhoto
		binding.layoutContent.isVisible = hasPhoto
		bindStatus(content.status)
		val menu = binding.toolbar.menu
		val hasTranslations = content.items.any { it.translation != TranslationState.Disabled }
		menu.findItem(R.id.action_reveal_all).apply {
			isVisible = hasTranslations && content.isRevealable
			setIcon(if (content.isAllRevealed) R.drawable.ic_eye_off else R.drawable.ic_eye)
			setTitle(if (content.isAllRevealed) R.string.ocr_hide_all else R.string.ocr_reveal_all)
		}
		menu.findItem(R.id.action_reread).isVisible = content.status is OcrStatus.Done
	}

	private fun bindStatus(status: OcrStatus) {
		binding.progressIndicator.isVisible = status is OcrStatus.Working
		binding.buttonRetry.isVisible = status is OcrStatus.Failed
		binding.textViewStatus.text = when (status) {
			OcrStatus.Idle -> null
			is OcrStatus.Working -> getString(status.phase.titleRes())
			is OcrStatus.Failed -> status.error.displayMessage(resources)
			is OcrStatus.Done -> if (status.blockCount == 0) {
				getString(R.string.ocr_no_text)
			} else {
				resources.getQuantityString(R.plurals.ocr_blocks, status.blockCount, status.blockCount)
			}
		}
	}

	private fun OcrStatus.Phase.titleRes(): Int = when (this) {
		OcrStatus.Phase.LOADING_PHOTO -> R.string.ocr_loading_photo
		OcrStatus.Phase.RECOGNIZING -> R.string.ocr_recognizing
		OcrStatus.Phase.ANALYZING -> R.string.ocr_analyzing
	}

	private fun scrollToBlock(blockId: Int) {
		val position = blockAdapter.currentList.indexOfFirst { it.block.id == blockId }
		if (position >= 0) {
			(binding.recyclerView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 0)
		}
	}

	override fun onBlockClick(blockId: Int) = binding.photo.focusBlock(blockId)

	override fun onWordClick(blockId: Int, wordIndex: Int) {
		binding.photo.focusBlock(blockId)
		viewModel.onWordClick(blockId, wordIndex)
	}

	override fun onTranslationClick(blockId: Int) = viewModel.toggleTranslation(blockId)

	override fun onKanjiClick(kanji: String) = viewModel.onKanjiClick(kanji)

	override fun onAddToListClick(word: RenshuuWord) = viewModel.onAddToListClick(word)

	override fun onRetryLookup() = viewModel.retryLookup()

	override fun onOpenSettings() {
		startActivity(Intent(this, SettingsActivity::class.java))
	}

	private fun showListChooser(choice: ListChoice) {
		// A tick on the lists that already have the word, so adding it twice is a choice and not
		// an accident.
		val present = choice.word.inLists.mapTo(HashSet()) { it.key }
		val titles = choice.lists.map { if (it.key in present) "✓ ${it.title}" else it.title }
		MaterialAlertDialogBuilder(this)
			.setTitle(getString(R.string.renshuu_choose_list, choice.word.headword))
			.setSingleChoiceItems(
				titles.toTypedArray(),
				choice.lists.indexOfFirst { it.key == choice.selectedListKey },
			) { dialog, which ->
				dialog.dismiss()
				viewModel.addToList(choice.word, choice.lists[which])
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showMessage(message: OcrMessage) {
		val text = when (message) {
			is OcrMessage.Added -> getString(R.string.renshuu_added, message.word, message.list)
			is OcrMessage.AlreadyInList -> getString(R.string.renshuu_already_in_list, message.word, message.list)
			OcrMessage.NoLists -> getString(R.string.renshuu_no_lists)
		}
		Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT).setAnchorView(binding.bottomBar).show()
	}

	private fun showError(error: Throwable) {
		val snackbar = Snackbar.make(binding.root, error.displayMessage(resources), Snackbar.LENGTH_LONG)
			.setAnchorView(binding.bottomBar)
		if (error is RenshuuAuthException) {
			snackbar.setAction(R.string.settings) { onOpenSettings() }
		}
		snackbar.show()
	}

	private fun <T> observe(flow: Flow<T>, action: (T) -> Unit) {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				flow.collect { action(it) }
			}
		}
	}

	private companion object {

		const val STATE_CAPTURE = "pending_capture"
		const val CAPTURE_DIR = "camera"
	}
}
