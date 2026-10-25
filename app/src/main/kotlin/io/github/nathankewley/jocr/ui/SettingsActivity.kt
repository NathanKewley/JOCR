package io.github.nathankewley.jocr.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.nathankewley.jocr.JocrApp
import io.github.nathankewley.jocr.R
import io.github.nathankewley.jocr.Settings
import io.github.nathankewley.jocr.databinding.ActivitySettingsBinding
import io.github.nathankewley.jocr.renshuu.RenshuuAuthException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		val binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.toolbar.setNavigationOnClickListener { finish() }
		ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			binding.appBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
			binding.container.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
			WindowInsetsCompat.CONSUMED
		}
		if (savedInstanceState == null) {
			supportFragmentManager.beginTransaction()
				.replace(R.id.container, SettingsFragment())
				.commit()
		}
	}

	class SettingsFragment : PreferenceFragmentCompat() {

		private val app get() = requireActivity().application as JocrApp
		private var checkJob: Job? = null

		override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
			setPreferencesFromResource(R.xml.preferences, rootKey)
			val apiKey = checkNotNull(findPreference<EditTextPreference>(Settings.KEY_RENSHUU_API_KEY))
			apiKey.setOnBindEditTextListener {
				it.setHint(R.string.renshuu_api_key_hint)
				it.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
				it.isSingleLine = true
			}
			apiKey.setOnPreferenceChangeListener { _, newValue ->
				// Another account's lists and presence must not be offered for this one.
				app.renshuu.clearCache()
				checkApiKey((newValue as? String)?.trim().orEmpty())
				true
			}
			checkApiKey(app.settings.renshuuApiKey.orEmpty())
		}

		override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
			KEY_OPEN_RENSHUU -> {
				startActivity(Intent(Intent.ACTION_VIEW, RENSHUU_URL.toUri()))
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}

		/** Says in the setting's summary whether renshuu accepts the key, and whose account it is. */
		private fun checkApiKey(key: String) {
			val pref = findPreference<EditTextPreference>(Settings.KEY_RENSHUU_API_KEY) ?: return
			checkJob?.cancel()
			if (key.isEmpty()) {
				pref.setSummary(R.string.renshuu_api_key_summary)
				return
			}
			pref.setSummary(R.string.renshuu_api_key_checking)
			checkJob = lifecycleScope.launch {
				pref.summary = runCatchingCancellable { app.renshuu.checkApiKey(key) }.fold(
					onSuccess = { name -> getString(R.string.renshuu_api_key_valid, name.ifEmpty { "renshuu" }) },
					onFailure = { error ->
						if (error is RenshuuAuthException) {
							getString(R.string.renshuu_api_key_invalid)
						} else {
							error.displayMessage(resources)
						}
					},
				)
			}
		}
	}

	private companion object {

		const val KEY_OPEN_RENSHUU = "renshuu_open"
		const val RENSHUU_URL = "https://www.renshuu.org/"
	}
}
