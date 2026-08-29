package com.blackjacktrainer.game

class Hand(var bet: Int = 0) {
    val cards = mutableListOf<Card>()

    var doubled = false
    var surrendered = false
    var stood = false
    var fromSplit = false
    var isSplitAces = false

    fun add(card: Card) = cards.add(card)

    /** Bester Wert <= 21, sonst der harte Wert. */
    val total: Int
        get() {
            var sum = cards.sumOf { it.rank.value }
            var aces = cards.count { it.rank == Rank.ACE }
            while (sum > 21 && aces > 0) {
                sum -= 10
                aces--
            }
            return sum
        }

    /** true, wenn ein Ass noch als 11 gewertet wird. */
    val isSoft: Boolean
        get() {
            var sum = cards.sumOf { it.rank.value }
            var aces = cards.count { it.rank == Rank.ACE }
            while (sum > 21 && aces > 0) {
                sum -= 10
                aces--
            }
            return aces > 0 && sum <= 21
        }

    val isBusted: Boolean get() = total > 21

    val isBlackjack: Boolean get() = !fromSplit && cards.size == 2 && total == 21

    /** Paar nach Kartenwert - zwei Zehnerkarten gelten im Casino als Paar. */
    val isPair: Boolean
        get() = cards.size == 2 && cards[0].rank.value == cards[1].rank.value

    val isFinished: Boolean get() = stood || doubled || surrendered || isBusted || total == 21

    /** Gesamter Einsatz dieser Hand (nach Verdoppeln also der doppelte). */
    val totalWager: Int get() = if (doubled) bet * 2 else bet

    fun displayTotal(): String {
        if (cards.isEmpty()) return ""
        if (isBlackjack) return "Blackjack"
        val t = total
        return if (isSoft && t != 21) "$t / ${t - 10}" else "$t"
    }
}
