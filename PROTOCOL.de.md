# REDMAGIC VC Cooler 6 Pro – BLE-Protokoll (reverse engineered)

*English version: [PROTOCOL.md](PROTOCOL.md)*

Reverse engineered am 2026-08-17 durch:

- Statische Analyse der Goper-App (`cn.nubia.externdevice`, Version 3.4.0) – Dalvik-Bytecode der
  Klassen `com.xiaoji.sdk.gcm.GcmProtocol`, `com.xiaoji.sdk.gcm.GcmRadiatorUtil`,
  `com.xiaoji.sdk.device.config.base.RadiatorCfg`.
- Bluetooth-HCI-Snoop-Log-Mitschnitte (`adb bugreport`) der echten Goper-App im Betrieb mit einem
  physischen REDMAGIC VC Cooler 6 Pro (Firmware V6.1.5, beworben als "RM Magcooler 6pro" /
  "REDMAGIC Cooler 6 pro+").

Die App enthält zusätzlich ein generisches, herstellerübergreifendes "GCM"-Protokoll
(`GcmProtocol`, Firma "xiaoji/gtouch"), das auch Gamepads, Tastaturen etc. bedient. Der Kühler
verwendet davon **nur** die im Folgenden dokumentierten rohen GATT-Characteristics – das
GCM-Rahmenformat mit Prüfsumme (`cmd1 cmd2 len ack payload... checksum`) konnte für dieses Gerät
**nicht** bestätigt werden (Schreibversuche in diesem Format wurden mit
`ATT_ERROR_INVALID_ATTRIBUTE_LENGTH` abgelehnt). Die tatsächlich genutzten Werte sind kurze
Rohwerte pro Characteristic.

## BLE-Verbindung

- Werbename (Advertising Name): `RM Magcooler 6pro` bzw. `REDMAGIC Cooler 6 pro+`
- Kein Pairing/Bonding nötig (eine einfache GATT-Verbindung reicht)
- Relevanter Service: **`d52082ad-e805-9f97-9d4e-1c682d9c9ce6`**
- Ein zweiter Service (`00010203-0405-0607-0809-0a0b0c0d1912`) folgt dem Muster eines
  Telink-OTA-/Firmware-Update-Profils. **Nicht anfassen** – ein Testschreiben mit fremden Daten hat
  die Verbindung sofort gekappt (vermutlich Schutzmechanismus der OTA-Logik).

## Characteristics im Service `d52082ad-...`

Alle Characteristic-UUIDs folgen der Standard-Bluetooth-Basis-UUID
(`0000XXXX-0000-1000-8000-00805f9b34fb`).

| UUID (kurz) | Properties | Funktion | Bestätigt über |
|---|---|---|---|
| `0x1011` | Read, Write | **Kühlschalter** (Kühlung an/aus) | HCI-Snoop + Live-Test |
| `0x1012` | Read, Write | **Kühlstufe** (Lüfterstufe) | HCI-Snoop + Live-Test |
| `0x1013` | Read, Write, Notify* | **LED an/aus** | HCI-Snoop + Live-Test |
| `0x1014` | Read, Notify* | **Temperatur** (einzelnes Byte, °C) | Read-Wert deckte sich mit Goper-UI |
| `0x1015` | Read, Notify | **Temperatursensor** (Notify-Stream) | HCI-Snoop + UI-Abgleich |
| `0x1016` | Read, Notify | unbekannte Telemetrie (16 Byte, laufend) | Beobachtung |
| `0x1017` | Read, Write | **Zerstörungsgott-/Diablo-Modus** | HCI-Snoop |
| `0x1018` | Read, Write | **Automatische Temperaturregelung** | HCI-Snoop + Live-Test |
| `0x1019` | Read | unbekannt, **Schreibzugriff verweigert** (`ATT_ERROR_WRITE_NOT_PERMITTED`) | Beobachtung |

\* Das Gerät exponiert für `0x1013`/`0x1014` keinen CCCD-Descriptor (`0x2902`) – Notify lässt sich
dort nicht abonnieren, obwohl das Property-Flag gesetzt ist. Diese Characteristics daher explizit
per `Read` abfragen, statt sich auf Notifications zu verlassen.

## Befehle im Detail

### Kühlschalter (`0x1011`)

Einzelnes Byte:

| Wert | Bedeutung |
|---|---|
| `0x02` | AN (Kühlung aktiv) |
| `0x03` | AUS (Standby) |

### Kühlstufe (`0x1012`)

Einzelnes Byte. Goper bietet **9 Stufen**. Die exakten Werte wurden per HCI-Snoop ermittelt, indem
in Goper die Stufen 1→9 der Reihe nach durchgeschaltet wurden; die Sequenz trat im selben Mitschnitt
zweimal identisch auf:

| Stufe | Hex | Dezimal |
|---|---|---|
| 1 (Min) | `0x28` | 40 |
| 2 | `0x2E` | 46 |
| 3 | `0x34` | 52 |
| 4 | `0x3A` | 58 |
| 5 | `0x40` | 64 |
| 6 | `0x44` | 68 |
| 7 | `0x48` | 72 |
| 8 | `0x4C` | 76 |
| 9 (Max) | `0x50` | 80 |

