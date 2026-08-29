package com.blackjacktrainer

import android.content.Context
import com.blackjacktrainer.game.Rules
import com.blackjacktrainer.game.Stats

/** Speichert Tischregeln, Trainingsoptionen, Guthaben und Statistik. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("blackjack_trainer", Context.MODE_PRIVATE)

    var decks: Int
        get() = sp.getInt("decks", 6)
        set(v) = sp.edit().putInt("decks", v).apply()

    var hitsSoft17: Boolean
        get() = sp.getBoolean("h17", true)
        set(v) = sp.edit().putBoolean("h17", v).apply()

    var blackjack32: Boolean
        get() = sp.getBoolean("bj32", true)
        set(v) = sp.edit().putBoolean("bj32", v).apply()

    var doubleAfterSplit: Boolean
        get() = sp.getBoolean("das", true)
        set(v) = sp.edit().putBoolean("das", v).apply()

    var lateSurrender: Boolean
        get() = sp.getBoolean("surrender", true)
        set(v) = sp.edit().putBoolean("surrender", v).apply()

    var autoTip: Boolean
        get() = sp.getBoolean("auto_tip", true)
        set(v) = sp.edit().putBoolean("auto_tip", v).apply()

    var warnOnMistake: Boolean
        get() = sp.getBoolean("warn", true)
        set(v) = sp.edit().putBoolean("warn", v).apply()

    var counting: Boolean
        get() = sp.getBoolean("counting", false)
        set(v) = sp.edit().putBoolean("counting", v).apply()

    var bankroll: Int
        get() = sp.getInt("bankroll", 1000)
        set(v) = sp.edit().putInt("bankroll", v).apply()

    var lastBet: Int
        get() = sp.getInt("last_bet", 25)
        set(v) = sp.edit().putInt("last_bet", v).apply()

    fun rules(): Rules = Rules(
        numDecks = decks,
        dealerHitsSoft17 = hitsSoft17,
        blackjackPays3to2 = blackjack32,
        doubleAfterSplit = doubleAfterSplit,
        lateSurrender = lateSurrender
    )

    fun saveStats(s: Stats) {
        sp.edit()
            .putInt("s_hands", s.handsPlayed)
            .putInt("s_won", s.won)
            .putInt("s_lost", s.lost)
            .putInt("s_push", s.pushed)
            .putInt("s_bj", s.blackjacks)
            .putInt("s_dec", s.decisions)
            .putInt("s_cor", s.correctDecisions)
            .putInt("s_net", s.net)
            .apply()
    }

    fun loadStats(s: Stats) {
        s.handsPlayed = sp.getInt("s_hands", 0)
        s.won = sp.getInt("s_won", 0)
        s.lost = sp.getInt("s_lost", 0)
        s.pushed = sp.getInt("s_push", 0)
        s.blackjacks = sp.getInt("s_bj", 0)
        s.decisions = sp.getInt("s_dec", 0)
        s.correctDecisions = sp.getInt("s_cor", 0)
        s.net = sp.getInt("s_net", 0)
    }
}
