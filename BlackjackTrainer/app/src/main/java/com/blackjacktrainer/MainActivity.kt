package com.blackjacktrainer

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import com.blackjacktrainer.databinding.ActivityMainBinding
import com.blackjacktrainer.databinding.ViewHandBinding
import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.BasicStrategy
import com.blackjacktrainer.game.BlackjackGame
import com.blackjacktrainer.game.Card
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

    /** Zählt die Karten, die in diesem Durchlauf neu einfliegen - für die
     *  zeitliche Staffelung, damit sie nacheinander landen. */
    private var dealStagger = 0

    /** Taktgeber für den Dealer-Zug: eine Karte nach der anderen. */
    private val handler = Handler(Looper.getMainLooper())
    private var dealerRunning = false

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
        handler.removeCallbacks(dealerStep)
        dealerRunning = false
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
        btnLive.setOnClickListener { startActivity(Intent(this@MainActivity, LiveActivity::class.java)) }
    }

    /**
     * Spielt den Dealer-Zug im Takt ab, statt ihn in einem Schritt aufzulösen:
     * aufdecken, kurz warten, Karte, warten, Karte ... und erst danach
     * abrechnen. Wird nach jedem render() angestoßen und läuft nur einmal.
     */
    private fun runDealerTurn() {
        if (game.state != GameState.DEALER_TURN || dealerRunning) return
        dealerRunning = true
        handler.postDelayed(dealerStep, FIRST_DEALER_PAUSE)
    }

    private val dealerStep = object : Runnable {
        override fun run() {
            if (game.state != GameState.DEALER_TURN) {
                dealerRunning = false
                return
            }
            if (game.dealerNeedsCard()) {
                game.dealerDrawCard()
                render()
                handler.postDelayed(this, DEALER_CARD_PAUSE)
            } else {
                game.finishRound()
                dealerRunning = false
                render()
            }
        }
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
        val advice = BasicStrategy.insuranceAdvice()
        game.stats.decisions++
        if (!take) {
            game.stats.correctDecisions++
            feedback = true to "Richtig: keine Versicherung."
        } else {
            feedback = false to "Versicherung ist auf Dauer ein Verlustgeschäft."
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

        val advice = game.advice()
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
        dealStagger = 0
        renderShoe()
        // Spielerhände zuerst: beim Austeilen fliegen deine Karten vor denen
        // des Dealers ein, so wie am Tisch.
        renderPlayerHands()
        renderDealer()
        renderStatus()
        renderPanels()
        renderTip()
        renderStats()
        renderGlow()
        runDealerTurn()
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
                val advice = game.advice() ?: return
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
            GameState.INSURANCE -> binding.incInsurance.btnInsuranceNo
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

    private fun renderShoe() {
        val decks = String.format(Locale.GERMAN, "%.1f", game.shoe.decksRemaining)
        binding.shoeLabel.text = if (game.shoe.cutCardReached) "mischen" else decks
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
        // Ohne laufende Runde gibt es kein Blatt zu beschriften
        binding.playerHeader.visibility = visibleIf(game.hands.isNotEmpty())
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

            handBinding.handChips.amount = hand.totalWager
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
        incDealer.root.visibility = visibleIf(game.state == GameState.DEALER_TURN)

        if (game.state == GameState.BETTING) {
            incBetting.betDisplay.text = "Einsatz: ${money.format(game.pendingBet)}"
            incBetting.betChips.amount = game.pendingBet
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

        when (game.state) {
            GameState.BETTING -> {
                binding.tipAction.text = "Einsatz"
                binding.tipReason.text =
                    "Immer gleich viel setzen. Tisch: ${game.rules.describe()}"
            }
            GameState.INSURANCE -> {
                if (!tipRevealed && !prefs.autoTip) {
                    binding.tipAction.text = "Verdeckt"
                    binding.tipReason.text = "Antippen, um die Empfehlung zu sehen."
                } else {
                    binding.tipAction.text = "Nicht versichern"
                    binding.tipReason.text = BasicStrategy.insuranceAdvice().reason
                }
            }
            GameState.PLAYER_TURN -> {
                if (!tipRevealed && !prefs.autoTip) {
                    binding.tipAction.text = "Verdeckt"
                    binding.tipReason.text = "Entscheide selbst - oder lass dir den Tipp zeigen."
                } else {
                    val advice = game.advice()
                    if (advice == null) {
                        binding.tipAction.text = "—"
                        binding.tipReason.text = ""
                    } else {
                        binding.tipAction.text = advice.action.label
                        binding.tipReason.text = advice.reason
                    }
                }
            }
            GameState.DEALER_TURN -> {
                binding.tipAction.text = "Dealer zieht"
                binding.tipReason.text = "Er muss bis 17 ziehen" +
                    if (game.rules.dealerHitsSoft17) " und auch eine Soft 17 noch verbessern." else "."
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
        binding.statsBar.text =
            "Hände ${s.handsPlayed} · Richtig ${s.accuracy} % · Bilanz $net"
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

        if (animate) flyFromShoe(view, dealStagger++)
    }

    /**
     * Lässt eine Karte vom Schlitten an ihren Platz fliegen. Die Zielposition
     * steht erst nach dem Layout fest, deshalb wird der Startpunkt im
     * PreDraw gesetzt - vor dem ersten Zeichnen, die Karte ist also nie
     * an der falschen Stelle zu sehen.
     */
    private fun flyFromShoe(view: View, order: Int) {
        view.alpha = 0f
        view.doOnPreDraw {
            placeAtShoe(view)
            view.animate()
                .translationX(0f)
                .translationY(0f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(order * 85L)
                .setDuration(330)
                .setInterpolator(DecelerateInterpolator(1.6f))
                // Endzustand hart setzen, falls Animationen im System aus sind
                .withEndAction {
                    view.translationX = 0f
                    view.translationY = 0f
                    view.rotation = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.alpha = 1f
                }
                .start()
        }
    }

    /**
     * Versetzt eine bereits einsortierte Karte auf die Position des
     * Schlittens - der Startpunkt des Einflugs. Setzt die Verschiebung
     * vorher zurück, ist also mehrfach aufrufbar.
     */
    internal fun placeAtShoe(view: View) {
        view.translationX = 0f
        view.translationY = 0f

        val shoe = IntArray(2)
        val target = IntArray(2)
        binding.shoeStack.getLocationOnScreen(shoe)
        view.getLocationOnScreen(target)

        view.translationX = (shoe[0] - target[0]).toFloat()
        view.translationY = (shoe[1] - target[1]).toFloat()
        view.rotation = -14f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.alpha = 1f
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // --------------------------------------------------------- Einstellungen

    private fun showSettings() {
        SettingsDialog.show(
            activity = this,
            prefs = prefs,
            onResetStats = {
                game.stats.reset()
                prefs.saveStats(game.stats)
                toast("Statistik zurückgesetzt.")
                render()
            },
            onResetBankroll = {
                game.bankroll = 1000
                prefs.bankroll = 1000
                toast("Guthaben zurückgesetzt.")
                render()
            }
        ) { newRules ->
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

    private companion object {
        /** Pause nach dem Aufdecken der verdeckten Karte. */
        const val FIRST_DEALER_PAUSE = 700L
        /** Pause zwischen zwei Karten des Dealers. */
        const val DEALER_CARD_PAUSE = 850L
    }
}