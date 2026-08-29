package com.blackjacktrainer.game

/** Tischregeln - alles, was von Casino zu Casino unterschiedlich sein kann. */
data class Rules(
    val numDecks: Int = 6,
    /** true = Dealer zieht bei Soft 17 (H17), false = Dealer bleibt stehen (S17) */
    val dealerHitsSoft17: Boolean = true,
    /** true = Blackjack zahlt 3:2, false = 6:5 */
    val blackjackPays3to2: Boolean = true,
    /** Verdoppeln nach dem Teilen erlaubt */
    val doubleAfterSplit: Boolean = true,
    /** Maximale Anzahl Hände nach dem Teilen */
    val maxHands: Int = 4,
    /** Geteilte Asse dürfen erneut geteilt werden */
    val resplitAces: Boolean = false,
    /** Auf geteilte Asse gibt es nur eine Karte */
    val hitSplitAces: Boolean = false,
    /** Late Surrender (Aufgeben) erlaubt */
    val lateSurrender: Boolean = true,
    /** true = auf jede Zweikartenhand verdoppeln, false = nur auf 9/10/11 */
    val doubleAnyTwo: Boolean = true,
    /** Anteil des Schlittens, der gespielt wird, bevor neu gemischt wird */
    val penetration: Double = 0.75
) {
    val blackjackMultiplier: Double get() = if (blackjackPays3to2) 1.5 else 1.2

    fun describe(): String {
        val s17 = if (dealerHitsSoft17) "H17" else "S17"
        val bj = if (blackjackPays3to2) "BJ 3:2" else "BJ 6:5"
        val das = if (doubleAfterSplit) "DAS" else "kein DAS"
        val sur = if (lateSurrender) "Surrender" else "kein Surrender"
        return "$numDecks Decks · $s17 · $bj · $das · $sur"
    }
}
