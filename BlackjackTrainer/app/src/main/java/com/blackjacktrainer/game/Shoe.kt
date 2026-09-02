package com.blackjacktrainer.game

import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random

/** Kartenschlitten mit mehreren Decks und Cut-Card. */
class Shoe(
    numDecks: Int,
    private var penetration: Double = 0.75,
    private val random: Random = Random.Default
) {
    private val cards = ArrayDeque<Card>()

    var numDecks: Int = numDecks
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
        dealtCount = 0
        cutCardReached = false
    }

    val cardsRemaining: Int get() = cards.size

    /** Verbleibende Decks, auf halbe Decks gerundet. */
    val decksRemaining: Double
        get() = max(0.5, round((cardsRemaining / 52.0) * 2.0) / 2.0)

    fun deal(): Card = drawRaw()

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
