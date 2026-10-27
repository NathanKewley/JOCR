package io.github.nathankewley.jocr

import io.github.nathankewley.jocr.ocr.ModelStore
import io.github.nathankewley.jocr.ocr.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Choosing a text recogniser: downloads its models if it needs them, and makes it the one photos are
 * read with once they are in place - not before, so a failed download leaves the last choice working.
 * Lives as long as the app, so a download carries on while the screen that started it is rotated or
 * left for another.
 */
class EngineSetup(
	private val models: ModelStore,
	private val settings: Settings,
	private val scope: CoroutineScope,
) {

	sealed interface State {

		data object Idle : State

		data class Downloading(val engine: OcrEngine, val done: Long, val total: Long) : State

		data class Failed(val engine: OcrEngine, val error: Throwable) : State
	}

	private val _state = MutableStateFlow<State>(State.Idle)
	val state: StateFlow<State> = _state.asStateFlow()

	private var job: Job? = null

	/** Whether a recogniser still has to be chosen: on the first run, or when its models are gone. */
	val isNeeded: Boolean
		get() = settings.ocrEngine?.let(models::isReady) != true

	fun choose(engine: OcrEngine) {
		job?.cancel()
		if (models.isReady(engine)) {
			settings.ocrEngine = engine
			_state.value = State.Idle
			return
		}
		_state.value = State.Downloading(engine, 0, 0)
		job = scope.launch {
			val self = coroutineContext.job
			try {
				models.download(engine) { done, total ->
					// Progress still on its way from a download that was just cancelled is not news.
					if (self.isActive) {
						_state.value = State.Downloading(engine, done, total)
					}
				}
				settings.ocrEngine = engine
				_state.value = State.Idle
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				_state.value = State.Failed(engine, e)
			}
		}
	}

	/** Stops the download. What is already downloaded is kept, and picked up from next time. */
	fun cancel() {
		job?.cancel()
		job = null
		_state.value = State.Idle
	}

	fun dismissError() {
		if (_state.value is State.Failed) {
			_state.value = State.Idle
		}
	}
}
