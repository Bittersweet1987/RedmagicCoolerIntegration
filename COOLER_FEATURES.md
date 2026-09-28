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

**Wichtig:** Das sind keine Kühler-Kommandos, sondern App-seitige Dauerschleifen. Der Kühler kennt
keinen „Feuer-Modus" – die App berechnet jeden Frame selbst und schickt ihn über Funktion 7
(Einzel-LED-Farbe, `0xF0`/`0xF1`-Sequenz auf `0x1013`) an alle 16 LEDs. Deshalb reicht ein
Methodenname allein nicht zum Nachbauen; unten steht deshalb der tatsächliche Algorithmus.

**Gemeinsames Baumuster aller 30 Effekte:**
1. Laufenden Effekt stoppen, `isAnimationRunning = true` setzen.
2. Eine Schleife starten, die sich per Timer selbst alle *X* Millisekunden neu aufruft (siehe
   Intervall je Effekt unten).
3. Pro Durchlauf: für jede der 16 LEDs (Index 0–15) eine Farbe berechnen und einzeln per
   `sendPerPixelColor(index, r, g, b)` senden (siehe Funktion 7).
4. Endlos, bis `stopAnimation()` aufgerufen wird – das setzt `isAnimationRunning = false`, bricht
   den Timer ab und leert die Warteschlange offener BLE-Writes. Einzige Ausnahme: „Auffüllen" (#9)
   stoppt von selbst, sobald alle 16 LEDs gefüllt sind.

**Hilfsfunktion HSV→RGB**, von mehreren Effekten benutzt, um eine Farbe aus Farbton (0–359°),
Sättigung (0–1) und Helligkeit (0–1) in R/G/B (0–255) umzurechnen (Standard-HSV-zu-RGB-Umrechnung,
6 Sektoren à 60°). Wird unten als „HSV(Farbton, Sättigung, Helligkeit)" abgekürzt.

