import Foundation
import CoreBluetooth

/// Everything currently known about a connected REDMAGIC VC Cooler 6 Pro.
///
/// Optional fields are `nil` until the corresponding characteristic has been read at least once.
///
/// DE: Alle aktuell bekannten Werte eines verbundenen REDMAGIC VC Cooler 6 Pro.
/// Optionale Felder sind `nil`, solange die zugehoerige Characteristic noch nicht mindestens
/// einmal gelesen wurde.
public struct CoolerState: Equatable {
    public var connected: Bool = false
    public var coolingEnabled: Bool?
    /// Raw fan level byte (40...80). Use `fanStep` for Goper's 1...9 numbering.
    /// DE: Roher Kuehlstufen-Bytewert (40...80). Fuer die Goper-Nummerierung 1...9 siehe `fanStep`.
    public var fanLevel: Int?
    public var diabloMode: Bool?
    public var autoTemperatureControl: Bool?
    public var temperatureCelsius: Int?
    public var ledOn: Bool?

    /// The fan level expressed as Goper's step number (1...9), or nil if the raw value
    /// is not one of the nine values Goper uses.
    ///
    /// DE: Die Kuehlstufe als Goper-Stufennummer (1...9), oder nil, wenn der Rohwert keiner der
    /// neun von Goper verwendeten Werte ist.
    public var fanStep: Int? {
        guard let level = fanLevel,
              let index = RedMagicCooler6Pro.fanLevels.firstIndex(of: UInt8(level)) else { return nil }
        return index + 1
    }

    public init() {}
}

/// BLE control library for the REDMAGIC VC Cooler 6 Pro (advertised as "RM Magcooler 6pro" /
/// "REDMAGIC Cooler 6 pro+"), reverse engineered from the Goper companion app
/// (cn.nubia.externdevice) via static bytecode analysis and Bluetooth HCI snoop logging,
/// then verified command-by-command against real hardware.
///
/// See PROTOCOL.md for the full protocol writeup including open questions.
///
/// DE: BLE-Steuerungsbibliothek fuer den REDMAGIC VC Cooler 6 Pro (Werbename "RM Magcooler 6pro" /
/// "REDMAGIC Cooler 6 pro+"), reverse engineered aus der Goper-Companion-App
/// (cn.nubia.externdevice) per statischer Bytecode-Analyse und Bluetooth-HCI-Snoop-Mitschnitten
/// und anschliessend Befehl fuer Befehl an echter Hardware verifiziert.
/// Die vollstaendige Protokollbeschreibung inklusive offener Fragen steht in PROTOCOL.de.md.
///
/// Usage / DE: Verwendung:
/// ```swift
/// let cooler = RedMagicCooler6Pro()
/// cooler.onStateChanged = { state in print(state) }
/// cooler.startScanAndConnect()
/// // ...once connected:
/// cooler.setCoolingEnabled(true)
/// cooler.setFanStep(5)
/// cooler.setLedEnabled(true)
/// ```
public final class RedMagicCooler6Pro: NSObject {

    // MARK: - Protocol constants

    public static let serviceUUID = CBUUID(string: "d52082ad-e805-9f97-9d4e-1c682d9c9ce6")

    /// Cooling on/off. 0x02 = on, 0x03 = off.
    /// DE: Kuehlung an/aus. 0x02 = an, 0x03 = aus.
    private static let charCoolingSwitch = CBUUID(string: "1011")
    /// Fan level, single raw byte. See `fanLevels`.
    /// DE: Kuehlstufe, einzelnes Rohbyte. Siehe `fanLevels`.
    private static let charFanLevel = CBUUID(string: "1012")
    /// LED, 4 bytes. 01 00 00 00 = on, 06 00 00 00 = off. Readable.
    /// DE: LED, 4 Byte. 01 00 00 00 = an, 06 00 00 00 = aus. Auslesbar.
    private static let charLed = CBUUID(string: "1013")
    /// Temperature as a single byte (degrees Celsius), readable.
    /// DE: Temperatur als einzelnes Byte (Grad Celsius), auslesbar.
    private static let charTemperatureRead = CBUUID(string: "1014")
    /// Temperature notify stream, "04 TT" where TT is degrees Celsius (alternates with "05 00").
    /// DE: Temperatur-Notify-Stream, "04 TT" mit TT in Grad Celsius (im Wechsel mit "05 00").
    private static let charTemperatureNotify = CBUUID(string: "1015")
    /// Diablo / "destruction god" mode. 0x01 = on, 0x00 = off.
    /// DE: Zerstoerungsgott-/Diablo-Modus. 0x01 = an, 0x00 = aus.
    private static let charDiabloMode = CBUUID(string: "1017")
    /// Automatic temperature control. 0x01 = on, 0x00 = off.
    /// DE: Automatische Temperaturregelung. 0x01 = an, 0x00 = aus.
    private static let charAutoTemp = CBUUID(string: "1018")

