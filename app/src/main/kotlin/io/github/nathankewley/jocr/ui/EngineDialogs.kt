package io.github.nathankewley.jocr.ui

import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import io.github.nathankewley.jocr.EngineSetup
import io.github.nathankewley.jocr.R
import io.github.nathankewley.jocr.databinding.DialogDownloadBinding
import io.github.nathankewley.jocr.databinding.DialogEngineChoiceBinding
import io.github.nathankewley.jocr.ocr.ModelStore
import io.github.nathankewley.jocr.ocr.OcrEngine
import java.net.SocketTimeoutException

/**
 * The dialogs for choosing a text recogniser and downloading its models, shared by the first run
 * and settings. The download dialogs follow [EngineSetup.state] through [bind], so they come back as
 * they were when the screen is rotated or returned to.
 */
class EngineDialogs(
	private val activity: AppCompatActivity,
	private val setup: EngineSetup,
	private val models: ModelStore,
) {

	private var chooser: AlertDialog? = null
	private var download: AlertDialog? = null
	private var downloadBinding: DialogDownloadBinding? = null
	private var failure: AlertDialog? = null

	init {
		// A dialog left open when its activity goes leaks its window.
		activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onDestroy(owner: LifecycleOwner) {
				listOfNotNull(chooser, download, failure).forEach { it.dismiss() }
			}
		})
	}

	/**
	 * Offers the recognisers with [current] ticked. On the first run there is no way out but to
	 * choose: a photo cannot be read until one is.
	 */
	fun showChooser(current: OcrEngine?, isFirstRun: Boolean) {
		if (chooser?.isShowing == true) {
			return
		}
		val binding = DialogEngineChoiceBinding.inflate(activity.layoutInflater)
		var selected = current?.takeIf { models.isReady(it) } ?: OcrEngine.PADDLE_MANGA
		for (engine in OcrEngine.entries) {
			binding.groupEngines.addView(
				MaterialRadioButton(activity).apply {
					id = View.generateViewId()
					text = describe(engine)
					isChecked = engine == selected
					val padding = (PADDING_V_DP * resources.displayMetrics.density).toInt()
					setPadding(paddingLeft, padding, paddingRight, padding)
					setOnCheckedChangeListener { _, isChecked ->
						if (isChecked) {
							selected = engine
							chooser?.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(actionFor(engine))
						}
					}
				},
			)
		}
		chooser = MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.engine_choose_title)
			.setView(binding.root)
			.setCancelable(!isFirstRun)
			.setPositiveButton(actionFor(selected)) { _, _ -> setup.choose(selected) }
			.apply {
				if (!isFirstRun) {
					setNegativeButton(android.R.string.cancel, null)
				}
			}
			.show()
	}

	fun bind(state: EngineSetup.State) {
		if (state is EngineSetup.State.Downloading) {
			showDownload(state)
		} else {
			download?.dismiss()
			download = null
			downloadBinding = null
		}
		if (state is EngineSetup.State.Failed) {
			showFailure(state)
		} else {
			failure?.dismiss()
			failure = null
		}
	}

	private fun showDownload(state: EngineSetup.State.Downloading) {
		val binding = downloadBinding ?: DialogDownloadBinding.inflate(activity.layoutInflater).also { binding ->
			downloadBinding = binding
			download = MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getString(R.string.engine_downloading, activity.getString(state.engine.titleRes())))
				.setView(binding.root)
				.setCancelable(false)
				.setNegativeButton(android.R.string.cancel) { _, _ -> setup.cancel() }
				.show()
		}
		if (state.total > 0) {
			binding.progress.isIndeterminate = false
			binding.progress.max = PROGRESS_STEPS
			binding.progress.setProgressCompat((state.done * PROGRESS_STEPS / state.total).toInt(), false)
			binding.textViewProgress.text = activity.getString(
				R.string.engine_download_progress,
				Formatter.formatShortFileSize(activity, state.done),
				Formatter.formatShortFileSize(activity, state.total),
			)
		}
	}

	private fun showFailure(state: EngineSetup.State.Failed) {
		if (failure != null) {
			return
		}
		// The general message for a timeout is about renshuu.
		val reason = if (state.error is SocketTimeoutException) {
			activity.getString(R.string.error_download_timeout)
		} else {
			state.error.displayMessage(activity.resources)
		}
		failure = MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.engine_download_failed)
			.setMessage(activity.getString(R.string.engine_download_failed_message, reason))
			.setCancelable(false)
			.setPositiveButton(R.string.try_again) { _, _ -> setup.choose(state.engine) }
			.setNegativeButton(android.R.string.cancel) { _, _ -> setup.dismissError() }
			.show()
	}

	/** The engine's name, then what it would download and how accurate and fast it is, smaller. */
	private fun describe(engine: OcrEngine): CharSequence {
		val remaining = models.remainingSize(engine)
		val cost = when {
			engine.files.isEmpty() -> activity.getString(R.string.engine_no_download)
			remaining == 0L -> activity.getString(R.string.engine_downloaded)
			else -> activity.getString(R.string.engine_download_size, Formatter.formatShortFileSize(activity, remaining))
		}
		val secondary = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
		return buildSpannedString {
			bold { append(activity.getString(engine.titleRes())) }
			append('\n')
			inSpans(RelativeSizeSpan(SMALL_TEXT), ForegroundColorSpan(secondary)) {
				append(cost)
				append(SEPARATOR)
				append(activity.getString(R.string.engine_accuracy, engine.accuracy()))
				append(SEPARATOR)
				append(activity.getString(R.string.engine_speed, engine.secondsPerPage()))
			}
		}
	}

	private fun actionFor(engine: OcrEngine): Int =
		if (models.isReady(engine)) R.string.engine_use else R.string.engine_download

	/** Per cent of characters read right on the manga test pages, on a Pixel 4 (see OcrEvalTest). */
	private fun OcrEngine.accuracy(): Int = when (this) {
		OcrEngine.PADDLE_MANGA -> 96
		OcrEngine.PADDLE -> 88
		OcrEngine.MLKIT -> 70
	}

	private fun OcrEngine.secondsPerPage(): Int = when (this) {
		OcrEngine.PADDLE_MANGA -> 11
		OcrEngine.PADDLE, OcrEngine.MLKIT -> 2
	}

	private companion object {

		const val PROGRESS_STEPS = 1000
		const val SMALL_TEXT = 0.875f
		const val SEPARATOR = " · "
		const val PADDING_V_DP = 12
	}
}

fun OcrEngine.titleRes(): Int = when (this) {
	OcrEngine.PADDLE_MANGA -> R.string.engine_paddle_manga
	OcrEngine.PADDLE -> R.string.engine_paddle
	OcrEngine.MLKIT -> R.string.engine_mlkit
}
