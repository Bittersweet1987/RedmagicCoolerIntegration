# RedMagic Kühler – Funktionsübersicht für Entwickler

**Quelle:** [`jty657/RedMagic8Pro-Cooler-Controller`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller),
Datei für Datei aus dem dortigen Quelltext (`ble/BleManager.java`, `control/FanController.java`,
`control/LedController.java`, `MainActivity.java`). Alle Werte unten sind wörtlich aus diesem Code
entnommen, nicht selbst berechnet oder angenommen.

**Vorbedingung für alle Funktionen:** Verbindung zum Kühler steht (`connectGatt` + `discoverServices`).
Wie man den Kühler findet, steht in [`ble-diagnose/DEVELOPER.md`](ble-diagnose/DEVELOPER.md).

---

## Verbindung

Quelle: [`BleManager.java`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller/blob/main/app/src/main/java/com/redmagic/coolercontrol/ble/BleManager.java)

| Funktion | Deutscher Name | Wie |
|---|---|---|
| Scannen & Verbinden | **Suchen und verbinden** | `startScan()` scannt 6 s ohne Filter, zeigt alle gefundenen Geräte in einer Liste, Nutzer wählt manuell aus. Danach `device.connectGatt(context, false, callback, TRANSPORT_LE)`. |
| Trennen | **Verbindung trennen** | `disconnect()` |

Beim Verbindungsaufbau werden alle Services nach beschreibbaren Characteristics mit den
Kurz-UUIDs `0x1011`, `0x1012`, `0x1013`, `0x1017`, `0x1018` durchsucht. Nur diese fünf werden
verwendet.

---

## Lüfter

Quelle: [`FanController.java`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller/blob/main/app/src/main/java/com/redmagic/coolercontrol/control/FanController.java)

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

Quelle: [`LedController.java`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller/blob/main/app/src/main/java/com/redmagic/coolercontrol/control/LedController.java).
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

#### Vollständiger Original-Code jedes Effekts

Quelle: [`LedController.java`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller/blob/main/app/src/main/java/com/redmagic/coolercontrol/control/LedController.java)
im Projekt [`jty657/RedMagic8Pro-Cooler-Controller`](https://github.com/jty657/RedMagic8Pro-Cooler-Controller).
Wörtlich übernommen, unverändert. Klassenfelder, die alle Effekte gemeinsam nutzen:

```java
private static final int LED_COUNT = 16;
private Runnable currentAnimation;
private boolean isAnimationRunning = false;
private final Handler mainHandler = new Handler(Looper.getMainLooper());
```

```java
public void stopAnimation() {
    isAnimationRunning = false;
    mainHandler.removeCallbacksAndMessages(null);
    currentAnimation = null;
    writeQueue.clear();
}
```

```java
private int[] hsvToRgb(int h, float s, float v) {
    float c = v * s;
    float x = c * (1 - Math.abs(((h / 60.0f) % 2) - 1));
    float m = v - c;
    float r, g, b;
    if (h < 60) { r = c; g = x; b = 0; }
    else if (h < 120) { r = x; g = c; b = 0; }
    else if (h < 180) { r = 0; g = c; b = x; }
    else if (h < 240) { r = 0; g = x; b = c; }
    else if (h < 300) { r = x; g = 0; b = c; }
    else { r = c; g = 0; b = x; }
    return new int[]{(int) ((r + m) * 255), (int) ((g + m) * 255), (int) ((b + m) * 255)};
}
```

<details><summary>1. Verfolgungsjagd – chaseAnimation()</summary>

```java
public void chaseAnimation() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int ledIndex = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int j = 0; j < LED_COUNT; j++) {
                if (j != ledIndex) sendPerPixelColor(j, 0, 0, 0);
            }
            sendPerPixelColor(ledIndex, 32, 0, 0);
            ledIndex = (ledIndex + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 300);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>2. Regenbogen-Verfolgung – rainbowChase()</summary>

```java
public void rainbowChase() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int position = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int hue = (position + i * 22) % 360;
                int[] rgb = hsvToRgb(hue, 1.0f, 0.3f);
                sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
            }
            position = (position + 10) % 360;
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>3. Regenbogen-Kreislauf – rainbowCycle()</summary>

