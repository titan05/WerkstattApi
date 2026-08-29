package com.blackjacktrainer.game

/**
 * Basisstrategie für Multi-Deck-Spiele (4-8 Decks), inklusive der
 * Unterschiede zwischen H17/S17, DAS und Late Surrender.
 *
 * Die Prüfreihenfolge entspricht der Reihenfolge am Tisch:
 * Aufgeben -> Teilen -> Soft-Hände -> harte Hände.
 *
 * Die Begründungen sind bewusst kurz: Die Aktion selbst steht schon in der
 * Überschrift des Tipp-Felds, hier steht nur noch das Warum.
 */
object BasicStrategy {

    fun advise(hand: Hand, dealerUpcard: Card, rules: Rules, options: Options): Advice {
        val up = dealerUpcard.rank.value // Ass = 11
        val h17 = rules.dealerHitsSoft17

        if (options.canSurrender) {
            surrenderAdvice(hand, up, h17)?.let { return it }
        }
        if (options.canSplit && hand.isPair) {
            splitAdvice(hand, up, rules)?.let { return it }
        }
        if (hand.isSoft) {
            return softAdvice(hand, up, rules, options)
        }
        return hardAdvice(hand, up, rules, options)
    }

    // ---------------------------------------------------------------- Aufgeben

    private fun surrenderAdvice(hand: Hand, up: Int, h17: Boolean): Advice? {
        if (hand.cards.size != 2 || hand.isSoft) return null
        val total = hand.total

        // Ein Paar Achten wird sonst immer geteilt - einzige Ausnahme: gegen Ass im H17.
        if (hand.isPair && hand.cards[0].rank.value == 8) {
            return if (up == 11 && h17) Advice(
                Action.SURRENDER,
                "Der einzige Fall, in dem Aufgeben besser ist als Teilen."
            ) else null
        }

        return when {
            total == 16 && up in 9..11 -> Advice(
                Action.SURRENDER,
                "Die schlechteste Lage im Spiel: Ziehen überkauft zu 62 %, Stehen " +
                    "verliert fast genauso oft. Rette den halben Einsatz."
            )
            total == 15 && (up == 10 || (up == 11 && h17)) -> Advice(
                Action.SURRENDER,
                "Diese Hand verliert langfristig mehr als den halben Einsatz."
            )
            total == 17 && up == 11 && h17 -> Advice(
                Action.SURRENDER,
                "Harte 17 gegen ein Ass ist im H17-Spiel ein klarer Verlustfall."
            )
            else -> null
        }
    }

    // ------------------------------------------------------------------ Teilen

    private fun splitAdvice(hand: Hand, up: Int, rules: Rules): Advice? {
        val rank = hand.cards[0].rank
        val v = rank.value
        val das = rules.doubleAfterSplit

        return when {
            rank == Rank.ACE -> Advice(
                Action.SPLIT,
                "Asse immer teilen: Aus einer weichen 12 werden zwei Hände, die mit " +
                    "jeder Zehnerkarte 21 treffen."
            )
            v == 10 -> null // 20 ist zum Teilen viel zu gut, fällt auf Stehen durch
            v == 9 -> if (up in 2..6 || up == 8 || up == 9) Advice(
                Action.SPLIT,
                "Zwei Hände mit einer 9 bringen mehr als eine 18. Nur gegen 7, 10 " +
                    "und Ass bleibst du stehen."
            ) else null
            v == 8 -> Advice(
                Action.SPLIT,
                "8-8 immer teilen: Die 16 ist die schwächste Hand im Spiel, zwei " +
                    "Achten sind selbst gegen eine Zehn besser."
            )
            v == 7 -> if (up in 2..7) Advice(
                Action.SPLIT,
                "Deine 14 ist chancenlos, mit einer 7 als Start triffst du oft 17+."
            ) else null
            v == 6 -> if ((das && up in 2..6) || (!das && up in 3..6)) Advice(
                Action.SPLIT,
                "Die 12 verliert nur, und der Dealer hat eine Bust-Karte."
            ) else null
            v == 5 -> null // wie harte 10 behandeln
            v == 4 -> if (das && up in 5..6) Advice(
                Action.SPLIT,
                "Nur gegen 5 und 6 - und auch nur, weil danach verdoppelt werden darf."
            ) else null
            v == 3 || v == 2 -> if ((das && up in 2..7) || (!das && up in 4..7)) Advice(
                Action.SPLIT,
                "Aus einer schwachen ${v * 2} werden zwei Hände mit gutem Trefferpotenzial."
            ) else null
            else -> null
        }
    }

    // ------------------------------------------------------------- Soft-Hände

