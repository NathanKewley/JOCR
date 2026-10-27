package io.github.nathankewley.jocr.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The downloaded models, in the app's no-backup files: half a gigabyte that can always be downloaded
 * again has no place in a backup.
 */
class ModelStore(context: Context, private val http: OkHttpClient) {

	private val dir = File(context.noBackupFilesDir, "models")

	fun file(model: ModelFile) = File(dir, model.name)

	fun isReady(engine: OcrEngine) = engine.files.all { file(it).exists() }

	/** How much of [engine]'s models is still to be downloaded, in bytes, counting what was cut off. */
	fun remainingSize(engine: OcrEngine): Long = engine.files.filterNot { file(it).exists() }
		.sumOf { (it.size - partialFile(it).length()).coerceAtLeast(0) }

	private fun partialFile(model: ModelFile) = File(dir, model.name + PARTIAL)

	/**
	 * Downloads what [engine] still needs, reporting bytes done of the total as it goes. A file is only
	 * put in place once its hash checks out, and one cut off - the app closed, the connection lost - is
	 * picked up where it stopped next time.
	 */
	suspend fun download(engine: OcrEngine, onProgress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
		dir.mkdirs()
		val total = engine.files.sumOf { it.size }
		// Files cut off part way report their progress from where they stopped, in fetch.
		var done = engine.files.filter { file(it).exists() }.sumOf { it.size }
		onProgress(total - remainingSize(engine), total)
		for (model in engine.files) {
			if (file(model).exists()) {
				continue
			}
			val before = done
			fetch(model) { onProgress(before + it, total) }
			done += model.size
		}
	}

	private suspend fun fetch(model: ModelFile, onProgress: (Long) -> Unit) {
		val partial = partialFile(model)
		val digest = MessageDigest.getInstance("SHA-256")
		var offset = partial.length()
		if (offset >= model.size) {
			partial.delete()
			offset = 0
		}
		val request = Request.Builder().url(model.url).apply {
			if (offset > 0) {
				header("Range", "bytes=$offset-")
			}
		}.build()
		http.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				throw IOException("HTTP ${response.code}")
			}
			// 206 is the rest of the file; 200, a server that ignored the range, is all of it again.
			val append = response.code == HTTP_PARTIAL && offset > 0
			if (append) {
				partial.inputStream().use { it.copyTo(digest) }
			} else {
				offset = 0
			}
			FileOutputStream(partial, append).use { output ->
				response.body.byteStream().use { input ->
					val buffer = ByteArray(BUFFER_SIZE)
					var read = offset
					while (true) {
						currentCoroutineContext().ensureActive()
						val n = input.read(buffer)
						if (n < 0) {
							break
						}
						digest.update(buffer, 0, n)
						output.write(buffer, 0, n)
						read += n
						onProgress(read)
					}
				}
			}
		}
		val actual = digest.digest().joinToString("") { "%02x".format(it) }
		if (actual != model.sha256) {
			partial.delete()
			throw ModelCorruptException(model.name)
		}
		if (!partial.renameTo(file(model))) {
			throw IOException("Could not save ${model.name}")
		}
	}

	private fun java.io.InputStream.copyTo(digest: MessageDigest) {
		val buffer = ByteArray(BUFFER_SIZE)
		while (true) {
			val n = read(buffer)
			if (n < 0) {
				return
			}
			digest.update(buffer, 0, n)
		}
	}

	private companion object {

		const val PARTIAL = ".part"
		const val HTTP_PARTIAL = 206
		const val BUFFER_SIZE = 1 shl 16
	}
}

/** A model that downloaded but is not the file it should be: cut short, or changed on the server. */
class ModelCorruptException(name: String) : IOException("$name did not download correctly")

/** A photo to read before the chosen recogniser's models are on the phone. */
class ModelsMissingException : IllegalStateException("No text recogniser is ready")
