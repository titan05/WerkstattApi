package com.blackjacktrainer

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blackjacktrainer.databinding.ActivityLiveBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Der Live-Modus soll in drei Antippen zur Empfehlung führen: Dealerkarte,
 * eigene Karte, eigene Karte. Genau das prüfen diese Tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-rDE-w411dp-h891dp-xxhdpi")
class LiveModeTest {

    private fun launch(): Pair<LiveActivity, ActivityLiveBinding> {
        val activity = Robolectric.buildActivity(LiveActivity::class.java).setup().get()
        val field = LiveActivity::class.java.getDeclaredField("binding")
        field.isAccessible = true
        return activity to field.get(activity) as ActivityLiveBinding
    }

    /** Tippt eine Karte auf dem Tastenfeld an. */
    private fun tap(binding: ActivityLiveBinding, label: String) {
        val rows = listOf(binding.keypadRow1, binding.keypadRow2)
        for (row in rows) {
            for (i in 0 until row.childCount) {
                val button = row.getChildAt(i) as Button
                if (button.text.toString() == label) {
                    button.performClick()
                    return
                }
            }
        }
        throw AssertionError("Keine Taste für $label gefunden")
    }

    @Test
    fun tastenfeldHatAlleZehnWerte() {
        val (_, binding) = launch()
        assertEquals(5, binding.keypadRow1.childCount)
        assertEquals(5, binding.keypadRow2.childCount)
        for (label in listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "A")) {
            tap(binding, label) // wirft, wenn eine Taste fehlt
            binding.btnNewRound.performClick()
        }
    }

    @Test
    fun ersteTasteIstDerDealerDannDieEigenenKarten() {
        val (_, binding) = launch()
        assertTrue(binding.keypadPrompt.text.toString().contains("Dealer"))

        tap(binding, "6")
        assertEquals(1, binding.dealerSlot.childCount)
        assertEquals(0, binding.liveCards.childCount)
        assertEquals("Deine erste Karte eintippen", binding.keypadPrompt.text.toString())

        tap(binding, "8")
        assertEquals(1, binding.liveCards.childCount)
        assertEquals("Deine zweite Karte eintippen", binding.keypadPrompt.text.toString())

        tap(binding, "8")
        assertEquals(2, binding.liveCards.childCount)
        // 8-8 gegen 6 wird geteilt
        assertEquals("TEILEN", binding.adviceAction.text.toString())
        assertTrue(binding.adviceReason.text.isNotEmpty())
    }

    @Test
    fun sechzehnGegenZehnWirdAufgegeben() {
        val (_, binding) = launch()
        tap(binding, "10")
        tap(binding, "10")
        tap(binding, "6")
        assertEquals("AUFGEBEN", binding.adviceAction.text.toString())
    }

    @Test
    fun softAchtzehnGegenNeunWirdGezogen() {
        val (_, binding) = launch()
        tap(binding, "9")
        tap(binding, "A")
        tap(binding, "7")
        assertEquals("KARTE NEHMEN", binding.adviceAction.text.toString())
    }

    @Test
    fun elfWirdVerdoppeltAberNachDemDrittenBlattNichtMehr() {
        val (_, binding) = launch()
        tap(binding, "6")
        tap(binding, "5")
        tap(binding, "6")
        assertEquals("VERDOPPELN", binding.adviceAction.text.toString())

        // Dritte Karte: verdoppeln ist vorbei, aus 13 gegen 6 wird Stehen
        tap(binding, "2")
        assertEquals("STEHEN BLEIBEN", binding.adviceAction.text.toString())
    }

    @Test
    fun ueberkaufteHandWirdAlsSolcheGemeldet() {
        val (_, binding) = launch()
        tap(binding, "7")
        tap(binding, "10")
        tap(binding, "9")
        tap(binding, "5")
        assertEquals("ÜBERKAUFT", binding.adviceAction.text.toString())
    }

    @Test
    fun blackjackWirdErkannt() {
        val (_, binding) = launch()
        tap(binding, "7")
        tap(binding, "A")
        tap(binding, "10")
        assertEquals("BLACKJACK", binding.adviceAction.text.toString())
    }

    @Test
    fun beimAssKommtDerVersicherungshinweis() {
        val (_, binding) = launch()
        tap(binding, "A")
        assertEquals(View.VISIBLE, binding.adviceInsurance.visibility)
        assertTrue(binding.adviceInsurance.text.toString().startsWith("Versicherung: nein"))

        binding.btnNewRound.performClick()
        tap(binding, "6")
        assertEquals(View.GONE, binding.adviceInsurance.visibility)
    }

    @Test
    fun zurueckNimmtImmerDieLetzteEingabeZurueck() {
        val (_, binding) = launch()
        tap(binding, "6")
        tap(binding, "8")
        tap(binding, "8")

        binding.btnUndoCard.performClick()
        assertEquals(1, binding.liveCards.childCount)
        binding.btnUndoCard.performClick()
        assertEquals(0, binding.liveCards.childCount)
        // Jetzt ist die Dealerkarte dran
        binding.btnUndoCard.performClick()
        assertEquals(0, binding.dealerSlot.childCount)
        assertTrue(binding.keypadPrompt.text.toString().contains("Dealer"))
    }

    @Test
    fun neueRundeLeertAuchDenDealerUndBeginntWiederDort() {
        val (_, binding) = launch()
        tap(binding, "6")
        tap(binding, "8")
        tap(binding, "8")

        binding.btnNewRound.performClick()
        assertEquals(0, binding.dealerSlot.childCount)
        assertEquals(0, binding.liveCards.childCount)
        // Die Eingabe steht wieder beim Dealer
        assertTrue(binding.dealerSlotLabel.text.toString().contains("EINGABE"))
        assertTrue(binding.keypadPrompt.text.toString().contains("Dealer"))

        // und der erste Druck landet folgerichtig dort
        tap(binding, "9")
        assertEquals(1, binding.dealerSlot.childCount)
        assertEquals(0, binding.liveCards.childCount)
    }

    @Test
    fun countPanelLaesstSichEinUndAusblenden() {
        val (_, binding) = launch()
        val before = binding.countPanel.visibility
        binding.btnToggleCount.performClick()
        assertTrue(binding.countPanel.visibility != before)

        // Mit sichtbarem Count greifen die Abweichungen: 16 gegen 10 bei
        // hohem True Count heißt stehen statt aufgeben.
        if (binding.countPanel.visibility != View.VISIBLE) binding.btnToggleCount.performClick()
        repeat(12) { binding.btnCountUp.performClick() }
        tap(binding, "10")
        tap(binding, "10")
        tap(binding, "6")
        assertEquals("STEHEN BLEIBEN", binding.adviceAction.text.toString())
        assertEquals(View.VISIBLE, binding.adviceCountNote.visibility)
    }

    @Test
    fun eingabezielIstBeschriftetUndWechseltNachDerDealerkarte() {
        val (_, binding) = launch()
        assertTrue(binding.dealerSlotLabel.text.toString().contains("EINGABE"))
        assertTrue(!binding.playerSlotLabel.text.toString().contains("EINGABE"))

        tap(binding, "6")
        // Die Dealerkarte gibt es nur einmal, danach ist die Hand dran
        assertTrue(!binding.dealerSlotLabel.text.toString().contains("EINGABE"))
        assertTrue(binding.playerSlotLabel.text.toString().contains("EINGABE"))
    }

    @Test
    fun zielLaesstSichDurchAntippenWechseln() {
        val (_, binding) = launch()
        tap(binding, "6")
        tap(binding, "8")
        assertEquals(1, binding.liveCards.childCount)

        // Zurück zum Dealer wechseln und die Karte korrigieren
        binding.dealerSlotBox.performClick()
        assertTrue(binding.dealerSlotLabel.text.toString().contains("EINGABE"))
        tap(binding, "10")
        assertEquals(1, binding.dealerSlot.childCount)
        // Die eigene Karte bleibt unangetastet
        assertEquals(1, binding.liveCards.childCount)
        // und danach ist wieder die Hand dran
        assertTrue(binding.playerSlotLabel.text.toString().contains("EINGABE"))
    }

    @Test
    fun zurueckWirktAufDasAusgewaehlteFeld() {
        val (_, binding) = launch()
        tap(binding, "6")
        tap(binding, "8")

        binding.dealerSlotBox.performClick()
        binding.btnUndoCard.performClick()
        assertEquals(0, binding.dealerSlot.childCount)
        assertEquals(1, binding.liveCards.childCount)
    }

    private fun bannerColor(binding: ActivityLiveBinding): Int =
        (binding.advicePanel.background as GradientDrawable).color!!.defaultColor

    @Test
    fun jedeEntscheidungHatIhreEigeneFarbe() {
        val (_, binding) = launch()

        // 20 gegen 6: stehen
        tap(binding, "6"); tap(binding, "10"); tap(binding, "10")
        assertEquals("STEHEN BLEIBEN", binding.adviceAction.text.toString())
        val stand = bannerColor(binding)

        // 16 gegen 10: aufgeben
        binding.btnNewRound.performClick()
        tap(binding, "10"); tap(binding, "10"); tap(binding, "6")
        val surrender = bannerColor(binding)

        // 11 gegen 6: verdoppeln
        binding.btnNewRound.performClick()
        tap(binding, "6"); tap(binding, "5"); tap(binding, "6")
        val double = bannerColor(binding)

        // 12 gegen 10: ziehen
        binding.btnNewRound.performClick()
        tap(binding, "10"); tap(binding, "8"); tap(binding, "4")
        val hit = bannerColor(binding)

        val colors = listOf(stand, surrender, double, hit)
        assertEquals("Farben müssen sich unterscheiden", 4, colors.toSet().size)
    }
}