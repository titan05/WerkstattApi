package com.blackjacktrainer

import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.blackjacktrainer.databinding.DialogSettingsBinding
import com.blackjacktrainer.game.Rules

/**
 * Der Einstellungs-Dialog wird vom Spieltisch und vom Live-Modus benutzt.
 * Beim Schließen werden die Werte gespeichert und die neuen Regeln gemeldet.
 */
object SettingsDialog {

    private val deckOptions = listOf(1, 2, 4, 6, 8)

    fun show(
        activity: AppCompatActivity,
        prefs: Prefs,
        onResetStats: (() -> Unit)? = null,
        onResetBankroll: (() -> Unit)? = null,
        onClosed: (Rules) -> Unit
    ) {
        val binding = DialogSettingsBinding.inflate(activity.layoutInflater)

        binding.spinnerDecks.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            deckOptions.map { "$it Deck${if (it == 1) "" else "s"}" }
        )
        binding.spinnerDecks.setSelection(deckOptions.indexOf(prefs.decks).coerceAtLeast(0))
        binding.swH17.isChecked = prefs.hitsSoft17
        binding.swBj32.isChecked = prefs.blackjack32
        binding.swDas.isChecked = prefs.doubleAfterSplit
        binding.swSurrender.isChecked = prefs.lateSurrender
        binding.swAutoTip.isChecked = prefs.autoTip
        binding.swWarn.isChecked = prefs.warnOnMistake
        binding.swCounting.isChecked = prefs.counting

        // Im Live-Modus gibt es kein Guthaben und keine Statistik.
        binding.btnResetStats.visibility = if (onResetStats == null) View.GONE else View.VISIBLE
        binding.btnResetBankroll.visibility =
            if (onResetBankroll == null) View.GONE else View.VISIBLE
        onResetStats?.let { action -> binding.btnResetStats.setOnClickListener { action() } }
        onResetBankroll?.let { action -> binding.btnResetBankroll.setOnClickListener { action() } }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Einstellungen")
            .setView(binding.root)
            .setPositiveButton("Fertig", null)
            .create()

        dialog.setOnDismissListener {
            prefs.decks = deckOptions[binding.spinnerDecks.selectedItemPosition]
            prefs.hitsSoft17 = binding.swH17.isChecked
            prefs.blackjack32 = binding.swBj32.isChecked
            prefs.doubleAfterSplit = binding.swDas.isChecked
            prefs.lateSurrender = binding.swSurrender.isChecked
            prefs.autoTip = binding.swAutoTip.isChecked
            prefs.warnOnMistake = binding.swWarn.isChecked
            prefs.counting = binding.swCounting.isChecked
            onClosed(prefs.rules())
        }
        dialog.show()
    }
}
