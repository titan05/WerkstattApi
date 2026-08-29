# Blackjack Trainer

Android-App zum Üben von Blackjack unter Casino-Bedingungen — mit Tipps, die zu
jeder Hand sagen, was mathematisch richtig ist und **warum**.

Fertige APK: [`apk/blackjack-trainer.apk`](apk/blackjack-trainer.apk)

## Installation

1. APK auf das Handy kopieren (oder direkt darauf herunterladen).
2. Datei antippen. Android fragt nach der Erlaubnis, Apps aus dieser Quelle zu
   installieren — erlauben.
3. Fertig. Die App braucht keine Berechtigungen und kein Internet.

Ab Android 7.0 (API 24) lauffähig.

## Was die App kann

**Echtes Casino-Spiel.** Schlitten aus 1–8 Decks mit Cut-Card, Dealer mit
verdeckter Karte und Peek auf Blackjack, Teilen auf bis zu vier Hände,
Verdoppeln, Aufgeben, Versicherung, Blackjack zahlt 3:2. Die Karten fliegen
gestaffelt aus dem Schlitten oben rechts an ihren Platz — erst deine, dann die
des Dealers. Am Schlitten steht, wie viele Decks noch drin sind.

Beim Teilen bekommen **beide** Hände sofort ihre zweite Karte, bevor du die
erste spielst — du siehst also, worauf du dich einlässt. Der Dealer deckt auf
und zieht dann Karte für Karte mit Pause dazwischen; abgerechnet wird erst,
wenn er fertig ist. Einsätze liegen als Jetonstapel auf dem Tisch, aus 500ern,
100ern, 25ern und 5ern zusammengesetzt.

**Tipps mit Begründung.** Vor jeder Entscheidung sagt die App, was die
Basisstrategie vorschreibt, und erklärt in einem Satz, warum — nicht nur
"Stehen", sondern *warum* Stehen gegen eine 6 richtig ist. Der Button der
empfohlenen Aktion glüht dabei golden auf. Wahlweise automatisch oder erst auf
Knopfdruck ("Zeigen"), damit du dich selbst prüfen kannst — solange der Tipp
verdeckt ist, glüht auch nichts.

**Fehler-Feedback.** Weichst du vom Tipp ab, sagt die App direkt, was besser
gewesen wäre. Eine Trefferquote in der Fußzeile zeigt, wie sauber du spielst.

**Live-Modus** (Knopf „LIVE" oben). Für den echten Tisch: Ein großes
Tastenfeld, das immer an derselben Stelle liegt. Der erste Druck ist die
offene Karte des Dealers, die nächsten sind deine eigenen — die App weiß
selbst, was gerade dran ist, und sagt es in der Zeile über dem Tastenfeld.
Drei Antippen bis zur Empfehlung, danach reicht pro gezogener Karte ein
weiterer Druck. „Zurück" nimmt die letzte Eingabe zurück, „Neue Hand" behält
die Dealerkarte (für die zweite Hand nach einem Split), „Neue Runde" leert
alles. Bube, Dame und König werden als 10 eingegeben.

Über „Count" lässt sich eine Zeile für die Hi-Lo-Zählung einblenden: Running
Count hoch/runter, verbleibende Decks, daraus der True Count. Gezählt wird von
Hand, denn am Tisch zählt man alle Karten, nicht nur die eigenen — automatisch
mitgezählte Eingaben ergäben einen falschen Count, der richtig aussieht. Ist
die Zeile sichtbar, berücksichtigen die Empfehlungen die Abweichungen.

**Strategietabelle.** Über das Symbol oben rechts: die komplette Tabelle für
harte Hände, Soft-Hände und Paare. Tippe auf ein Feld für die Begründung. Die
Tabelle wird aus derselben Engine erzeugt wie die Tipps im Spiel und passt sich
automatisch an die eingestellten Tischregeln an.

**Kartenzählen (optional).** Hi-Lo-Modus mit Running Count und True Count. Die
verdeckte Karte des Dealers wird — wie am echten Tisch — erst beim Aufdecken
mitgezählt. Die Tipps berücksichtigen dann die Abweichungen der
"Illustrious 18" und die Versicherung ab True Count +3.

**Einstellbare Tischregeln.** Anzahl Decks, H17/S17, Blackjack 3:2 oder 6:5,
Verdoppeln nach Teilen, Aufgeben. Die Tipps ändern sich entsprechend — bei
H17 wird Soft 18 gegen die 2 verdoppelt, bei S17 nicht.

## Aufbau

```
app/src/main/java/com/blackjacktrainer/
  game/          Spiellogik, ohne Android-Abhängigkeiten
    Card, Shoe, Hand, Rules      Karten, Schlitten, Hi-Lo-Count
    BasicStrategy                Basisstrategie + Begründungstexte
    CountStrategy                Hi-Lo-Abweichungen, Versicherung
    BlackjackGame                Ablauf, Splits, Auszahlungen, Statistik
  ui/PlayingCardView             gezeichnete Spielkarte
  ui/ChipStackView               Betrag als Jetonstapel
  MainActivity                   Spieltisch
  LiveActivity                   Berater für den echten Tisch
  StrategyActivity               Strategietabelle
  SettingsDialog                 Tischregeln, von beiden Modi genutzt
```

Die Spiellogik hängt nicht am Android-Framework und ist deshalb komplett auf
der JVM testbar.

## Bauen und testen

```bash
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleRelease      # APK -> app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # alle Tests
```

Die Tests vergleichen die Engine Zelle für Zelle mit der veröffentlichten
Multi-Deck-Basisstrategie (harte Hände, Soft-Hände, Paare, jeweils für H17 und
S17), prüfen Auszahlungen, Splits, Versicherung und Kartenzählung und spielen
die Oberfläche mit Robolectric 100 Runden lang durch.

Robolectric schaltet Animatoren ab, der Einflug ist dort also nicht
beobachtbar. Getestet wird stattdessen seine Geometrie: dass jede neue Karte
ihren Weg am Schlitten beginnt.

`ScreenshotTest` rendert die Bildschirme ohne Gerät nach
`app/build/screenshots/`.

## Signierung

Die mitgelieferte APK ist mit einem lokal erzeugten Schlüssel signiert. Der
Schlüssel selbst liegt bewusst **nicht** im Repo. Wer selbst eine Release-APK
bauen will, legt sich einen eigenen an:

```bash
keytool -genkeypair -v -keystore blackjack-trainer.keystore \
  -alias blackjacktrainer -keyalg RSA -keysize 2048 -validity 10000

cat > keystore.properties <<EOF
storeFile=blackjack-trainer.keystore
storePassword=DEIN_PASSWORT
keyAlias=blackjacktrainer
keyPassword=DEIN_PASSWORT
EOF
```

Beide Dateien sind in `.gitignore`. Fehlen sie, baut Gradle einfach mit dem
Debug-Schlüssel weiter — die APK ist dann trotzdem installierbar. Wichtig: Eine
APK mit anderem Schlüssel lässt sich nicht über eine bereits installierte
drüberinstallieren, dafür muss die alte Version zuerst deinstalliert werden.

## Ehrlicher Hinweis

Perfekte Basisstrategie drückt den Hausvorteil auf etwa 0,5 % — sie beseitigt
ihn nicht. Auf Dauer verliert man. Die App ist zum Üben da, nicht als
Gewinnstrategie.