```java
public void rainbowCycle() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int offset = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int hue = (offset + i * (360 / LED_COUNT)) % 360;
                int[] rgb = hsvToRgb(hue, 1.0f, 0.3f);
                sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
            }
            offset = (offset + 5) % 360;
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>4. Atmen – breathingEffect(int r, int g, int b)</summary>

```java
public void breathingEffect(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        float brightness = 0.0f;
        boolean increasing = true;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            if (increasing) {
                brightness += 0.02f;
                if (brightness >= 1.0f) { brightness = 1.0f; increasing = false; }
            } else {
                brightness -= 0.02f;
                if (brightness <= 0.0f) { brightness = 0.0f; increasing = true; }
            }
            int br = (int) (r * brightness);
            int bg = (int) (g * brightness);
            int bb = (int) (b * brightness);
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, br, bg, bb);
            mainHandler.postDelayed(this, 30);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>5. Welle – waveEffect()</summary>

```java
public void waveEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int wavePosition = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                double distance = Math.abs(i - wavePosition);
                double intensity = Math.max(0, 1.0 - (distance / 8.0));
                int hue = (wavePosition * 30) % 360;
                int[] rgb = hsvToRgb(hue, 1.0f, (float) (intensity * 0.4f));
                sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
            }
            wavePosition = (wavePosition + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 80);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>6. Sternschnuppe – meteorEffect()</summary>

```java
public void meteorEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int meteorPos = 0;
        final int trailLength = 5;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, 0, 0, 0);
            for (int i = 0; i < trailLength; i++) {
                int pos = (meteorPos - i + LED_COUNT) % LED_COUNT;
                float intensity = 1.0f - (i / (float) trailLength);
                int brightness = (int) (80 * intensity);
                sendPerPixelColor(pos, brightness, brightness, brightness / 2);
            }
            meteorPos = (meteorPos + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 60);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>7. Stroboskop – strobeEffect(int r, int g, int b)</summary>

```java
public void strobeEffect(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        boolean on = false;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if (on) sendPerPixelColor(i, r, g, b);
                else sendPerPixelColor(i, 0, 0, 0);
            }
            on = !on;
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>8. Theater-Lauflicht – theaterChase(int r, int g, int b)</summary>

```java
public void theaterChase(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int step = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if ((i + step) % 3 == 0) sendPerPixelColor(i, r, g, b);
                else sendPerPixelColor(i, 0, 0, 0);
            }
            step = (step + 1) % 3;
            mainHandler.postDelayed(this, 200);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>9. Auffüllen – colorWipe(int r, int g, int b)</summary>

```java
public void colorWipe(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int index = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            sendPerPixelColor(index, r, g, b);
            index++;
            if (index < LED_COUNT) mainHandler.postDelayed(this, 100);
            else isAnimationRunning = false;
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>10. Feuer – fireEffect()</summary>

```java
public void fireEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int r = 200 + (int) (Math.random() * 55);
                int g = 60 + (int) (Math.random() * 25);
                int b = 0;
                sendPerPixelColor(i, r, g, b);
            }
            mainHandler.postDelayed(this, 80);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>11. Wasser – waterEffect()</summary>

```java
public void waterEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int wavePos = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            int center = LED_COUNT / 2;
            for (int i = 0; i < LED_COUNT; i++) {
                int dist = Math.abs(i - center);
                int phase = (wavePos + dist * 40) % 360;
                float brightness = (float) (Math.sin(Math.toRadians(phase)) + 1) / 2;
                int blue = (int) (brightness * 100);
                sendPerPixelColor(i, 0, blue / 3, blue);
            }
            wavePos = (wavePos + 15) % 360;
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>12. Sonnenaufgang – sunriseEffect()</summary>

```java
public void sunriseEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int step = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            float progress = (step % 200) / 200.0f;
            int r = (int) (progress * 255);
            int g = (int) (progress * 180);
            int b = (int) ((1 - progress) * 100);
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, r, g, b);
            step++;
            mainHandler.postDelayed(this, 40);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>13. Sonnenuntergang – sunsetEffect()</summary>

