package com.blackjacktrainer

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
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

        var decks = prefs.decks
        val deckButtons = mutableListOf<Button>()
        val density = activity.resources.displayMetrics.density
        fun markDecks() {
            for ((index, button) in deckButtons.withIndex()) {
                val selected = deckOptions[index] == decks
                button.setBackgroundResource(
                    if (selected) R.drawable.btn_gold else R.drawable.btn_outline
                )
                button.setTextColor(
                    if (selected) ContextCompat.getColor(activity, R.color.btn_action_text)
                    else ContextCompat.getColor(activity, R.color.text_primary)
                )
            }
        }
        for (option in deckOptions) {
            val button = AppCompatButton(activity)
            button.text = option.toString()
            button.textSize = 15f
            button.isAllCaps = false
            button.setPadding(0, 0, 0, 0)
            button.minWidth = 0
            button.minimumWidth = 0
            button.stateListAnimator = null
            val params = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f)
            if (deckButtons.isNotEmpty()) params.marginStart = (6 * density).toInt()
            button.layoutParams = params
            button.setOnClickListener {
                decks = option
                markDecks()
            }
            binding.deckPicker.addView(button)
            deckButtons.add(button)
        }
        markDecks()
        binding.swH17.isChecked = prefs.hitsSoft17
        binding.swBj32.isChecked = prefs.blackjack32
        binding.swDas.isChecked = prefs.doubleAfterSplit
        binding.swSurrender.isChecked = prefs.lateSurrender
        binding.swAutoTip.isChecked = prefs.autoTip
        binding.swWarn.isChecked = prefs.warnOnMistake

        // Im Live-Modus gibt es kein Guthaben und keine Statistik.
        binding.btnResetStats.visibility = if (onResetStats == null) View.GONE else View.VISIBLE
        binding.btnResetBankroll.visibility =
            if (onResetBankroll == null) View.GONE else View.VISIBLE
        onResetStats?.let { action -> binding.btnResetStats.setOnClickListener { action() } }
        onResetBankroll?.let { action -> binding.btnResetBankroll.setOnClickListener { action() } }

        val dialog = AlertDialog.Builder(activity, R.style.Theme_BlackjackTrainer_Dialog)
            .setTitle("Einstellungen")
            .setView(binding.root)
            .setPositiveButton("Fertig", null)
            .create()

        dialog.setOnDismissListener {
            prefs.decks = decks
            prefs.hitsSoft17 = binding.swH17.isChecked
            prefs.blackjack32 = binding.swBj32.isChecked
            prefs.doubleAfterSplit = binding.swDas.isChecked
            prefs.lateSurrender = binding.swSurrender.isChecked
            prefs.autoTip = binding.swAutoTip.isChecked
            prefs.warnOnMistake = binding.swWarn.isChecked
            onClosed(prefs.rules())
        }
        dialog.show()
    }
}
