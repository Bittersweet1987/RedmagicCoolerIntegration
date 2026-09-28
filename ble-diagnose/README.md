# BLE-Diagnose

Kleine Android-App (ca. 28 KB, Android 7+), die prüft, warum ein Handy den
RedMagic-Kühler – oder überhaupt BLE-Geräte – nicht findet. Sie schreibt ein
lesbares Log für Entwickler. An den Kühler sendet sie **keine** Befehle,
der optionale Verbindungstest liest nur.

## Benutzung

1. `ble-diagnose.apk` installieren (Installation aus unbekannten Quellen erlauben).
2. Kühler einschalten. Hersteller-App und andere Kühler-Apps komplett schließen,
   denn ein verbundener Kühler sendet keine Werbung und taucht in keinem Scan auf.
3. **Test starten**, Berechtigungen erlauben, ca. 30–50 s warten.
4. **Log teilen**: Die Datei liegt zusätzlich unter `Download/ble-diagnose-<Zeit>.txt`.

## Was geprüft wird

| Abschnitt | Inhalt |
|---|---|
| Gerät & App | Hersteller, Modell, Android-Version, Firmware |
| Berechtigungen | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, Standort (erteilt / fehlt / abgelehnt) |
| Bluetooth, Standort, Energie | BT an/aus, Scanner vorhanden, GPS-Schalter, Energiesparmodus, Bildschirm |
| Verbundene Geräte | Ob der Kühler gerade schon mit dem Handy verbunden ist |
| Scan 1 | 12 s ohne Filter: alle Geräte mit Name, RSSI, UUIDs, Herstellerdaten |
| Scan 2 | Hardware-Filter auf Service-UUID `d52082ad-e805-9f97-9d4e-1c682d9c9ce6` |
| Scan 3 | Hardware-Filter auf den exakten Namen (falls Kühler in Scan 1 gefunden) |
| Verbindungstest | Connect, Service-Discovery, Geräteinfo lesen, 5 s Notifications, `gatt.close()` |

Oben im Log steht eine **Zusammenfassung** mit `[FEHLER]`, `[WARNUNG]`, `[HINWEIS]`
und `[OK]`, darunter die Details mit Zeitstempeln.

Ausführliche Beschreibung für Entwickler (APIs, Parameter, Log lesen, nachbauen): [DEVELOPER.md](DEVELOPER.md)

Die App deklariert `BLUETOOTH_SCAN` absichtlich **ohne** `neverForLocation` und fragt
den Standort mit ab, damit Android keine Scan-Ergebnisse wegfiltert. Findet diese
App Geräte und eine andere App nicht, liegt es an der anderen App.

## Bauen

Ohne Android SDK und ohne Gradle. Alle Werkzeuge kommen von Maven Central:

```sh
./build.sh   # braucht JDK 17+, python3, curl, unzip
```

Ergebnis: `build/ble-diagnose.apk`, signiert (v2) mit einem lokal erzeugten Debug-Schlüssel
in `~/.cache/ble-diagnose-tools/debug.p12`. Bei einem neuen Schlüssel muss die alte App
vor dem Update deinstalliert werden.
