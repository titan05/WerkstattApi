package com.blackjacktrainer

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blackjacktrainer.databinding.ActivityLiveBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Live-Modus: Eingegeben wird wie am Tisch ausgeteilt wird - deine erste
 * Karte, die Karte des Dealers, deine zweite. Geteilte Hände stehen
 * nebeneinander, Hand 1 rechts.
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
        for (row in listOf(binding.keypadRow1, binding.keypadRow2)) {
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

    private fun tapAll(binding: ActivityLiveBinding, vararg labels: String) {
        for (label in labels) tap(binding, label)
    }

    /** Hand 1 steht rechts, liegt im Layout also als letztes Kind. */
    private fun handBox(binding: ActivityLiveBinding, index: Int): View {
        val row = binding.playerHandsRow
        return row.getChildAt(row.childCount - 1 - index)
    }

    private fun handCards(binding: ActivityLiveBinding, index: Int = 0) =
        handBox(binding, index).findViewById<LinearLayout>(R.id.liveHandCards)

    private fun handLabel(binding: ActivityLiveBinding, index: Int = 0) =
        handBox(binding, index).findViewById<TextView>(R.id.liveHandLabel).text.toString()

    private fun action(binding: ActivityLiveBinding) = binding.adviceAction.text.toString()

    private fun bannerColor(binding: ActivityLiveBinding): Int =
        (binding.advicePanel.background as GradientDrawable).color!!.defaultColor

    // ------------------------------------------------------------ Grundlagen

    @Test
    fun tastenfeldHatAlleZehnWerte() {
        val (_, binding) = launch()
        assertEquals(5, binding.keypadRow1.childCount)
        assertEquals(5, binding.keypadRow2.childCount)
        for (label in listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "A")) {
            tap(binding, label)
            binding.btnNewRound.performClick()
        }
    }

    @Test
    fun reihenfolgeIstDeineKarteDannDealerDannDeineKarte() {
        val (_, binding) = launch()
        // Es beginnt bei dir, nicht beim Dealer
        assertTrue(handLabel(binding).contains("EINGABE"))
        assertEquals("Deine erste Karte eintippen", binding.keypadPrompt.text.toString())

        tap(binding, "8")
        assertEquals(1, handCards(binding).childCount)
        assertEquals(0, binding.dealerSlot.childCount)
        // Jetzt ist der Dealer dran
        assertTrue(binding.dealerSlotLabel.text.toString().contains("EINGABE"))
        assertEquals("Karte des Dealers eintippen", binding.keypadPrompt.text.toString())

        tap(binding, "6")
        assertEquals(1, binding.dealerSlot.childCount)
        // und danach wieder du
        assertTrue(handLabel(binding).contains("EINGABE"))
        assertEquals("Deine zweite Karte eintippen", binding.keypadPrompt.text.toString())

        tap(binding, "8")
        assertEquals(2, handCards(binding).childCount)
        assertEquals("TEILEN", action(binding))
    }

    @Test
    fun sechzehnGegenZehnWirdAufgegeben() {
        val (_, binding) = launch()
        tapAll(binding, "10", "10", "6") // du 10, Dealer 10, du 6
        assertEquals("AUFGEBEN", action(binding))
    }

    @Test
    fun softAchtzehnGegenNeunWirdGezogen() {
        val (_, binding) = launch()
        tapAll(binding, "A", "9", "7")
        assertEquals("KARTE NEHMEN", action(binding))
    }

    @Test
    fun elfWirdVerdoppeltAberNachDerDrittenKarteNichtMehr() {
        val (_, binding) = launch()
        tapAll(binding, "5", "6", "6") // du 5, Dealer 6, du 6 -> 11
        assertEquals("VERDOPPELN", action(binding))

        tap(binding, "2") // 13 gegen 6
        assertEquals("STEHEN BLEIBEN", action(binding))
    }

    @Test
    fun ueberkaufteHandWirdAlsSolcheGemeldet() {
        val (_, binding) = launch()
        tapAll(binding, "10", "7", "9", "5") // 10 + 9 + 5 = 24
        assertEquals("ÜBERKAUFT", action(binding))
    }

    @Test
    fun blackjackWirdErkannt() {
        val (_, binding) = launch()
        tapAll(binding, "A", "7", "10")
        assertEquals("BLACKJACK", action(binding))
    }

    @Test
    fun beimAssKommtDerVersicherungshinweis() {
        val (_, binding) = launch()
        tapAll(binding, "5", "A")
        assertEquals(View.VISIBLE, binding.adviceInsurance.visibility)
        assertTrue(binding.adviceInsurance.text.toString().startsWith("Versicherung: nein"))

        binding.btnNewRound.performClick()
        tapAll(binding, "5", "6")
        assertEquals(View.GONE, binding.adviceInsurance.visibility)
    }

    // ---------------------------------------------------------------- Teilen

    @Test
    fun teilenErzeugtZweiHaendeMitHandEinsRechts() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")
        assertEquals("TEILEN", action(binding))
        assertTrue(binding.btnSplit.isEnabled)

        binding.btnSplit.performClick()
        assertEquals(2, binding.playerHandsRow.childCount)
        // Beide Hände haben je eine Karte
        assertEquals(1, handCards(binding, 0).childCount)
        assertEquals(1, handCards(binding, 1).childCount)
        // Hand 1 liegt rechts, also als letztes Kind der Reihe
        val row = binding.playerHandsRow
        val rightmost = row.getChildAt(row.childCount - 1)
        assertEquals(
            "HAND 1 ▸ EINGABE",
            rightmost.findViewById<TextView>(R.id.liveHandLabel).text.toString()
        )
        val leftmost = row.getChildAt(0)
        assertEquals(
            "HAND 2",
            leftmost.findViewById<TextView>(R.id.liveHandLabel).text.toString()
        )
        // Weitergespielt wird mit Hand 1
        assertEquals("Karte für Hand 1 eintippen", binding.keypadPrompt.text.toString())
    }

    @Test
    fun splitKnopfIstNurBeiEinemPaarAktiv() {
        val (_, binding) = launch()
        assertFalse(binding.btnSplit.isEnabled)

        tapAll(binding, "8", "6", "9") // kein Paar
        assertFalse(binding.btnSplit.isEnabled)

        binding.btnNewRound.performClick()
        tapAll(binding, "8", "6", "8")
        assertTrue(binding.btnSplit.isEnabled)
    }

    @Test
    fun nachDemTeilenGiltDieRegelFuerGeteilteHaende() {
        val (_, binding) = launch()
        tapAll(binding, "8", "10", "8")
        // Ungeteilt wäre die 16 gegen eine Zehn ein Fall zum Aufgeben
        assertEquals("TEILEN", action(binding))
        binding.btnSplit.performClick()

        // Hand 1: 8 + 7 = 15 gegen 10. Aufgeben ist nach einem Split nicht
        // mehr erlaubt, also wird gezogen.
        tap(binding, "7")
        assertEquals("KARTE NEHMEN", action(binding))
    }

    @Test
    fun zwischenGeteiltenHaendenLaesstSichWechseln() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")
        binding.btnSplit.performClick()

        // Auf Hand 2 wechseln (links) und ihr eine Karte geben
        handBox(binding, 1).performClick()
        assertEquals("Karte für Hand 2 eintippen", binding.keypadPrompt.text.toString())
        tap(binding, "3")
        assertEquals(2, handCards(binding, 1).childCount)
        assertEquals(1, handCards(binding, 0).childCount)
    }

    @Test
    fun mehrfachesTeilenErzeugtWeitereHaende() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")
        binding.btnSplit.performClick()
        // Hand 1 bekommt wieder eine 8 - erneut teilbar
        tap(binding, "8")
        assertTrue(binding.btnSplit.isEnabled)
        binding.btnSplit.performClick()
        assertEquals(3, binding.playerHandsRow.childCount)
    }

    // ----------------------------------------------------------- Bedienung

    @Test
    fun zurueckNimmtDieLetzteEingabeZurueck() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")

        binding.btnUndoCard.performClick()
        assertEquals(1, handCards(binding).childCount)
        binding.btnUndoCard.performClick()
        assertEquals(0, handCards(binding).childCount)
        // Jetzt ist die Dealerkarte an der Reihe
        binding.btnUndoCard.performClick()
        assertEquals(0, binding.dealerSlot.childCount)
    }

    @Test
    fun zurueckWirktAufDasAusgewaehlteFeld() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")

        binding.dealerSlotBox.performClick()
        binding.btnUndoCard.performClick()
        assertEquals(0, binding.dealerSlot.childCount)
        assertEquals(2, handCards(binding).childCount)
    }

    @Test
    fun zielLaesstSichDurchAntippenWechseln() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6")
        assertEquals(1, binding.dealerSlot.childCount)

        // Dealerkarte korrigieren, ohne die eigene Karte zu verlieren
        binding.dealerSlotBox.performClick()
        tap(binding, "10")
        assertEquals(1, binding.dealerSlot.childCount)
        assertEquals(1, handCards(binding).childCount)
        assertTrue(handLabel(binding).contains("EINGABE"))
    }

    @Test
    fun neueRundeLeertAllesUndBeginntWiederBeiDir() {
        val (_, binding) = launch()
        tapAll(binding, "8", "6", "8")
        binding.btnSplit.performClick()

        binding.btnNewRound.performClick()
        assertEquals(1, binding.playerHandsRow.childCount)
        assertEquals(0, binding.dealerSlot.childCount)
        assertEquals(0, handCards(binding).childCount)
        assertTrue(handLabel(binding).contains("EINGABE"))
        assertEquals("Deine erste Karte eintippen", binding.keypadPrompt.text.toString())
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
        tapAll(binding, "10", "10", "6")
        assertEquals("STEHEN BLEIBEN", action(binding))
        assertEquals(View.VISIBLE, binding.adviceCountNote.visibility)
    }

    @Test
    fun jedeEntscheidungHatIhreEigeneFarbe() {
        val (_, binding) = launch()

        tapAll(binding, "10", "6", "10") // 20 gegen 6: stehen
        assertEquals("STEHEN BLEIBEN", action(binding))
        val stand = bannerColor(binding)

        binding.btnNewRound.performClick()
        tapAll(binding, "10", "10", "6") // 16 gegen 10: aufgeben
        val surrender = bannerColor(binding)

        binding.btnNewRound.performClick()
        tapAll(binding, "5", "6", "6") // 11 gegen 6: verdoppeln
        val double = bannerColor(binding)

        binding.btnNewRound.performClick()
        tapAll(binding, "8", "10", "4") // 12 gegen 10: ziehen
        val hit = bannerColor(binding)

        assertEquals("Farben müssen sich unterscheiden", 4, listOf(stand, surrender, double, hit).toSet().size)
    }
}
