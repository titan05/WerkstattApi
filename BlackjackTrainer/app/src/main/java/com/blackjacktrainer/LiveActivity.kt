package com.blackjacktrainer

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.blackjacktrainer.databinding.ActivityLiveBinding
import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.Advice
import com.blackjacktrainer.game.BasicStrategy
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.CountStrategy
import com.blackjacktrainer.game.Hand
import com.blackjacktrainer.game.Options
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Suit
import com.blackjacktrainer.ui.PlayingCardView
import java.util.Locale

/**
 * Berater für den echten Tisch. Ein einziges Tastenfeld, das immer an
 * derselben Stelle liegt: Der erste Druck ist die offene Karte des Dealers,
 * die nächsten sind deine eigenen. Drei Antippen bis zur Empfehlung.
 *
 * Gerechnet wird mit derselben Engine wie im Spielmodus, also mit den
 * Tischregeln aus den Einstellungen.
 */
class LiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveBinding
    private lateinit var prefs: Prefs
    private lateinit var rules: Rules

    private var dealerUp: Card? = null
    private var hand = Hand()
    private var fromSplit = false

    private var runningCount = 0
    /** In halben Decks, damit sich in 0,5er-Schritten zählen lässt. */
    private var halfDecks = 12
    private var countVisible = false

    private val keypadRanks = listOf(
        Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX,
        Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.ACE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        rules = prefs.rules()
        halfDecks = rules.numDecks * 2
        countVisible = prefs.counting

        buildKeypad()

        binding.btnUndoCard.setOnClickListener { undo() }
        binding.btnNewHand.setOnClickListener {
            hand = Hand()
            update()
        }
        binding.btnNewRound.setOnClickListener { newRound() }
        binding.swFromSplit.setOnCheckedChangeListener { _, checked ->
            fromSplit = checked
            update()
        }

        binding.btnToggleCount.setOnClickListener {
            countVisible = !countVisible
            update()
        }
        binding.btnCountUp.setOnClickListener { runningCount++; update() }
        binding.btnCountDown.setOnClickListener { runningCount--; update() }
        binding.btnDecksUp.setOnClickListener {
            halfDecks = (halfDecks + 1).coerceAtMost(rules.numDecks * 2)
            update()
        }
        binding.btnDecksDown.setOnClickListener {
            halfDecks = (halfDecks - 1).coerceAtLeast(1)
            update()
        }
        binding.btnNewShoe.setOnClickListener {
            runningCount = 0
            halfDecks = rules.numDecks * 2
            update()
        }

        binding.liveSettings.setOnClickListener {
            SettingsDialog.show(this, prefs) { newRules ->
                rules = newRules
                halfDecks = halfDecks.coerceAtMost(rules.numDecks * 2)
                countVisible = prefs.counting
                update()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val current = prefs.rules()
        if (current != rules) {
            rules = current
            halfDecks = halfDecks.coerceAtMost(rules.numDecks * 2)
        }
        update()
    }

    // -------------------------------------------------------------- Eingabe

    /**
     * Ein Tastendruck bedeutet immer das, was gerade fehlt: erst die Karte
     * des Dealers, danach die eigenen. Kein Umschalten nötig.
     */
    private fun onKey(rank: Rank) {
        if (dealerUp == null) {
            dealerUp = Card(rank, Suit.KARO)
        } else if (hand.cards.size < 11) {
            hand.add(Card(rank, suitFor(hand.cards.size)))
        }
        update()
    }

    private fun undo() {
        if (hand.cards.isNotEmpty()) {
            hand.cards.removeAt(hand.cards.size - 1)
        } else {
            dealerUp = null
        }
        update()
    }

    private fun newRound() {
        hand = Hand()
        dealerUp = null
        fromSplit = false
        binding.swFromSplit.isChecked = false
        update()
    }

    private fun buildKeypad() {
        val rows = listOf(binding.keypadRow1, binding.keypadRow2)
        for ((index, rank) in keypadRanks.withIndex()) {
            val row = rows[index / 5]
            val button = AppCompatButton(this)
            button.text = rank.label
            button.textSize = 20f
            button.isAllCaps = false
            button.setPadding(0, 0, 0, 0)
            button.minWidth = 0
            button.minimumWidth = 0
            button.stateListAnimator = null
            button.setBackgroundResource(R.drawable.btn_light)
            button.setTextColor(ContextCompat.getColor(this, R.color.btn_action_text))
            val params = LinearLayout.LayoutParams(0, dp(58), 1f)
            params.marginStart = if (row.childCount == 0) 0 else dp(6)
            button.layoutParams = params
            button.setOnClickListener { onKey(rank) }
            row.addView(button)
        }
    }

    private fun suitFor(index: Int): Suit = Suit.entries[index % Suit.entries.size]

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------------- Anzeige

    private fun update() {
        binding.liveRules.text = rules.describe()
        renderPrompt()
        renderTable()
        renderCount()
        renderAdvice()
    }

    private fun renderPrompt() {
        binding.keypadPrompt.text = when {
            dealerUp == null -> "Was zeigt der Dealer?"
            hand.cards.isEmpty() -> "Deine erste Karte"
            hand.cards.size == 1 -> "Deine zweite Karte"
            else -> "Gezogene Karte eintippen"
        }
    }

    private fun renderTable() {
        binding.dealerSlot.removeAllViews()
        dealerUp?.let { card ->
            val view = PlayingCardView(this)
            view.cardWidthDp = 58f
            view.card = card
            binding.dealerSlot.addView(view)
        }

        val container = binding.liveCards
        container.removeAllViews()
        for (card in hand.cards) {
            val view = PlayingCardView(this)
            view.cardWidthDp = 58f
            view.card = card
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.marginStart = if (container.childCount == 0) 0 else dp(3)
            container.addView(view, params)
        }

        binding.liveTotal.text = when {
            hand.cards.isEmpty() -> "—"
            hand.isBusted -> "${hand.total} ✗"
            else -> hand.displayTotal()
        }
        binding.liveTotal.setTextColor(
            ContextCompat.getColor(this, if (hand.isBusted) R.color.lose else R.color.text_primary)
        )
    }

    private val trueCount: Double get() = runningCount / (halfDecks / 2.0)

    private fun renderCount() {
        binding.countPanel.visibility = if (countVisible) View.VISIBLE else View.GONE
        binding.countValueLive.text = if (runningCount > 0) "+$runningCount" else "$runningCount"
        binding.decksValue.text = String.format(Locale.GERMAN, "%.1f", halfDecks / 2.0)
        binding.trueCountValue.text = "True Count ${BasicStrategy.fmt(trueCount)}"
    }

    /** Der Count wirkt nur auf die Tipps, wenn er auch geführt wird. */
    private val countingActive: Boolean get() = countVisible

    private fun renderAdvice() {
        binding.adviceCountNote.visibility = View.GONE
        binding.adviceInsurance.visibility = View.GONE

        val up = dealerUp
        if (up == null) {
            setAdvice("—", "Tippe die offene Karte des Dealers ein.")
            return
        }
        if (hand.cards.size < 2) {
            setAdvice("—", "Tippe deine beiden Karten ein.")
            showInsuranceNote(up)
            return
        }
        if (hand.isBusted) {
            setAdvice("Überkauft", "Mit ${hand.total} ist die Hand verloren.")
            return
        }
        if (hand.cards.size == 2 && hand.total == 21 && !fromSplit) {
            val payout = if (rules.blackjackPays3to2) "3:2" else "6:5"
            setAdvice(
                "Blackjack",
                "Zahlt $payout. Nimm kein Even Money, auch wenn der Dealer ein Ass zeigt."
            )
            return
        }

        val options = Options(
            canHit = true,
            canStand = true,
            canDouble = hand.cards.size == 2 &&
                (rules.doubleAnyTwo || hand.total in 9..11) &&
                (!fromSplit || rules.doubleAfterSplit),
            canSplit = hand.cards.size == 2 && hand.isPair,
            canSurrender = rules.lateSurrender && hand.cards.size == 2 && !fromSplit
        )

        var advice: Advice = BasicStrategy.advise(hand, up, rules, options)
        if (countingActive) {
            advice = CountStrategy.apply(advice, hand, up, trueCount, options)
        }

        setAdvice(advice.action.label, advice.reason)
        advice.countNote?.let {
            binding.adviceCountNote.text = it
            binding.adviceCountNote.visibility = View.VISIBLE
        }
        showInsuranceNote(up)
    }

    /** Zeigt der Dealer ein Ass, kommt die Versicherungsfrage vor allem anderen. */
    private fun showInsuranceNote(up: Card) {
        if (up.rank != Rank.ACE) return
        val insurance = CountStrategy.insuranceAdvice(
            if (countingActive) trueCount else null,
            countingActive
        )
        val take = insurance.action == Action.STAND
        binding.adviceInsurance.text =
            "Versicherung: ${if (take) "ja" else "nein"} — ${insurance.reason}"
        binding.adviceInsurance.visibility = View.VISIBLE
    }

    private fun setAdvice(action: String, reason: String) {
        binding.adviceAction.text = action
        binding.adviceReason.text = reason
    }
}
