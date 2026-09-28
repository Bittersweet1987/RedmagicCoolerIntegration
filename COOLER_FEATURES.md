# RedMagic Kühler – Funktionsübersicht für Entwickler

**Quelle:** [`jty657/RedMagic8Pro-Cooler-Controller`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller)
(Android-Controller für den RedMagic 8 Pro Kühler). Dieses Dokument listet die dortigen Funktionen
mit deutschem Namen und deutscher Erklärung, direkt aus dem dortigen Quelltext abgelesen
(`ble/BleManager.java`, `control/FanController.java`, `control/LedController.java`,
`MainActivity.java`).

> **Anderes Gerät, gleiche Characteristic-Nummern:** Dieses Projekt steuert den RedMagic **8 Pro**
> Kühler, nicht den 6 Pro, für den [`PROTOCOL.md`](PROTOCOL.md) in diesem Repo per HCI-Snoop und
> Live-Test verifiziert wurde. Beide Kühler nutzen dieselben Characteristic-Nummern
> (`0x1011`–`0x1018`) im selben Service `d52082ad-e805-9f97-9d4e-1c682d9c9ce6`, aber teils andere
> Werte und Bedeutungen – siehe die Hinweise unter jeder Funktion. Vor dem produktiven Einsatz an
> echter 6-Pro-Hardware mit der [BLE-Diagnose-App](ble-diagnose/) gegenprüfen.

**Vorbedingung für alle Funktionen:** Verbindung zum Kühler steht (`connectGatt` + `discoverServices`).
Wie man den Kühler überhaupt findet, steht in [`ble-diagnose/DEVELOPER.md`](ble-diagnose/DEVELOPER.md).

---

## Verbindung

| Funktion | Deutscher Name | Wie |
|---|---|---|
| Scannen & Verbinden | **Suchen und verbinden** | `startScan()` scannt 6 s ohne Filter, zeigt alle gefundenen Geräte in einer Liste, Nutzer wählt manuell aus. Danach `device.connectGatt(context, false, callback, TRANSPORT_LE)`. Kein automatischer Namens- oder UUID-Filter. |
| Trennen | **Verbindung trennen** | `disconnect()` |

Beim Verbindungsaufbau werden **alle** Services nach beschreibbaren Characteristics mit den
Kurz-UUIDs `0x1011`, `0x1012`, `0x1013`, `0x1017`, `0x1018` durchsucht (kein gezielter Zugriff auf
den bekannten Service). Nur diese fünf werden verwendet – `0x1014`–`0x1016` und `0x1019` (Temperatur/
Telemetrie) werden **nicht** gelesen oder abonniert. Diese App hat also keine Temperaturanzeige.

---

## Lüfter

### 1. Manuelle Lüfterstufe

**Deutscher Name:** Manuelle Stufe (1–10)

Regler von 1 bis 10. Umgerechnet in ein Byte auf `0x1012` nach der Formel:

```
Byte = 0x28 + 4 × (Stufe − 1)     // Stufe 1 = 0x28 (40), Stufe 10 = 0x64 (100)
```

Beim Setzen einer Stufe wird immer dieselbe Reihenfolge geschrieben:

| Characteristic | Wert | Bedeutung hier |
|---|---|---|
| `0x1011` | `0x02` | (immer dieser Wert, siehe Hinweis unten) |
| `0x1018` | `0x00` | Auto-Temperaturregelung aus |
| `0x1017` | `0x00` | Berserker-Modus aus |
| `0x1012` | berechneter Wert | die gewählte Stufe |

**Implementierung:**
```java
byte fanValue = (byte) (0x28 + 4 * (level - 1));
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1017, new byte[]{0x00});
write(c1012, new byte[]{fanValue});
```

**Unterschied zum 6 Pro:** Dort sind es 9 Stufen mit einer nicht-linearen Wertetabelle
(`0x28…0x50`, siehe `PROTOCOL.md`), keine lineare Formel bis `0x64`. Auf 6-Pro-Hardware zuerst
prüfen, ob Werte über `0x50` überhaupt angenommen werden.

### 2. Intelligente Temperaturregelung

**Deutscher Name:** Smart-Modus / Intelligente Kühlung

Der Kühler regelt die Lüfterstufe selbständig nach Temperatur, keine manuelle Stufe nötig.

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1017` | `0x00` |
| `0x1018` | `0x01` |

```java
write(c1011, new byte[]{0x02});
write(c1017, new byte[]{0x00});
write(c1018, new byte[]{0x01});
```

Entspricht inhaltlich der „Automatischen Temperaturregelung“ (`0x1018`) im 6-Pro-Protokoll, wird
hier aber immer zusammen mit `0x1011` und `0x1017` als festes Bündel gesetzt statt einzeln
umgeschaltet.

### 3. Berserker-Modus

**Deutscher Name:** Berserker-Modus (chin. 破坏神, wörtlich „Zerstörer-Gott“)

Maximale, aggressive Kühlleistung – lauter Lüfter für schnellere Wärmeabfuhr.

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1018` | `0x00` |
| `0x1012` | `0x50` (Lüfter auf Stufe fest, nicht die aktuell gewählte manuelle Stufe) |
| `0x1017` | `0x01` |

