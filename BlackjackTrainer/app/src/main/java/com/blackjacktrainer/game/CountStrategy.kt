package com.blackjacktrainer.game

import kotlin.math.floor

/**
 * Hi-Lo-Abweichungen ("Illustrious 18") und Versicherungsentscheidung.
 *
 * Diese Abweichungen sind nur sinnvoll, wenn der Kartenzähl-Modus aktiv ist.
 * Ohne Count bleibt immer die Basisstrategie richtig.
 */
object CountStrategy {

    private data class Deviation(
        val total: Int,
        val up: Int,
        val index: Double,
        val atOrAbove: Action,
        val below: Action,
        val pairOfTens: Boolean = false
    )

    private val deviations = listOf(
        // Stehen statt ziehen, wenn viele Zehner übrig sind
        Deviation(16, 10, 0.0, Action.STAND, Action.HIT),
        Deviation(16, 9, 5.0, Action.STAND, Action.HIT),
        Deviation(15, 10, 4.0, Action.STAND, Action.HIT),
        Deviation(12, 2, 3.0, Action.STAND, Action.HIT),
        Deviation(12, 3, 2.0, Action.STAND, Action.HIT),
        // Ziehen statt stehen, wenn viele kleine Karten übrig sind
        Deviation(12, 4, 0.0, Action.STAND, Action.HIT),
        Deviation(12, 5, -2.0, Action.STAND, Action.HIT),
        Deviation(12, 6, -1.0, Action.STAND, Action.HIT),
        Deviation(13, 2, -1.0, Action.STAND, Action.HIT),
        Deviation(13, 3, -2.0, Action.STAND, Action.HIT),
        // Zusätzliche Verdoppelungen bei hohem Count
        Deviation(11, 11, 1.0, Action.DOUBLE, Action.HIT),
        Deviation(10, 10, 4.0, Action.DOUBLE, Action.HIT),
        Deviation(10, 11, 4.0, Action.DOUBLE, Action.HIT),
        Deviation(9, 2, 1.0, Action.DOUBLE, Action.HIT),
        Deviation(9, 7, 3.0, Action.DOUBLE, Action.HIT),
        // Zehnerpaare teilen - mathematisch korrekt, fällt am Tisch aber auf
        Deviation(20, 5, 5.0, Action.SPLIT, Action.STAND, pairOfTens = true),
        Deviation(20, 6, 4.0, Action.SPLIT, Action.STAND, pairOfTens = true)
    )

    /**
     * Passt die Basisstrategie an den True Count an. Gibt den unveränderten
     * Rat zurück, wenn keine Abweichung greift.
     */
    fun apply(
        basic: Advice,
        hand: Hand,
        dealerUpcard: Card,
        trueCount: Double,
        options: Options
    ): Advice {
        if (hand.isSoft) return basic
        val up = dealerUpcard.rank.value
        val total = hand.total
        val tensPair = hand.isPair && hand.cards[0].rank.value == 10

        val dev = deviations.firstOrNull {
            it.total == total && it.up == up && it.pairOfTens == tensPair
        } ?: return basic

        val target = if (trueCount >= dev.index) dev.atOrAbove else dev.below
        if (target == basic.action) return basic

        val possible = when (target) {
            Action.DOUBLE -> options.canDouble
            Action.SPLIT -> options.canSplit
            Action.HIT -> options.canHit
            Action.STAND -> options.canStand
            Action.SURRENDER -> options.canSurrender
        }
        if (!possible) return basic

        val direction = if (trueCount >= dev.index) "ab" else "unter"
        val note = "Abweichung $direction TC ${BasicStrategy.fmt(dev.index)}: " +
            "${target.label} statt ${basic.action.label} (jetzt ${BasicStrategy.fmt(trueCount)})"

        val reason = when (target) {
            Action.STAND -> "Viele Zehner im Schlitten - du überkaufst öfter, der Dealer auch."
            Action.HIT -> "Viele kleine Karten übrig - du triffst öfter, der Dealer " +
                "überkauft seltener."
            Action.DOUBLE -> "Hoher Count heißt mehr Zehner - genau die Karten, die diese " +
                "Hand stark machen."
            Action.SPLIT -> "Aus zwei Zehnen werden zwei sehr starke Hände. Rechnerisch " +
                "korrekt, am Tisch aber auffällig."
            Action.SURRENDER -> basic.reason
        }
        return Advice(target, reason, note)
    }

    /** Versicherung lohnt sich erst ab True Count +3. */
    fun insuranceAdvice(trueCount: Double?, countingEnabled: Boolean): Advice {
        if (countingEnabled && trueCount != null && trueCount >= 3.0) {
            return Advice(
                Action.STAND, // steht hier für "Ja, versichern"
                "Ab True Count +3 sind genug Zehner im Schlitten - erst dann lohnt sie sich.",
                "Count-Abweichung: Versicherung ab TC +3 " +
                    "(jetzt ${BasicStrategy.fmt(trueCount)})"
            )
        }
        return Advice(
            Action.HIT, // steht hier für "Nein, keine Versicherung"
            "Eigenständige Wette mit rund 7 % Hausvorteil - auch mit eigenem Blackjack. " +
                "\"Even Money\" ist derselbe schlechte Deal in hübsch."
        )
    }

    /** Einsatzempfehlung nach True Count (1-8 Einheiten Spread). */
    fun betUnits(trueCount: Double): Int {
        if (trueCount < 1.0) return 1
        return floor(trueCount).toInt().coerceIn(1, 8)
    }

    fun betHint(trueCount: Double, unit: Int): String {
        val units = betUnits(trueCount)
        return if (units <= 1) {
            "TC ${BasicStrategy.fmt(trueCount)}: Mindesteinsatz ($unit)."
        } else {
            "TC ${BasicStrategy.fmt(trueCount)}: Schlitten zu deinen Gunsten - " +
                "$units Einheiten (${unit * units})."
        }
    }
}
