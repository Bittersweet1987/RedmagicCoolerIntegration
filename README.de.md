# RedmagicCoolerIntegration

*English version: [README.md](README.md)*

BLE-Steuerungsbibliothek für den **REDMAGIC VC Cooler 6 Pro** (Handykühler von Nubia, beworben als
"RM Magcooler 6pro" / "REDMAGIC Cooler 6 pro+"), reverse engineered aus der offiziellen
Goper-Companion-App.

Gedacht als Baustein für die Integration in
[pocketSRT](https://github.com/romestylez/pocketSRT), analog zur dort bereits vorhandenen
BlackShark-Kühler-Unterstützung.

Es liegen zwei Implementierungen bei – **Kotlin/Android** und **Swift/iOS** – beide auf Basis
derselben, an echter Hardware verifizierten Protokolldokumentation.

## Inhalt

| Pfad | Inhalt |
|---|---|
| [`PROTOCOL.de.md`](PROTOCOL.de.md) | **Die eigentliche Arbeit**: vollständige Dokumentation des reverse-engineerten BLE-Protokolls (Service-/Characteristic-UUIDs, exakte Byte-Werte, Fallstricke, offene Fragen). Plattformunabhängig. |
| [`PROTOCOL.md`](PROTOCOL.md) | Dasselbe Dokument auf Englisch |
| `android/` | Kotlin-Implementierung als Android-Library-Modul (`com.romestylez.redmagiccooler`) |
| `swift/` | Swift-Implementierung als Swift Package (`RedMagicCoolerLib`, CoreBluetooth) |
| [`test-app/`](test-app/) | Eigenständige Android-Test-App, mit der das Protokoll ermittelt und verifiziert wurde. Spricht rohes GATT und nutzt die Bibliothek nicht — praktisch zum Gegenprüfen an eigener Hardware. |

Wer nur das Protokoll braucht und selbst implementieren will, kommt mit `PROTOCOL.de.md` allein aus.

## Funktionsumfang (in beiden Implementierungen identisch)

- Scan und Verbindungsaufbau
- Kühlung an/aus
- Kühlstufe 1–9 (exakt die Werte, die Goper sendet)
- Zerstörungsgott-/Diablo-Modus an/aus
- Automatische Temperaturregelung an/aus
- LED an/aus (inklusive Rücklesen des aktuellen Zustands)
- Temperaturauslesung (laufend per Notify sowie direkt lesbar)

## Verifikationsstand

Das **Protokoll** wurde nicht nur aus App-Bytecode abgeleitet: Jede einzelne Funktion wurde per
Bluetooth-HCI-Snoop-Log an echter Hardware (Firmware V6.1.5) mitgeschnitten, während die Goper-App
bedient wurde, und anschließend mit einer eigens gebauten Test-App gegengeprüft. Am realen Gerät
live bestätigt:

- Kühlschalter an/aus ✅
- Kühlstufen 1–9 ✅
- LED an/aus ✅
- Automatische Temperaturregelung an/aus ✅
- Temperaturanzeige ✅
- Diablo-Modus: Byte-Werte bestätigt ✅, aber **ohne sofort wahrnehmbaren Effekt** – das gilt auch
  für Goper selbst, siehe Hinweis in `PROTOCOL.de.md`.

Zum **Code-Stand**:

- **Kotlin/Android**: kompiliert fehlerfrei (Gradle-Build). Die zugrundeliegenden Rohbefehle sind an
  echter Hardware getestet, genau dieser Wrapper-Code aber noch nicht end-to-end.
- **Swift/iOS**: sorgfältig aus der Kotlin-Fassung portiert, aber **weder kompiliert noch getestet** –
  die Entwicklungsumgebung hier war Windows, und CoreBluetooth gibt es nur auf Apple-Plattformen.
  Bitte vor dem Einsatz einmal bauen und gegen echte Hardware prüfen.

## Verwendung

### Android (Kotlin)

Als Gradle-Modul einbinden (`settings.gradle`):

```groovy
include ':RedMagicCooler6ProLib'
project(':RedMagicCooler6ProLib').projectDir = new File(rootDir, 'pfad/zu/RedMagicCooler6ProLib/android')
```

```kotlin
val cooler = RedMagicCooler6Pro(context)
cooler.onStateChanged = { state -> /* UI aktualisieren */ }
cooler.onConnectionStateChanged = { connected -> /* ... */ }
cooler.startScanAndConnect()

// sobald verbunden:
cooler.setCoolingEnabled(true)
cooler.setFanStep(5)          // 1..9
cooler.setLedEnabled(true)
cooler.setAutoTemperatureControl(false)
```

Benötigte Berechtigungen: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+) bzw.
`ACCESS_FINE_LOCATION` darunter.

### iOS (Swift)

Als Swift Package einbinden (lokaler Pfad `swift/`) oder einfach die einzelne Datei
`swift/Sources/RedMagicCoolerLib/RedMagicCooler6Pro.swift` ins Projekt kopieren.

```swift
let cooler = RedMagicCooler6Pro()
cooler.onStateChanged = { state in /* UI aktualisieren */ }
cooler.startScanAndConnect()

// sobald verbunden:
cooler.setCoolingEnabled(true)
cooler.setFanStep(5)          // 1...9
cooler.setLedEnabled(true)
cooler.setAutoTemperatureControl(false)
```

In der `Info.plist` wird `NSBluetoothAlwaysUsageDescription` benötigt.

Hinweis: iOS liefert keine MAC-Adressen. Für ein Wiederverbinden ohne Scan die
`CBPeripheral.identifier` (eine UUID) persistieren und `connect(identifier:)` verwenden.

## Integration in pocketSRT (Vorschlag)

Beide Klassen sind bewusst im Stil der bestehenden BlackShark-Integration gehalten (Callback-basiert,
`startScanAndConnect()` / `disconnect()`, State-Objekt mit Änderungs-Callback). Sie lassen sich
entweder als eigenes Modul/Package einbinden oder direkt in ein bestehendes Kühler-Paket kopieren
und dort als weiterer unterstützter Gerätetyp neben MagCooler 4/5 Pro und FunCooler 6 registrieren.

Für eine Temperaturautomatik analog zur BlackShark-Unterstützung (Start-/Stopptemperatur mit
Hysterese) kann entweder die gerätinterne Automatik genutzt werden
(`setAutoTemperatureControl(true)`) oder die Temperatur aus dem Notify-Stream selbst ausgewertet und
die Kühlstufe manuell gesetzt werden.

## Bekannte Einschränkungen / offene Punkte

Siehe Abschnitt "Offene Punkte für den Entwickler" am Ende von `PROTOCOL.de.md`.

## Lizenz / Herkunft

Lizenziert unter GPL-3.0, siehe [LICENSE](LICENSE).

Das Reverse Engineering erfolgte ausschließlich zu Interoperabilitätszwecken. Es wurde kein Code aus
der Goper-App übernommen – lediglich das beobachtete Protokollverhalten wurde dokumentiert und
unabhängig neu implementiert. Die Protokolldokumentation beschreibt beobachtetes Verhalten fremder
Hardware und ist nicht aus proprietärem Quellcode abgeleitet.