**Die Skala ist nicht linear:** Stufen 1–5 steigen in 6er-Schritten, Stufen 5–9 in 4er-Schritten.
Diese Werte sollten 1:1 übernommen werden, statt sie zu interpolieren.

Der aktuelle Wert ist per Read auslesbar.

Nicht getestet wurde, ob das Gerät auch Werte außerhalb von 40–80 akzeptiert (z. B. `0x00` oder
`0xFF`) – Goper selbst sendet ausschließlich die neun Werte oben.

### LED (`0x1013`) – 4-Byte-Wert, an/aus

Feste Werte, **kein Toggle** (eine frühe Fehlannahme, per Live-Test widerlegt):

| Bytes | Bedeutung |
|---|---|
| `01 00 00 00` | LED **AN** |
| `06 00 00 00` | LED **AUS** |

Beide Werte sind per Live-Test an echter Hardware bestätigt.

Der aktuelle Zustand ist **auslesbar**: Ein `Read` auf `0x1013` liefert `01 00 00 00`, wenn die LED
an ist, bzw. `06 00 00 00`, wenn sie aus ist. Eine Client-App muss den Zustand also nicht selbst
mitverfolgen.

Das erste Byte wirkt wie eine Modus-/Effektnummer (Goper hat einen separaten Menüpunkt "Anpassung
des Lichts"). Ob die Werte `02`–`05` weitere Lichteffekte schalten, wurde **nicht systematisch
getestet** – `01` (an) und `06` (aus) sind die einzigen von Goper beobachteten Werte.

Achtung beim Nachbau: `01 00 00 00` und `06 00 00 00` **nicht** kurz hintereinander senden – die LED
blinkt dann nur auf und landet sofort wieder im Aus-Zustand.

### Zerstörungsgott-/Diablo-Modus (`0x1017`)

Einzelnes Byte, klassisches Boolean:

| Wert | Bedeutung |
|---|---|
| `0x01` | AN (lauterer Lüfter für bessere Wärmeabfuhr) |
| `0x00` | AUS |

Per HCI-Snoop an echter Hardware bestätigt (Goper sendet genau diese beiden Werte).

**Hinweis für Tester:** Beim Umschalten ist **kein sofortiger Effekt** wahrnehmbar – weder hörbar
noch im Telemetrie-Stream (`0x1016` liefert vor und nach dem Umschalten identische Werte). Das gilt
auch für Goper selbst, ist also kein Fehler der Nachimplementierung. Der Modus greift offenbar erst
unter realer Last (warmes Handy angedockt, tatsächlicher Kühlbedarf). Ein Funktionstest sollte sich
daher auf den per Read zurücklesbaren Registerwert stützen, nicht auf "hört man was".

### Automatische Temperaturregelung (`0x1018`)

Einzelnes Byte, klassisches Boolean:

| Wert | Bedeutung |
|---|---|
| `0x01` | AN (Gerät regelt die Kühlstufe selbstständig nach Temperatur) |
| `0x00` | AUS (manuelle Kühlstufe über `0x1012`) |

### Temperatursensor (`0x1015`) – Notify

Sendet etwa 1x pro Sekunde 2-Byte-Pakete im Wechsel:

- `04 TT` – `TT` ist die **Temperatur in °C als einzelnes Byte** (z. B. `04 0A` = 10 °C,
  `04 0E` = 14 °C, `04 12` = 18 °C). Deckt sich mit der in der Goper-UI angezeigten
  "Rückentaschentemperatur".
- `05 80` (auch `05 00` beobachtet) – fester Wert, Bedeutung unklar (evtl. Platzhalter oder zweiter
  Sensorkanal ohne Nutzdaten bei diesem Modell).

Dieselbe Temperatur ist auch als einfacher 1-Byte-`Read` auf `0x1014` verfügbar.

### Unbekannte Telemetrie (`0x1016`) – Notify

Sendet laufend 16-Byte-Pakete der Form:

```
AA 63 31 50 00 31 50 19 01 01 00 00 00 XX 0C DD
```

Nur Byte 13 (`XX`; beobachtet u. a. `0B`, `0F`, `13`, `17`, `1B`, `1F`, `23`, `27`, `2B`) ändert sich
über die Zeit – möglicherweise ein Zähler, Spannungs- oder Stromwert. **Nicht dekodiert** und für
die Kern-Kühlerfunktionen nicht notwendig.

## Verifikationsstand

An echter Hardware live bestätigt: Kühlschalter, Kühlstufen 1–9, LED an/aus, automatische
Temperaturregelung, Temperaturauslesung. Diablo-Modus: Byte-Werte bestätigt, aber ohne beobachtbaren
Effekt (siehe Hinweis oben).

## Offene Punkte für den Entwickler

1. Exakte Min-/Max-Grenzen von `0x1012` (Kühlstufe) mit echter Hardware verifizieren.
2. Byte 13 von `0x1016` dekodieren (evtl. für erweiterte Telemetrie/Diagnose interessant).
3. Zweck von `0x1019` (nur lesbar) klären.
4. Klären, ob `02`–`05` auf `0x1013` weitere Lichteffekte schalten.
