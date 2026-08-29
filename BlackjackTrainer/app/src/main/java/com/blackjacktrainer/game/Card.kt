package com.blackjacktrainer.game

enum class Suit(val symbol: String, val isRed: Boolean) {
    PIK("♠", false),
    HERZ("♥", true),
    KARO("♦", true),
    KREUZ("♣", false)
}

enum class Rank(val label: String, val value: Int) {
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 10),
    QUEEN("Q", 10),
    KING("K", 10),
    ACE("A", 11);

    /** Hi-Lo Zählwert: 2-6 = +1, 7-9 = 0, 10/Bild/Ass = -1 */
    val hiLo: Int
        get() = when (this) {
            TWO, THREE, FOUR, FIVE, SIX -> 1
            SEVEN, EIGHT, NINE -> 0
            else -> -1
        }
}

data class Card(val rank: Rank, val suit: Suit) {
    override fun toString(): String = "${rank.label}${suit.symbol}"
}