```java
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1012, new byte[]{0x50});
write(c1017, new byte[]{0x01});
```

Entspricht dem „Diablo-Modus“ im 6-Pro-Protokoll (`0x1017 = 0x01`), wird hier aber nicht isoliert
geschaltet, sondern immer zusammen mit einer festen Lüfterstufe (`0x1012 = 0x50`) als Preset.

### 4. Kühlung ausschalten

**Deutscher Name:** Kühlung ausschalten

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1018` | `0x00` |
| `0x1012` | `0x00` |
| `0x1017` | `0x00` |

```java
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1012, new byte[]{0x00});
write(c1017, new byte[]{0x00});
```

**Wichtiger Unterschied zum 6-Pro-Protokoll:** Dort schaltet `0x1011 = 0x03` die Kühlung
vollständig ab, `0x02` bedeutet „an“. In diesem Projekt taucht der Wert `0x03` an **keiner** Stelle
im Code auf – `0x1011` wird immer auf `0x02` gesetzt, auch beim „Ausschalten“. Aus geschaltet wird
hier ausschließlich über die Lüfterstufe `0x1012 = 0x00`. Ob `0x1011 = 0x03` beim 8 Pro überhaupt
existiert, ist aus diesem Code nicht ersichtlich – es wird dort schlicht nie gesendet.

---

## LED / Licht

Alles über Characteristic `0x1013`.

### 5. Voreingestellte native Lichtmodi

**Deutscher Name:** Native Lichtmodi

4 Byte `[Modus, R, G, B]`, direkt an den Kühler:

| Button | Deutsche Bezeichnung | Aufruf | Gesendete Bytes |
|---|---|---|---|
| „模式01 默认/联动" | Modus 1 – Standard/Synchronisiert | `sendNativeLight(1, 0, 0, 0)` | `01 00 00 00` |
| „模式03 + RGB" | Modus 3 – mit freier Farbe | `sendNativeLight(3, r, g, b)` | `03 RR GG BB` |
| „模式04 + RGB" | Modus 4 – mit freier Farbe | `sendNativeLight(4, r, g, b)` | `04 RR GG BB` |
| „模式06 原生" | Modus 6 – nativ | `sendNativeLight(6, 0, 0, 0)` | `06 00 00 00` |

```java
public void sendNativeLight(int mode, int r, int g, int b) {
    byte[] data = {(byte) mode, (byte) r, (byte) g, (byte) b};
    write(c1013, data);
}
```

**Unterschied zum 6 Pro:** Dort sind nur zwei feste 4-Byte-Werte verifiziert:
`01 00 00 00` = LED an, `06 00 00 00` = LED aus – **kein** Modus mit freier Farbe. Interessant:
Byte `06` ist beim 6 Pro „aus“, hier ist es „nativer Modus 6“. Vor dem Übernehmen unbedingt an
echter 6-Pro-Hardware testen, ob `06 00 00 00` dort wirklich ausschaltet oder etwas anderes tut.

### 6. Einzelne LED einfärben

**Deutscher Name:** Einzel-LED-Farbe (Pixel-Steuerung)

Der Kühler hat 16 einzeln ansteuerbare LEDs. Jede wird über eine zweiteilige Sequenz gesetzt
(Vorbereiten, dann Bestätigen):

```java
public void sendPerPixelColor(int ledIndex, int r, int g, int b) {
    write(c1013, new byte[]{(byte) 0xF0, (byte) ledIndex, (byte) r, (byte) g});  // Vorbereiten
    write(c1013, new byte[]{(byte) 0xF1, (byte) ledIndex, (byte) b, 0x00});       // Bestätigen
}
```

In der App: LED-Nummer (1–16) per Button auswählen, Farbe per RGB-Schieberegler einstellen,
„发送当前 RGB" (**aktuelle RGB-Farbe senden**) drücken.

**Nicht bestätigt für den 6 Pro** – dort ist nur ein einziges festes 4-Byte-Kommando fürs Ganze
Gerät belegt, keine Einzel-LED-Adressierung.

### 7. Alle LEDs ausschalten

**Deutscher Name:** Alle LEDs ausschalten

```java
public void turnOffAll() {
    stopAnimation();
    for (int i = 0; i < 16; i++) {
        sendPerPixelColor(i, 0, 0, 0);
    }
}
```

Setzt alle 16 LEDs einzeln auf Schwarz (0,0,0), statt eines einzigen Aus-Kommandos.

### 8. Lichteffekte / Animationen

**Deutscher Name:** Lichteffekte

30 vorprogrammierte Animationen. Jede berechnet clientseitig eine Bildfolge und schreibt sie
per Einzel-LED-Sequenz (siehe Funktion 6) an den Kühler. Gestoppt werden sie über
**„Alle Effekte stoppen"** (`stopAnimation()`).

| # | Deutscher Name | Methode | Parameter |
|---|---|---|---|
| 1 | Verfolgungsjagd | `chaseAnimation()` | – |
| 2 | Regenbogen-Verfolgung | `rainbowChase()` | – |
| 3 | Regenbogen-Kreislauf | `rainbowCycle()` | – |
| 4 | Atmen | `breathingEffect(r, g, b)` | Farbe |
| 5 | Welle | `waveEffect()` | – |
| 6 | Sternschnuppe | `meteorEffect()` | – |
| 7 | Stroboskop | `strobeEffect(r, g, b)` | Farbe |
| 8 | Theater-Lauflicht | `theaterChase(r, g, b)` | Farbe |
| 9 | Auffüllen | `colorWipe(r, g, b)` | Farbe |
| 10 | Feuer | `fireEffect()` | – |
| 11 | Wasser | `waterEffect()` | – |
| 12 | Sonnenaufgang | `sunriseEffect()` | – |
| 13 | Sonnenuntergang | `sunsetEffect()` | – |
| 14 | Polarlicht | `auraBorealis()` | – |
| 15 | Kerzenflackern | `candleFlicker()` | – |
| 16 | Doppel-Verfolgung | `dualChase()` | – |
| 17 | Ping-Pong | `pingPong()` | – |
| 18 | Spirale | `spiral()` | – |
| 19 | Zufälliges Blinken | `randomBlink()` | – |
| 20 | Schlange | `snake()` | – |
| 21 | Scanner (KITT-Effekt) | `scanner()` | – |
| 22 | Komet | `comet()` | – |
| 23 | Farbverlauf | `colorFade(r1,g1,b1, r2,g2,b2)` | zwei Farben |
| 24 | Regenbogen-Verlauf | `rainbowFade()` | – |
| 25 | Funkeln | `twinkle(r, g, b)` | Farbe |
| 26 | Glitzern | `sparkle(r, g, b)` | Farbe |
| 27 | Puls | `pulse(r, g, b)` | Farbe |
| 28 | Geteilt (links/rechts) | `halfAndHalf(r1,g1,b1, r2,g2,b2)` | zwei Farben |
| 29 | Wechselnd | `alternate(r, g, b)` | Farbe |
| 30 | Ladebalken | `loading(r, g, b)` | Farbe |

**Nicht bestätigt für den 6 Pro** – wie bei Funktion 5 und 6 ist unklar, ob dieselbe
Pixel-Adressierung auf dem 6-Pro-Kühler überhaupt existiert.

---

## Was in diesem Projekt fehlt (im Vergleich zum 6-Pro-Protokoll)

Diese App liest nichts vom Kühler zurück – kein Status, keine Temperatur:

| Funktion (nur beim 6 Pro belegt) | Characteristic |
|---|---|
| Temperatur auslesen | `0x1014` (Read), `0x1015` (Read + Notify) |
| Unbekannte Telemetrie | `0x1016` (Notify) |
| Unbekanntes Nur-Lese-Register | `0x1019` |

Details dazu stehen in [`PROTOCOL.md`](PROTOCOL.md). Ob diese Characteristics auf dem 8-Pro-Kühler
genauso funktionieren, ist unbekannt, weil dieses Projekt sie nie anspricht.

---

## Offene Punkte vor dem Einsatz auf echter 6-Pro-Hardware

1. **`0x1011 = 0x03`** (Kühlung aus laut 6-Pro-Protokoll) wird hier nie gesendet – prüfen, ob es
   auf dem 6 Pro weiterhin gebraucht wird oder ob „aus“ auch dort über `0x1012 = 0x00` geht.
2. **LED-Modi 3, 4 und die Pixel-Adressierung** (`0xF0`/`0xF1`-Präfix) sind für den 6 Pro nicht
   verifiziert – testen, bevor sie in eine 6-Pro-App eingebaut werden.
3. **Lüfterformel:** 6 Pro hat 9 Stufen mit fester Wertetabelle, dieses Projekt 10 Stufen linear
   bis `0x64` – prüfen, welche Werte der 6-Pro-Kühler oberhalb `0x50` tatsächlich annimmt.

Am zuverlässigsten lässt sich das mit der [BLE-Diagnose-App](ble-diagnose/) dieses Repos gegen die
echte Hardware verifizieren: Wert schreiben, per `Read` zurücklesen, Verhalten beobachten.
