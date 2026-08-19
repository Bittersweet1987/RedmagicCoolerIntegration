# RedmagicCoolerIntegration

*Deutsche Fassung: [README.de.md](README.de.md)*

BLE control library for the **REDMAGIC VC Cooler 6 Pro** (Nubia phone cooler, advertised as
"RM Magcooler 6pro" / "REDMAGIC Cooler 6 pro+"), reverse engineered from the official Goper
companion app.

Intended as a building block for integration into
[pocketSRT](https://github.com/romestylez/pocketSRT), mirroring its existing BlackShark cooler
support.

Two implementations are included — **Kotlin/Android** and **Swift/iOS** — both built on the same
protocol documentation, which was verified against real hardware.

## Contents

| Path | Contents |
|---|---|
| [`PROTOCOL.md`](PROTOCOL.md) | **The actual work**: full documentation of the reverse engineered BLE protocol (service/characteristic UUIDs, exact byte values, pitfalls, open questions). Platform independent. |
| [`PROTOCOL.de.md`](PROTOCOL.de.md) | Same document in German / Dasselbe Dokument auf Deutsch |
| `android/` | Kotlin implementation as an Android library module (`com.romestylez.redmagiccooler`) |
| `swift/` | Swift implementation as a Swift package (`RedMagicCoolerLib`, CoreBluetooth) |
| [`test-app/`](test-app/) | Standalone Android test app used to reverse engineer and verify the protocol. Talks raw GATT, does not use the library — handy for cross-checking against your own hardware. |

If you only need the protocol and want to implement it yourself, `PROTOCOL.md` is sufficient on its
own.

## Features (identical in both implementations)

- Scanning and connecting
- Cooling on/off
- Fan level 1–9 (the exact values Goper sends)
- Diablo / "destruction god" mode on/off
- Automatic temperature control on/off
- LED on/off (including reading back the current state)
- Temperature readout (continuous via notify, plus direct read)

## Verification status

The **protocol** was not merely inferred from app bytecode: every function was captured from real
hardware (firmware V6.1.5) via Bluetooth HCI snoop logging while operating the Goper app, then
cross-checked with a purpose-built test app. Confirmed live on the real device:

- Cooling switch on/off ✅
- Fan levels 1–9 ✅
- LED on/off ✅
- Automatic temperature control on/off ✅
- Temperature readout ✅
- Diablo mode: byte values confirmed ✅, but **with no immediately perceptible effect** — this is
  equally true of Goper itself, see the note in `PROTOCOL.md`.

Regarding the **code**:

- **Kotlin/Android**: compiles cleanly (Gradle build). The underlying raw commands are hardware
  tested, but this exact wrapper has not yet been exercised end to end.
- **Swift/iOS**: carefully ported from the Kotlin version, but **neither compiled nor tested** — the
  development environment here was Windows, and CoreBluetooth only exists on Apple platforms. Please
  build it once and verify against real hardware before relying on it.

## Usage

### Android (Kotlin)

Include as a Gradle module (`settings.gradle`):

```groovy
include ':RedMagicCooler6ProLib'
project(':RedMagicCooler6ProLib').projectDir = new File(rootDir, 'path/to/RedMagicCooler6ProLib/android')
```

```kotlin
val cooler = RedMagicCooler6Pro(context)
cooler.onStateChanged = { state -> /* update UI */ }
cooler.onConnectionStateChanged = { connected -> /* ... */ }
cooler.startScanAndConnect()

// once connected:
cooler.setCoolingEnabled(true)
cooler.setFanStep(5)          // 1..9
cooler.setLedEnabled(true)
cooler.setAutoTemperatureControl(false)
```

Required permissions: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+) or
`ACCESS_FINE_LOCATION` below that.

### iOS (Swift)

Add as a Swift package (local path `swift/`), or just copy the single file
`swift/Sources/RedMagicCoolerLib/RedMagicCooler6Pro.swift` into your project.

```swift
let cooler = RedMagicCooler6Pro()
cooler.onStateChanged = { state in /* update UI */ }
cooler.startScanAndConnect()

// once connected:
cooler.setCoolingEnabled(true)
cooler.setFanStep(5)          // 1...9
cooler.setLedEnabled(true)
cooler.setAutoTemperatureControl(false)
```

`NSBluetoothAlwaysUsageDescription` is required in `Info.plist`.

Note: iOS does not expose MAC addresses. To reconnect without scanning, persist the
`CBPeripheral.identifier` (a UUID) and use `connect(identifier:)`.

## Integrating into pocketSRT (suggestion)

Both classes deliberately follow the style of the existing BlackShark integration (callback based,
`startScanAndConnect()` / `disconnect()`, a state object with a change callback). They can be added
as a separate module/package, or copied straight into an existing cooler package and registered as
another supported device type alongside MagCooler 4/5 Pro and FunCooler 6.

For temperature automation like the BlackShark support has (start/stop temperature with hysteresis),
either use the device's own automation (`setAutoTemperatureControl(true)`) or evaluate the
temperature from the notify stream yourself and set the fan level manually.

## Known limitations / open points

See "Open questions for the developer" at the end of `PROTOCOL.md`.

## Licence / provenance

Licensed under GPL-3.0, see [LICENSE](LICENSE).

Reverse engineering was done purely for interoperability. No code was taken from the Goper app —
only the observed protocol behaviour was documented and independently reimplemented. The protocol
documentation describes observed behaviour of third-party hardware and is not derived from any
proprietary source code.
