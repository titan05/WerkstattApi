package com.blackjacktrainer.game

enum class GameState { BETTING, INSURANCE, PLAYER_TURN, DEALER_TURN, ROUND_OVER }

enum class Outcome(val label: String) {
    BLACKJACK("Blackjack!"),
    WIN("Gewonnen"),
    PUSH("Unentschieden"),
    LOSE("Verloren"),
    BUST("Überkauft"),
    SURRENDER("Aufgegeben"),
    DEALER_BLACKJACK("Dealer Blackjack")
}

data class HandResult(val hand: Hand, val outcome: Outcome, val net: Int)

data class Stats(
    var handsPlayed: Int = 0,
    var won: Int = 0,
    var lost: Int = 0,
    var pushed: Int = 0,
    var blackjacks: Int = 0,
    var decisions: Int = 0,
    var correctDecisions: Int = 0,
    var net: Int = 0
) {
    val accuracy: Int get() = if (decisions == 0) 100 else (correctDecisions * 100) / decisions

    fun reset() {
        handsPlayed = 0; won = 0; lost = 0; pushed = 0; blackjacks = 0
        decisions = 0; correctDecisions = 0; net = 0
    }
}

/**
 * Vollständiger Casino-Ablauf: Einsatz -> Austeilen -> Versicherung ->
 * Spielerhände (inkl. Splits) -> Dealer -> Auszahlung.
 */
class BlackjackGame(var rules: Rules, var bankroll: Int = 1000) {

    var shoe = Shoe(rules.numDecks, rules.penetration)
        private set

    val stats = Stats()

    var state: GameState = GameState.BETTING
        private set

    var pendingBet: Int = 0
        private set

    val dealer = Hand()
    private var holeCard: Card? = null
    var holeHidden = true
        private set

    val hands = mutableListOf<Hand>()
    var activeIndex = 0
        private set

    var insuranceBet = 0
        private set
    var insuranceWon = false
        private set

    var results: List<HandResult> = emptyList()
        private set

    var lastMessage: String = "Setze deinen Einsatz."
        private set

    var justShuffled = false
        private set

    val dealerUpcard: Card? get() = dealer.cards.firstOrNull()

    val activeHand: Hand? get() = hands.getOrNull(activeIndex)

    fun applyRules(newRules: Rules) {
        rules = newRules
        shoe.reconfigure(newRules.numDecks, newRules.penetration)
        resetToBetting()
    }

    private fun resetToBetting() {
        hands.clear()
        dealer.cards.clear()
        holeCard = null
        holeHidden = true
        insuranceBet = 0
        insuranceWon = false
        results = emptyList()
        activeIndex = 0
        state = GameState.BETTING
    }

    // ------------------------------------------------------------- Einsätze

    fun addChip(amount: Int): Boolean {
        if (state != GameState.BETTING) return false
        if (pendingBet + amount > bankroll) return false
        pendingBet += amount
        return true
    }

    fun clearBet() {
        if (state == GameState.BETTING) pendingBet = 0
    }

    fun setBet(amount: Int) {
        if (state == GameState.BETTING) pendingBet = amount.coerceIn(0, bankroll)
    }

    // ------------------------------------------------------------- Austeilen

    fun deal(): Boolean {
        if (state != GameState.BETTING || pendingBet <= 0 || pendingBet > bankroll) return false

        justShuffled = false
        if (shoe.cutCardReached) {
            shoe.shuffle()
            justShuffled = true
        }

        resetToBetting()
        bankroll -= pendingBet
        val hand = Hand(pendingBet)
        hands.add(hand)

        hand.add(shoe.deal())
        dealer.add(shoe.deal())          // offene Karte
        hand.add(shoe.deal())
        holeCard = shoe.deal()           // verdeckte Karte
        holeHidden = true

        if (dealerUpcard?.rank == Rank.ACE) {
            state = GameState.INSURANCE
            lastMessage = "Der Dealer zeigt ein Ass - Versicherung?"
            return true
        }
        proceedAfterPeek()
        return true
    }

    /** Der Dealer schaut bei 10 oder Ass unter die verdeckte Karte. */
    private fun dealerHasBlackjack(): Boolean {
        val up = dealerUpcard ?: return false
        val hole = holeCard ?: return false
        if (up.rank.value != 10 && up.rank != Rank.ACE) return false
        return up.rank.value + hole.rank.value == 21
    }

    private fun revealHole() {
        val hole = holeCard ?: return
        if (!holeHidden) return
        dealer.add(hole)
        holeHidden = false
    }

