package com.blackjacktrainer.game

enum class Action(val label: String, val short: String) {
    HIT("Karte", "K"),
    STAND("Stehen", "S"),
    DOUBLE("Verdoppeln", "V"),
    SPLIT("Teilen", "T"),
    SURRENDER("Aufgeben", "A")
}

/** Welche Aktionen sind für die aktuelle Hand überhaupt möglich? */
data class Options(
    val canHit: Boolean,
    val canStand: Boolean,
    val canDouble: Boolean,
    val canSplit: Boolean,
    val canSurrender: Boolean
)

data class Advice(
    val action: Action,
    val reason: String,
    /** Hinweis aus dem Kartenzählen, falls der Count die Entscheidung ändert. */
    val countNote: String? = null
)
