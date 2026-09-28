# BLE-Diagnose: Dokumentation für Entwickler

Diese Doku beschreibt, was die Diagnose-App genau macht, mit welchen Android-APIs und
Parametern, und wie du die Ergebnisse in deiner eigenen App nachbaust oder überprüfst.
Der komplette Code steckt in einer Datei:
[`src/de/redmagiccooler/blediagnose/MainActivity.java`](src/de/redmagiccooler/blediagnose/MainActivity.java).

---

## 1. Kernergebnis: so wird der Kühler gefunden

Gemessen auf Samsung S23 Ultra (SM-S918B), Android 16 / SDK 36, Kühler „RM Magcooler 6pro“:

| Scan | Filter | Ergebnis |
|---|---|---|
| 1 | keiner | 703 Pakete, 16 Geräte, **Kühler gefunden** nach 0,1 s, -25 dBm |
| 2 | Service-UUID `d52082ad-e805-9f97-9d4e-1c682d9c9ce6` | **0 Pakete** |
| 3 | Gerätename `RM Magcooler 6pro` | 177 Pakete, **Kühler gefunden** |

Das Werbepaket (Advertising) des Kühlers enthält:

| Feld | Wert |
|---|---|
| Name (`scanRecord.getDeviceName()`) | `RM Magcooler 6pro` |
| Beworbene Service-UUID | `00004a41-0000-1000-8000-00805f9b34fb` (16-Bit **0x4A41**) |
| Herstellerdaten | Company-ID `0x08CA`, Daten `05 06 02 50 02 00 00 00 00 00` |
| Verbindbar | ja |

**Wichtig:** `d52082ad-…` ist der **GATT-Service** zum Steuern. Er ist erst nach
`connectGatt()` + `discoverServices()` sichtbar und steht **nicht** im Werbepaket. Ein
`ScanFilter` auf diese UUID findet den Kühler deshalb nie.

Richtig ist eine dieser Varianten:

```java
List<ScanFilter> filters = new ArrayList<>();
// Mehrere ScanFilter in der Liste sind ODER-verknüpft.
filters.add(new ScanFilter.Builder()
        .setServiceUuid(ParcelUuid.fromString("00004a41-0000-1000-8000-00805f9b34fb"))
        .build());
filters.add(new ScanFilter.Builder()
        .setDeviceName("RM Magcooler 6pro")   // exakter Name, kein "enthält"
        .build());
scanner.startScan(filters, settings, callback);
```

Die Kriterien **innerhalb eines** `ScanFilter` sind UND-verknüpft. Wer Name und UUID in
denselben Builder schreibt, findet nur Geräte, die beides erfüllen.

Alternativ ohne Filter scannen (`startScan(null, settings, cb)`) und in `onScanResult`
selbst prüfen, ob der Name `MAGCOOLER` enthält, ohne auf Groß- und Kleinschreibung zu
achten. So macht es die Test-App im Repo.

> Nicht gemessen, aber auf Samsung bekannt: Bei ausgeschaltetem Display liefern nur
> Scans **mit** Filter Ergebnisse. Für Hintergrund-Scans also die Filter-Variante nehmen.
> Der Filter auf `0x4A41` ist aus dem Werbepaket abgeleitet, aber noch nicht in der
> Diagnose-App selbst getestet (siehe 5.).

---

## 2. Ablauf der App

`startTest()` arbeitet die Schritte der Reihe nach ab. Jeder Schritt schreibt Zeilen
mit Zeitstempel (`+  3.17s`) ins Log und legt bei Auffälligkeiten einen Eintrag in der
Zusammenfassung an.

```
Gerät & App → Berechtigungen → Bluetooth/Standort/Energie → verbundene Geräte
  → Scan 1 (12 s, ohne Filter) → Scan 2 (7 s, UUID-Filter) → Scan 3 (7 s, Namensfilter)
  → optional Verbindungstest (nur lesen) → Bericht speichern
```