    private static let coolingOn: UInt8 = 0x02
    private static let coolingOff: UInt8 = 0x03

    private static let ledOnFrame = Data([0x01, 0x00, 0x00, 0x00])
    private static let ledOffFrame = Data([0x06, 0x00, 0x00, 0x00])

    /// The nine fan levels Goper offers, in the exact raw byte values it sends (captured via HCI
    /// snoop). Deliberately a lookup table rather than a formula - the scale is NOT linear
    /// (steps 1-5 rise by 6, steps 5-9 by 4).
    ///
    /// DE: Die neun Kuehlstufen von Goper, in exakt den Rohwerten, die Goper sendet (per HCI-Snoop
    /// ermittelt). Bewusst eine Wertetabelle statt einer Formel - die Skala ist NICHT linear
    /// (Stufen 1-5 steigen in 6er-Schritten, Stufen 5-9 in 4er-Schritten).
    public static let fanLevels: [UInt8] = [0x28, 0x2E, 0x34, 0x3A, 0x40, 0x44, 0x48, 0x4C, 0x50]

    /// Name substrings this device has been observed advertising under.
    /// DE: Namensbestandteile, unter denen das Geraet beobachtet wurde.
    public static let nameFilters = ["MAGCOOLER", "REDMAGIC", "COOLER"]

    // MARK: - Callbacks

    public var onStateChanged: ((CoolerState) -> Void)?
    public var onConnectionStateChanged: ((Bool) -> Void)?
    public var onLog: ((String) -> Void)?

    public private(set) var state = CoolerState() {
        didSet {
            if state != oldValue {
                onStateChanged?(state)
            }
        }
    }

    // MARK: - Internals

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var characteristics: [CBUUID: CBCharacteristic] = [:]
    private var wantsScan = false
    private var targetIdentifier: UUID?

