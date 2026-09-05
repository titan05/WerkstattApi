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
| Veröffentlichung im Play Store | **Nicht möglich.** Verstößt gegen die Android-Auto-Richtlinien. |
| Sideload per `adb install` | **Reicht nicht.** Der Entwicklerschalter „Unbekannte Quellen“ gilt laut Google ausdrücklich nur für Media-, Messaging- und Parked-Apps, **nicht** für Apps der Car App Library. Eine sideloadete Template-App erscheint im Auto gar nicht erst im Menü. |
| Installation im echten Auto | Nur aus einer vertrauenswürdigen Quelle, d. h. über Google Play – für den Eigenbedarf reicht **Internal App Sharing** (kein Review nötig, Play-Entwicklerkonto einmalig ca. 25 USD). |
| Entwicklung ohne Auto | Funktioniert per `adb install` + **Desktop Head Unit** am Rechner. |
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

> **Wichtig:** Ein einfaches `adb install` genügt für den Betrieb im echten Auto **nicht**.
> Android Auto blendet Apps der Car App Library aus, wenn sie nicht aus einer
> vertrauenswürdigen Quelle stammen. Der Entwicklerschalter „Unbekannte Quellen“ ändert
> daran nichts – er gilt laut
> [Google-Dokumentation](https://developer.android.com/training/cars/testing#unknown-sources)
> nur für Media-, Messaging- und Parked-Apps.

### Weg 1: Desktop Head Unit (Entwicklung, ohne Auto)

Hier reicht der Sideload:

1. Im SDK-Manager „Android Auto Desktop Head Unit emulator“ installieren.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. In den Android-Auto-Entwicklereinstellungen **„Head-Unit-Server starten“** aktivieren.
4. `adb forward tcp:5277 tcp:5277`, danach `./desktop-head-unit` aus dem SDK starten.

### Weg 2: Internal App Sharing (echtes Auto)

Der von Google dokumentierte Weg, eine Template-App ohne Review auf ein echtes Head-Unit zu
bekommen. Wichtig: **ein eigener Signaturschlüssel ist nicht nötig** – Internal App Sharing
akzeptiert ausdrücklich debug-signierte APKs und signiert sie selbst neu.

**Einmalig: Konto**

Play-Entwicklerkonto anlegen (einmalig ca. 25 USD). Neue Konten müssen eine
Identitätsprüfung durchlaufen, das kann einige Tage dauern.

**Am Rechner: APK hochladen**

1. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
2. Play Console öffnen → linkes Menü **Testen und freigeben → Interne App-Freigabe**
   (direkt: `play.google.com/console/internal-app-sharing`).
3. **Hochladen** wählen, das APK auswählen, einen Versionsnamen vergeben.
4. Neben dem Upload auf das Kopiersymbol klicken – das ist der Installationslink.

**Am Handy: Interne App-Freigabe einschalten**

1. Play Store öffnen → Profilbild → **Einstellungen** → Abschnitt **Info**.
2. **7× auf „Play Store-Version“** tippen, bis der Schalter erscheint.
3. Schalter **„Interne App-Freigabe“** einschalten → *Aktivieren*.

**Installieren und prüfen**

1. Den kopierten Link am Handy öffnen → über den Play Store installieren.
   Damit gilt Play als Installationsquelle, und die App wird für Android Auto sichtbar.
2. Android Auto neu einlesen lassen:
   `adb shell am force-stop com.google.android.projection.gearhead`, danach das Handy neu
   mit dem Auto verbinden.
3. Die App erscheint im Autolauncher unter den **Navigations-Apps** als „Car Screen Mirror“.

Grenzen: Der Link läuft nach **60 Tagen** ab und erlaubt maximal **100 Downloads**; danach
einfach neu hochladen. Uploads hier landen nie automatisch in einem Testtrack oder in der
Produktion.

Falls Android Auto die App auch danach nicht anzeigt, ist der nächste Schritt ein
**Internal Test Track** – dafür muss die App in der Play Console für die Formfaktor-Kategorie
Android Auto deklariert werden, und der Upload durchläuft ein Review.

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
| App taucht im Auto nicht auf | Häufigster Fall: per `adb install` sideloadet. Das genügt für Template-Apps nicht, siehe *Installieren*. Andernfalls prüfen, ob Android Auto seit der Installation neu gestartet wurde – die App liegt unter *Navigation*, nicht unter *Medien*. |
| Schwarzes Bild mit Hinweistext | Freigabe am Handy noch nicht gestartet. |
| Dauerhaft „Pausiert“ | Das Fahrzeug meldet Bewegung. Zum Testen den Schalter „Nur im Stand spiegeln“ ausschalten. |
| Spiegelung stoppt beim Verlassen der App im Auto | Der Host gibt die Surface frei, sobald eine andere App im Vordergrund ist – das ist so vorgesehen. Die Freigabe am Handy bleibt aktiv, das Bild kehrt beim Zurückwechseln zurück. |
| Nach Handy-Neustart tot | `MediaProjection` überlebt keinen Neustart, Freigabe erneut starten. |
| Verzerrtes Seitenverhältnis | Das System skaliert das Handybild auf die Autoauflösung und ergänzt schwarze Ränder. Ein anderes Verhältnis lässt sich ohne Zwischenrendering nicht erzwingen. |

## Stand der Prüfung

Gebaut und mit Android Lint geprüft (`./gradlew assembleDebug lintDebug` – erfolgreich, keine
Lint-Fehler). **Nicht** auf echter Hardware bzw. in einem Fahrzeug getestet – dafür braucht es
ein Handy und eine Head-Unit.
