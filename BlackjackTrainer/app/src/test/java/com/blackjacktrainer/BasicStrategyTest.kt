package com.blackjacktrainer

import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.BasicStrategy
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.Hand
import com.blackjacktrainer.game.Options
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Vergleicht die Engine Zelle für Zelle mit der veröffentlichten
 * Multi-Deck-Basisstrategie (4-8 Decks).
 *
 * Legende der Referenz: H = Karte, S = Stehen, D = Verdoppeln,
 * P = Teilen, R = Aufgeben.
 */
class BasicStrategyTest {

    private val upcards = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    private val h17 = Rules(numDecks = 6, dealerHitsSoft17 = true, doubleAfterSplit = true, lateSurrender = true)
    private val s17 = Rules(numDecks = 6, dealerHitsSoft17 = false, doubleAfterSplit = true, lateSurrender = true)
    private val h17NoSurrender = h17.copy(lateSurrender = false)

    // ------------------------------------------------------------- Referenz

    // Dealer:            2  3  4  5  6  7  8  9  10 A
    private val hardH17 = mapOf(
        17 to "S  S  S  S  S  S  S  S  S  R",
        16 to "S  S  S  S  S  H  H  R  R  R",
        15 to "S  S  S  S  S  H  H  H  R  R",
        14 to "S  S  S  S  S  H  H  H  H  H",
        13 to "S  S  S  S  S  H  H  H  H  H",
        12 to "H  H  S  S  S  H  H  H  H  H",
        11 to "D  D  D  D  D  D  D  D  D  D",
        10 to "D  D  D  D  D  D  D  D  H  H",
        9 to "H  D  D  D  D  H  H  H  H  H",
        8 to "H  H  H  H  H  H  H  H  H  H"
    )

    private val hardS17 = mapOf(
        17 to "S  S  S  S  S  S  S  S  S  S",
        16 to "S  S  S  S  S  H  H  R  R  R",
        15 to "S  S  S  S  S  H  H  H  R  H",
        14 to "S  S  S  S  S  H  H  H  H  H",
        13 to "S  S  S  S  S  H  H  H  H  H",
        12 to "H  H  S  S  S  H  H  H  H  H",
        11 to "D  D  D  D  D  D  D  D  D  H",
        10 to "D  D  D  D  D  D  D  D  H  H",
        9 to "H  D  D  D  D  H  H  H  H  H",
        8 to "H  H  H  H  H  H  H  H  H  H"
    )

    private val softH17 = mapOf(
        9 to "S  S  S  S  S  S  S  S  S  S",
        8 to "S  S  S  S  D  S  S  S  S  S",
        7 to "D  D  D  D  D  S  S  H  H  H",
        6 to "H  D  D  D  D  H  H  H  H  H",
        5 to "H  H  D  D  D  H  H  H  H  H",
        4 to "H  H  D  D  D  H  H  H  H  H",
        3 to "H  H  H  D  D  H  H  H  H  H",
        2 to "H  H  H  D  D  H  H  H  H  H"
    )

    private val softS17 = mapOf(
        9 to "S  S  S  S  S  S  S  S  S  S",
        8 to "S  S  S  S  S  S  S  S  S  S",
        7 to "S  D  D  D  D  S  S  H  H  H",
        6 to "H  D  D  D  D  H  H  H  H  H",
        5 to "H  H  D  D  D  H  H  H  H  H",
        4 to "H  H  D  D  D  H  H  H  H  H",
        3 to "H  H  H  D  D  H  H  H  H  H",
        2 to "H  H  H  D  D  H  H  H  H  H"
    )

    private val pairsH17 = mapOf(
        11 to "P  P  P  P  P  P  P  P  P  P",  // A,A
        10 to "S  S  S  S  S  S  S  S  S  S",
        9 to "P  P  P  P  P  S  P  P  S  S",
        8 to "P  P  P  P  P  P  P  P  P  R",
        7 to "P  P  P  P  P  P  H  H  H  H",
        6 to "P  P  P  P  P  H  H  H  H  H",
        5 to "D  D  D  D  D  D  D  D  H  H",
        4 to "H  H  H  P  P  H  H  H  H  H",
        3 to "P  P  P  P  P  P  H  H  H  H",
        2 to "P  P  P  P  P  P  H  H  H  H"
    )

    private val pairsS17 = pairsH17 + mapOf(8 to "P  P  P  P  P  P  P  P  P  P")

    // ---------------------------------------------------------------- Tests

    @Test
    fun harteHaendeH17() = checkHard(h17, hardH17)

    @Test
    fun harteHaendeS17() = checkHard(s17, hardS17)

    @Test
    fun softHaendeH17() = checkSoft(h17, softH17)

    @Test
    fun softHaendeS17() = checkSoft(s17, softS17)

    @Test
    fun paareH17() = checkPairs(h17, pairsH17)

    @Test
    fun paareS17() = checkPairs(s17, pairsS17)

    @Test
    fun ohneSurrenderWirdGezogenOderGestanden() {
        // 16 gegen 10 ohne Surrender = ziehen, 17 gegen Ass = stehen
        assertEquals(Action.HIT, advise(hardHand(16), 10, h17NoSurrender, canSurrender = false).action)
        assertEquals(Action.STAND, advise(hardHand(17), 11, h17NoSurrender, canSurrender = false).action)
        assertEquals(Action.SPLIT, advise(pairHand(Rank.EIGHT), 11, h17NoSurrender, canSurrender = false).action)
    }