    public override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: .main)
    }

    public var isConnected: Bool { state.connected }

    // MARK: - Connection

    /// Scans for a cooler by advertised name and connects to the first match.
    /// DE: Sucht per Werbename nach einem Kuehler und verbindet sich mit dem ersten Treffer.
    public func startScanAndConnect() {
        wantsScan = true
        if centralManager.state == .poweredOn {
            beginScan()
        }
        // otherwise centralManagerDidUpdateState will pick it up
    }

    /// Connects to a specific peripheral identifier discovered earlier.
    /// On iOS there are no MAC addresses - persist `CBPeripheral.identifier` across launches
    /// and use `retrievePeripherals(withIdentifiers:)`, which this method wraps.
    ///
    /// DE: Verbindet sich mit einer zuvor entdeckten Peripheral-Kennung. Unter iOS gibt es keine
    /// MAC-Adressen - dafuer `CBPeripheral.identifier` ueber App-Starts hinweg speichern; diese
    /// Methode kapselt `retrievePeripherals(withIdentifiers:)`.
    public func connect(identifier: UUID) {
        targetIdentifier = identifier
        guard centralManager.state == .poweredOn else {
            wantsScan = true
            return
        }
        if let known = centralManager.retrievePeripherals(withIdentifiers: [identifier]).first {
            connect(to: known)
        } else {
            beginScan()
        }
    }

    public func disconnect() {
        if let peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        characteristics.removeAll()
        state = CoolerState()
        onConnectionStateChanged?(false)
    }

    private func beginScan() {
        log("Scanning for cooler...")
        // Some units do not advertise the service UUID, so scan broadly and filter by name.
        // DE: Manche Geraete werben die Service-UUID nicht, daher breit scannen und nach Namen
        // filtern.
        centralManager.scanForPeripherals(withServices: nil, options: nil)
    }

    private func connect(to peripheral: CBPeripheral) {
        centralManager.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        log("Connecting to \(peripheral.identifier)...")
        centralManager.connect(peripheral, options: nil)
    }

    // MARK: - Public control API

    public func setCoolingEnabled(_ enabled: Bool) {
        write(Data([enabled ? Self.coolingOn : Self.coolingOff]), to: Self.charCoolingSwitch)
        state.coolingEnabled = enabled
    }

    /// Sets the fan speed by Goper's own step number, 1 (quietest) to 9 (fastest).
    /// This is what UI should normally use - it writes exactly the values Goper writes.
    ///
    /// DE: Setzt die Kuehlstufe anhand der Goper-Stufennummer, 1 (leiseste) bis 9 (schnellste).
    /// Das ist der normale Weg fuer die UI - es werden exakt die Werte geschrieben, die auch
    /// Goper schreibt.
    public func setFanStep(_ step: Int) {
        let index = min(max(step, 1), Self.fanLevels.count) - 1
        setFanLevel(Int(Self.fanLevels[index]))
    }

    /// Sets the fan level by raw byte value. Prefer `setFanStep(_:)` unless you specifically want
    /// to probe values Goper never sends; behaviour outside 40...80 is untested.
    ///
    /// DE: Setzt die Kuehlstufe als Rohbytewert. Bevorzugt `setFanStep(_:)` verwenden, ausser es
    /// sollen bewusst Werte getestet werden, die Goper nie sendet; das Verhalten ausserhalb von
    /// 40...80 ist ungetestet.
    public func setFanLevel(_ level: Int) {
        let clamped = UInt8(min(max(level, 0), 255))
        write(Data([clamped]), to: Self.charFanLevel)
        state.fanLevel = Int(clamped)
    }

    public func setDiabloMode(_ enabled: Bool) {
        write(Data([enabled ? 0x01 : 0x00]), to: Self.charDiabloMode)
        state.diabloMode = enabled
    }

    public func setAutoTemperatureControl(_ enabled: Bool) {
        write(Data([enabled ? 0x01 : 0x00]), to: Self.charAutoTemp)
        state.autoTemperatureControl = enabled
    }

    /// Turns the LED ring on or off. This is a plain set (not a toggle) and the current state is
    /// readable back from the same characteristic.
    ///
    /// Do not call twice in quick succession with opposite values - the device visibly flashes and
    /// settles on the last value written.
    ///
    /// DE: Schaltet den LED-Ring ein oder aus. Das ist ein einfaches Setzen (kein Toggle); der
    /// aktuelle Zustand ist ueber dieselbe Characteristic auslesbar. Nicht zweimal kurz
    /// hintereinander mit entgegengesetzten Werten aufrufen - die LED blinkt dann sichtbar auf
    /// und bleibt beim zuletzt geschriebenen Wert stehen.
    public func setLedEnabled(_ enabled: Bool) {
        write(enabled ? Self.ledOnFrame : Self.ledOffFrame, to: Self.charLed)
        state.ledOn = enabled
    }

    /// Re-reads every readable characteristic and updates `state`.
    /// DE: Liest alle lesbaren Characteristics neu und aktualisiert `state`.
    public func refreshState() {
        guard let peripheral else { return }
        for uuid in [Self.charCoolingSwitch, Self.charFanLevel, Self.charLed,
                     Self.charDiabloMode, Self.charAutoTemp, Self.charTemperatureRead] {
            if let characteristic = characteristics[uuid] {
                peripheral.readValue(for: characteristic)
            }
        }
    }

    // MARK: - Helpers

    private func write(_ data: Data, to uuid: CBUUID) {
        guard let peripheral, let characteristic = characteristics[uuid] else {
            log("Not connected or characteristic \(uuid) missing")
            return
        }
        peripheral.writeValue(data, for: characteristic, type: .withResponse)
    }

    private func apply(value: Data, from uuid: CBUUID) {
        guard !value.isEmpty else { return }
        switch uuid {
        case Self.charCoolingSwitch:
            state.coolingEnabled = value[0] == Self.coolingOn
        case Self.charFanLevel:
            state.fanLevel = Int(value[0])
        case Self.charLed:
            // 0x01 = on, 0x06 = off; anything else is left as-is rather than guessed at.
            // DE: 0x01 = an, 0x06 = aus; alles andere bleibt unveraendert statt geraten.
            if value[0] == 0x01 { state.ledOn = true }
            else if value[0] == 0x06 { state.ledOn = false }
        case Self.charDiabloMode:
            state.diabloMode = value[0] != 0
        case Self.charAutoTemp:
            state.autoTemperatureControl = value[0] != 0
        case Self.charTemperatureRead:
            state.temperatureCelsius = Int(value[0])
        case Self.charTemperatureNotify:
            // Stream alternates "04 TT" (TT = degrees C) and "05 00" (meaning unknown).
            // DE: Der Stream wechselt zwischen "04 TT" (TT = Grad C) und "05 00" (Bedeutung
            // unklar).
            if value.count >= 2, value[0] == 0x04 {
                state.temperatureCelsius = Int(value[1])
            }
        default:
            break
        }
    }

    private func log(_ message: String) {
        onLog?(message)
    }
}