```java
public void sunsetEffect() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int step = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            float progress = (step % 200) / 200.0f;
            int r = (int) ((1 - progress * 0.5) * 200);
            int g = (int) ((1 - progress) * 100);
            int b = (int) (progress * 80);
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, r, g, b);
            step++;
            mainHandler.postDelayed(this, 40);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>14. Polarlicht – auraBorealis()</summary>

```java
public void auraBorealis() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int offset = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                float wave1 = (float) Math.sin(Math.toRadians((i * 30 + offset) % 360));
                float wave2 = (float) Math.sin(Math.toRadians((i * 20 + offset * 1.5) % 360));
                int g = (int) ((wave1 + 1) * 40);
                int b = (int) ((wave2 + 1) * 30);
                sendPerPixelColor(i, 10, g, b);
            }
            offset = (offset + 5) % 360;
            mainHandler.postDelayed(this, 60);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>15. Kerzenflackern – candleFlicker()</summary>

```java
public void candleFlicker() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int flicker = 40 + (int) (Math.random() * 30);
                sendPerPixelColor(i, flicker, flicker - 10, 0);
            }
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>16. Doppel-Verfolgung – dualChase()</summary>

```java
public void dualChase() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int pos = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, 0, 0, 0);
            int pos1 = pos % LED_COUNT;
            int pos2 = (LED_COUNT - 1 - pos) % LED_COUNT;
            sendPerPixelColor(pos1, 0, 50, 0);
            sendPerPixelColor(pos2, 50, 0, 0);
            pos++;
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>17. Ping-Pong – pingPong()</summary>

```java
public void pingPong() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int pos = 0;
        int direction = 1;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, 0, 0, 0);
            sendPerPixelColor(pos, 50, 50, 0);
            pos += direction;
            if (pos >= LED_COUNT - 1 || pos <= 0) direction = -direction;
            mainHandler.postDelayed(this, 80);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>18. Spirale – spiral()</summary>

```java
public void spiral() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int offset = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int hue = ((i * 60 + offset) % 360);
                float brightness = (float) Math.sin(Math.toRadians(hue)) * 0.5f + 0.5f;
                int[] rgb = hsvToRgb(hue, 1.0f, brightness * 0.3f);
                sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
            }
            offset = (offset + 10) % 360;
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>19. Zufälliges Blinken – randomBlink()</summary>

