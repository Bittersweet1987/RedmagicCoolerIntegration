# RedMagic VC Cooler 6 Pro – Funktionsübersicht für Entwickler

Alle Werte in dieser Tabelle stammen aus [`PROTOCOL.md`](PROTOCOL.md) (statische Analyse der
Goper-App + Bluetooth-HCI-Snoop + Live-Test auf echter Hardware, Firmware V6.1.5). Die
Referenzimplementierung liegt in
[`android/src/main/kotlin/com/romestylez/redmagiccooler/RedMagicCooler6Pro.kt`](android/src/main/kotlin/com/romestylez/redmagiccooler/RedMagicCooler6Pro.kt).

**Vorbedingung für alle Funktionen:** Verbindung zum Kühler steht (`connectGatt` + `discoverServices`),
Service `d52082ad-e805-9f97-9d4e-1c682d9c9ce6` wurde gefunden. Wie man den Kühler überhaupt findet,
steht in [`ble-diagnose/DEVELOPER.md`](ble-diagnose/DEVELOPER.md) (Werbe-UUID `0x4A41`, nicht die
Service-UUID oben).

---

## 1. Kühlung ein/aus

| | |
|---|---|
| Characteristic | `0x1011`, Read + Write |
| Wert | 1 Byte: `0x02` = an, `0x03` = aus |
| Status lesbar | ja, per `Read` |

**Implementierung:**
```kotlin
gatt.writeCharacteristic(char0x1011, byteArrayOf(0x02), WRITE_TYPE_DEFAULT) // an
gatt.writeCharacteristic(char0x1011, byteArrayOf(0x03), WRITE_TYPE_DEFAULT) // aus
```
Referenz: `setCoolingEnabled(enabled: Boolean)` in `RedMagicCooler6Pro.kt:308`.

---

## 2. Lüfterstufe (1–9)

| | |
|---|---|
| Characteristic | `0x1012`, Read + Write |
| Wert | 1 Byte, **Wertetabelle**, nicht linear |
| Status lesbar | ja, per `Read` |

Die Skala ist nicht gleichmäßig (Stufen 1–5 steigen um 6, Stufen 5–9 um 4). Deshalb Werte aus
dieser Tabelle nehmen, nicht interpolieren:

| Stufe | Hex | Dezimal |
|---|---|---|
| 1 (min) | `0x28` | 40 |
| 2 | `0x2E` | 46 |
| 3 | `0x34` | 52 |
| 4 | `0x3A` | 58 |
| 5 | `0x40` | 64 |
| 6 | `0x44` | 68 |
| 7 | `0x48` | 72 |
| 8 | `0x4C` | 76 |
| 9 (max) | `0x50` | 80 |

**Implementierung:**
```kotlin
val FAN_LEVELS = byteArrayOf(0x28, 0x2E, 0x34, 0x3A, 0x40, 0x44, 0x48, 0x4C, 0x50)
gatt.writeCharacteristic(char0x1012, byteArrayOf(FAN_LEVELS[stufe - 1]), WRITE_TYPE_DEFAULT)
```
Referenz: `setFanStep(step: Int)` / `setFanLevel(level: Int)` in `RedMagicCooler6Pro.kt:321,336`.
Ob Werte außerhalb 40–80 akzeptiert werden, ist ungetestet – nicht spekulativ senden.

---

## 3. LED ein/aus

| | |
|---|---|
| Characteristic | `0x1013`, Read + Write (kein CCCD, also keine Notify-Subscription möglich) |
| Wert | 4 Byte, **feste Werte, kein Toggle** |
| Status lesbar | ja, per `Read` |

| Bytes | Bedeutung |
|---|---|
| `01 00 00 00` | LED an |
| `06 00 00 00` | LED aus |

**Implementierung:**
```kotlin
val LED_ON = byteArrayOf(0x01, 0x00, 0x00, 0x00)
val LED_OFF = byteArrayOf(0x06, 0x00, 0x00, 0x00)
gatt.writeCharacteristic(char0x1013, LED_ON, WRITE_TYPE_DEFAULT)
```
Referenz: `setLedEnabled(enabled: Boolean)` in `RedMagicCooler6Pro.kt:365`.

**Falle:** `LED_ON` und `LED_OFF` nicht kurz hintereinander senden – der Kühler blinkt dann nur
kurz auf und fällt in den Aus-Zustand zurück. Zwischen den Aufrufen etwas warten oder den
gelesenen Zustand prüfen.

Ob die ersten Bytes `02`–`05` weitere Lichteffekte auswählen, ist **nicht getestet** – Goper
sendet nur `01` und `06`.

---

## 4. Diablo-Modus ("Zerstörer"-Modus, lauter/aggressiver)