    private fun proceedAfterPeek() {
        if (dealerHasBlackjack()) {
            revealHole()
            settle()
            return
        }
        if (hands[0].isBlackjack) {
            revealHole()
            settle()
            return
        }
        state = GameState.PLAYER_TURN
        lastMessage = "Du bist am Zug."
    }

    // ---------------------------------------------------------- Versicherung

    fun resolveInsurance(take: Boolean) {
        if (state != GameState.INSURANCE) return
        if (take) {
            val amount = hands[0].bet / 2
            if (amount in 1..bankroll) {
                insuranceBet = amount
                bankroll -= amount
            }
        }
        if (dealerHasBlackjack() && insuranceBet > 0) {
            bankroll += insuranceBet * 3 // Einsatz zurück + 2:1
            insuranceWon = true
        }
        proceedAfterPeek()
    }

    // ------------------------------------------------------- Spieleraktionen

    fun options(): Options {
        val hand = activeHand ?: return Options(false, false, false, false, false)
        if (state != GameState.PLAYER_TURN) return Options(false, false, false, false, false)

        val frozenAces = hand.isSplitAces && !rules.hitSplitAces
        val twoCards = hand.cards.size == 2

        val canDouble = twoCards && !frozenAces &&
            bankroll >= hand.bet &&
            (rules.doubleAnyTwo || hand.total in 9..11) &&
            (!hand.fromSplit || rules.doubleAfterSplit)

        val canSplit = twoCards && hand.isPair &&
            hands.size < rules.maxHands &&
            bankroll >= hand.bet &&
            !(hand.isSplitAces && !rules.resplitAces)

        val canSurrender = rules.lateSurrender && twoCards &&
            hands.size == 1 && !hand.fromSplit

        return Options(
            canHit = !frozenAces,
            canStand = true,
            canDouble = canDouble,
            canSplit = canSplit,
            canSurrender = canSurrender
        )
    }

    fun hit() {
        val hand = activeHand ?: return
        if (state != GameState.PLAYER_TURN || !options().canHit) return
        hand.add(shoe.deal())
        if (hand.isBusted || hand.total == 21) advanceHand()
    }

    fun stand() {
        val hand = activeHand ?: return
        if (state != GameState.PLAYER_TURN) return
        hand.stood = true
        advanceHand()
    }

    fun double() {
        val hand = activeHand ?: return
        if (!options().canDouble) return
        bankroll -= hand.bet
        hand.doubled = true
        hand.add(shoe.deal())
        advanceHand()
    }

    fun split() {
        val hand = activeHand ?: return
        if (!options().canSplit) return
        val moved = hand.cards.removeAt(1)
        bankroll -= hand.bet

        val newHand = Hand(hand.bet)
        newHand.add(moved)
        newHand.fromSplit = true
        hand.fromSplit = true

        val splittingAces = hand.cards[0].rank == Rank.ACE
        if (splittingAces) {
            hand.isSplitAces = true
            newHand.isSplitAces = true
        }
        hands.add(activeIndex + 1, newHand)

        // Beide Hände bekommen sofort ihre zweite Karte - so siehst du, worauf
        // du dich einlässt, bevor du die erste Hand spielst.
        hand.add(shoe.deal())
        newHand.add(shoe.deal())

        if (hand.total == 21 || (hand.isSplitAces && !rules.hitSplitAces)) {
            advanceHand()
        }
    }

    fun surrender() {
        val hand = activeHand ?: return
        if (!options().canSurrender) return
        hand.surrendered = true
        advanceHand()
    }

    private fun advanceHand() {
        var i = activeIndex + 1
        while (i < hands.size) {
            // Alle Hände haben schon zwei Karten - split() teilt beiden aus.
            val hand = hands[i]
            val done = hand.isBusted || hand.total == 21 ||
                (hand.isSplitAces && !rules.hitSplitAces)
            if (!done) {
                activeIndex = i
                lastMessage = "Hand ${i + 1} ist am Zug."
                return
            }
            i++
        }
        activeIndex = hands.size - 1
        beginDealerTurn()
    }

    // ----------------------------------------------------------- Dealer-Zug

    /**
     * Der Dealer-Zug läuft schrittweise ab: erst aufdecken, dann Karte für
     * Karte. Die Oberfläche taktet die Schritte, damit man zusehen kann.
     * [playDealerOut] spielt ihn am Stück - für Tests und als Notausgang.
     */
    private fun beginDealerTurn() {
        state = GameState.DEALER_TURN
        revealHole()
        lastMessage = "Der Dealer ist am Zug."
    }

