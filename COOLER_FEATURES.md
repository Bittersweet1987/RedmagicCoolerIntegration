# RedMagic Kühler – Funktionsübersicht für Entwickler

**Quelle:** [`jty657/RedMagic8Pro-Cooler-Controller`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller),
Datei für Datei aus dem dortigen Quelltext (`ble/BleManager.java`, `control/FanController.java`,
`control/LedController.java`, `MainActivity.java`). Alle Werte unten sind wörtlich aus diesem Code
entnommen, nicht selbst berechnet oder angenommen.

**Vorbedingung für alle Funktionen:** Verbindung zum Kühler steht (`connectGatt` + `discoverServices`).
Wie man den Kühler findet, steht in [`ble-diagnose/DEVELOPER.md`](ble-diagnose/DEVELOPER.md).

---

## Verbindung

| Funktion | Deutscher Name | Wie |
|---|---|---|
| Scannen & Verbinden | **Suchen und verbinden** | `startScan()` scannt 6 s ohne Filter, zeigt alle gefundenen Geräte in einer Liste, Nutzer wählt manuell aus. Danach `device.connectGatt(context, false, callback, TRANSPORT_LE)`. |
| Trennen | **Verbindung trennen** | `disconnect()` |

Beim Verbindungsaufbau werden alle Services nach beschreibbaren Characteristics mit den
Kurz-UUIDs `0x1011`, `0x1012`, `0x1013`, `0x1017`, `0x1018` durchsucht. Nur diese fünf werden
verwendet.

---

## Lüfter

### 1. Manuelle Lüfterstufe

**Deutscher Name:** Manuelle Stufe (1–10)

Regler von 1 bis 10, ein Byte auf `0x1012`. Alle 10 Stufen mit ihrem Byte-Wert (aus
`setManualFanLevel(int level)` im Originalcode, für jede Stufe ausgewertet):

| Stufe | Hex | Dezimal |
|---|---|---|
| 1 (min) | `0x28` | 40 |
| 2 | `0x2C` | 44 |
| 3 | `0x30` | 48 |
| 4 | `0x34` | 52 |
| 5 | `0x38` | 56 |
| 6 | `0x3C` | 60 |
| 7 | `0x40` | 64 |
| 8 | `0x44` | 68 |
| 9 | `0x48` | 72 |
| 10 (max) | `0x4C` | 76 |

Beim Setzen einer Stufe wird immer dieselbe Reihenfolge geschrieben:

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1018` | `0x00` |
| `0x1017` | `0x00` |
| `0x1012` | Wert aus obiger Tabelle |

**Aufruf, z. B. für Stufe 5:**
```java
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1017, new byte[]{0x00});
write(c1012, new byte[]{0x38});   // Stufe 5
```

### 2. Intelligente Temperaturregelung

**Deutscher Name:** Smart-Modus / Intelligente Kühlung

Der Kühler regelt die Lüfterstufe selbständig nach Temperatur.

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1017` | `0x00` |
| `0x1018` | `0x01` |

**Aufruf:**
```java
write(c1011, new byte[]{0x02});
write(c1017, new byte[]{0x00});
write(c1018, new byte[]{0x01});
```

### 3. Berserker-Modus