Pausen: 0,5 s vor Scan 1, je 1 s zwischen den Scans, 0,8 s vor dem Verbindungstest.
Scan 3 läuft nur, wenn Scan 1 den Kühler mit Namen gefunden hat. Das ergibt 3
`startScan()`-Aufrufe in etwa 30 s und bleibt damit unter Androids Grenze von 5 in 30 s.

### 2.1 Berechtigungen (`checkPermissions`, `requiredPermissions`)

| Android | Angefragt zur Laufzeit |
|---|---|
| 12+ (SDK 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` |
| 7–11 | `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` |

Das Manifest deklariert `BLUETOOTH_SCAN` **ohne** `neverForLocation`, damit Android keine
Scan-Ergebnisse wegfiltert. `COARSE` wird zusammen mit `FINE` angefragt, weil Android 12+
eine alleinige `FINE`-Anfrage ignoriert.

Bewertung:
- SDK 31+ ohne `BLUETOOTH_SCAN` → FEHLER (SecurityException oder keine Ergebnisse)
- SDK 31+ ohne `BLUETOOTH_CONNECT` → FEHLER (`getName()` gibt `null` zurück, `connectGatt()` scheitert)
- SDK ≤ 30 ohne `ACCESS_FINE_LOCATION` → FEHLER (Scan liefert stumm 0 Ergebnisse)

### 2.2 Bluetooth, Standort, Energie (`checkBluetoothAndLocation`)

| Prüfung | API |
|---|---|
| BLE-Hardware | `PackageManager.hasSystemFeature(FEATURE_BLUETOOTH_LE)` |
| Bluetooth an | `BluetoothAdapter.isEnabled()`, `getState()` |
| Hardware-Filter / Batching | `isOffloadedFilteringSupported()`, `isOffloadedScanBatchingSupported()` |
| 2M PHY / Extended Advertising (SDK 26+) | `isLe2MPhySupported()`, `isLeExtendedAdvertisingSupported()` |
| Standort-Schalter | `LocationManager.isLocationEnabled()` (SDK 28+), sonst `Settings.Secure.LOCATION_MODE` |
| Energie | `PowerManager.isInteractive()`, `isPowerSaveMode()`, `isIgnoringBatteryOptimizations()` |
| Scanner vorhanden | `BluetoothAdapter.getBluetoothLeScanner() != null` |

Ist Bluetooth aus oder fehlt der Scanner, bricht der Test hier ab.

### 2.3 Verbundene Geräte (`listConnectedAndBonded`)

`BluetoothManager.getConnectedDevices(BluetoothProfile.GATT)` zeigt, ob der Kühler schon
mit dem Handy verbunden ist. Ein verbundener Kühler sendet **keine Werbung** und taucht
in keinem Scan auf. Das ist der häufigste Grund für „nicht gefunden“, wenn eine andere
App (Hersteller-App, Test-App) die Verbindung noch hält. Gekoppelte Geräte kommen aus
`getBondedDevices()`. Für den Kühler ist Koppeln nicht nötig.

### 2.4 Scans (`startScan`, `ScanRun`)

Für alle drei Scans gilt:

```java
ScanSettings settings = new ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build();
scanner.startScan(filters /* null bei Scan 1 */, settings, run);
// nach 12 s bzw. 7 s:
scanner.stopScan(run);
```

`ScanRun extends ScanCallback` zählt jedes Paket (`onScanResult` und
`onBatchScanResults`) und fasst es pro MAC-Adresse zusammen:
- `advName` = `scanRecord.getDeviceName()` (Name aus dem Werbepaket)
- `sysName` = `device.getName()` (Name aus dem Cache des Systems, braucht `BLUETOOTH_CONNECT`)
- beste RSSI, Anzahl Pakete, Zeit bis zum ersten Paket
- `scanRecord.getServiceUuids()`, erster Eintrag aus `getManufacturerSpecificData()`
- `isConnectable()` (SDK 26+)

Als Kühler gilt ein Gerät, wenn `advName` oder `sysName` (in Großbuchstaben) `MAGCOOLER`,
`REDMAGIC`, `RED MAGIC` oder `COOLER` enthält **oder** `d52082ad-…` beworben wird.

`onScanFailed(code)` wird mit Namen und Tipp ausgegeben:

| Code | Name | Bedeutung |
|---|---|---|
| 1 | `ALREADY_STARTED` | Scan mit demselben Callback läuft schon |
| 2 | `APPLICATION_REGISTRATION_FAILED` | zu viele Scanner in der App oder Stack hängt |
| 3 | `INTERNAL_ERROR` | Fehler im Bluetooth-Stack |
| 4 | `FEATURE_UNSUPPORTED` | Einstellung vom Gerät nicht unterstützt |
| 5 | `OUT_OF_HARDWARE_RESOURCES` | keine Hardware-Filter mehr frei |
| 6 | `SCANNING_TOO_FREQUENTLY` | mehr als 5× `startScan()` in 30 s |

Die App merkt sich Scan-Starts auch über mehrere Testläufe hinweg und warnt, wenn es mehr
als 5 in 30 s werden.

### 2.5 Verbindungstest (`startConnectTest`, `onServices`)

Nur wenn der Kühler gefunden wurde und das Häkchen gesetzt ist. Es wird **nichts
geschrieben** außer den CCCD-Deskriptoren für die Notifications.

1. `device.connectGatt(ctx, false, cb, TRANSPORT_LE)`, Timeout 15 s
2. nach `STATE_CONNECTED` 600 ms warten, dann `discoverServices()`
3. alle Services und Characteristics mit Properties und `[CCCD]`-Markierung auflisten
4. nacheinander lesen, falls vorhanden: `0x2A29` Hersteller, `0x2A24` Modell,
   `0x2A26` Firmware, `0x2A28` Software, `0x2A27` Hardware
5. Notifications auf `0x1015` und `0x1016` einschalten: `setCharacteristicNotification(true)`
   und CCCD `0x2902` mit `ENABLE_NOTIFICATION_VALUE` schreiben
6. 5 s lauschen, Pakete pro Characteristic zählen, die ersten 3 als Hex loggen
7. `disconnect()` + `close()`

GATT-Operationen laufen strikt nacheinander. Die nächste startet erst im Callback der
vorherigen, spätestens nach 3 s Timeout. Beide Callback-Varianten sind implementiert:
die alten mit `characteristic.getValue()` bis SDK 32 und die neuen mit `byte[] value`
ab SDK 33.

### 2.6 Bericht (`finishTest`, `saveReport`, `shareReport`)

- Kopf: Zeitpunkt, Gerät, Android-Version, Dateipfad
- `ZUSAMMENFASSUNG`: je eine Zeile pro Scan, danach die Einträge `[FEHLER]`, `[WARNUNG]`,
  `[HINWEIS]`, `[INFO]`, `[OK]`
- `DETAILS`: das vollständige Log mit Zeitstempeln
- Gespeichert unter `Download/ble-diagnose-JJJJMMTT-HHMMSS.txt` (SDK 29+ über `MediaStore`,
  darunter im App-Ordner)
- „Log teilen“ schickt die Datei als Anhang (`EXTRA_STREAM`), „Kopieren“ legt den ganzen
  Text in die Zwischenablage

---

## 3. Log lesen: Symptom → Ursache

| Im Log | Bedeutung | Was tun |
|---|---|---|
| Scan 1: `0 Ergebnisse`, kein `onScanFailed` | Scan läuft, Android liefert aber nichts | Berechtigungen, Standort-Schalter, Drosselung prüfen |
| Scan 1: `onScanFailed(2)` oder `(6)` | zu viele Scanner oder zu häufig gestartet | alte Scans stoppen, seltener starten |
| Scan 1: Geräte, aber `Kühler: nein` | Kühler sendet keine Werbung | Kühler an? schon verbunden (siehe 2.3)? in Reichweite? |
| Scan 1 `JA`, Scan 2 `0 Ergebnisse` | UUID-Filter mit falscher UUID | auf `0x4A41` oder den Namen filtern (siehe 1.) |
| `onConnectionStateChange status=133` | GATT-Fehler, oft eine alte Verbindung ohne `close()` | vor jedem Connect `close()` auf die alte `BluetoothGatt` |
| `Notifications empfangen: 0` | CCCD nicht geschrieben oder Kühler antwortet nicht | Reihenfolge `setCharacteristicNotification` → `writeDescriptor` prüfen |

---

## 4. Selbst verifizieren

### Mit der fertigen APK

1. APK installieren. Prüfsumme der Datei im Repo:
   ```
   sha256  1989afc9b17450449e75139f16541b442cc9c505c4d0f7d035d3bac9826b613d  ble-diagnose.apk
   ```
   Signatur-Zertifikat (v2, selbst erzeugter Debug-Schlüssel), prüfbar mit
   `apksigner verify --print-certs ble-diagnose.apk`:
   ```
   SHA-256: 67:8B:9B:32:FC:A2:12:AF:79:F0:3B:60:A4:FE:79:4A:07:89:E7:71:F0:30:3D:A9:CB:C6:2E:CD:6D:94:38:E2
   ```
2. Alle anderen Apps schließen, die mit dem Kühler verbunden sein könnten.
3. Test starten und prüfen, dass du dasselbe Bild wie in Abschnitt 1 bekommst.
4. Danach deine eigene App mit denselben Filtern testen. Zum Vergleich hilft
   `adb logcat | grep -i -E "BtGatt|bt_btm|scan|BluetoothLe"`.

### Selbst bauen

**Ohne Android SDK** (so wurde die APK erzeugt):

```sh
cd ble-diagnose
./build.sh        # braucht JDK 17+, python3, curl, unzip, keytool
```

`build.sh` lädt alles von Maven Central:

| Schritt | Werkzeug | Quelle |
|---|---|---|
| Ressourcen + Manifest | `aapt2` + Framework-Ressourcen | `org.apktool:apktool-lib:3.0.3` (`prebuilt/`) |
| Kompilieren | `javac --release 8` gegen Android-API 34 | `org.robolectric:android-all:14-robolectric-10818077` |
| Dex | `dx` | `com.jakewharton.android.repackaged:dalvik-dx:16.0.1` |
| Packen + Ausrichten | `tools/package_apk.py` (`resources.arsc` unkomprimiert, 4-Byte-aligned) | – |
| Signieren (nur v2) | `tools/Sign.java` | `com.android.tools.build:apksig:2.3.0` |

Weil ein eigener Debug-Schlüssel erzeugt wird, ist deine APK nicht byte-identisch mit
der im Repo. Das ist normal.

**Mit Android Studio:** Neues Projekt „No Activity“ anlegen, Package
`de.redmagiccooler.blediagnose`, minSdk 24, targetSdk 34, Sprache Java. Dann
`AndroidManifest.xml`, `res/drawable/ic_launcher.xml` und `MainActivity.java` übernehmen.
Es gibt keine Abhängigkeiten, kein AndroidX und keine Layouts, die Oberfläche wird im Code
aufgebaut.

---

## 5. Bekannte Einschränkungen

- Scan 2 filtert noch auf den GATT-Service `d52082ad-…` statt auf die Werbe-UUID `0x4A41`.
  Er zeigt damit den Fehler, testet aber nicht die korrekte Variante. Der Hinweis in der
  Zusammenfassung rät deshalb zu pauschal von UUID-Filtern ab.
- Die Kühler-Erkennung per Name ist bewusst großzügig (`COOLER`). Fremde Geräte mit
  „Cooler“ im Namen würden ebenfalls markiert.
- Nicht getestet: Scans bei ausgeschaltetem Display bzw. im Hintergrund.
- Gemessen bisher nur auf einem Gerät (S23 Ultra, Android 16).
