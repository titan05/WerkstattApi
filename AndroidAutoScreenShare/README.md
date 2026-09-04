# Car Screen Mirror – Handybildschirm auf Android Auto

Spiegelt den Bildschirm des Handys auf das Display der Android-Auto-Einheit im Auto.

Dieses Verzeichnis ist ein eigenständiges Android-Projekt und hat mit der .NET-Lösung
`WerkstattApi` im übergeordneten Ordner nichts zu tun – es liegt nur im selben Repository.

---

## Bitte zuerst lesen: was hier möglich ist und was nicht

Android Auto hat **keine offizielle Schnittstelle für Bildschirmspiegelung**. Erlaubt sind nur
Template-Apps aus festgelegten Kategorien (Navigation, Medien, Nachrichten, POI, IOT).
Diese App nutzt deshalb den einzigen praktikablen Weg:

> Eine App der Kategorie **Navigation** bekommt vom Host eine echte `Surface` auf dem
> Autodisplay. Auf diese Surface wird der `MediaProjection`-Stream des Handys gerendert.

Daraus folgen ein paar harte Grenzen, die kein Code umgehen kann:

| Punkt | Status |
| --- | --- |
| Veröffentlichung im Play Store | **Nicht möglich.** Verstößt gegen die Android-Auto-Richtlinien. Nur Sideload für den Eigenbedarf. |
| Installation | Nur per `adb install` / APK, plus „Unbekannte Quellen“ in den Android-Auto-Entwicklereinstellungen. |
| Bedienung am Autodisplay | **Nur Anzeige.** Berührungen am Autodisplay werden nicht ans Handy zurückgeleitet – dafür bräuchte es Root oder eine Systemberechtigung. Bedient wird am Handy. |
| Langlebigkeit | Google kann diesen Weg mit einem Android-Auto-Update jederzeit schließen. Genau das ist bereits mehrfach mit vergleichbaren Apps passiert. |
| Recht & Sicherheit | Videoinhalte im Sichtfeld des Fahrers sind in vielen Ländern (auch in Österreich und Deutschland) verboten. Die App ist für Standbetrieb bzw. Beifahrer gedacht und pausiert die Anzeige standardmäßig, sobald sich das Fahrzeug bewegt. |

---

## Voraussetzungen

* Handy mit **Android 10 (API 29)** oder neuer
* Android Auto (App am Handy) sowie ein Fahrzeug/Head-Unit mit Android Auto
  – oder die **Desktop Head Unit (DHU)** zum Testen am Rechner
* Zum Bauen: JDK 17+ und Android SDK (Plattform 35, Build-Tools 35). Android Studio bringt beides mit.

## Bauen

```bash
cd AndroidAutoScreenShare
./gradlew assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk
```

Alternativ den Ordner `AndroidAutoScreenShare` einfach in Android Studio öffnen.

Der Debug-Build akzeptiert bewusst **jeden** Car-Host (`ALLOW_ALL_HOSTS_VALIDATOR`), damit
Sideload und die Desktop Head Unit funktionieren. Der Release-Build prüft die Host-Signatur
gegen die Allowlist der Car App Library.

## Installieren

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Danach muss Android Auto Apps aus unbekannten Quellen erlauben:

1. Am Handy die **Android-Auto-Einstellungen** öffnen
   (Systemeinstellungen → *Verbundene Geräte* → *Verbindungseinstellungen* → *Android Auto*).
2. Ganz nach unten scrollen und **10× auf „Version“** tippen, bis die Entwicklereinstellungen
   freigeschaltet werden.
3. Oben rechts über das Dreipunkt-Menü die **Entwicklereinstellungen** öffnen und
   **„Unbekannte Quellen“** aktivieren.
4. Android Auto beenden und das Handy neu mit dem Auto verbinden.

Die App erscheint im Autolauncher unter den **Navigations-Apps** als „Car Screen Mirror“.

### Testen ohne Auto (Desktop Head Unit)

1. Im SDK-Manager „Android Auto Desktop Head Unit emulator“ installieren.
2. In den Android-Auto-Entwicklereinstellungen **„Head-Unit-Server starten“** aktivieren.
3. `adb forward tcp:5277 tcp:5277` und danach `./desktop-head-unit` aus dem SDK starten.

## Benutzung

