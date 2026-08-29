package com.blackjacktrainer.game

/**
 * Basisstrategie für Multi-Deck-Spiele (4-8 Decks), inklusive der
 * Unterschiede zwischen H17/S17, DAS und Late Surrender.
 *
 * Die Prüfreihenfolge entspricht der Reihenfolge am Tisch:
 * Aufgeben -> Teilen -> Soft-Hände -> harte Hände.
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
                "8-8 gegen ein Ass ist im H17-Spiel der einzige Fall, in dem Aufgeben " +
                    "besser ist als Teilen - du verlierst nur den halben Einsatz."
            ) else null
        }

        return when {
            total == 16 && up in 9..11 -> Advice(
                Action.SURRENDER,
                "16 gegen ${upName(up)} ist die schlechteste Lage im Spiel: Ziehen lässt " +
                    "dich in rund 62 % der Fälle überkaufen, Stehenbleiben verliert fast genauso oft. " +
                    "Den halben Einsatz zu retten ist hier die beste Option."
            )
            total == 15 && (up == 10 || (up == 11 && h17)) -> Advice(
                Action.SURRENDER,
                "15 gegen ${upName(up)} verliert langfristig mehr als den halben Einsatz - " +
                    "aufgeben begrenzt den Schaden."
            )
            total == 17 && up == 11 && h17 -> Advice(
                Action.SURRENDER,
                "Harte 17 gegen ein Ass ist im H17-Spiel ein klarer Verlustfall: " +
                    "Aufgeben kostet dich nur die Hälfte."
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
                "Asse immer teilen. Aus einer weichen 12 werden zwei Hände, die mit jeder " +
                    "Zehnerkarte zur 21 werden - die beste Ausgangslage überhaupt."
            )
            v == 10 -> null // 20 ist zum Teilen viel zu gut, fällt auf Stehen durch
            v == 9 -> if (up in 2..6 || up == 8 || up == 9) Advice(
                Action.SPLIT,
                "9-9 gegen ${upName(up)} teilen: Zwei Hände mit einer 9 als Basis bringen " +
                    "mehr als eine 18. Gegen 7, 10 und Ass bleibst du dagegen auf der 18 stehen - " +
                    "gegen die 7 gewinnt sie ohnehin meistens, weil der Dealer dort oft 17 macht."
            ) else null
            v == 8 -> Advice(
                Action.SPLIT,
                "8-8 immer teilen. Die 16 ist die schwächste Hand im Spiel; zwei Hände, " +
                    "die je mit einer 8 starten, sind selbst gegen eine Zehn die bessere Wahl."
            )
            v == 7 -> if (up in 2..7) Advice(
                Action.SPLIT,
                "7-7 gegen ${upName(up)} teilen: Deine 14 ist chancenlos, mit einer 7 als " +
                    "Start triffst du dagegen oft 17+, während der Dealer schwach steht."
            ) else null
            v == 6 -> if ((das && up in 2..6) || (!das && up in 3..6)) Advice(
                Action.SPLIT,
                "6-6 gegen ${upName(up)} teilen: Die 12 ist eine reine Verlusthand, der " +
                    "Dealer hat eine Bust-Karte. Genau dann willst du mehr Geld auf dem Tisch haben."
            ) else null
            v == 5 -> null // wie harte 10 behandeln
            v == 4 -> if (das && up in 5..6) Advice(
                Action.SPLIT,
                "4-4 nur gegen 5 und 6 teilen, und auch nur, wenn danach verdoppelt werden " +
                    "darf. Sonst spielst du die 8 besser als eine einzige Hand."
            ) else null
            v == 3 || v == 2 -> if ((das && up in 2..7) || (!das && up in 4..7)) Advice(
                Action.SPLIT,
                "$v-$v gegen ${upName(up)} teilen: Aus einer schwachen ${v * 2} werden zwei " +
                    "Hände mit gutem Trefferpotenzial, während der Dealer selbst überkaufen kann."
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
                "Soft $total ist praktisch unschlagbar - hier wird nur noch stehen geblieben."
            )
            other == 8 -> { // Soft 19
                if (h17 && up == 6 && options.canDouble) Advice(
                    Action.DOUBLE,
                    "Soft 19 gegen 6 ist im H17-Spiel der eine Ausnahmefall zum Verdoppeln: " +
                        "Die 6 ist die schwächste Dealerkarte und überkaufen kannst du nicht."
                ) else Advice(
                    Action.STAND,
                    "Soft 19 ist stark. Stehen bleiben - der mögliche Gewinn durch eine " +
                        "weitere Karte ist kleiner als das, was du an Hand verschenkst."
                )
            }
            other == 7 -> { // Soft 18 - die am häufigsten falsch gespielte Hand
                val doubleRange = if (h17) 2..6 else 3..6
                when {
                    up in doubleRange -> doubleOrStand(
                        "Soft 18 gegen ${upName(up)} verdoppeln: Der Dealer ist schwach, und " +
                            "mit dem Ass kannst du nicht überkaufen - doppelter Einsatz bei bester Lage.",
                        "Soft 18 gegen ${upName(up)}: Verdoppeln geht nicht mehr, also stehen bleiben."
                    )
                    up == 2 || up == 7 || up == 8 -> Advice(
                        Action.STAND,
                        "Soft 18 gegen ${upName(up)}: stehen bleiben. Gegen die 7 gewinnt deine " +
                            "18 sogar häufig, weil der Dealer dort besonders oft genau 17 macht."
                    )
                    else -> Advice(
                        Action.HIT,
                        "Soft 18 gegen ${upName(up)} ziehen! Das ist die Hand, die die meisten " +
                            "Spieler falsch spielen: Gegen 9, 10 und Ass verliert die 18 klar - und " +
                            "mit dem Ass kannst du gefahrlos nachziehen, überkaufen ist unmöglich."
                    )
                }
            }
            other == 6 -> if (up in 3..6) doubleOrHit( // Soft 17
                "Soft 17 gegen ${upName(up)} verdoppeln - der Dealer hat eine Bust-Karte, " +
                    "und deine Hand kann sich durch eine Karte nur verbessern.",
                "Soft 17 ziehen: Eine 17 gewinnt zu selten, und überkaufen kannst du nicht."
            ) else Advice(
                Action.HIT,
                "Soft 17 ist keine Stehhand. Ziehen ist risikofrei - im schlechtesten Fall " +
                    "zählt das Ass danach nur noch 1."
            )
            other == 5 || other == 4 -> if (up in 4..6) doubleOrHit( // Soft 15/16
                "Soft $total gegen ${upName(up)} verdoppeln: Gegen 4, 5 und 6 überkauft sich " +
                    "der Dealer am häufigsten - genau hier holst du dir zusätzliches Geld.",
                "Soft $total ziehen - die Hand ist zum Stehen viel zu schwach."
            ) else Advice(
                Action.HIT,
                "Soft $total ziehen. Kein Risiko, und zum Stehenbleiben ist die Hand zu schwach."
            )
            other == 3 || other == 2 -> if (up in 5..6) doubleOrHit( // Soft 13/14
                "Soft $total gegen ${upName(up)} verdoppeln: Nur gegen die beiden schwächsten " +
                    "Dealerkarten lohnt sich hier der doppelte Einsatz.",
                "Soft $total ziehen - zum Stehen viel zu schwach."
            ) else Advice(
                Action.HIT,
                "Soft $total ziehen. Du kannst nicht überkaufen, also nimmst du immer eine Karte."
            )
            else -> Advice(Action.HIT, "Soft $total: ziehen, überkaufen ist unmöglich.")
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
                "Harte $total: stehen bleiben. Jede Karte über die 4 lässt dich überkaufen - " +
                    "das Risiko ist zu hoch, egal was der Dealer zeigt."
            )
            total in 13..16 -> if (dealerWeak) Advice(
                Action.STAND,
                "Harte $total gegen ${upName(up)}: stehen bleiben. Der Dealer muss auf 17 " +
                    "ziehen und überkauft sich mit dieser Karte in rund 40 % der Fälle. Lass ihn " +
                    "die Arbeit machen."
            ) else Advice(
                Action.HIT,
                "Harte $total gegen ${upName(up)}: ziehen. Der Dealer erreicht mit dieser " +
                    "Karte fast sicher 17 oder mehr - mit $total verlierst du sonst automatisch. " +
                    "Ziehen ist die kleinere von zwei schlechten Optionen."
            )
            total == 12 -> if (up in 4..6) Advice(
                Action.STAND,
                "12 gegen ${upName(up)}: stehen bleiben. Nur gegen 4, 5 und 6 ist die " +
                    "Bust-Wahrscheinlichkeit des Dealers hoch genug, um dein eigenes Risiko zu meiden."
            ) else Advice(
                Action.HIT,
                "12 gegen ${upName(up)}: ziehen. Du überkaufst nur mit einer Zehnerkarte, " +
                    "also in rund 31 % der Fälle - klar weniger riskant, als mit einer 12 stehen zu bleiben."
            )
            total == 11 -> {
                if (up == 11 && !h17) Advice(
                    Action.HIT,
                    "11 gegen ein Ass: Im S17-Spiel ist Ziehen knapp besser als Verdoppeln, " +
                        "weil der Dealer nach dem Peek einen Blackjack schon ausgeschlossen hat und stark steht."
                ) else doubleOrHit(
                    "11 ist die beste Verdoppelhand überhaupt: In rund 31 % der Fälle kommt " +
                        "eine Zehnerkarte und du stehst auf 21 - überkaufen kannst du nie.",
                    "11: ziehen, verdoppeln ist hier nicht mehr möglich."
                )
            }
            total == 10 -> if (up in 2..9) doubleOrHit(
                "10 gegen ${upName(up)} verdoppeln: Deine Hand wird öfter zur 20 als die " +
                    "des Dealers - genau dann willst du mehr Geld im Spiel haben.",
                "10: ziehen, verdoppeln ist nicht mehr möglich."
            ) else Advice(
                Action.HIT,
                "10 gegen ${upName(up)}: nur ziehen. Der Dealer hat zu oft selbst 20 oder 21 - " +
                    "den Einsatz zu verdoppeln wäre hier ein Verlustgeschäft."
            )
            total == 9 -> if (up in 3..6) doubleOrHit(
                "9 gegen ${upName(up)} verdoppeln: Der Dealer ist schwach, und mit jeder " +
                    "Zehnerkarte triffst du eine 19.",
                "9: ziehen."
            ) else Advice(
                Action.HIT,
                "9 gegen ${upName(up)}: ziehen. Zum Verdoppeln ist die Dealerkarte zu stark."
            )
            else -> Advice(
                Action.HIT,
                "Harte $total: Ziehen ist risikofrei - mit keiner einzigen Karte kannst du " +
                    "hier überkaufen."
            )
        }
    }

    fun upName(up: Int): String = when (up) {
        11 -> "ein Ass"
        10 -> "eine Zehnerkarte"
        else -> "eine $up"
    }

    fun fmt(d: Double): String = String.format("%+.1f", d)
}
