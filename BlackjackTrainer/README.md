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
Tastenfeld, das immer an derselben Stelle liegt. Eingetippt wird in der
Reihenfolge, in der am Tisch ausgeteilt wird — deine erste Karte, die offene
Karte des Dealers, deine zweite. Die App springt selbst zwischen den Feldern
und sagt in der Zeile über dem Tastenfeld, was ein Druck gerade bedeutet.
Drei Antippen bis zur Empfehlung, danach reicht pro gezogener Karte ein
weiterer Druck. „Zurück" nimmt die letzte Eingabe zurück, „Neue Runde" leert
alles und beginnt wieder bei dir. Bube, Dame und König werden als 10
eingegeben.

Welches Feld die Eingabe bekommt, ist immer sichtbar: Dealer oben, deine Hand
darunter, wie am Tisch. Das aktive Feld hat einen goldenen Rahmen und die
Beschriftung „▸ EINGABE". Antippen wechselt das Ziel — praktisch, um eine
falsch eingetippte Dealerkarte zu korrigieren, ohne die Hand neu einzugeben.
„Zurück" wirkt auf das ausgewählte Feld.

**Teilen** macht aus einer Hand zwei. Die Hände stehen dann nebeneinander,
Hand 1 rechts — so wie sie am Tisch gespielt werden. Weitergespielt wird mit
Hand 1; ein Tipp auf die andere Hand wechselt dorthin. Für geteilte Hände
gelten automatisch die passenden Regeln (kein Aufgeben mehr, Verdoppeln nur
mit DAS), und erneutes Teilen ist bis zur vierten Hand möglich.

Die Entscheidung erscheint als farbige Tafel in Klartext („KARTE NEHMEN",
„STEHEN BLEIBEN", „VERDOPPELN", „TEILEN", „AUFGEBEN"), in denselben Farben
wie die Strategietabelle — grün für Stehen, rot für Ziehen, blau für
Verdoppeln, violett für Teilen, grau für Aufgeben.

**Strategietabelle.** Über das Symbol oben rechts: die komplette Tabelle für
harte Hände, Soft-Hände und Paare. Tippe auf ein Feld für die Begründung. Die
Tabelle wird aus derselben Engine erzeugt wie die Tipps im Spiel und passt sich
automatisch an die eingestellten Tischregeln an.

**Einstellbare Tischregeln.** Anzahl Decks, H17/S17, Blackjack 3:2 oder 6:5,
Verdoppeln nach Teilen, Aufgeben. Die Tipps ändern sich entsprechend — bei
H17 wird Soft 18 gegen die 2 verdoppelt, bei S17 nicht.

## Aufbau

```
app/src/main/java/com/blackjacktrainer/
  game/          Spiellogik, ohne Android-Abhängigkeiten
    Card, Shoe, Hand, Rules      Karten, Schlitten, Tischregeln
    BasicStrategy                Basisstrategie + Begründungstexte
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
S17), prüfen Auszahlungen, Splits und Versicherung und spielen
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