1. App am Handy öffnen → **„Bildschirmfreigabe starten“** → Systemdialog bestätigen.
   (Ab Android 14 muss diese Freigabe nach jedem Beenden erneut bestätigt werden.)
2. Im Auto „Car Screen Mirror“ öffnen. Sobald beide Seiten bereit sind, läuft die Spiegelung.
3. Beenden über den Button in der App oder über die laufende Benachrichtigung.

Solange nicht gespiegelt wird, zeigt das Autodisplay einen Hinweistext an
(„Bildschirmfreigabe am Handy starten“ bzw. „Pausiert – Fahrzeug in Bewegung“).

### Sperre „Nur im Stand spiegeln“

Standardmäßig aktiv. Die App abonniert die Fahrzeuggeschwindigkeit über die
Car-Hardware-API und pausiert das Bild ab ca. 2 km/h. Liefert das Fahrzeug keinen Wert oder
wird die Berechtigung `CAR_SPEED` verweigert, bleibt die Sperre wirkungslos – dann liegt die
Verantwortung vollständig beim Nutzer. Der Schalter in der App wirkt sofort.

---

## Aufbau

```
app/src/main/java/at/werkstatt/screenmirror/
├── MainActivity.kt              Steuerung am Handy (Start/Stopp, Status, Einstellung)
├── ProjectionService.kt         Foreground-Service, hält die MediaProjection am Leben
├── core/
│   ├── MirrorEngine.kt          Kern: verbindet MediaProjection + Auto-Surface, VirtualDisplay
│   └── Prefs.kt                 Einstellungen
└── car/
    ├── MirrorCarAppService.kt   Einstiegspunkt für den Android-Auto-Host
    ├── MirrorSession.kt         Session des Hosts
    ├── MirrorScreen.kt          NavigationTemplate + Actionleiste im Auto
    ├── CarSurfaceRenderer.kt    Surface-Callbacks des Hosts → MirrorEngine
    └── SpeedGuard.kt            Geschwindigkeitssperre
```

Der Ablauf in einem Satz: `MediaProjection.createVirtualDisplay(...)` schreibt den gespiegelten
Handybildschirm direkt in die `Surface`, die der Android-Auto-Host der Navigations-App gibt.
Fällt eine der beiden Seiten weg, wird das `VirtualDisplay` freigegeben und stattdessen ein
Hinweistext auf die Surface gezeichnet. Alles läuft über den Main-Thread, damit sich die
beiden Besitzer der Surface (VirtualDisplay bzw. `lockCanvas`) nicht in die Quere kommen.

Wichtige Reihenfolge (ab Android 14 zwingend): erst Foreground-Service mit Typ
`mediaProjection` starten, dann `getMediaProjection()` aufrufen, und den
`MediaProjection.Callback` vor `createVirtualDisplay()` registrieren.

## Fehlersuche

| Symptom | Ursache / Lösung |
| --- | --- |
| App taucht im Auto nicht auf | „Unbekannte Quellen“ nicht aktiviert, oder Android Auto wurde nicht neu verbunden. Die App liegt unter *Navigation*, nicht unter *Medien*. |
| Schwarzes Bild mit Hinweistext | Freigabe am Handy noch nicht gestartet. |
| Dauerhaft „Pausiert“ | Das Fahrzeug meldet Bewegung. Zum Testen den Schalter „Nur im Stand spiegeln“ ausschalten. |
| Spiegelung stoppt beim Verlassen der App im Auto | Der Host gibt die Surface frei, sobald eine andere App im Vordergrund ist – das ist so vorgesehen. Die Freigabe am Handy bleibt aktiv, das Bild kehrt beim Zurückwechseln zurück. |
| Nach Handy-Neustart tot | `MediaProjection` überlebt keinen Neustart, Freigabe erneut starten. |
| Verzerrtes Seitenverhältnis | Das System skaliert das Handybild auf die Autoauflösung und ergänzt schwarze Ränder. Ein anderes Verhältnis lässt sich ohne Zwischenrendering nicht erzwingen. |

## Stand der Prüfung

Gebaut und mit Android Lint geprüft (`./gradlew assembleDebug lintDebug` – erfolgreich, keine
Lint-Fehler). **Nicht** auf echter Hardware bzw. in einem Fahrzeug getestet – dafür braucht es
ein Handy und eine Head-Unit.