| | |
|---|---|
| Characteristic | `0x1017`, Read + Write |
| Wert | 1 Byte: `0x01` = an, `0x00` = aus |
| Status lesbar | ja, per `Read` |

**Implementierung:**
```kotlin
gatt.writeCharacteristic(char0x1017, byteArrayOf(0x01), WRITE_TYPE_DEFAULT) // an
```
Referenz: `setDiabloMode(enabled: Boolean)` in `RedMagicCooler6Pro.kt:342`.

**Hinweis:** Beim Umschalten passiert weder hörbar noch in der Telemetrie (`0x1016`) sofort etwas.
Das ist auch bei der Original-App so – der Modus wirkt vermutlich erst unter echter Last (warmes,
angedocktes Telefon). Zum Testen den Wert zurücklesen statt auf eine hörbare Änderung zu warten.

---

## 5. Automatische Temperaturregelung ein/aus

| | |
|---|---|
| Characteristic | `0x1018`, Read + Write |
| Wert | 1 Byte: `0x01` = an (Kühler regelt Lüfterstufe selbst), `0x00` = aus (manuell über `0x1012`) |
| Status lesbar | ja, per `Read` |

**Implementierung:**
```kotlin
gatt.writeCharacteristic(char0x1018, byteArrayOf(0x01), WRITE_TYPE_DEFAULT)
```
Referenz: `setAutoTemperatureControl(enabled: Boolean)` in `RedMagicCooler6Pro.kt:347`.

---

## 6. Temperatur auslesen

| | |
|---|---|
| Characteristic | `0x1015`, Read + **Notify** (funktioniert, hat ein CCCD) |
| alternativ | `0x1014`, nur Read, gleicher Wert, kein CCCD |
| Format | 2 Byte, `04 TT` → `TT` ist die Temperatur in °C als einzelnes Byte |

`0x1015` sendet etwa 1×/s ein Paket, abwechselnd `04 TT` (Temperatur) und `05 80` (fester Platzhalter-
Wert, Bedeutung unklar).

**Implementierung (Notify abonnieren):**
```kotlin
gatt.setCharacteristicNotification(char0x1015, true)
val cccd = char0x1015.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
gatt.writeDescriptor(cccd)
// im Callback: if (value[0] == 0x04) temperaturCelsius = value[1]
```
Referenz: `enqueueEnableNotify` + `applyNotifyValue` in `RedMagicCooler6Pro.kt`.

---

## 7. Nicht nutzbar / ungeklärt

| Characteristic | Status |
|---|---|
| `0x1016` | Notify, 16-Byte-Pakete, nur Byte 13 ändert sich laufend – unbekannte Telemetrie (Spannung? Zähler?), nicht dekodiert, für die Kernfunktionen nicht nötig |
| `0x1019` | nur Read, Schreiben wird mit `ATT_ERROR_WRITE_NOT_PERMITTED` abgelehnt |
| Service `00010203-0405-0607-0809-0a0b0c0d1912` | sieht nach einem Telink-OTA/Firmware-Update-Profil aus. **Nicht anfassen** – ein Testschreiben mit falschen Daten hat die Verbindung sofort getrennt (vermutlich eine Sicherung in der OTA-Logik) |

---

## Zum Vergleich: RedMagic 8 Pro (anderes Community-Projekt)

Das Projekt [`jty657/RedMagic8Pro-Cooler-Controller`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller)
steuert einen anderen Kühler (8 Pro statt 6 Pro), nutzt aber dieselben Characteristic-Nummern.
Unterschiede zur obigen Tabelle:

- **Lüfter:** 10 Stufen statt 9, lineare Formel `0x28 + 4×(Stufe-1)` (0x28…0x64) statt Wertetabelle.
  Setzt dabei immer `0x1011=02`, `0x1017=00`, `0x1018=00` mit.
- **Diablo-Modus:** wird nicht einzeln geschaltet, sondern ist Teil eines "Boost"-Presets
  (`0x1011=02, 0x1012=0x50, 0x1017=01, 0x1018=00` in einem Rutsch).
- **Smart-Modus:** `0x1011=02, 0x1017=00, 0x1018=01` – overlap mit "Auto-Temp" oben, aber als festes
  Preset statt einzeln toggle-bar.
- **LED (`0x1013`):** komplett anderes Format – 4 Byte `[Modus, R, G, B]` mit Dutzenden Animations-
  Presets, nicht die feste An/Aus-Sequenz `01 00 00 00` / `06 00 00 00` von oben.

Für den 6 Pro gilt die Tabelle oben, nicht das 8-Pro-Verhalten. Bei Unsicherheit lieber mit der
[BLE-Diagnose-App](ble-diagnose/) gegen die echte Hardware verifizieren statt das 8-Pro-Verhalten zu
übernehmen.