    @Test
    fun ohneDasWerdenKleinePaareNichtGeteilt() {
        val noDas = h17.copy(doubleAfterSplit = false)
        assertEquals(Action.HIT, advise(pairHand(Rank.TWO), 2, noDas).action)
        assertEquals(Action.HIT, advise(pairHand(Rank.THREE), 3, noDas).action)
        assertEquals(Action.HIT, advise(pairHand(Rank.SIX), 2, noDas).action)
        assertEquals(Action.HIT, advise(pairHand(Rank.FOUR), 5, noDas).action)
        // ab Dealer 4 wird weiter geteilt
        assertEquals(Action.SPLIT, advise(pairHand(Rank.TWO), 4, noDas).action)
    }

    @Test
    fun ohneVerdoppelnGibtEsSinnvolleAlternativen() {
        // Soft 18 gegen 4: verdoppeln nicht möglich -> stehen
        val soft18 = softHand(7)
        val advice = BasicStrategy.advise(
            soft18, card(4), h17,
            Options(canHit = true, canStand = true, canDouble = false, canSplit = false, canSurrender = false)
        )
        assertEquals(Action.STAND, advice.action)

        // Harte 11 gegen 6: verdoppeln nicht möglich -> ziehen
        val hard11 = hardHand(11)
        val advice2 = BasicStrategy.advise(
            hard11, card(6), h17,
            Options(canHit = true, canStand = true, canDouble = false, canSplit = false, canSurrender = false)
        )
        assertEquals(Action.HIT, advice2.action)
    }

    @Test
    fun jederTippHatEineBegruendung() {
        for (total in 5..17) {
            for (up in upcards) {
                val advice = advise(hardHand(total), up, h17)
                assert(advice.reason.length > 20) { "Begründung fehlt für $total gegen $up" }
            }
        }
    }

    // ------------------------------------------------------------- Helferlein

    private fun checkHard(rules: Rules, reference: Map<Int, String>) {
        for ((total, row) in reference) {
            val expected = row.trim().split(Regex("\\s+"))
            upcards.forEachIndexed { i, up ->
                val actual = advise(hardHand(total), up, rules).action
                assertEquals(
                    "Harte $total gegen ${label(up)} (${ruleName(rules)})",
                    expected[i], letter(actual)
                )
            }
        }
    }

    private fun checkSoft(rules: Rules, reference: Map<Int, String>) {
        for ((other, row) in reference) {
            val expected = row.trim().split(Regex("\\s+"))
            upcards.forEachIndexed { i, up ->
                val actual = advise(softHand(other), up, rules).action
                assertEquals(
                    "Soft A-$other gegen ${label(up)} (${ruleName(rules)})",
                    expected[i], letter(actual)
                )
            }
        }
    }

    private fun checkPairs(rules: Rules, reference: Map<Int, String>) {
        for ((value, row) in reference) {
            val expected = row.trim().split(Regex("\\s+"))
            val rank = rankOf(value)
            upcards.forEachIndexed { i, up ->
                val actual = advise(pairHand(rank), up, rules, canSplit = true).action
                assertEquals(
                    "Paar $value-$value gegen ${label(up)} (${ruleName(rules)})",
                    expected[i], letter(actual)
                )
            }
        }
    }

    private fun advise(
        hand: Hand,
        up: Int,
        rules: Rules,
        canSplit: Boolean = hand.isPair,
        canSurrender: Boolean = rules.lateSurrender
    ) = BasicStrategy.advise(
        hand, card(up), rules,
        Options(
            canHit = true,
            canStand = true,
            canDouble = true,
            canSplit = canSplit,
            canSurrender = canSurrender
        )
    )

    private fun letter(action: Action) = when (action) {
        Action.HIT -> "H"
        Action.STAND -> "S"
        Action.DOUBLE -> "D"
        Action.SPLIT -> "P"
        Action.SURRENDER -> "R"
    }

    private fun label(up: Int) = if (up == 11) "Ass" else "$up"

    private fun ruleName(rules: Rules) = if (rules.dealerHitsSoft17) "H17" else "S17"

    private fun card(value: Int) = Card(rankOf(value), Suit.KARO)

    private fun rankOf(value: Int) = when (value) {
        2 -> Rank.TWO
        3 -> Rank.THREE
        4 -> Rank.FOUR
        5 -> Rank.FIVE
        6 -> Rank.SIX
        7 -> Rank.SEVEN
        8 -> Rank.EIGHT
        9 -> Rank.NINE
        10 -> Rank.TEN
        else -> Rank.ACE
    }

    /** Zweikartenhand ohne Ass und ohne Paar (funktioniert für 5 bis 17). */
    private fun hardHand(total: Int): Hand {
        for (a in 2..10) {
            val b = total - a
            if (b in 2..10 && b != a) {
                val hand = Hand(10)
                hand.add(card(a))
                hand.add(card(b))
                return hand
            }
        }
        throw IllegalArgumentException("Keine harte Zweikartenhand für $total")
    }

    private fun softHand(other: Int): Hand {
        val hand = Hand(10)
        hand.add(Card(Rank.ACE, Suit.PIK))
        hand.add(card(other))
        return hand
    }

    private fun pairHand(rank: Rank): Hand {
        val hand = Hand(10)
        hand.add(Card(rank, Suit.PIK))
        hand.add(Card(rank, Suit.HERZ))
        return hand
    }
}
