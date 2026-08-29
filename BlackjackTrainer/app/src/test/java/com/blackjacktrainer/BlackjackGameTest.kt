package com.blackjacktrainer

import com.blackjacktrainer.game.BlackjackGame
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.GameState
import com.blackjacktrainer.game.Outcome
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft den Spielablauf und die Auszahlungen mit vorgelegten Karten. */
class BlackjackGameTest {

    private val h17 = Rules(numDecks = 6, dealerHitsSoft17 = true, lateSurrender = true)
    private val s17 = h17.copy(dealerHitsSoft17 = false)

    private fun c(rank: Rank) = Card(rank, Suit.PIK)

    /** Reihenfolge beim Austeilen: Spieler, Dealer offen, Spieler, Dealer verdeckt. */
    private fun game(rules: Rules = h17, bankroll: Int = 1000, bet: Int = 100, stack: List<Card>): BlackjackGame {
        val game = BlackjackGame(rules, bankroll)
        game.shoe.stackTop(stack)
        game.setBet(bet)
        game.deal()
        return game
    }

    @Test
    fun blackjackZahltDreiTzuZwei() {
        val game = game(stack = listOf(c(Rank.ACE), c(Rank.NINE), c(Rank.KING), c(Rank.FIVE)))
        assertEquals(GameState.ROUND_OVER, game.state)
        assertEquals(Outcome.BLACKJACK, game.results[0].outcome)
        assertEquals(150, game.results[0].net)
        assertEquals(1150, game.bankroll)
    }

    @Test
    fun blackjackZahltSechsZuFuenfWennEingestellt() {
        val game = game(
            rules = h17.copy(blackjackPays3to2 = false),
            stack = listOf(c(Rank.ACE), c(Rank.NINE), c(Rank.KING), c(Rank.FIVE))
        )
        assertEquals(120, game.results[0].net)
    }

    @Test
    fun gleicherWertIstUnentschieden() {
        val game = game(stack = listOf(c(Rank.KING), c(Rank.NINE), c(Rank.NINE), c(Rank.TEN)))
        game.stand()
        assertEquals(Outcome.PUSH, game.results[0].outcome)
        assertEquals(1000, game.bankroll)
    }

    @Test
    fun dealerZiehtBeiSoftSiebzehnNurImH17Spiel() {
        val stack = listOf(c(Rank.TEN), c(Rank.SIX), c(Rank.KING), c(Rank.ACE), c(Rank.THREE))
        val hitting = game(rules = h17, stack = stack)
        hitting.stand()
        assertEquals(20, hitting.dealer.total)
        assertEquals(Outcome.PUSH, hitting.results[0].outcome)

        val standing = game(rules = s17, stack = stack)
        standing.stand()
        assertEquals(17, standing.dealer.total)
        assertEquals(Outcome.WIN, standing.results[0].outcome)
        assertEquals(1100, standing.bankroll)
    }

    @Test
    fun aufgebenKostetDenHalbenEinsatz() {
        val game = game(stack = listOf(c(Rank.NINE), c(Rank.TEN), c(Rank.SEVEN), c(Rank.FIVE)))
        assertTrue(game.options().canSurrender)
        game.surrender()
        assertEquals(Outcome.SURRENDER, game.results[0].outcome)
        assertEquals(-50, game.results[0].net)
        assertEquals(950, game.bankroll)
    }

    @Test
    fun ueberkaufenVerliertSofortOhneDassDerDealerZieht() {
        val game = game(stack = listOf(c(Rank.TEN), c(Rank.FIVE), c(Rank.SIX), c(Rank.TWO), c(Rank.KING)))
        game.hit() // 10 + 6 + K = 26
        assertEquals(Outcome.BUST, game.results[0].outcome)
        assertEquals(900, game.bankroll)
        // Dealer deckt auf, zieht aber nicht mehr nach
        assertEquals(2, game.dealer.cards.size)
    }

