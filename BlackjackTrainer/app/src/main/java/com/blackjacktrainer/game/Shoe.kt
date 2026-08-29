package com.blackjacktrainer.game

import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random

/**
 * Kartenschlitten mit mehreren Decks und Cut-Card.
 *
 * Der laufende Hi-Lo-Count zählt nur Karten, die auch tatsächlich offen
 * auf dem Tisch lagen - die verdeckte Karte des Dealers wird erst beim
 * Aufdecken mitgezählt, genau wie am echten Tisch.
 */
class Shoe(
    numDecks: Int,
    private var penetration: Double = 0.75,
    private val random: Random = Random.Default
) {
    private val cards = ArrayDeque<Card>()

    var numDecks: Int = numDecks
        private set

    var runningCount: Int = 0
        private set

    var dealtCount: Int = 0
        private set

    /** true, sobald die Cut-Card erreicht wurde -> nach der Runde neu mischen */
    var cutCardReached: Boolean = false
        private set

    init {
        shuffle()
    }

    fun reconfigure(numDecks: Int, penetration: Double) {
        this.numDecks = numDecks
        this.penetration = penetration
        shuffle()
    }

    fun shuffle() {
        cards.clear()
        val fresh = ArrayList<Card>(numDecks * 52)
        repeat(numDecks) {
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    fresh.add(Card(rank, suit))
                }
            }
        }
        fresh.shuffle(random)
        cards.addAll(fresh)
        runningCount = 0
        dealtCount = 0
        cutCardReached = false
    }

    val cardsRemaining: Int get() = cards.size

    /** Verbleibende Decks, auf halbe Decks gerundet - so schätzt man am Tisch. */
    val decksRemaining: Double
        get() = max(0.5, round((cardsRemaining / 52.0) * 2.0) / 2.0)

    val trueCount: Double get() = runningCount / decksRemaining

    /** Zieht eine offene Karte (wird sofort gezählt). */
    fun deal(): Card {
        val card = drawRaw()
        runningCount += card.rank.hiLo
        return card
    }

    /** Zieht eine verdeckte Karte - noch nicht zählen. */
    fun dealHidden(): Card = drawRaw()

    /** Deckt eine zuvor verdeckte Karte auf und nimmt sie in den Count. */
    fun reveal(card: Card) {
        runningCount += card.rank.hiLo
    }

    /** Legt Karten in der angegebenen Reihenfolge nach oben - nur für Tests. */
    fun stackTop(top: List<Card>) {
        for (card in top.asReversed()) cards.addFirst(card)
    }

    private fun drawRaw(): Card {
        if (cards.isEmpty()) shuffle()
        val card = cards.removeFirst()
        dealtCount++
        if (dealtCount >= numDecks * 52 * penetration) cutCardReached = true
        return card
    }
}
