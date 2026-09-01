package com.blackjacktrainer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
 * Berater für den echten Tisch. Ein einziges Tastenfeld an fester Stelle;
 * wohin ein Druck geht, zeigt das hervorgehobene Feld - Dealer oder eigene
 * Hand. Nach der Dealerkarte springt das Ziel von selbst weiter, antippen
 * wechselt es jederzeit zurück.
 *
 * Gerechnet wird mit derselben Engine wie im Spielmodus, also mit den
 * Tischregeln aus den Einstellungen.
 */
class LiveActivity : AppCompatActivity() {

    /** Wohin der nächste Tastendruck geht. */
    private enum class Target { DEALER, PLAYER }

    private lateinit var binding: ActivityLiveBinding
    private lateinit var prefs: Prefs
    private lateinit var rules: Rules

    private var dealerUp: Card? = null
    private var hand = Hand()
    private var fromSplit = false
    private var target = Target.DEALER

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

        binding.dealerSlotBox.setOnClickListener { target = Target.DEALER; update() }
        binding.playerSlotBox.setOnClickListener { target = Target.PLAYER; update() }

        binding.btnUndoCard.setOnClickListener { undo() }
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

        update()
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

    /** Der Druck landet dort, wo das hervorgehobene Feld ist. */
    private fun onKey(rank: Rank) {
        if (target == Target.DEALER) {
            dealerUp = Card(rank, Suit.KARO)
            // Die Dealerkarte gibt es nur einmal - danach ist die Hand dran.
            target = Target.PLAYER
        } else if (hand.cards.size < 11) {
            hand.add(Card(rank, suitFor(hand.cards.size)))
        }
        update()
    }

    private fun undo() {
        if (target == Target.DEALER) {
            dealerUp = null
        } else if (hand.cards.isNotEmpty()) {
            hand.cards.removeAt(hand.cards.size - 1)
        } else {
            // Die Hand ist schon leer, also zurück zur Dealerkarte
            target = Target.DEALER
            dealerUp = null
        }
        update()
    }

    /**
     * Leert den Tisch komplett - auch die Dealerkarte - und stellt die
     * Eingabe wieder auf den Dealer. Jede neue Hand beginnt also gleich.
     */
    private fun newRound() {
        hand = Hand()
        dealerUp = null
        fromSplit = false
        target = Target.DEALER
        binding.swFromSplit.isChecked = false
        update()
    }

    private fun buildKeypad() {
        val rows = listOf(binding.keypadRow1, binding.keypadRow2)
        for ((index, rank) in keypadRanks.withIndex()) {
            val row = rows[index / 5]
            val button = AppCompatButton(this)
            button.text = rank.label
            button.textSize = 21f
            button.isAllCaps = false
            button.setPadding(0, 0, 0, 0)
            button.minWidth = 0
            button.minimumWidth = 0
            button.stateListAnimator = null
            button.setBackgroundResource(R.drawable.btn_light)
            button.setTextColor(ContextCompat.getColor(this, R.color.btn_action_text))
            val params = LinearLayout.LayoutParams(0, dp(62), 1f)
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
        renderTarget()
        renderTable()
        renderCount()
        renderAdvice()
    }

    /** Das Eingabeziel muss auf einen Blick erkennbar sein. */
    private fun renderTarget() {
        val dealerActive = target == Target.DEALER
        binding.dealerSlotBox.setBackgroundResource(
            if (dealerActive) R.drawable.hand_active else R.drawable.hand_idle
        )
        binding.playerSlotBox.setBackgroundResource(
            if (dealerActive) R.drawable.hand_idle else R.drawable.hand_active
        )

        val gold = ContextCompat.getColor(this, R.color.gold)
        val muted = ContextCompat.getColor(this, R.color.text_muted)
        binding.dealerSlotLabel.text = if (dealerActive) "DEALER ▸ EINGABE" else "DEALER"
        binding.dealerSlotLabel.setTextColor(if (dealerActive) gold else muted)
        binding.playerSlotLabel.text = if (dealerActive) "DEINE HAND" else "DEINE HAND ▸ EINGABE"
        binding.playerSlotLabel.setTextColor(if (dealerActive) muted else gold)

        binding.keypadPrompt.text = when {
            dealerActive -> "Karte des Dealers eintippen"
            hand.cards.isEmpty() -> "Deine erste Karte eintippen"
            hand.cards.size == 1 -> "Deine zweite Karte eintippen"
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

    // ---------------------------------------------------------- Entscheidung

    /**
     * Klartext statt Fachbegriff und dazu die Farbe aus der Strategietabelle -
     * die Entscheidung soll man aus einem Meter Entfernung erkennen.
     */
    private fun bannerFor(action: Action): Pair<String, Int> = when (action) {
        Action.HIT -> "KARTE NEHMEN" to Color.parseColor("#C0392B")
        Action.STAND -> "STEHEN BLEIBEN" to Color.parseColor("#1E8449")
        Action.DOUBLE -> "VERDOPPELN" to Color.parseColor("#1F6FB2")
        Action.SPLIT -> "TEILEN" to Color.parseColor("#7D3C98")
        Action.SURRENDER -> "AUFGEBEN" to Color.parseColor("#5D6D7E")
    }

    private fun renderAdvice() {
        binding.adviceCountNote.visibility = View.GONE
        binding.adviceInsurance.visibility = View.GONE

        val up = dealerUp
        if (up == null) {
            setBanner("—", "Tippe die offene Karte des Dealers ein.", NEUTRAL)
            return
        }
        if (hand.cards.size < 2) {
            setBanner("—", "Tippe deine beiden Karten ein.", NEUTRAL)
            showInsuranceNote(up)
            return
        }
        if (hand.isBusted) {
            setBanner("ÜBERKAUFT", "Mit ${hand.total} ist die Hand verloren.", BUSTED)
            return
        }
        if (hand.cards.size == 2 && hand.total == 21 && !fromSplit) {
            val payout = if (rules.blackjackPays3to2) "3:2" else "6:5"
            setBanner(
                "BLACKJACK",
                "Zahlt $payout. Nimm kein Even Money, auch wenn der Dealer ein Ass zeigt.",
                BLACKJACK
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

        val (label, color) = bannerFor(advice.action)
        setBanner(label, advice.reason, color)
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

    private fun setBanner(action: String, reason: String, color: Int) {
        binding.adviceAction.text = action
        binding.adviceReason.text = reason
        val background = GradientDrawable()
        background.cornerRadius = dp(16).toFloat()
        background.setColor(color)
        // Helle Kante, damit sich auch die grüne Tafel vom Filz abhebt
        background.setStroke(dp(2), Color.parseColor("#59FFFFFF"))
        binding.advicePanel.background = background
    }

    private companion object {
        val NEUTRAL: Int = Color.parseColor("#1E4430")
        val BUSTED: Int = Color.parseColor("#7A1F1F")
        val BLACKJACK: Int = Color.parseColor("#B8890F")
    }
}
