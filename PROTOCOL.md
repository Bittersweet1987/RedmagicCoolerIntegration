# REDMAGIC VC Cooler 6 Pro – BLE protocol (reverse engineered)

*Deutsche Fassung: [PROTOCOL.de.md](PROTOCOL.de.md)*

Reverse engineered on 2026-08-17 via:

- Static analysis of the Goper app (`cn.nubia.externdevice`, version 3.4.0) — Dalvik bytecode of
  `com.xiaoji.sdk.gcm.GcmProtocol`, `com.xiaoji.sdk.gcm.GcmRadiatorUtil`,
  `com.xiaoji.sdk.device.config.base.RadiatorCfg`.
- Bluetooth HCI snoop log captures (`adb bugreport`) of the real Goper app driving a physical
  REDMAGIC VC Cooler 6 Pro (firmware V6.1.5, advertised as "RM Magcooler 6pro" /
  "REDMAGIC Cooler 6 pro+").

The app also contains a generic, vendor-wide "GCM" protocol (`GcmProtocol`, from "xiaoji/gtouch")
that drives gamepads, keyboards and so on. The cooler uses **only** the raw GATT characteristics
documented below — the GCM framing format with checksum (`cmd1 cmd2 len ack payload... checksum`)
could **not** be confirmed for this device (writes in that format were rejected with
`ATT_ERROR_INVALID_ATTRIBUTE_LENGTH`). The values actually used are short raw values per
characteristic.

## BLE connection

- Advertising name: `RM Magcooler 6pro` or `REDMAGIC Cooler 6 pro+`
- No pairing/bonding required (a plain GATT connection is enough)
- Relevant service: **`d52082ad-e805-9f97-9d4e-1c682d9c9ce6`**
- A second service (`00010203-0405-0607-0809-0a0b0c0d1912`) matches the pattern of a Telink
  OTA/firmware update profile. **Leave it alone** — a test write with unrelated data dropped the
  connection immediately (presumably a safety mechanism in the OTA logic).

## Characteristics in service `d52082ad-...`

All characteristic UUIDs use the standard Bluetooth base UUID
(`0000XXXX-0000-1000-8000-00805f9b34fb`).

| UUID (short) | Properties | Function | Confirmed by |
|---|---|---|---|
| `0x1011` | Read, Write | **Cooling switch** (cooling on/off) | HCI snoop + live test |
| `0x1012` | Read, Write | **Fan level** | HCI snoop + live test |
| `0x1013` | Read, Write, Notify* | **LED on/off** | HCI snoop + live test |
| `0x1014` | Read, Notify* | **Temperature** (single byte, °C) | Read value matched Goper UI |
| `0x1015` | Read, Notify | **Temperature sensor** (notify stream) | HCI snoop + UI cross-check |
| `0x1016` | Read, Notify | unknown telemetry (16 bytes, continuous) | observation |
| `0x1017` | Read, Write | **Diablo / "destruction god" mode** | HCI snoop |
| `0x1018` | Read, Write | **Automatic temperature control** | HCI snoop + live test |
| `0x1019` | Read | unknown, **writes rejected** (`ATT_ERROR_WRITE_NOT_PERMITTED`) | observation |

\* The device exposes no CCCD descriptor (`0x2902`) for `0x1013`/`0x1014`, so notifications cannot be
subscribed there even though the property flag is set. Read those characteristics explicitly instead
of relying on notifications.

## Commands in detail

### Cooling switch (`0x1011`)

Single byte:

| Value | Meaning |
|---|---|
| `0x02` | ON (cooling active) |
| `0x03` | OFF (standby) |

### Fan level (`0x1012`)

Single byte. Goper exposes **9 steps**. The exact values were captured by stepping through 1→9 in
Goper while recording; the same sequence appeared twice identically in one capture:

| Step | Hex | Decimal |
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

**The scale is not linear:** steps 1–5 rise by 6, steps 5–9 by 4. Use these values verbatim rather
than interpolating.

The current value is readable.

It was not tested whether the device accepts values outside 40–80 (e.g. `0x00` or `0xFF`) — Goper
itself only ever sends the nine values above.

### LED (`0x1013`) — 4-byte value, on/off

Fixed values, **not a toggle** (an early assumption that live testing disproved):

| Bytes | Meaning |
|---|---|
| `01 00 00 00` | LED **ON** |
| `06 00 00 00` | LED **OFF** |

Both confirmed by live test on real hardware.

The current state is **readable**: a `Read` on `0x1013` returns `01 00 00 00` when the LED is on and
`06 00 00 00` when it is off, so a client app does not need to track it itself.

The first byte looks like a mode/effect number (Goper has a separate "light customization" menu
entry). Whether `02`–`05` select further lighting effects was **not tested systematically** — `01`
(on) and `06` (off) are the only values Goper was observed sending.

Caution when reimplementing: do **not** send `01 00 00 00` and `06 00 00 00` in quick succession —
the LED merely flashes and settles back into the off state.

### Diablo / "destruction god" mode (`0x1017`)

Single byte, plain boolean:

| Value | Meaning |
|---|---|
| `0x01` | ON (louder fan for better heat dissipation) |
| `0x00` | OFF |

Confirmed by HCI snoop on real hardware (Goper sends exactly these two values).

**Note for testers:** toggling produces **no immediately perceptible effect** — neither audible nor
in the telemetry stream (`0x1016` returns identical values before and after). This is equally true
of Goper itself, so it is not a flaw in the reimplementation. The mode apparently only takes effect
under real load (a warm phone docked, actual cooling demand). Verify it by reading the register back
rather than by listening for a change.

### Automatic temperature control (`0x1018`)

Single byte, plain boolean:

| Value | Meaning |
|---|---|
| `0x01` | ON (device adjusts fan level by temperature on its own) |
| `0x00` | OFF (manual fan level via `0x1012`) |

### Temperature sensor (`0x1015`) — notify

Emits 2-byte packets roughly once per second, alternating between:

- `04 TT` — `TT` is the **temperature in °C as a single byte** (e.g. `04 0A` = 10 °C, `04 0E` = 14 °C,
  `04 12` = 18 °C). Matches the "back pocket temperature" shown in the Goper UI.
- `05 80` (also seen as `05 00`) — fixed value, meaning unclear (possibly a placeholder or a second
  sensor channel carrying no payload on this model).

The same temperature is also available as a plain single-byte `Read` on `0x1014`.

### Unknown telemetry (`0x1016`) — notify

Emits 16-byte packets of the form:

```
AA 63 31 50 00 31 50 19 01 01 00 00 00 XX 0C DD
```

Only byte 13 (`XX`; observed as `0B`, `0F`, `13`, `17`, `1B`, `1F`, `23`, `27`, `2B`, …) changes over
time — possibly a counter, voltage or current reading. **Not decoded**, and not needed for the core
cooler functions.

## Verification status

Confirmed live on real hardware: cooling switch, fan steps 1–9, LED on/off, automatic temperature
control, temperature readout. Diablo mode: byte values confirmed, but with no observable effect (see
note above).

## Open questions for the developer

1. Verify the exact min/max bounds of `0x1012` (fan level) against real hardware.
2. Decode byte 13 of `0x1016` (may be useful for extended telemetry/diagnostics).
3. Determine the purpose of `0x1019` (read-only).
4. Determine whether `02`–`05` on `0x1013` select additional lighting effects.
