# Video Hintergrund – Live Wallpaper für Android

Eine kleine Android-App, mit der eigene Videos als Live-Hintergrund laufen.

**Fertige App:** [`dist/VideoHintergrund-1.1.apk`](dist/VideoHintergrund-1.1.apk)

## Installieren

1. Die Datei `dist/VideoHintergrund-1.1.apk` auf das Handy kopieren (USB, Cloud, E‑Mail …).
2. Im Dateimanager antippen. Android fragt einmalig nach der Erlaubnis
   „Unbekannte Apps installieren“ für den Dateimanager – erlauben.
3. Installieren, App „Video Hintergrund“ öffnen.

Signiert wird mit dem festen Schlüssel unter `keystore/debug.keystore` – einem
Android-Debug-Schlüssel mit dem üblichen Standardpasswort. Dadurch lässt sich eine
neu gebaute Version immer über die installierte drüberlegen, egal auf welchem
Rechner gebaut wurde. Für den Play Store ist so ein Schlüssel nicht geeignet.

## Benutzen

1. **Videos hinzufügen** – ein oder mehrere Videos auswählen. Sie werden in die App
   kopiert, damit der Hintergrund auch nach einem Neustart und unabhängig vom
   Original weiterläuft.
2. Darstellung und Wiedergabe einstellen.
3. **Als Hintergrund festlegen** – die Android-Vorschau öffnet sich, dort bestätigen.

Änderungen an den Einstellungen wirken sofort, auch während die Vorschau offen ist.

## Funktionen

| Einstellung | Beschreibung |
|---|---|
| Bildausschnitt | **Füllen** (Bildschirm voll, Ränder abgeschnitten), **Einpassen** (ganzes Video, schwarze Balken), **Strecken** (verzerrt auf Bildschirmformat) |
| Abdunkeln | 0–80 %, damit Icons und Text lesbar bleiben |
| Parallax | Der Ausschnitt wandert beim Wischen zwischen den Startbildschirmen mit |
| Ton | Standardmäßig aus, mit Lautstärkeregler zuschaltbar |
| Geschwindigkeit | 0,25× bis 2× |
| Mehrere Videos | Werden nacheinander abgespielt, Reihenfolge per Langdruck-Ziehen änderbar, optional zufällig |
| Einfrieren nach | Video läuft bei jedem Blick los und friert dann ein (Standard 15 s) |
| Bildrate begrenzen | Voll / 30 / 24 fps (Standard 30) |
| Standbild ab Akkustand | Unter dieser Grenze nur ein Standbild, Standard 15 % |
| Energiesparmodus | Zeigt statt des Videos ein Standbild |

## Akku

Ein laufendes Video ist der teuerste Hintergrund-Typ überhaupt. Die App bremst ihn
an fünf Stellen, die Standardwerte sind bereits sparsam eingestellt:

1. **Pause bei Unsichtbarkeit** – sobald eine App im Vordergrund ist oder der
   Bildschirm aus geht, wird der Decoder gestoppt. Das ist die größte Ersparnis
   und lässt sich nicht abschalten.
2. **Einfrieren nach 15 Sekunden** – der Startbildschirm ist meist nur ein paar
   Sekunden zu sehen. Danach friert das Bild ein, Decoder und Grafikeinheit
   schlafen komplett. Bei jedem neuen Blick läuft das Video wieder an.
3. **Bildrate begrenzt auf 30 fps** – jedes ausgelassene Bild spart einen
   kompletten Zeichen- und Anzeigedurchlauf. Der Puffer des Decoders wird
   trotzdem abgeholt, sonst würde die Wiedergabe stocken.
4. **Standbild bei schwachem Akku** – unter 15 % (und ohne Ladekabel) sowie im
   Energiesparmodus wird nur das erste Bild gezeigt.
5. **Keine unnötigen Meldungen** – ist Parallax aus, meldet der Launcher seine
   Wischbewegungen gar nicht erst an den Dienst.

Gezeichnet wird ohnehin nur, wenn der Decoder tatsächlich ein neues Bild liefert –
es läuft keine Schleife im Leerlauf.

## Technisch

* Kotlin, minSdk 24 (Android 7), targetSdk 35, keine Berechtigungen, kein Internet.
* `VideoWallpaperService` – der Live-Hintergrund, steuert `MediaPlayer` nach Sichtbarkeit.
* `VideoRenderer` – eigener EGL/OpenGL-ES-2-Thread. Das Video landet über eine
  `SurfaceTexture` als externe Textur auf einem Rechteck; erst dadurch lassen sich
  Seitenverhältnis, Abdunklung und Parallax frei steuern. Der `MediaPlayer` würde
  direkt auf der Wallpaper-Surface immer verzerren.
* `ScaleMath` / `PowerRules` – Geometrie und Strom-Entscheidungen ohne
  Android-Abhängigkeit, mit Unit-Tests abgedeckt.
* `VideoImporter` – kopiert Videos in den App-Speicher und legt ein Vorschaubild an.

## Selbst bauen

```bash
cd VideoLiveWallpaper
export ANDROID_HOME=/pfad/zum/android-sdk   # braucht Platform 35 + Build-Tools 35
./gradlew assembleRelease                   # APK: app/build/outputs/apk/release/
./gradlew testReleaseUnitTest lintRelease   # Tests und Lint
```