    @Test
    fun teilenErzeugtZweiHaendeUndZiehtDenEinsatzAb() {
        val game = game(
            stack = listOf(
                c(Rank.EIGHT), c(Rank.SIX), c(Rank.EIGHT), c(Rank.TEN),
                c(Rank.THREE), c(Rank.TWO)
            )
        )
        assertTrue(game.options().canSplit)
        game.split()
        assertEquals(2, game.hands.size)
        assertEquals(800, game.bankroll)
        assertEquals(11, game.hands[0].total) // 8 + 3
        assertTrue(game.hands[0].fromSplit)
    }

    @Test
    fun geteilteAsseBekommenNurEineKarte() {
        val game = game(
            stack = listOf(
                c(Rank.ACE), c(Rank.SIX), c(Rank.ACE), c(Rank.TEN),
                c(Rank.KING), c(Rank.QUEEN), c(Rank.FIVE)
            )
        )
        game.split()
        // Beide Hände sind sofort fertig -> der Dealer ist dran
        assertEquals(GameState.ROUND_OVER, game.state)
        assertEquals(21, game.hands[0].total)
        assertEquals(21, game.hands[1].total)
        assertFalse(game.hands[0].isBlackjack) // 21 nach dem Teilen ist kein Blackjack
    }

    @Test
    fun versicherungGleichtDenVerlustGenauAus() {
        val game = game(stack = listOf(c(Rank.TEN), c(Rank.ACE), c(Rank.SEVEN), c(Rank.KING)))
        assertEquals(GameState.INSURANCE, game.state)
        game.resolveInsurance(true)
        assertEquals(Outcome.DEALER_BLACKJACK, game.results[0].outcome)
        assertEquals(1000, game.bankroll) // 100 verloren, 100 über die Versicherung gewonnen
    }

    @Test
    fun ohneVersicherungKostetDerDealerBlackjackDenEinsatz() {
        val game = game(stack = listOf(c(Rank.TEN), c(Rank.ACE), c(Rank.SEVEN), c(Rank.KING)))
        game.resolveInsurance(false)
        assertEquals(900, game.bankroll)
    }

    @Test
    fun verdeckteKarteWirdErstBeimAufdeckenGezaehlt() {
        // 5 (+1), 6 (+1), 5 (+1) offen, König (-1) verdeckt
        val game = game(stack = listOf(c(Rank.FIVE), c(Rank.SIX), c(Rank.FIVE), c(Rank.KING), c(Rank.FOUR)))
        // Wäre der König mitgezählt worden, stünde hier +2 statt +3.
        assertEquals(3, game.shoe.runningCount)
        assertTrue(game.holeHidden)
        assertEquals(1, game.dealer.cards.size)

        game.stand()
        // Dealer deckt den König auf (16) und zieht die 4: 3 - 1 + 1 = 3
        assertFalse(game.holeHidden)
        assertEquals(20, game.dealer.total)
        assertEquals(3, game.shoe.runningCount)
    }

    @Test
    fun verdoppelnGibtGenauEineKarteUndVerdoppeltDenEinsatz() {
        val game = game(stack = listOf(c(Rank.SIX), c(Rank.FIVE), c(Rank.FIVE), c(Rank.NINE), c(Rank.KING), c(Rank.TWO)))
        assertTrue(game.options().canDouble)
        game.double()
        assertEquals(21, game.hands[0].total)
        assertEquals(200, game.hands[0].totalWager)
        assertEquals(Outcome.WIN, game.results[0].outcome)
        assertEquals(1200, game.bankroll) // 200 Einsatz, 400 zurück
    }

    @Test
    fun statistikZaehltRichtigeUndFalscheEntscheidungen() {
        val game = game(stack = listOf(c(Rank.TEN), c(Rank.SIX), c(Rank.SIX), c(Rank.NINE), c(Rank.TWO), c(Rank.KING)))
        val advice = game.advice(countingEnabled = false)!!
        game.recordDecision(advice.action, advice.action)
        assertEquals(1, game.stats.decisions)
        assertEquals(1, game.stats.correctDecisions)
        assertEquals(100, game.stats.accuracy)
    }
}
