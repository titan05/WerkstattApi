# Video Hintergrund – Live Wallpaper für Android

Eine kleine Android-App, mit der eigene Videos als Live-Hintergrund laufen.

**Fertige App:** [`dist/VideoHintergrund-1.0.apk`](dist/VideoHintergrund-1.0.apk)

## Installieren

1. Die Datei `dist/VideoHintergrund-1.0.apk` auf das Handy kopieren (USB, Cloud, E‑Mail …).
2. Im Dateimanager antippen. Android fragt einmalig nach der Erlaubnis
   „Unbekannte Apps installieren“ für den Dateimanager – erlauben.
3. Installieren, App „Video Hintergrund“ öffnen.

Die APK ist mit dem Standard-Debug-Schlüssel signiert. Das reicht zum Installieren
per Hand, nicht aber für den Play Store. Wird die App später neu gebaut und mit
einem anderen Schlüssel signiert, muss die alte Version vorher deinstalliert werden.

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
| Energiesparmodus | Zeigt statt des Videos ein Standbild |

Akku: Die Wiedergabe pausiert automatisch, sobald der Startbildschirm nicht mehr
sichtbar ist (App im Vordergrund, Bildschirm aus). Es wird nur dann ein Bild
gezeichnet, wenn der Decoder tatsächlich ein neues Frame liefert.

## Technisch

* Kotlin, minSdk 24 (Android 7), targetSdk 35, keine Berechtigungen, kein Internet.
* `VideoWallpaperService` – der Live-Hintergrund, steuert `MediaPlayer` nach Sichtbarkeit.
* `VideoRenderer` – eigener EGL/OpenGL-ES-2-Thread. Das Video landet über eine
  `SurfaceTexture` als externe Textur auf einem Rechteck; erst dadurch lassen sich
  Seitenverhältnis, Abdunklung und Parallax frei steuern. Der `MediaPlayer` würde
  direkt auf der Wallpaper-Surface immer verzerren.
* `ScaleMath` – die Geometrie ohne Android-Abhängigkeit, mit Unit-Tests abgedeckt.
* `VideoImporter` – kopiert Videos in den App-Speicher und legt ein Vorschaubild an.

## Selbst bauen

```bash
cd VideoLiveWallpaper
export ANDROID_HOME=/pfad/zum/android-sdk   # braucht Platform 35 + Build-Tools 35
./gradlew assembleRelease                   # APK: app/build/outputs/apk/release/
./gradlew testReleaseUnitTest lintRelease   # Tests und Lint
```