```java
public void randomBlink() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if (Math.random() < 0.3) {
                    int hue = (int) (Math.random() * 360);
                    int[] rgb = hsvToRgb(hue, 1.0f, 0.4f);
                    sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
                } else {
                    sendPerPixelColor(i, 0, 0, 0);
                }
            }
            mainHandler.postDelayed(this, 150);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>20. Schlange – snake()</summary>

```java
public void snake() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int headPos = 0;
        final int tailLength = 4;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, 0, 0, 0);
            for (int i = 0; i < tailLength; i++) {
                int pos = (headPos - i + LED_COUNT) % LED_COUNT;
                float intensity = 1.0f - (i / (float) tailLength);
                int brightness = (int) (intensity * 60);
                sendPerPixelColor(pos, 0, brightness, 0);
            }
            headPos = (headPos + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>21. Scanner (KITT-Effekt) – scanner()</summary>

```java
public void scanner() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int pos = 0;
        int direction = 1;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                int dist = Math.abs(i - pos);
                int brightness = Math.max(0, 80 - dist * 20);
                sendPerPixelColor(i, brightness, 0, 0);
            }
            pos += direction;
            if (pos >= LED_COUNT - 1 || pos <= 0) direction = -direction;
            mainHandler.postDelayed(this, 60);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>22. Komet – comet()</summary>

```java
public void comet() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int cometPos = 0;
        final int cometLength = 8;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, 0, 0, 0);
            for (int i = 0; i < cometLength; i++) {
                int pos = (cometPos - i + LED_COUNT) % LED_COUNT;
                float intensity = 1.0f - (i / (float) cometLength);
                int brightness = (int) (intensity * 100);
                sendPerPixelColor(pos, brightness, brightness, brightness);
            }
            cometPos = (cometPos + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>23. Farbverlauf – colorFade(int r1, int g1, int b1, int r2, int g2, int b2)</summary>

```java
public void colorFade(int r1, int g1, int b1, int r2, int g2, int b2) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int step = 0;
        boolean forward = true;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            float progress = (step % 100) / 100.0f;
            if (!forward) progress = 1.0f - progress;
            int r = (int) (r1 + (r2 - r1) * progress);
            int g = (int) (g1 + (g2 - g1) * progress);
            int b = (int) (b1 + (b2 - b1) * progress);
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, r, g, b);
            step++;
            if (step >= 100) { step = 0; forward = !forward; }
            mainHandler.postDelayed(this, 50);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>24. Regenbogen-Verlauf – rainbowFade()</summary>

```java
public void rainbowFade() {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int hue = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            int[] rgb = hsvToRgb(hue, 1.0f, 0.3f);
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, rgb[0], rgb[1], rgb[2]);
            hue = (hue + 3) % 360;
            mainHandler.postDelayed(this, 40);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>25. Funkeln – twinkle(int r, int g, int b)</summary>

```java
public void twinkle(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if (Math.random() < 0.05) {
                    if (Math.random() < 0.5) sendPerPixelColor(i, r, g, b);
                    else sendPerPixelColor(i, 0, 0, 0);
                }
            }
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>26. Glitzern – sparkle(int r, int g, int b)</summary>

```java
public void sparkle(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) sendPerPixelColor(i, r / 8, g / 8, b / 8);
            int numSparkles = 2 + (int) (Math.random() * 3);
            for (int i = 0; i < numSparkles; i++) {
                int pos = (int) (Math.random() * LED_COUNT);
                sendPerPixelColor(pos, r, g, b);
            }
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>27. Puls – pulse(int r, int g, int b)</summary>

```java
public void pulse(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int pulsePos = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            int center = LED_COUNT / 2;
            for (int i = 0; i < LED_COUNT; i++) {
                int dist = Math.abs(i - center);
                int effectiveDist = (pulsePos - dist + LED_COUNT) % LED_COUNT;
                float brightness = effectiveDist < 3 ? (1.0f - effectiveDist / 3.0f) : 0;
                sendPerPixelColor(i, (int) (r * brightness), (int) (g * brightness), (int) (b * brightness));
            }
            pulsePos = (pulsePos + 1) % LED_COUNT;
            mainHandler.postDelayed(this, 80);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>28. Geteilt (links/rechts) – halfAndHalf(int r1, int g1, int b1, int r2, int g2, int b2)</summary>

```java
public void halfAndHalf(int r1, int g1, int b1, int r2, int g2, int b2) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        boolean swap = false;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            int half = LED_COUNT / 2;
            for (int i = 0; i < LED_COUNT; i++) {
                if ((i < half) != swap) sendPerPixelColor(i, r1, g1, b1);
                else sendPerPixelColor(i, r2, g2, b2);
            }
            swap = !swap;
            mainHandler.postDelayed(this, 500);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>29. Wechselnd – alternate(int r, int g, int b)</summary>

```java
public void alternate(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        boolean oddOn = true;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if ((i % 2 == 0) == oddOn) sendPerPixelColor(i, r, g, b);
                else sendPerPixelColor(i, 0, 0, 0);
            }
            oddOn = !oddOn;
            mainHandler.postDelayed(this, 300);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>

<details><summary>30. Ladebalken – loading(int r, int g, int b)</summary>

```java
public void loading(int r, int g, int b) {
    stopAnimation();
    isAnimationRunning = true;
    currentAnimation = new Runnable() {
        int fillPos = 0;
        @Override
        public void run() {
            if (!isAnimationRunning) return;
            for (int i = 0; i < LED_COUNT; i++) {
                if (i <= fillPos) sendPerPixelColor(i, r, g, b);
                else sendPerPixelColor(i, 0, 0, 0);
            }
            fillPos++;
            if (fillPos >= LED_COUNT) fillPos = 0;
            mainHandler.postDelayed(this, 100);
        }
    };
    mainHandler.post(currentAnimation);
}
```
</details>
