package com.blackjacktrainer

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blackjacktrainer.databinding.ActivityMainBinding
import com.blackjacktrainer.databinding.DialogSettingsBinding
import com.blackjacktrainer.databinding.ViewHandBinding
import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.BlackjackGame
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.CountStrategy
import com.blackjacktrainer.game.GameState
import com.blackjacktrainer.game.Hand
import com.blackjacktrainer.ui.PlayingCardView
import java.text.NumberFormat
import java.util.IdentityHashMap
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var game: BlackjackGame

    /** Wie viele Karten einer Hand schon animiert dargestellt wurden. */
    private val shownCards = IdentityHashMap<Hand, Int>()
    private var shownDealerCards = 0

    private var tipRevealed = false
    private var feedback: Pair<Boolean, String>? = null

    /** Puls des hervorgehobenen Buttons. */
    private var glowAnimator: ValueAnimator? = null

    private val money: NumberFormat = NumberFormat.getIntegerInstance(Locale.GERMAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        game = BlackjackGame(prefs.rules(), prefs.bankroll)
        prefs.loadStats(game.stats)
        game.setBet(prefs.lastBet.coerceAtMost(game.bankroll))

        wireButtons()
        tipRevealed = prefs.autoTip
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        stopGlow()
        prefs.bankroll = game.bankroll
        prefs.saveStats(game.stats)
    }

    // ------------------------------------------------------------- Bedienung

    private fun wireButtons() = with(binding) {
        incBetting.chip5.setOnClickListener { addChip(5) }
        incBetting.chip25.setOnClickListener { addChip(25) }
        incBetting.chip100.setOnClickListener { addChip(100) }
        incBetting.chip500.setOnClickListener { addChip(500) }
        incBetting.btnClearBet.setOnClickListener { game.clearBet(); render() }
        incBetting.btnRepeatBet.setOnClickListener {
            game.setBet(prefs.lastBet.coerceAtMost(game.bankroll))
            render()
        }
        incBetting.btnDeal.setOnClickListener { startRound() }

        incActions.btnHit.setOnClickListener { playerAction(Action.HIT) }
        incActions.btnStand.setOnClickListener { playerAction(Action.STAND) }
        incActions.btnDouble.setOnClickListener { playerAction(Action.DOUBLE) }
        incActions.btnSplit.setOnClickListener { playerAction(Action.SPLIT) }
        incActions.btnSurrender.setOnClickListener { playerAction(Action.SURRENDER) }

        incInsurance.btnInsuranceYes.setOnClickListener { decideInsurance(true) }
        incInsurance.btnInsuranceNo.setOnClickListener { decideInsurance(false) }

        incRoundOver.btnNextRound.setOnClickListener {
            game.nextRound()
            shownCards.clear()
            shownDealerCards = 0
            feedback = null
            tipRevealed = prefs.autoTip
            render()
            if (game.bankroll < 5) offerRefill()
        }

        btnRevealTip.setOnClickListener { tipRevealed = true; render() }
        btnSettings.setOnClickListener { showSettings() }
        btnChart.setOnClickListener { startActivity(Intent(this@MainActivity, StrategyActivity::class.java)) }
    }

    private fun addChip(amount: Int) {
        if (!game.addChip(amount)) {
            toast("Dafür reicht dein Guthaben nicht.")
            return
        }
        render()
    }

    private fun startRound() {
        if (game.pendingBet <= 0) {
            toast("Bitte zuerst einen Einsatz setzen.")
            return
        }
        prefs.lastBet = game.pendingBet
        shownCards.clear()
        shownDealerCards = 0
        feedback = null
        tipRevealed = prefs.autoTip
        if (!game.deal()) return
        if (game.justShuffled) toast("Neuer Schlitten - die Karten wurden gemischt.")
        render()
    }

    private fun decideInsurance(take: Boolean) {
        val advice = CountStrategy.insuranceAdvice(
            if (prefs.counting) game.shoe.trueCount else null,
            prefs.counting
        )
        val recommendTake = advice.action == Action.STAND
        game.stats.decisions++
        if (take == recommendTake) {
            game.stats.correctDecisions++
            feedback = true to if (take) "Versicherung genommen - hier korrekt." else "Richtig: keine Versicherung."
        } else {
            feedback = false to if (recommendTake) "Hier wäre die Versicherung korrekt gewesen." else "Versicherung ist auf Dauer ein Verlustgeschäft."
            if (prefs.warnOnMistake) toast(advice.reason)
        }
        game.resolveInsurance(take)
        tipRevealed = prefs.autoTip
        render()
    }

    private fun playerAction(action: Action) {
        if (game.state != GameState.PLAYER_TURN) return
        val opts = game.options()
        val allowed = when (action) {
            Action.HIT -> opts.canHit
            Action.STAND -> opts.canStand
            Action.DOUBLE -> opts.canDouble
            Action.SPLIT -> opts.canSplit
            Action.SURRENDER -> opts.canSurrender
        }
        if (!allowed) return

        val advice = game.advice(prefs.counting)
        if (advice != null) {
            game.recordDecision(action, advice.action)
            feedback = if (action == advice.action) {
                true to "Richtig: ${action.label}"
            } else {
                false to "Besser wäre: ${advice.action.label}"
            }
            if (action != advice.action && prefs.warnOnMistake) {
                toast("Besser: ${advice.action.label}\n\n${advice.reason}")
            }
        }

        when (action) {
            Action.HIT -> game.hit()
            Action.STAND -> game.stand()
            Action.DOUBLE -> game.double()
            Action.SPLIT -> game.split()
            Action.SURRENDER -> game.surrender()
        }
        tipRevealed = prefs.autoTip
        render()
    }

    // --------------------------------------------------------------- Anzeige

    private fun render() {
        binding.bankrollValue.text = money.format(game.bankroll)
        renderCount()
        renderDealer()
        renderPlayerHands()
        renderStatus()
        renderPanels()
        renderTip()
        renderStats()
        renderGlow()
    }

    // ------------------------------------------------------------ Hervorheben

    private fun actionButtons(): List<Button> = with(binding.incActions) {
        listOf(btnHit, btnStand, btnDouble, btnSplit, btnSurrender)
    }

    private fun insuranceButtons(): List<Button> = with(binding.incInsurance) {
        listOf(btnInsuranceYes, btnInsuranceNo)
    }

    private fun stopGlow() {
        glowAnimator?.cancel()
        glowAnimator = null
        for (button in actionButtons()) {
            button.setBackgroundResource(R.drawable.btn_light)
            button.scaleX = 1f
            button.scaleY = 1f
        }
        for (button in insuranceButtons()) {
            button.setBackgroundResource(R.drawable.btn_outline)
            button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            button.scaleX = 1f
            button.scaleY = 1f
        }
    }

    /** Lässt den Button der empfohlenen Aktion glühen - aber nur, wenn der
     *  Tipp auch sichtbar ist, sonst wäre die Selbstkontrolle sinnlos. */
    private fun renderGlow() {
        stopGlow()
        if (!prefs.autoTip && !tipRevealed) return

        val target: Button = when (game.state) {
            GameState.PLAYER_TURN -> {
                val advice = game.advice(prefs.counting) ?: return
                with(binding.incActions) {
                    when (advice.action) {
                        Action.HIT -> btnHit
                        Action.STAND -> btnStand
                        Action.DOUBLE -> btnDouble
                        Action.SPLIT -> btnSplit
                        Action.SURRENDER -> btnSurrender
                    }
                }
            }
            GameState.INSURANCE -> {
                val advice = CountStrategy.insuranceAdvice(
                    if (prefs.counting) game.shoe.trueCount else null,
                    prefs.counting
                )
                if (advice.action == Action.STAND) {
                    binding.incInsurance.btnInsuranceYes
                } else {
                    binding.incInsurance.btnInsuranceNo
                }
            }
            else -> return
        }
        if (!target.isEnabled) return

        target.setBackgroundResource(R.drawable.btn_glow)
        if (target in insuranceButtons()) target.setTextColor(Color.parseColor("#0C2A18"))

        glowAnimator = ValueAnimator.ofFloat(1f, 1.04f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                target.scaleX = scale
                target.scaleY = scale
            }
            start()
        }
    }

    private fun renderCount() {
        if (!prefs.counting) {
            binding.countBox.visibility = View.GONE
            return
        }
        binding.countBox.visibility = View.VISIBLE
        val rc = game.shoe.runningCount
        val tc = game.shoe.trueCount
        binding.countValue.text = "${if (rc > 0) "+$rc" else "$rc"} / ${String.format(Locale.GERMAN, "%.1f", tc)}"
        binding.countValue.setTextColor(
            when {
                tc >= 2 -> ContextCompat.getColor(this, R.color.win)
                tc <= -2 -> ContextCompat.getColor(this, R.color.lose)
                else -> Color.WHITE
            }
        )
    }

    private fun cardWidthDp(): Float = when {
        game.hands.size >= 4 -> 38f
        game.hands.size == 3 -> 46f
        game.hands.size == 2 -> 56f
        else -> 66f
    }

    private fun renderDealer() {
        val container = binding.dealerCards
        container.removeAllViews()
        if (game.dealer.cards.isEmpty()) {
            binding.dealerTotal.visibility = View.INVISIBLE
            shownDealerCards = 0
            return
        }
        var index = 0
        for (card in game.dealer.cards) {
            addCard(container, card, false, 66f, animate = index >= shownDealerCards)
            index++
        }
        if (game.holeHidden) {
            addCard(container, null, true, 66f, animate = index >= shownDealerCards)
            index++
        }
        shownDealerCards = index

        binding.dealerTotal.visibility = View.VISIBLE
        binding.dealerTotal.text = if (game.holeHidden) {
            "zeigt ${game.dealer.displayTotal()}"
        } else if (game.dealer.isBusted) {
            "${game.dealer.total} - überkauft"
        } else {
            game.dealer.displayTotal()
        }
    }

    private fun renderPlayerHands() {
        val container = binding.playerHands
        container.removeAllViews()
        val width = cardWidthDp()
        val resultsByHand = game.results.associateBy({ it.hand }, { it })

        game.hands.forEachIndexed { index, hand ->
            val handBinding = ViewHandBinding.inflate(layoutInflater, container, false)
            val isActive = game.state == GameState.PLAYER_TURN && index == game.activeIndex
            handBinding.root.setBackgroundResource(
                if (isActive && game.hands.size > 1) R.drawable.hand_active else R.drawable.hand_idle
            )

            val already = shownCards[hand] ?: 0
            hand.cards.forEachIndexed { cardIndex, card ->
                addCard(handBinding.handCards, card, false, width, animate = cardIndex >= already)
            }
            shownCards[hand] = hand.cards.size

            handBinding.handTotal.text = when {
                hand.isBusted -> "${hand.total} ✗"
                else -> hand.displayTotal()
            }
            handBinding.handTotal.setTextColor(
                if (hand.isBusted) ContextCompat.getColor(this, R.color.lose) else Color.WHITE
            )

            val betText = StringBuilder(money.format(hand.totalWager))
            if (hand.doubled) betText.append(" (verdoppelt)")
            if (hand.surrendered) betText.append(" (aufgegeben)")
            handBinding.handBet.text = betText

            val result = resultsByHand[hand]
            if (result != null) {
                handBinding.handOutcome.visibility = View.VISIBLE
                val net = result.net
                handBinding.handOutcome.text = when {
                    net > 0 -> "${result.outcome.label} +${money.format(net)}"
                    net < 0 -> "${result.outcome.label} ${money.format(net)}"
                    else -> result.outcome.label
                }
                handBinding.handOutcome.setTextColor(
                    ContextCompat.getColor(
                        this,
                        when {
                            net > 0 -> R.color.win
                            net < 0 -> R.color.lose
                            else -> R.color.push
                        }
                    )
                )
            }
            container.addView(handBinding.root)
        }
    }

    private fun renderStatus() {
        val fb = feedback
        if (fb != null && game.state != GameState.ROUND_OVER) {
            binding.statusText.text = (if (fb.first) "✓ " else "✗ ") + fb.second
            binding.statusText.setTextColor(
                ContextCompat.getColor(this, if (fb.first) R.color.win else R.color.lose)
            )
            return
        }
        binding.statusText.text = game.lastMessage
        val color = when {
            game.state != GameState.ROUND_OVER -> Color.WHITE
            game.results.sumOf { it.net } > 0 -> ContextCompat.getColor(this, R.color.win)
            game.results.sumOf { it.net } < 0 -> ContextCompat.getColor(this, R.color.lose)
            else -> ContextCompat.getColor(this, R.color.push)
        }
        binding.statusText.setTextColor(color)
    }

    private fun renderPanels() = with(binding) {
        incBetting.root.visibility = visibleIf(game.state == GameState.BETTING)
        incActions.root.visibility = visibleIf(game.state == GameState.PLAYER_TURN)
        incInsurance.root.visibility = visibleIf(game.state == GameState.INSURANCE)
        incRoundOver.root.visibility = visibleIf(game.state == GameState.ROUND_OVER)

        if (game.state == GameState.BETTING) {
            incBetting.betDisplay.text = "Einsatz: ${money.format(game.pendingBet)}"
            incBetting.btnDeal.isEnabled = game.pendingBet > 0
        }
        if (game.state == GameState.PLAYER_TURN) {
            val opts = game.options()
            incActions.btnHit.isEnabled = opts.canHit
            incActions.btnStand.isEnabled = opts.canStand
            incActions.btnDouble.isEnabled = opts.canDouble
            incActions.btnSplit.isEnabled = opts.canSplit
            incActions.btnSurrender.isEnabled = opts.canSurrender
        }
    }

    private fun renderTip() {
        val showButton = !prefs.autoTip && !tipRevealed &&
            (game.state == GameState.PLAYER_TURN || game.state == GameState.INSURANCE)
        binding.btnRevealTip.visibility = visibleIf(showButton)
        binding.tipCountNote.visibility = View.GONE

        when (game.state) {
            GameState.BETTING -> {
                binding.tipAction.text = "Einsatz"
                binding.tipReason.text = if (prefs.counting) {
                    CountStrategy.betHint(game.shoe.trueCount, 25)
                } else {
                    "Immer gleich viel setzen. Tisch: ${game.rules.describe()}"
                }
            }
            GameState.INSURANCE -> {
                if (!tipRevealed && !prefs.autoTip) {
                    binding.tipAction.text = "Verdeckt"
                    binding.tipReason.text = "Antippen, um die Empfehlung zu sehen."
                } else {
                    val advice = CountStrategy.insuranceAdvice(
                        if (prefs.counting) game.shoe.trueCount else null,
                        prefs.counting
                    )
                    binding.tipAction.text =
                        if (advice.action == Action.STAND) "Versichern" else "Nicht versichern"
                    binding.tipReason.text = advice.reason
                    advice.countNote?.let {
                        binding.tipCountNote.text = it
                        binding.tipCountNote.visibility = View.VISIBLE
                    }
                }
            }
            GameState.PLAYER_TURN -> {
                if (!tipRevealed && !prefs.autoTip) {
                    binding.tipAction.text = "Verdeckt"
                    binding.tipReason.text = "Entscheide selbst - oder lass dir den Tipp zeigen."
                } else {
                    val advice = game.advice(prefs.counting)
                    if (advice == null) {
                        binding.tipAction.text = "—"
                        binding.tipReason.text = ""
                    } else {
                        binding.tipAction.text = advice.action.label
                        binding.tipReason.text = advice.reason
                        advice.countNote?.let {
                            binding.tipCountNote.text = it
                            binding.tipCountNote.visibility = View.VISIBLE
                        }
                    }
                }
            }
            else -> {
                binding.tipAction.text = "Runde beendet"
                binding.tipReason.text = roundSummaryHint()
            }
        }
    }

    private fun roundSummaryHint(): String {
        val dealerText = if (game.dealer.isBusted) {
            "Dealer überkauft mit ${game.dealer.total}."
        } else {
            "Dealer steht auf ${game.dealer.total}."
        }
        val accuracy = game.stats.accuracy
        val extra = when {
            game.stats.decisions < 5 -> "Noch ein paar Runden für deine Trefferquote."
            accuracy >= 95 -> "Trefferquote $accuracy % - Casino-Niveau."
            accuracy >= 85 -> "Trefferquote $accuracy % - die Tabelle oben rechts hilft."
            else -> "Trefferquote $accuracy % - spiel eine Weile mit eingeblendeten Tipps."
        }
        return "$dealerText $extra"
    }

    private fun renderStats() {
        val s = game.stats
        val net = if (s.net >= 0) "+${money.format(s.net)}" else money.format(s.net)
        val decks = String.format(Locale.GERMAN, "%.1f", game.shoe.decksRemaining)
        binding.statsBar.text =
            "Hände ${s.handsPlayed} · Richtig ${s.accuracy} % · Bilanz $net · Schuh $decks Decks"
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun addCard(
        container: LinearLayout,
        card: Card?,
        faceDown: Boolean,
        widthDp: Float,
        animate: Boolean
    ) {
        val view = PlayingCardView(this)
        view.cardWidthDp = widthDp
        view.card = card
        view.faceDown = faceDown
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.marginStart = if (container.childCount == 0) 0 else (2 * resources.displayMetrics.density).toInt()
        container.addView(view, params)

        if (animate) {
            view.alpha = 0f
            view.translationX = -30 * resources.displayMetrics.density
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(220)
                // Endzustand hart setzen, damit die Karte auch dann sichtbar ist,
                // wenn das System Animationen abgeschaltet hat.
                .withEndAction { view.alpha = 1f; view.translationX = 0f }
                .start()
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // --------------------------------------------------------- Einstellungen

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val deckOptions = listOf(1, 2, 4, 6, 8)
        dialogBinding.spinnerDecks.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deckOptions.map { "$it Deck${if (it == 1) "" else "s"}" }
        )
        dialogBinding.spinnerDecks.setSelection(deckOptions.indexOf(prefs.decks).coerceAtLeast(0))
        dialogBinding.swH17.isChecked = prefs.hitsSoft17
        dialogBinding.swBj32.isChecked = prefs.blackjack32
        dialogBinding.swDas.isChecked = prefs.doubleAfterSplit
        dialogBinding.swSurrender.isChecked = prefs.lateSurrender
        dialogBinding.swAutoTip.isChecked = prefs.autoTip
        dialogBinding.swWarn.isChecked = prefs.warnOnMistake
        dialogBinding.swCounting.isChecked = prefs.counting

        val dialog = AlertDialog.Builder(this)
            .setTitle("Einstellungen")
            .setView(dialogBinding.root)
            .setPositiveButton("Fertig", null)
            .create()

        dialogBinding.btnResetStats.setOnClickListener {
            game.stats.reset()
            prefs.saveStats(game.stats)
            toast("Statistik zurückgesetzt.")
            render()
        }
        dialogBinding.btnResetBankroll.setOnClickListener {
            game.bankroll = 1000
            prefs.bankroll = 1000
            toast("Guthaben zurückgesetzt.")
            render()
        }

        dialog.setOnDismissListener {
            prefs.decks = deckOptions[dialogBinding.spinnerDecks.selectedItemPosition]
            prefs.hitsSoft17 = dialogBinding.swH17.isChecked
            prefs.blackjack32 = dialogBinding.swBj32.isChecked
            prefs.doubleAfterSplit = dialogBinding.swDas.isChecked
            prefs.lateSurrender = dialogBinding.swSurrender.isChecked
            prefs.autoTip = dialogBinding.swAutoTip.isChecked
            prefs.warnOnMistake = dialogBinding.swWarn.isChecked
            prefs.counting = dialogBinding.swCounting.isChecked

            val newRules = prefs.rules()
            if (newRules != game.rules) {
                game.applyRules(newRules)
                shownCards.clear()
                shownDealerCards = 0
                feedback = null
                game.setBet(prefs.lastBet.coerceAtMost(game.bankroll))
                toast("Neue Regeln: ${newRules.describe()}")
            }
            tipRevealed = prefs.autoTip
            render()
        }
        dialog.show()
    }

    private fun offerRefill() {
        AlertDialog.Builder(this)
            .setTitle("Guthaben aufgebraucht")
            .setMessage(
                "Das passiert auch mit perfekter Basisstrategie - der Hausvorteil bleibt. " +
                    "Neues Guthaben von 1.000 laden?"
            )
            .setCancelable(false)
            .setPositiveButton("Ja") { _, _ ->
                game.bankroll = 1000
                prefs.bankroll = 1000
                game.setBet(25)
                render()
            }
            .setNegativeButton("Nein", null)
            .show()
    }
}
