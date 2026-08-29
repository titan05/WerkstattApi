package com.blackjacktrainer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blackjacktrainer.databinding.ActivityStrategyBinding
import com.blackjacktrainer.game.Action
import com.blackjacktrainer.game.BasicStrategy
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.Hand
import com.blackjacktrainer.game.Options
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Suit

/**
 * Die Strategietabelle wird aus derselben Engine erzeugt, die auch die Tipps
 * im Spiel gibt - sie kann also nie davon abweichen und passt sich automatisch
 * an die eingestellten Tischregeln an.
 */
class StrategyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStrategyBinding
    private lateinit var rules: Rules

    private val dealerUpcards = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStrategyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        rules = Prefs(this).rules()
        binding.strategyRules.text = rules.describe()

        buildTable(
            "HARTE HÄNDE",
            (8..17).reversed().map { total -> total.toString() to hardHand(total) }
        )
        buildTable(
            "SOFT-HÄNDE (mit Ass)",
            (9 downTo 2).map { other -> "A-$other" to softHand(other) }
        )
        buildTable(
            "PAARE",
            listOf("A-A" to pairHand(Rank.ACE)) +
                listOf(10, 9, 8, 7, 6, 5, 4, 3, 2).map { v ->
                    "$v-$v" to pairHand(rankForValue(v))
                }
        )
        buildLegend()
    }

    private fun hardHand(total: Int): Hand {
        // Zweikartenkombination ohne Ass und ohne Paar
        val second = when {
            total == 10 -> 4      // 6+4 statt 5+5, damit es kein Paar wird
            total <= 11 -> total - 5
            else -> total - 9
        }
        val first = total - second
        val hand = Hand(10)
        hand.add(Card(rankForValue(first), Suit.PIK))
        hand.add(Card(rankForValue(second), Suit.HERZ))
        return hand
    }

    private fun softHand(other: Int): Hand {
        val hand = Hand(10)
        hand.add(Card(Rank.ACE, Suit.PIK))
        hand.add(Card(rankForValue(other), Suit.HERZ))
        return hand
    }

    private fun pairHand(rank: Rank): Hand {
        val hand = Hand(10)
        hand.add(Card(rank, Suit.PIK))
        hand.add(Card(rank, Suit.HERZ))
        return hand
    }

    private fun rankForValue(value: Int): Rank = when (value) {
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

    private fun buildTable(title: String, rows: List<Pair<String, Hand>>) {
        val container = binding.strategyContainer
        container.addView(sectionTitle(title))

        // Kopfzeile mit den Dealerkarten
        val header = row()
        header.addView(cell("", Color.TRANSPARENT, bold = true))
        for (up in dealerUpcards) {
            header.addView(cell(if (up == 11) "A" else "$up", Color.TRANSPARENT, bold = true))
        }
        container.addView(header)

        for ((label, hand) in rows) {
            val tableRow = row()
            tableRow.addView(cell(label, Color.TRANSPARENT, bold = true))
            for (up in dealerUpcards) {
                val upCard = Card(rankForValue(up), Suit.KARO)
                val options = Options(
                    canHit = true,
                    canStand = true,
                    canDouble = rules.doubleAnyTwo || hand.total in 9..11,
                    canSplit = hand.isPair,
                    canSurrender = rules.lateSurrender
                )
                val advice = BasicStrategy.advise(hand, upCard, rules, options)
                val view = cell(advice.action.short, colorFor(advice.action), bold = true)
                view.setTextColor(Color.WHITE)
                view.setOnClickListener {
                    Toast.makeText(
                        this,
                        "$label gegen ${if (up == 11) "Ass" else up}: ${advice.action.label}\n\n${advice.reason}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                tableRow.addView(view)
            }
            container.addView(tableRow)
        }
    }

    private fun colorFor(action: Action): Int = when (action) {
        Action.HIT -> Color.parseColor("#C0392B")
        Action.STAND -> Color.parseColor("#1E8449")
        Action.DOUBLE -> Color.parseColor("#1F6FB2")
        Action.SPLIT -> Color.parseColor("#7D3C98")
        Action.SURRENDER -> Color.parseColor("#5D6D7E")
    }

    private fun row(): LinearLayout {
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.HORIZONTAL
        layout.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return layout
    }

    private fun cell(text: String, background: Int, bold: Boolean = false): TextView {
        val view = TextView(this)
        view.text = text
        view.gravity = Gravity.CENTER
        view.textSize = 12f
        view.setTextColor(Color.WHITE)
        if (bold) view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
        view.setBackgroundColor(background)
        val params = LinearLayout.LayoutParams(0, dp(30), 1f)
        params.setMargins(dp(1), dp(1), dp(1), dp(1))
        view.layoutParams = params
        return view
    }

    private fun sectionTitle(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 12f
        view.letterSpacing = 0.14f
        view.setTextColor(Color.parseColor("#BFE3CC"))
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(18)
        params.bottomMargin = dp(6)
        view.layoutParams = params
        return view
    }

    private fun buildLegend() {
        val container = binding.strategyContainer
        container.addView(sectionTitle("LEGENDE"))
        val entries = listOf(
            Action.HIT to "Karte nehmen",
            Action.STAND to "Stehen bleiben",
            Action.DOUBLE to "Verdoppeln (wenn nicht möglich: ziehen, bei Soft 18/19 stehen)",
            Action.SPLIT to "Teilen",
            Action.SURRENDER to "Aufgeben (wenn nicht möglich: nach der Tabelle für harte Hände spielen)"
        )
        for ((action, description) in entries) {
            val legendRow = row()
            val badge = cell(action.short, colorFor(action), bold = true)
            badge.layoutParams = LinearLayout.LayoutParams(dp(34), dp(28)).also {
                it.setMargins(dp(1), dp(2), dp(8), dp(2))
            }
            legendRow.addView(badge)

            val text = TextView(this)
            text.text = description
            text.textSize = 12f
            text.setTextColor(Color.parseColor("#E6F3EA"))
            text.gravity = Gravity.CENTER_VERTICAL
            text.layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f)
            legendRow.addView(text)
            container.addView(legendRow)
        }

        val note = TextView(this)
        note.text = "Tippe auf ein Feld, um die Begründung zu sehen. Die Tabelle richtet sich " +
            "nach deinen Einstellungen: ${rules.describe()}. Harte 8 und weniger heißt immer " +
            "„Karte“, harte 17 und mehr immer „Stehen“."
        note.textSize = 12f
        note.setTextColor(Color.parseColor("#BFE3CC"))
        note.setPadding(0, dp(14), 0, dp(20))
        container.addView(note)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