// MARK: - CBCentralManagerDelegate

extension RedMagicCooler6Pro: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            log("Bluetooth not available (state: \(central.state.rawValue))")
            return
        }
        if let targetIdentifier,
           let known = central.retrievePeripherals(withIdentifiers: [targetIdentifier]).first {
            connect(to: known)
        } else if wantsScan {
            beginScan()
        }
    }

    public func centralManager(_ central: CBCentralManager,
                               didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any],
                               rssi RSSI: NSNumber) {
        if let targetIdentifier, peripheral.identifier == targetIdentifier {
            connect(to: peripheral)
            return
        }
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
        guard let name else { return }
        let upper = name.uppercased()
        if Self.nameFilters.contains(where: { upper.contains($0) }) {
            log("Found \(name)")
            connect(to: peripheral)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log("Connected, discovering services...")
        wantsScan = false
        peripheral.discoverServices([Self.serviceUUID])
    }

    public func centralManager(_ central: CBCentralManager,
                               didFailToConnect peripheral: CBPeripheral,
                               error: Error?) {
        log("Failed to connect: \(error?.localizedDescription ?? "unknown")")
        state = CoolerState()
        onConnectionStateChanged?(false)
    }

    public func centralManager(_ central: CBCentralManager,
                               didDisconnectPeripheral peripheral: CBPeripheral,
                               error: Error?) {
        log("Disconnected\(error.map { ": \($0.localizedDescription)" } ?? "")")
        characteristics.removeAll()
        state = CoolerState()
        onConnectionStateChanged?(false)
    }
}

// MARK: - CBPeripheralDelegate

extension RedMagicCooler6Pro: CBPeripheralDelegate {

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            log("Cooler service not found on this device")
            return
        }
        peripheral.discoverCharacteristics(nil, for: service)
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didDiscoverCharacteristicsFor service: CBService,
                           error: Error?) {
        for characteristic in service.characteristics ?? [] {
            characteristics[characteristic.uuid] = characteristic
        }

        state.connected = true
        onConnectionStateChanged?(true)

        // Subscribe to the temperature stream. Note that 0x1013/0x1014 advertise notify but expose
        // no CCCD, so subscribing to those fails - read them explicitly instead.
        // DE: Temperatur-Stream abonnieren. 0x1013/0x1014 melden zwar Notify, bieten aber keinen
        // CCCD - ein Abonnement schlaegt dort fehl, diese daher explizit lesen.
        if let temperature = characteristics[Self.charTemperatureNotify] {
            peripheral.setNotifyValue(true, for: temperature)
        }

        refreshState()
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didUpdateValueFor characteristic: CBCharacteristic,
                           error: Error?) {
        guard let value = characteristic.value else { return }
        apply(value: value, from: characteristic.uuid)
    }

    public func peripheral(_ peripheral: CBPeripheral,
                           didWriteValueFor characteristic: CBCharacteristic,
                           error: Error?) {
        if let error {
            log("Write to \(characteristic.uuid) failed: \(error.localizedDescription)")
        }
    }
}
