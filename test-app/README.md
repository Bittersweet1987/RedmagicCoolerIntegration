# BLE Cooler Tester (test app)

*Deutsche Fassung weiter unten / German version below.*

A small standalone Android app used to reverse engineer and verify the protocol documented in
[`../PROTOCOL.md`](../PROTOCOL.md). It talks to the cooler with raw `BluetoothGatt` calls and
deliberately does **not** use the library in `../android` — that way it can be used to
independently cross-check the library's behaviour.

Useful if you want to confirm the protocol against your own hardware before trusting the library.

## What it does

- Scan for a cooler by name, or connect directly by MAC address
- Dump all services and characteristics on connect
- Buttons for every documented function: cooling switch, fan steps 1–9, LED on/off, Diablo mode,
  automatic temperature control
- Live temperature display, fed from the notify stream
- Free-form hex field to write arbitrary bytes to the selected characteristic
- Full timestamped log with a "copy to clipboard" button

## Build

```
./gradlew :app:assembleDebug
```

You need a `local.properties` with your SDK path (note the escaped backslashes on Windows):

```properties
sdk.dir=C:\path\to\android-sdk
```

The APK lands in `app/build/outputs/apk/debug/`.

## Notes

- The direct-connect field expects a Bluetooth MAC address such as `AA:BB:CC:DD:EE:FF`. On the
  cooler this was stable across reconnects. If you do not know it, use "Scan + Verbinden" instead.
- The device only accepts one BLE connection at a time. If the Goper app (or nRF Connect) is still
  connected, this app will not find or reach the cooler — disconnect there first, and toggling
  Bluetooth off/on is a reliable way to clear a stuck connection.
- The UI is in German, since it was built ad hoc during the reverse engineering work.

---

# BLE Cooler Tester (Test-App)

Eine kleine eigenständige Android-App, mit der das in [`../PROTOCOL.de.md`](../PROTOCOL.de.md)
dokumentierte Protokoll ermittelt und verifiziert wurde. Sie spricht den Kühler über rohe
`BluetoothGatt`-Aufrufe an und nutzt die Bibliothek unter `../android` bewusst **nicht** — so lässt
sich das Verhalten der Bibliothek unabhängig gegenprüfen.

Praktisch, wenn man das Protokoll erst an eigener Hardware bestätigen will, bevor man sich auf die
Bibliothek verlässt.

## Funktionen

- Kühler per Namen suchen oder direkt per MAC-Adresse verbinden
- Beim Verbinden alle Services und Characteristics auflisten
- Buttons für alle dokumentierten Funktionen: Kühlschalter, Kühlstufen 1–9, LED an/aus,
  Diablo-Modus, automatische Temperaturregelung
- Live-Temperaturanzeige aus dem Notify-Stream
- Freies Hex-Feld, um beliebige Bytes an die ausgewählte Characteristic zu schreiben
- Vollständiges Log mit Zeitstempeln und "In Zwischenablage kopieren"

## Bauen

```
./gradlew :app:assembleDebug
```

Es wird eine `local.properties` mit dem SDK-Pfad benötigt (unter Windows mit doppelten
Backslashes):

```properties
sdk.dir=C:\pfad\zum\android-sdk
```

Die APK liegt anschließend in `app/build/outputs/apk/debug/`.

## Hinweise

- Das Direktverbinden-Feld erwartet eine Bluetooth-MAC wie `AA:BB:CC:DD:EE:FF`. Beim Kühler war
  diese über Neuverbindungen hinweg stabil. Wer sie nicht kennt, nutzt stattdessen
  "Scan + Verbinden".
- Das Gerät akzeptiert immer nur **eine** BLE-Verbindung gleichzeitig. Ist die Goper-App (oder
  nRF Connect) noch verbunden, findet bzw. erreicht diese App den Kühler nicht — dort zuerst
  trennen; Bluetooth einmal aus- und einzuschalten löst hängende Verbindungen zuverlässig.
