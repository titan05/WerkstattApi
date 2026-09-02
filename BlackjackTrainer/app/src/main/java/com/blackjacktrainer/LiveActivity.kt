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
import com.blackjacktrainer.databinding.ViewLiveHandBinding
import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.Advice
import com.blackjacktrainer.game.BasicStrategy
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.Hand
import com.blackjacktrainer.game.Options
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Suit
import com.blackjacktrainer.ui.PlayingCardView

/**
 * Berater für den echten Tisch.
 *
 * Eingegeben wird in der Reihenfolge, in der am Tisch ausgeteilt wird: deine
 * erste Karte, dann die offene Karte des Dealers, dann deine zweite. Wohin
 * der nächste Druck geht, zeigt das hervorgehobene Feld; antippen wechselt.
 *
 * "Teilen" macht aus einer Hand zwei. Die Hände stehen dann nebeneinander,
 * Hand 1 rechts - so wie sie am Tisch gespielt werden.
 *
 * Gerechnet wird mit derselben Engine wie im Spielmodus, also mit den
 * Tischregeln aus den Einstellungen.
 */
class LiveActivity : AppCompatActivity() {

    /** Wohin der nächste Tastendruck geht. */
    private enum class Target { DEALER, HAND }

    private lateinit var binding: ActivityLiveBinding
    private lateinit var prefs: Prefs
    private lateinit var rules: Rules

    private var dealerUp: Card? = null
    private val hands = mutableListOf(Hand())
    private var activeHand = 0
    private var target = Target.HAND

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
        buildKeypad()

        binding.dealerSlotBox.setOnClickListener { target = Target.DEALER; update() }

        binding.btnUndoCard.setOnClickListener { undo() }
        binding.btnSplit.setOnClickListener { splitActiveHand() }
        binding.btnNewRound.setOnClickListener { newRound() }

        binding.liveSettings.setOnClickListener {
            SettingsDialog.show(this, prefs) { newRules ->
                rules = newRules
                update()
            }
        }