| # | Deutscher Name | Intervall | Was pro Frame passiert |
|---|---|---|---|
| 1 | Verfolgungsjagd | 300 ms | Alle 16 LEDs löschen, dann eine LED an fester Position auf Rot (32,0,0) setzen. Position rückt jeden Frame um 1 weiter, ringförmig (Index modulo 16). |
| 2 | Regenbogen-Verfolgung | 50 ms | Für jede LED `i`: Farbton = `(Position + i×22) mod 360`, HSV(Farbton, 1.0, 0.3). `Position` steigt pro Frame um 10 (mod 360). |
| 3 | Regenbogen-Kreislauf | 50 ms | Für jede LED `i`: Farbton = `(Offset + i×(360/16)) mod 360`, HSV(Farbton, 1.0, 0.3). `Offset` steigt pro Frame um 5. |
| 4 | Atmen | 30 ms | Helligkeitsfaktor pendelt zwischen 0.0 und 1.0 (±0.02 pro Frame, kehrt an den Enden um). Alle 16 LEDs auf Grundfarbe × Helligkeitsfaktor. Aufruf mit Grundfarbe (80,40,0). |
| 5 | Welle | 80 ms | Für jede LED `i`: Abstand zur Wellenposition, Intensität = `max(0, 1 − Abstand/8)`. Farbton = `(Wellenposition×30) mod 360`, HSV(Farbton, 1.0, Intensität×0.4). Wellenposition +1 pro Frame (mod 16). |
| 6 | Sternschnuppe | 60 ms | Alle LEDs löschen. 5 LEDs hinter dem Kopf in Weiß/Grau, Helligkeit fällt linear von 80 auf 0 über die 5 Positionen, Blau-Anteil halbiert. Kopf +1 pro Frame (ringförmig). |
| 7 | Stroboskop | 100 ms | Alle 16 LEDs abwechselnd komplett auf die übergebene Farbe bzw. komplett aus, jeden Frame umgeschaltet. Aufruf mit (100,100,100) = Weiß. |
| 8 | Theater-Lauflicht | 200 ms | Jede 3. LED (`(i+step) mod 3 == 0`) auf die übergebene Farbe, Rest aus. `step` +1 pro Frame (mod 3). Aufruf mit (80,0,80) = Magenta. |
| 9 | Auffüllen | 100 ms | LEDs werden von Index 0 bis 15 nacheinander auf die aktuelle Schieberegler-Farbe gesetzt (eine neue LED pro Frame). **Stoppt automatisch**, sobald LED 15 erreicht ist. |
| 10 | Feuer | 80 ms | Jede LED bekommt zufälliges Rot 200–255 und Grün 60–85, Blau immer 0 – pro Frame neu gewürfelt, für jede LED unabhängig. |
| 11 | Wasser | 50 ms | Für jede LED `i`: Abstand zur Mitte (Index 8), Phase = `(Wellenposition + Abstand×40) mod 360`, Helligkeit = `(sin(Phase)+1)/2`. Blau-Kanal = Helligkeit×100, Grün = Blau/3, Rot = 0. Wellenposition +15 pro Frame. |
| 12 | Sonnenaufgang | 40 ms | Fortschritt läuft in 200 Schritten linear 0→1 und beginnt danach wieder bei 0. Alle LEDs auf Rot=Fortschritt×255, Grün=Fortschritt×180, Blau=(1−Fortschritt)×100 (Übergang von Blau nach Orange/Gelb). |
| 13 | Sonnenuntergang | 40 ms | Wie Sonnenaufgang, andere Formel: Rot=(1−Fortschritt×0.5)×200, Grün=(1−Fortschritt)×100, Blau=Fortschritt×80 (Übergang von Orange nach Violett). |
| 14 | Polarlicht | 60 ms | Für jede LED `i`: zwei überlagerte Sinuswellen ergeben Grün- und Blau-Anteil, Rot konstant 10. `offset` +5 pro Frame. |
| 15 | Kerzenflackern | 100 ms | Jede LED unabhängig: Grundhelligkeit 40 + Zufallswert 0–30, Rot = dieser Wert, Grün = Wert−10, Blau = 0 (warmes Flackern, pro Frame neu gewürfelt). |
| 16 | Doppel-Verfolgung | 100 ms | Alle LEDs löschen. Zwei LEDs gleichzeitig: eine läuft vorwärts (grün), eine rückwärts vom anderen Ende (rot). Beide Positionen +1 pro Frame, ringförmig. |
| 17 | Ping-Pong | 80 ms | Alle LEDs löschen, eine LED auf Gelb (50,50,0). Position bewegt sich vor/zurück zwischen LED 0 und 15, kehrt an den Enden um. |
| 18 | Spirale | 50 ms | Für jede LED `i`: Farbton = `(i×60 + offset) mod 360`, Helligkeit aus `sin(Farbton)`, HSV(Farbton, 1.0, Helligkeit×0.3). `offset` +10 pro Frame. |
| 19 | Zufälliges Blinken | 150 ms | Jede LED unabhängig mit 30 % Wahrscheinlichkeit: zufälliger Farbton, HSV(Farbton, 1.0, 0.4). Sonst aus. Pro Frame neu gewürfelt. |
| 20 | Schlange | 100 ms | Alle LEDs löschen, dann 4 LEDs hinter dem Kopf in Grün, Helligkeit linear von 60 auf 0 fallend. Kopf +1 pro Frame, ringförmig. Details siehe oben. |
| 21 | Scanner (KITT-Effekt) | 60 ms | Für jede LED: Abstand zur aktuellen Position, Helligkeit (Rot) = `max(0, 80 − Abstand×20)`. Position bewegt sich vor/zurück zwischen LED 0 und 15. |
| 22 | Komet | 50 ms | Alle LEDs löschen. 8 LEDs hinter dem Kopf in Weiß/Grau, Helligkeit linear von 100 auf 0 fallend. Kopf +1 pro Frame, ringförmig. |
| 23 | Farbverlauf | 50 ms | Überblendet linear zwischen zwei übergebenen Farben (100 Schritte hin, dann rückwärts, endlos). Alle 16 LEDs zeigen denselben Zwischenwert. Aufruf mit Rot (100,0,0) → Blau (0,0,100). |
| 24 | Regenbogen-Verlauf | 40 ms | Alle 16 LEDs zeigen denselben Farbton, HSV(Farbton, 1.0, 0.3). Farbton +3 pro Frame (mod 360) – der ganze Kühler wechselt gemeinsam die Farbe. |
| 25 | Funkeln | 100 ms | Jede LED unabhängig mit 5 % Wahrscheinlichkeit pro Frame: zur Hälfte auf die übergebene Farbe, zur Hälfte aus. Restliche LEDs bleiben unverändert (kein Löschen). Aufruf mit (80,80,80). |
| 26 | Glitzern | 100 ms | Alle LEDs erst auf Grundfarbe/8 (schwaches Grundleuchten) gesetzt, dann 2–4 zufällige LEDs pro Frame auf die volle Grundfarbe. Aufruf mit (100,100,0) = Gelb. |
| 27 | Puls | 80 ms | Ringförmige Helligkeitswelle, die von der Mitte (Index 8) ausgeht: LEDs innerhalb von 3 Positionen zur aktuellen Pulsposition leuchten mit abfallender Helligkeit, Rest aus. Pulsposition +1 pro Frame. Aufruf mit (60,0,60). |
| 28 | Geteilt (links/rechts) | 500 ms | LEDs 0–7 und 8–15 bekommen abwechselnd Farbe 1 bzw. Farbe 2, jeden Frame vertauscht. Aufruf mit Rot (100,0,0) / Blau (0,0,100). |
| 29 | Wechselnd | 300 ms | Gerade und ungerade LED-Indizes leuchten abwechselnd in der übergebenen Farbe, Rest aus. Jeden Frame vertauscht. Aufruf mit (80,80,0) = Gelb. |
| 30 | Ladebalken | 100 ms | LEDs 0 bis zur aktuellen Füllposition leuchten in der übergebenen Farbe, Rest aus. Füllposition +1 pro Frame, springt nach LED 15 zurück auf 0. Aufruf mit (0,100,100) = Cyan. |

**Stoppen aller Effekte:** Timer abbrechen, `isAnimationRunning` auf `false`, offene BLE-Writes
verwerfen. Danach z. B. Funktion 8 (Alle LEDs ausschalten) aufrufen, sonst bleibt der letzte Frame
stehen.
