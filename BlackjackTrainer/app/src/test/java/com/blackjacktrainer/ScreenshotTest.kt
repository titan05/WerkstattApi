package com.blackjacktrainer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blackjacktrainer.databinding.ActivityMainBinding
import com.blackjacktrainer.databinding.ActivityLiveBinding
import com.blackjacktrainer.databinding.ActivityStrategyBinding
import com.blackjacktrainer.game.BlackjackGame
import com.blackjacktrainer.game.Card
import com.blackjacktrainer.game.Rank
import com.blackjacktrainer.game.Suit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Rendert die Bildschirme in PNG-Dateien unter app/build/screenshots.
 * Kein Test im engeren Sinn - ein Werkzeug, um das Layout ohne Gerät anzusehen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-rDE-w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val outputDir = File("build/screenshots").apply { mkdirs() }

    private fun capture(view: View, name: String, settle: Boolean = true) {
        // Einflug-Animationen der Karten zu Ende laufen lassen
        if (settle) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        val width = 411 * 3
        val height = 891 * 3
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.background?.setBounds(0, 0, width, height)
        view.draw(canvas)
        File(outputDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun screenshots() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = MainActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        val binding = field.get(activity) as ActivityMainBinding
        binding.root.background = activity.getDrawable(R.drawable.bg_felt)

        binding.incBetting.btnClearBet.performClick()
        binding.incBetting.chip25.performClick()
        binding.incBetting.chip100.performClick()
        capture(binding.root, "01_einsatz")

        // Bis zu einem Spielerzug austeilen, damit der Tipp sichtbar wird
        var attempts = 0
        while (attempts++ < 40) {
            if (binding.incBetting.root.visibility == View.VISIBLE) {
                binding.incBetting.btnClearBet.performClick()
                binding.incBetting.chip25.performClick()
                binding.incBetting.btnDeal.performClick()
            }
            if (binding.incInsurance.root.visibility == View.VISIBLE) {
                capture(binding.root, "03_versicherung")
                binding.incInsurance.btnInsuranceNo.performClick()
            }
            if (binding.incActions.root.visibility == View.VISIBLE) break
            if (binding.incRoundOver.root.visibility == View.VISIBLE) {
                binding.incRoundOver.btnNextRound.performClick()
            }
        }
        capture(binding.root, "02_spielzug")

        // Eine Runde zu Ende spielen und das Ergebnis festhalten
        var guard = 0
        while (binding.incActions.root.visibility == View.VISIBLE && guard++ < 20) {
            binding.incActions.btnStand.performClick()
        }
        capture(binding.root, "04_ergebnis")

        val strategy = Robolectric.buildActivity(StrategyActivity::class.java).setup().get()
        val strategyField = StrategyActivity::class.java.getDeclaredField("binding")
        strategyField.isAccessible = true
        val strategyBinding = strategyField.get(strategy) as ActivityStrategyBinding
        strategyBinding.root.background = strategy.getDrawable(R.drawable.bg_felt)
        capture(strategyBinding.root, "05_strategie")
    }

    /**
     * Geteilte Hand und Dealer-Zug lassen sich nicht erwürfeln, deshalb hier
     * mit vorgelegten Karten: Ein Paar Achten gegen eine 6.
     */
    @Test
    fun splitUndDealerZug() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bindingField = MainActivity::class.java.getDeclaredField("binding")
        bindingField.isAccessible = true
        val binding = bindingField.get(activity) as ActivityMainBinding
        binding.root.background = activity.getDrawable(R.drawable.bg_felt)

        val gameField = MainActivity::class.java.getDeclaredField("game")
        gameField.isAccessible = true
        val game = gameField.get(activity) as BlackjackGame

        // Spieler, Dealer offen, Spieler, verdeckt, dann die Split-Karten
        game.shoe.stackTop(
            listOf(
                Card(Rank.EIGHT, Suit.PIK), Card(Rank.SIX, Suit.KARO),
                Card(Rank.EIGHT, Suit.HERZ), Card(Rank.QUEEN, Suit.KREUZ),
                Card(Rank.THREE, Suit.KARO), Card(Rank.KING, Suit.PIK),
                Card(Rank.NINE, Suit.HERZ), Card(Rank.FOUR, Suit.KREUZ)
            )
        )

        binding.incBetting.btnClearBet.performClick()
        binding.incBetting.chip25.performClick()
        binding.incBetting.chip100.performClick()
        binding.incBetting.btnDeal.performClick()
        binding.incActions.btnSplit.performClick()
        capture(binding.root, "06_split")

        // Beide Hände stehen lassen, dann fängt der Dealer an
        var guard = 0
        while (binding.incActions.root.visibility == View.VISIBLE && guard++ < 10) {
            binding.incActions.btnStand.performClick()
        }
        // Mitten im Dealer-Zug: aufgedeckt und die erste Karte gezogen, aber noch
        // nicht abgerechnet. Der Takt läuft über den Handler, ist also im
        // Test beobachtbar - anders als die Animationen.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(900))
        capture(binding.root, "07_dealer", settle = false)

        capture(binding.root, "08_ergebnis")
    }

    @Test
    fun liveModus() {
        val activity = Robolectric.buildActivity(LiveActivity::class.java).setup().get()
        val field = LiveActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        val binding = field.get(activity) as ActivityLiveBinding
        binding.root.background = activity.getDrawable(R.drawable.bg_felt)

        // 16 gegen eine Zehn: du 10, Dealer 10, du 6
        for (label in listOf("10", "10", "6")) tapLive(binding, label)
        capture(binding.root, "09_live")

        // Zweite Entscheidung in anderer Farbe: 20 gegen 6 heißt stehen
        binding.btnNewRound.performClick()
        for (label in listOf("10", "6", "10")) tapLive(binding, label)
        capture(binding.root, "10_live_stehen")

        // Geteilte Hände: Hand 1 rechts, Hand 2 links
        binding.btnNewRound.performClick()
        for (label in listOf("8", "6", "8")) tapLive(binding, label)
        binding.btnSplit.performClick()
        tapLive(binding, "3")
        capture(binding.root, "11_live_split")

        // Volles Blatt: die Karten müssen ohne Scrollen in die Box passen
        for (label in listOf("2", "2", "2", "2")) tapLive(binding, label)
        capture(binding.root, "12_live_viele_karten")
    }

    private fun tapLive(binding: ActivityLiveBinding, label: String) {
        val rows = listOf(binding.keypadRow1, binding.keypadRow2)
        for (row in rows) {
            for (i in 0 until row.childCount) {
                val button = row.getChildAt(i) as android.widget.Button
                if (button.text.toString() == label) {
                    button.performClick()
                    return
                }
            }
        }
    }

    /**
     * Der Einstellungsdialog. Das App-Theme ist die dunkle
     * MaterialComponents-Variante - ohne eigenes Dialog-Theme stünde hier
     * dunkler Text auf dunklem Grund.
     */
    @Test
    fun einstellungen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = MainActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        val binding = field.get(activity) as ActivityMainBinding

        binding.btnSettings.performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val decor = dialog.window!!.decorView

        val width = 411 * 3
        val height = 891 * 3
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val bitmap = Bitmap.createBitmap(decor.measuredWidth, decor.measuredHeight, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        File(outputDir, "13_einstellungen.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}