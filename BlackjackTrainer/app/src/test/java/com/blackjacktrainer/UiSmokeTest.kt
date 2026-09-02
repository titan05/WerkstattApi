package com.blackjacktrainer

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blackjacktrainer.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Spielt die App auf der JVM durch: Layout aufbauen, setzen, geben,
 * Karte nehmen, stehen bleiben, nächste Runde, Strategietabelle öffnen.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-rDE-w411dp-h891dp-xxhdpi")
class UiSmokeTest {

    /** Der Dealer-Zug läuft über den Handler - hier abwarten, bis er durch ist. */
    private fun settleDealer() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
    }

    private fun launch(): Pair<MainActivity, ActivityMainBinding> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = MainActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        return activity to field.get(activity) as ActivityMainBinding
    }

    @Test
    fun appStartetUndZeigtDieEinsatzleiste() {
        val (_, binding) = launch()
        assertEquals(View.VISIBLE, binding.incBetting.root.visibility)
        assertEquals(View.GONE, binding.incActions.root.visibility)
        assertTrue(binding.bankrollValue.text.isNotEmpty())
        assertTrue(binding.tipReason.text.isNotEmpty())
    }

    @Test
    fun kompletteRundeLaeuftDurch() {
        val (activity, binding) = launch()

        binding.incBetting.btnClearBet.performClick()
        binding.incBetting.chip25.performClick()
        assertTrue(binding.incBetting.betDisplay.text.contains("25"))

        binding.incBetting.btnDeal.performClick()

        // Es liegen Karten auf dem Tisch
        val playerCards = binding.playerHands.getChildAt(0) as LinearLayout
        assertTrue(playerCards.childCount >= 2)
        assertTrue(binding.dealerCards.childCount >= 2)

        // Je nach Blatt sind wir im Spielerzug, in der Versicherung oder schon fertig
        when {
            binding.incInsurance.root.visibility == View.VISIBLE ->
                binding.incInsurance.btnInsuranceNo.performClick()
            else -> {}
        }
        if (binding.incActions.root.visibility == View.VISIBLE) {
            binding.incActions.btnStand.performClick()
        }
        settleDealer()

        assertEquals(View.VISIBLE, binding.incRoundOver.root.visibility)
        assertTrue(binding.statusText.text.isNotEmpty())

        binding.incRoundOver.btnNextRound.performClick()
        assertEquals(View.VISIBLE, binding.incBetting.root.visibility)
        assertTrue(activity.isFinishing.not())
    }

    @Test
    fun hundertRundenOhneAbsturz() {
        val (_, binding) = launch()
        repeat(100) {
            if (binding.incBetting.root.visibility == View.VISIBLE) {
                binding.incBetting.btnClearBet.performClick()
                binding.incBetting.chip5.performClick()
                binding.incBetting.btnDeal.performClick()
            }
            if (binding.incInsurance.root.visibility == View.VISIBLE) {
                binding.incInsurance.btnInsuranceNo.performClick()
            }
            var guard = 0
            while (binding.incActions.root.visibility == View.VISIBLE && guard++ < 25) {
                // Immer dem Tipp folgen - das übt jeden Zweig der Engine durch
                when (binding.tipAction.text.toString()) {
                    "Karte" -> binding.incActions.btnHit.performClick()
                    "Verdoppeln" -> binding.incActions.btnDouble.performClick()
                    "Teilen" -> binding.incActions.btnSplit.performClick()
                    "Aufgeben" -> binding.incActions.btnSurrender.performClick()
                    else -> binding.incActions.btnStand.performClick()
                }
            }
            settleDealer()
            if (binding.incRoundOver.root.visibility == View.VISIBLE) {
                binding.incRoundOver.btnNextRound.performClick()
            }
        }
        assertTrue(binding.statsBar.text.contains("Hände"))
    }

    @Test
    fun strategietabelleOeffnetSich() {
        val (activity, binding) = launch()
        binding.btnChart.performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(StrategyActivity::class.java.name, next.component?.className)

        val strategy = Robolectric.buildActivity(StrategyActivity::class.java).setup().get()
        val field = StrategyActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        val strategyBinding =
            field.get(strategy) as com.blackjacktrainer.databinding.ActivityStrategyBinding
        // 3 Tabellen + Legende erzeugen eine Menge Zeilen
        assertTrue(strategyBinding.strategyContainer.childCount > 30)
    }

    @Test
    fun einstellungenLassenSichOeffnen() {
        val (_, binding) = launch()
        binding.btnSettings.performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog != null && dialog.isShowing)
        // Die Regel-Schalter aus dem Dialog sind da
        assertTrue(dialog.findViewById<View>(R.id.swH17) != null)
        assertTrue(dialog.findViewById<View>(R.id.swSurrender) != null)
        // Deck-Auswahl als Knopfreihe: fünf Möglichkeiten
        val picker = dialog.findViewById<android.widget.LinearLayout>(R.id.deckPicker)
        assertEquals(5, picker.childCount)
    }

    /**
     * Der Einflug selbst ist unter Robolectric nicht beobachtbar - Animatoren
     * sind dort abgeschaltet und springen ans Ende. Prüfbar ist aber die
     * Geometrie: Jede neue Karte muss ihren Flug am Schlitten beginnen, also
     * oberhalb und rechts von ihrem Platz auf dem Tisch.
     */
    @Test
    fun karteStartetAmSchlitten() {
        val (activity, binding) = launch()
        binding.incBetting.btnClearBet.performClick()
        binding.incBetting.chip25.performClick()
        binding.incBetting.btnDeal.performClick()

        val hand = binding.playerHands.getChildAt(0) as LinearLayout
        val cards = hand.findViewById<LinearLayout>(R.id.handCards)
        assertTrue(cards.childCount >= 2)

        val shoe = IntArray(2)
        binding.shoeStack.getLocationOnScreen(shoe)
        assertTrue("Schlitten muss einen Platz auf dem Tisch haben", shoe[0] > 0)

        for (i in 0 until cards.childCount) {
            val card = cards.getChildAt(i)
            val settled = IntArray(2)
            card.getLocationOnScreen(settled)

            activity.placeAtShoe(card)

            // Der Schlitten liegt oben rechts: nach rechts und nach oben
            assertTrue("Karte $i muss von rechts kommen", card.translationX > 0f)
            assertTrue("Karte $i muss von oben kommen", card.translationY < 0f)
            // und zwar über eine spürbare Strecke, nicht nur ein paar Pixel
            assertTrue("Flugstrecke zu kurz", card.translationY < -100f)

            // Startpunkt liegt exakt auf dem Schlitten
            assertEquals(shoe[0], settled[0] + card.translationX.toInt())
            assertEquals(shoe[1], settled[1] + card.translationY.toInt())
        }
    }

    @Test
    fun einsatzWirdAlsJetonstapelGezeigt() {
        val (_, binding) = launch()
        binding.incBetting.btnClearBet.performClick()
        assertEquals(0, binding.incBetting.betChips.amount)

        binding.incBetting.chip100.performClick()
        binding.incBetting.chip25.performClick()
        assertEquals(125, binding.incBetting.betChips.amount)
        // 100er und 25er ergeben zwei Jetons, der Stapel hat also Höhe
        assertTrue(binding.incBetting.betChips.measuredHeight > 0)

        binding.incBetting.btnDeal.performClick()
        val hand = binding.playerHands.getChildAt(0) as LinearLayout
        val chips = hand.findViewById<com.blackjacktrainer.ui.ChipStackView>(R.id.handChips)
        assertEquals(125, chips.amount)
    }

    @Test
    fun dealerZiehtNichtAllesAufEinmal() {
        val (_, binding) = launch()
        var found = false
        repeat(40) {
            if (found) return@repeat
            if (binding.incBetting.root.visibility == View.VISIBLE) {
                binding.incBetting.btnClearBet.performClick()
                binding.incBetting.chip5.performClick()
                binding.incBetting.btnDeal.performClick()
            }
            if (binding.incInsurance.root.visibility == View.VISIBLE) {
                binding.incInsurance.btnInsuranceNo.performClick()
            }
            if (binding.incActions.root.visibility == View.VISIBLE) {
                binding.incActions.btnStand.performClick()
                // Direkt nach dem Stehenbleiben ist der Dealer dran, aber die
                // Runde darf noch nicht abgerechnet sein.
                assertEquals(View.GONE, binding.incRoundOver.root.visibility)
                assertTrue(binding.tipAction.text.toString() == "Dealer zieht")
                found = true
            }
            settleDealer()
            if (binding.incRoundOver.root.visibility == View.VISIBLE) {
                binding.incRoundOver.btnNextRound.performClick()
            }
        }
        assertTrue("Keine Runde bis zum Dealer-Zug gekommen", found)
    }
}