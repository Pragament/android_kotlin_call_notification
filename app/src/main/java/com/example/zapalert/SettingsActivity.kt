package com.example.zapalert

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val ringtonePickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    preferenceManager.sharedPreferences?.edit()
                        ?.putString("notification_sound", it.toString())
                        ?.apply()

                    Log.d("Settings", "Saved sound URI: $it")
                    updateSoundSummary()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to save sound",
                        Toast.LENGTH_SHORT).show()
                    Log.e("Settings", "Error saving sound", e)
                }
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)

            findPreference<Preference>("notification_sound")?.setOnPreferenceClickListener {
                RingtonesDialog(requireContext()).show()
                true
            }

            val blockedAreaCodesPref = findPreference<EditTextPreference>("blocked_area_codes")
            blockedAreaCodesPref?.setOnPreferenceChangeListener { _, newValue ->
                updateBlockedAreaCodesSummary(newValue.toString())
                true
            }

            updateSoundSummary()
            updateBlockedAreaCodesSummary(
                preferenceManager.sharedPreferences?.getString("blocked_area_codes", "") ?: ""
            )
        }

        private fun updateBlockedAreaCodesSummary(areaCodes: String) {
            val summary = if (areaCodes.isNotEmpty()) {
                "Blocking: ${areaCodes.replace(",", ", ")}"
            } else {
                "No area codes blocked"
            }
            findPreference<Preference>("blocked_area_codes")?.summary = summary
        }

        private fun updateSoundSummary() {
            val sharedPreferences = preferenceManager.sharedPreferences
            val selectedRingtone = sharedPreferences?.getInt("selected_ringtone", R.raw.alert)
            val ringtoneName = when (selectedRingtone) {
                R.raw.alert -> "Alert Tone 1"
                R.raw.bell -> "Alert Tone 2"
                R.raw.chimes -> "Alert Tone 3"
                else -> "Default Tone"
            }

            findPreference<Preference>("notification_sound")?.summary = ringtoneName
        }
    }
}