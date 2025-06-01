package com.example.zapalert

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.preference.PreferenceManager

class RingtonesDialog(private val context: Context) {

    private val ringtones = listOf(
        R.raw.alert to "Alert",
        R.raw.bell to "Bell",
        R.raw.chimes to "Chimes"
    )

    fun show() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val currentSelection = sharedPreferences.getInt("selected_ringtone", R.raw.alert)

        val dialogView = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL

            ringtones.forEach { (resId, title) ->
                addView(RadioButton(context).apply {
                    text = title
                    tag = resId
                    id = resId
                    isChecked = resId == currentSelection
                })
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Select Ringtone")
            .setView(dialogView)
            .setPositiveButton("OK") { _: DialogInterface, _: Int ->
                val selectedId = dialogView.checkedRadioButtonId
                sharedPreferences.edit()
                    .putInt("selected_ringtone", selectedId)
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}