**Deutscher Name:** Berserker-Modus (chin. 破坏神, wörtlich „Zerstörer-Gott")

Maximale, aggressive Kühlleistung.

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1018` | `0x00` |
| `0x1012` | `0x50` |
| `0x1017` | `0x01` |

**Aufruf:**
```java
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1012, new byte[]{0x50});
write(c1017, new byte[]{0x01});
```

### 4. Kühlung ausschalten

**Deutscher Name:** Kühlung ausschalten

| Characteristic | Wert |
|---|---|
| `0x1011` | `0x02` |
| `0x1018` | `0x00` |
| `0x1012` | `0x00` |
| `0x1017` | `0x00` |

**Aufruf:**
```java
write(c1011, new byte[]{0x02});
write(c1018, new byte[]{0x00});
write(c1012, new byte[]{0x00});
write(c1017, new byte[]{0x00});
```

---

## LED / Licht

Alles über Characteristic `0x1013`.

### 5. Voreingestellte native Lichtmodi

**Deutscher Name:** Native Lichtmodi

4 Byte `[Modus, R, G, B]`, direkt an den Kühler. Konkrete Aufrufe aus der Original-App:

| Deutsche Bezeichnung | Aufruf | Gesendete Bytes |
|---|---|---|
| Modus 1 – Standard/Synchronisiert | `sendNativeLight(1, 0, 0, 0)` | `01 00 00 00` |
| Modus 3 – mit Farbe (Beispiel Rot) | `sendNativeLight(3, 32, 0, 0)` | `03 20 00 00` |
| Modus 4 – mit Farbe (Beispiel Rot) | `sendNativeLight(4, 32, 0, 0)` | `04 20 00 00` |
| Modus 6 – nativ | `sendNativeLight(6, 0, 0, 0)` | `06 00 00 00` |

Bei Modus 3 und 4 kommt die Farbe in der Original-App aus den RGB-Schiebereglern (Startwert
R=32, G=0, B=0 = gedämpftes Rot); jede andere Farbe 0–255 pro Kanal ist ebenso gültig.

**Implementierung:**
```java
public void sendNativeLight(int mode, int r, int g, int b) {
    byte[] data = {(byte) mode, (byte) r, (byte) g, (byte) b};
    write(c1013, data);
}
```

### 6. Voreingestellte Farben (gedämpfte Grundfarben)

**Deutscher Name:** Farbvoreinstellungen

Drei Buttons in der Original-App setzen feste, gedämpfte Grundfarben (Wert 32 von 255, also
niedrige Helligkeit):

| Button | Aufruf | Farbe |
|---|---|---|
| „低亮红" (gedämpftes Rot) | `setPreset(32, 0, 0)` | Rot, niedrige Helligkeit |
| „低亮绿" (gedämpftes Grün) | `setPreset(0, 32, 0)` | Grün, niedrige Helligkeit |
| „低亮蓝" (gedämpftes Blau) | `setPreset(0, 0, 32)` | Blau, niedrige Helligkeit |

`setPreset(r, g, b)` setzt intern nur die drei RGB-Schieberegler-Variablen, ohne sofort zu senden
– gesendet wird erst über Funktion 7 oder 5.

### 7. Einzelne LED einfärben

**Deutscher Name:** Einzel-LED-Farbe (Pixel-Steuerung)

Der Kühler hat 16 einzeln ansteuerbare LEDs (Index 0–15). Jede wird über eine zweiteilige Sequenz
gesetzt:

```java
public void sendPerPixelColor(int ledIndex, int r, int g, int b) {
    write(c1013, new byte[]{(byte) 0xF0, (byte) ledIndex, (byte) r, (byte) g});  // Vorbereiten
    write(c1013, new byte[]{(byte) 0xF1, (byte) ledIndex, (byte) b, 0x00});       // Bestätigen
}
```

**Aufruf, Beispiel LED Nr. 1 (Index 0) in gedämpftem Rot:**
```java
sendPerPixelColor(0, 32, 0, 0);
```
In der App: LED-Nummer (Button 1–16) auswählen, Farbe per RGB-Schieberegler einstellen,
„发送当前 RGB" (**aktuelle RGB-Farbe senden**) drücken – das ruft genau diesen Aufruf mit der
gewählten LED-Nummer und Farbe auf.

### 8. Alle LEDs ausschalten

**Deutscher Name:** Alle LEDs ausschalten

**Aufruf:**
```java
turnOffAll();
```

```java
public void turnOffAll() {
    stopAnimation();
    for (int i = 0; i < 16; i++) {
        sendPerPixelColor(i, 0, 0, 0);
    }
}
```

### 9. Lichteffekte / Animationen

**Deutscher Name:** Lichteffekte

30 vorprogrammierte Animationen. Jeder Aufruf unten ist die konkrete Zeile aus der Original-App
(Button-Klick → genau dieser Methodenaufruf):

| # | Deutscher Name | Aufruf |
|---|---|---|
| 1 | Verfolgungsjagd | `chaseAnimation()` |
| 2 | Regenbogen-Verfolgung | `rainbowChase()` |
| 3 | Regenbogen-Kreislauf | `rainbowCycle()` |
| 4 | Atmen | `breathingEffect(80, 40, 0)` |
| 5 | Welle | `waveEffect()` |
| 6 | Sternschnuppe | `meteorEffect()` |
| 7 | Stroboskop | `strobeEffect(100, 100, 100)` |
| 8 | Theater-Lauflicht | `theaterChase(80, 0, 80)` |
| 9 | Auffüllen | `colorWipe(red, green, blue)` — aktuelle Schieberegler-Farbe (Start: 32, 0, 0) |
| 10 | Feuer | `fireEffect()` |
| 11 | Wasser | `waterEffect()` |
| 12 | Sonnenaufgang | `sunriseEffect()` |
| 13 | Sonnenuntergang | `sunsetEffect()` |
| 14 | Polarlicht | `auraBorealis()` |
| 15 | Kerzenflackern | `candleFlicker()` |
| 16 | Doppel-Verfolgung | `dualChase()` |
| 17 | Ping-Pong | `pingPong()` |
| 18 | Spirale | `spiral()` |
| 19 | Zufälliges Blinken | `randomBlink()` |
| 20 | Schlange | `snake()` |
| 21 | Scanner (KITT-Effekt) | `scanner()` |
| 22 | Komet | `comet()` |
| 23 | Farbverlauf | `colorFade(100, 0, 0, 0, 0, 100)` |
| 24 | Regenbogen-Verlauf | `rainbowFade()` |
| 25 | Funkeln | `twinkle(80, 80, 80)` |
| 26 | Glitzern | `sparkle(100, 100, 0)` |
| 27 | Puls | `pulse(60, 0, 60)` |
| 28 | Geteilt (links/rechts) | `halfAndHalf(100, 0, 0, 0, 0, 100)` |
| 29 | Wechselnd | `alternate(80, 80, 0)` |
| 30 | Ladebalken | `loading(0, 100, 100)` |

**Stoppen aller Effekte:**
```java
stopAnimation();
```