    private fun softAdvice(hand: Hand, up: Int, rules: Rules, options: Options): Advice {
        val h17 = rules.dealerHitsSoft17
        val total = hand.total
        val other = total - 11 // A,7 -> 18 -> other = 7

        fun doubleOrHit(reason: String, fallback: String) =
            if (options.canDouble) Advice(Action.DOUBLE, reason)
            else Advice(Action.HIT, fallback)

        fun doubleOrStand(reason: String, fallback: String) =
            if (options.canDouble) Advice(Action.DOUBLE, reason)
            else Advice(Action.STAND, fallback)

        return when {
            total >= 20 -> Advice(
                Action.STAND,
                "Soft $total ist praktisch unschlagbar."
            )
            other == 8 -> { // Soft 19
                if (h17 && up == 6 && options.canDouble) Advice(
                    Action.DOUBLE,
                    "Der eine Ausnahmefall im H17-Spiel: Die 6 ist die schwächste " +
                        "Dealerkarte, und überkaufen kannst du nicht."
                ) else Advice(
                    Action.STAND,
                    "Soft 19 ist zu stark, um noch zu ziehen."
                )
            }
            other == 7 -> { // Soft 18 - die am häufigsten falsch gespielte Hand
                val doubleRange = if (h17) 2..6 else 3..6
                when {
                    up in doubleRange -> doubleOrStand(
                        "Der Dealer ist schwach, und mit dem Ass kannst du nicht überkaufen.",
                        "Verdoppeln geht nicht mehr - und ziehen lohnt gegen ${upName(up)} nicht."
                    )
                    up == 2 || up == 7 || up == 8 -> Advice(
                        Action.STAND,
                        "Gegen die 7 gewinnt deine 18 meistens: Der Dealer macht dort " +
                            "besonders oft genau 17."
                    )
                    else -> Advice(
                        Action.HIT,
                        "Die am häufigsten falsch gespielte Hand: Gegen 9, 10 und Ass " +
                            "verliert die 18 klar - und überkaufen ist unmöglich."
                    )
                }
            }
            other == 6 -> if (up in 3..6) doubleOrHit( // Soft 17
                "Der Dealer hat eine Bust-Karte, und deine Hand kann sich nur verbessern.",
                "Eine 17 gewinnt zu selten, und überkaufen kannst du nicht."
            ) else Advice(
                Action.HIT,
                "Soft 17 ist keine Stehhand - im schlechtesten Fall zählt das Ass nur noch 1."
            )
            other == 5 || other == 4 -> if (up in 4..6) doubleOrHit( // Soft 15/16
                "Gegen 4, 5 und 6 überkauft sich der Dealer am häufigsten.",
                "Soft $total ist zum Stehenbleiben viel zu schwach."
            ) else Advice(
                Action.HIT,
                "Zum Stehen zu schwach, und überkaufen kannst du nicht."
            )
            other == 3 || other == 2 -> if (up in 5..6) doubleOrHit( // Soft 13/14
                "Nur gegen die beiden schwächsten Dealerkarten lohnt der doppelte Einsatz.",
                "Soft $total ist zum Stehenbleiben viel zu schwach."
            ) else Advice(
                Action.HIT,
                "Du kannst nicht überkaufen - also immer eine Karte nehmen."
            )
            else -> Advice(Action.HIT, "Überkaufen ist mit einem Ass unmöglich.")
        }
    }

    // ------------------------------------------------------------ Harte Hände

    private fun hardAdvice(hand: Hand, up: Int, rules: Rules, options: Options): Advice {
        val total = hand.total
        val h17 = rules.dealerHitsSoft17
        val dealerWeak = up in 2..6

        fun doubleOrHit(reason: String, fallback: String) =
            if (options.canDouble) Advice(Action.DOUBLE, reason)
            else Advice(Action.HIT, fallback)

        return when {
            total >= 17 -> Advice(
                Action.STAND,
                "Jede Karte über die 4 lässt dich überkaufen - egal was der Dealer zeigt."
            )
            total in 13..16 -> if (dealerWeak) Advice(
                Action.STAND,
                "Der Dealer muss auf 17 ziehen und überkauft sich mit ${upNameDative(up)} " +
                    "zu rund 40 %. Lass ihn die Arbeit machen."
            ) else Advice(
                Action.HIT,
                "Der Dealer erreicht mit ${upNameDative(up)} fast sicher 17+ - mit $total " +
                    "verlierst du sonst automatisch."
            )
            total == 12 -> if (up in 4..6) Advice(
                Action.STAND,
                "Nur gegen 4, 5 und 6 überkauft sich der Dealer oft genug, dass du " +
                    "dein eigenes Risiko sparen kannst."
            ) else Advice(
                Action.HIT,
                "Du überkaufst nur mit einer Zehnerkarte, also zu 31 % - weniger " +
                    "riskant, als mit einer 12 stehen zu bleiben."
            )
            total == 11 -> {
                if (up == 11 && !h17) Advice(
                    Action.HIT,
                    "Im S17-Spiel knapp besser als Verdoppeln: Der Dealer hat nach dem " +
                        "Peek den Blackjack schon ausgeschlossen und steht stark."
                ) else doubleOrHit(
                    "Die beste Verdoppelhand: 31 % Chance auf eine Zehnerkarte, und " +
                        "überkaufen kannst du nie.",
                    "Verdoppeln ist hier nicht mehr möglich."
                )
            }
            total == 10 -> if (up in 2..9) doubleOrHit(
                "Deine Hand wird öfter zur 20 als die des Dealers.",
                "Verdoppeln ist hier nicht mehr möglich."
            ) else Advice(
                Action.HIT,
                "Der Dealer hat mit ${upNameDative(up)} zu oft selbst 20 oder 21 - " +
                    "Verdoppeln wäre ein Verlustgeschäft."
            )
            total == 9 -> if (up in 3..6) doubleOrHit(
                "Der Dealer ist schwach, und mit jeder Zehnerkarte triffst du eine 19.",
                "Verdoppeln ist hier nicht mehr möglich."
            ) else Advice(
                Action.HIT,
                "Zum Verdoppeln ist ${upName(up)} zu stark."
            )
            else -> Advice(
                Action.HIT,
                "Ziehen ist risikofrei - mit keiner einzigen Karte kannst du überkaufen."
            )
        }
    }

    /** Nominativ/Akkusativ: "gegen eine 6", "ist ein Ass zu stark" */
    fun upName(up: Int): String = when (up) {
        11 -> "ein Ass"
        10 -> "eine Zehnerkarte"
        else -> "eine $up"
    }

    /** Dativ: "mit einer 6", "mit einem Ass" */
    fun upNameDative(up: Int): String = when (up) {
        11 -> "einem Ass"
        10 -> "einer Zehnerkarte"
        else -> "einer $up"
    }

    fun fmt(d: Double): String = String.format("%+.1f", d)
}