    /** true, solange der Dealer noch eine Karte nehmen muss. */
    fun dealerNeedsCard(): Boolean {
        if (state != GameState.DEALER_TURN) return false
        // Sind alle Spielerhände überkauft oder aufgegeben, zieht er nicht mehr.
        if (hands.none { !it.isBusted && !it.surrendered }) return false
        val total = dealer.total
        return total < 17 || (total == 17 && dealer.isSoft && rules.dealerHitsSoft17)
    }

    fun dealerDrawCard() {
        if (!dealerNeedsCard()) return
        dealer.add(shoe.deal())
    }

    fun finishRound() {
        if (state != GameState.DEALER_TURN) return
        settle()
    }

    fun playDealerOut() {
        while (dealerNeedsCard()) dealerDrawCard()
        finishRound()
    }

    // ---------------------------------------------------------- Auszahlung

    private fun settle() {
        revealHole()
        val dealerBj = dealer.isBlackjack
        val dealerTotal = dealer.total
        val dealerBusted = dealer.isBusted

        val out = mutableListOf<HandResult>()
        for (hand in hands) {
            val wager = hand.totalWager
            var payout: Int
            val outcome: Outcome

            when {
                hand.surrendered -> {
                    payout = hand.bet / 2
                    outcome = Outcome.SURRENDER
                }
                hand.isBlackjack && !dealerBj -> {
                    payout = hand.bet + (hand.bet * rules.blackjackMultiplier).toInt()
                    outcome = Outcome.BLACKJACK
                }
                dealerBj -> {
                    if (hand.isBlackjack) {
                        payout = wager
                        outcome = Outcome.PUSH
                    } else {
                        payout = 0
                        outcome = Outcome.DEALER_BLACKJACK
                    }
                }
                hand.isBusted -> {
                    payout = 0
                    outcome = Outcome.BUST
                }
                dealerBusted || hand.total > dealerTotal -> {
                    payout = wager * 2
                    outcome = Outcome.WIN
                }
                hand.total == dealerTotal -> {
                    payout = wager
                    outcome = Outcome.PUSH
                }
                else -> {
                    payout = 0
                    outcome = Outcome.LOSE
                }
            }

            bankroll += payout
            val net = payout - wager
            out.add(HandResult(hand, outcome, net))

            stats.handsPlayed++
            stats.net += net
            when (outcome) {
                Outcome.BLACKJACK -> { stats.won++; stats.blackjacks++ }
                Outcome.WIN -> stats.won++
                Outcome.PUSH -> stats.pushed++
                else -> stats.lost++
            }
        }

        if (insuranceBet > 0) {
            val insuranceNet = if (insuranceWon) insuranceBet * 2 else -insuranceBet
            stats.net += insuranceNet
        }

        results = out
        state = GameState.ROUND_OVER
        lastMessage = summarise(out)
    }

    private fun summarise(out: List<HandResult>): String {
        val insuranceText = when {
            insuranceBet > 0 && insuranceWon -> " (Versicherung +${insuranceBet * 2})"
            insuranceBet > 0 -> " (Versicherung -$insuranceBet)"
            else -> ""
        }
        if (out.size == 1) {
            val r = out[0]
            val amount = when {
                r.net > 0 -> " +${r.net}"
                r.net < 0 -> " ${r.net}"
                else -> ""
            }
            return "${r.outcome.label}$amount$insuranceText"
        }
        val total = out.sumOf { it.net }
        val sign = if (total >= 0) "+$total" else "$total"
        return "${out.size} Hände: $sign$insuranceText"
    }

    fun nextRound() {
        if (state != GameState.ROUND_OVER) return
        val previous = pendingBet
        resetToBetting()
        pendingBet = previous.coerceAtMost(bankroll)
        lastMessage = if (shoe.cutCardReached) {
            "Cut-Card erreicht - der Schlitten wird neu gemischt."
        } else {
            "Setze deinen Einsatz."
        }
    }

    // ------------------------------------------------------------- Beratung

    /** Empfehlung für die aktuelle Hand. */
    fun advice(): Advice? {
        val hand = activeHand ?: return null
        val up = dealerUpcard ?: return null
        if (state != GameState.PLAYER_TURN) return null
        return BasicStrategy.advise(hand, up, rules, options())
    }

    fun recordDecision(chosen: Action, recommended: Action) {
        stats.decisions++
        if (chosen == recommended) stats.correctDecisions++
    }
}