        update()
    }

    override fun onResume() {
        super.onResume()
        rules = prefs.rules()
        update()
    }

    // -------------------------------------------------------------- Eingabe

    private val hand: Hand get() = hands[activeHand]

    /**
     * Der Druck landet im hervorgehobenen Feld. Nach deiner ersten Karte
     * springt die Eingabe von selbst zum Dealer und danach wieder zurück -
     * die Reihenfolge, in der am Tisch ausgeteilt wird.
     */
    private fun onKey(rank: Rank) {
        if (target == Target.DEALER) {
            dealerUp = Card(rank, Suit.KARO)
            target = Target.HAND
        } else {
            if (hand.cards.size >= 11) return
            hand.add(Card(rank, suitFor(hand.cards.size)))
            if (dealerUp == null && hands.size == 1 && hand.cards.size == 1) {
                target = Target.DEALER
            }
        }
        update()
    }

    private fun undo() {
        if (target == Target.DEALER) {
            dealerUp = null
        } else if (hand.cards.isNotEmpty()) {
            hand.cards.removeAt(hand.cards.size - 1)
        } else if (dealerUp != null) {
            target = Target.DEALER
            dealerUp = null
        }
        update()
    }

    /** Teilen ist möglich, solange die aktive Hand ein Paar aus zwei Karten ist. */
    private fun canSplit(): Boolean =
        hands.size < rules.maxHands && hand.cards.size == 2 && hand.isPair

    /**
     * Macht aus der aktiven Hand zwei. Die neue Hand kommt dahinter, wird
     * also links davon angezeigt; gespielt wird weiter mit der ersten.
     */
    private fun splitActiveHand() {
        if (!canSplit()) return
        val moved = hand.cards.removeAt(1)
        val newHand = Hand()
        newHand.add(moved)
        newHand.fromSplit = true
        hand.fromSplit = true
        hands.add(activeHand + 1, newHand)
        target = Target.HAND
        update()
    }

    private fun newRound() {
        hands.clear()
        hands.add(Hand())
        activeHand = 0
        dealerUp = null
        target = Target.HAND
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
        renderDealer()
        renderHands()
        renderPrompt()
        renderAdvice()
        binding.btnSplit.isEnabled = canSplit()
        binding.btnSplit.alpha = if (canSplit()) 1f else 0.45f
    }

    private fun renderDealer() {
        val dealerActive = target == Target.DEALER
        binding.dealerSlotBox.setBackgroundResource(
            if (dealerActive) R.drawable.hand_active else R.drawable.hand_idle
        )
        binding.dealerSlotLabel.text = if (dealerActive) "DEALER ▸ EINGABE" else "DEALER"
        binding.dealerSlotLabel.setTextColor(
            ContextCompat.getColor(this, if (dealerActive) R.color.gold else R.color.text_muted)
        )

        binding.dealerSlot.removeAllViews()
        dealerUp?.let { card ->
            val view = PlayingCardView(this)
            view.cardWidthDp = 58f
            view.card = card
            binding.dealerSlot.addView(view)
        }
    }

    /**
     * Kartenbreite und Abstand so, dass eine Hand immer in ihre Box passt -
     * ohne Scrollen, denn eine ScrollView würde die Klicks abfangen. Wird der
     * Platz knapp, wird der Abstand negativ und die Karten überlappen sich
     * wie ein aufgefächertes Blatt.
     */
    private fun cardLayout(): Pair<Float, Float> {
        val boxes = hands.size
        val screen = resources.configuration.screenWidthDp.toFloat()
        // Activity-Padding links/rechts, Abstände zwischen den Boxen, Innenabstand
        val perBox = (screen - 20f - (boxes - 1) * 8f) / boxes - 16f
        val maxCards = maxOf(2, hands.maxOf { it.cards.size })

        val width = ((perBox - (maxCards - 1) * 3f) / maxCards).coerceIn(30f, 58f)
        val gap = if (maxCards > 1) {
            ((perBox - maxCards * width) / (maxCards - 1)).coerceIn(-width * 0.55f, 3f)
        } else {
            3f
        }
        return width to gap
    }

    /** Hand 1 steht rechts, deshalb werden die Hände rückwärts eingehängt. */
    private fun renderHands() {
        val row = binding.playerHandsRow
        row.removeAllViews()
        val (width, gap) = cardLayout()

        for (index in hands.indices.reversed()) {
            val handBinding = ViewLiveHandBinding.inflate(layoutInflater, row, false)
            val current = hands[index]
            val isActive = target == Target.HAND && index == activeHand

            handBinding.root.setBackgroundResource(
                if (isActive) R.drawable.hand_active else R.drawable.hand_idle
            )
            val name = if (hands.size == 1) "DEINE HAND" else "HAND ${index + 1}"
            handBinding.liveHandLabel.text = if (isActive) "$name ▸ EINGABE" else name
            handBinding.liveHandLabel.setTextColor(
                ContextCompat.getColor(this, if (isActive) R.color.gold else R.color.text_muted)
            )

            handBinding.liveHandTotal.text = when {
                current.cards.isEmpty() -> "—"
                current.isBusted -> "${current.total} ✗"
                else -> current.displayTotal()
            }
            handBinding.liveHandTotal.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (current.isBusted) R.color.lose else R.color.text_primary
                )
            )

            for (card in current.cards) {
                val view = PlayingCardView(this)
                view.cardWidthDp = width
                view.card = card
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.marginStart =
                    if (handBinding.liveHandCards.childCount == 0) 0 else (gap * resources.displayMetrics.density).toInt()
                handBinding.liveHandCards.addView(view, params)
            }

            handBinding.root.setOnClickListener {
                activeHand = index
                target = Target.HAND
                update()
            }

            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            params.marginStart = if (row.childCount == 0) 0 else dp(8)
            row.addView(handBinding.root, params)
        }
    }

    private fun renderPrompt() {
        binding.keypadPrompt.text = when {
            target == Target.DEALER -> "Karte des Dealers eintippen"
            hands.size > 1 -> "Karte für Hand ${activeHand + 1} eintippen"
            hand.cards.isEmpty() -> "Deine erste Karte eintippen"
            hand.cards.size == 1 -> "Deine zweite Karte eintippen"
            else -> "Gezogene Karte eintippen"
        }
    }

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
        binding.adviceInsurance.visibility = View.GONE

        val up = dealerUp
        val current = hand
        val handName = if (hands.size == 1) "deine Karten" else "die Karten für Hand ${activeHand + 1}"

        if (current.cards.size < 2 && up == null) {
            setBanner("—", "Tippe $handName und die offene Karte des Dealers ein.", NEUTRAL)
            return
        }
        if (up == null) {
            setBanner("—", "Tippe noch die offene Karte des Dealers ein.", NEUTRAL)
            return
        }
        if (current.cards.size < 2) {
            setBanner("—", "Tippe $handName ein.", NEUTRAL)
            showInsuranceNote(up)
            return
        }
        if (current.isBusted) {
            setBanner("ÜBERKAUFT", "Mit ${current.total} ist die Hand verloren.", BUSTED)
            return
        }
        if (current.cards.size == 2 && current.total == 21 && !current.fromSplit) {
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
            canDouble = current.cards.size == 2 &&
                (rules.doubleAnyTwo || current.total in 9..11) &&
                (!current.fromSplit || rules.doubleAfterSplit),
            canSplit = canSplit(),
            canSurrender = rules.lateSurrender && current.cards.size == 2 && !current.fromSplit
        )

        val advice: Advice = BasicStrategy.advise(current, up, rules, options)
        val (label, color) = bannerFor(advice.action)
        setBanner(label, advice.reason, color)
        showInsuranceNote(up)
    }

    /** Zeigt der Dealer ein Ass, kommt die Versicherungsfrage vor allem anderen. */
    private fun showInsuranceNote(up: Card) {
        if (up.rank != Rank.ACE) return
        binding.adviceInsurance.text =
            "Versicherung: nein — ${BasicStrategy.insuranceAdvice().reason}"
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
