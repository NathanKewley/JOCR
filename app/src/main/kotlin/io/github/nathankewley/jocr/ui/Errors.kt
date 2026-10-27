package io.github.nathankewley.jocr.ui

import android.content.res.Resources
import io.github.nathankewley.jocr.R
import io.github.nathankewley.jocr.ocr.ModelCorruptException
import io.github.nathankewley.jocr.ocr.ModelsMissingException
import io.github.nathankewley.jocr.renshuu.RenshuuApiException
import io.github.nathankewley.jocr.renshuu.RenshuuAuthException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Something short enough for a snackbar that says what went wrong in the user's terms. */
fun Throwable.displayMessage(res: Resources): String = when (this) {
	is UnknownHostException -> res.getString(R.string.error_no_connection)
	is SocketTimeoutException -> res.getString(R.string.error_timeout)
	// The network gone mid-download: aeroplane mode, or out of Wi-Fi range.
	is SocketException -> res.getString(R.string.error_connection_lost)
	is RenshuuAuthException -> res.getString(R.string.renshuu_api_key_rejected)
	is RenshuuApiException -> message ?: res.getString(R.string.error_renshuu, code)
	is ModelsMissingException -> res.getString(R.string.error_models_missing)
	is ModelCorruptException -> res.getString(R.string.error_model_corrupt)
	// A photo that was deleted, or a share whose permission has lapsed.
	is FileNotFoundException, is SecurityException -> res.getString(R.string.error_photo_unreadable)
	is OutOfMemoryError -> res.getString(R.string.error_photo_too_big)
	is IOException -> localizedMessage ?: res.getString(R.string.error_photo_unreadable)
	else -> localizedMessage ?: javaClass.simpleName
}
