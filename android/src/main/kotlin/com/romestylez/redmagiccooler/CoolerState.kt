package com.romestylez.redmagiccooler

/**
 * Snapshot of everything currently known about a connected REDMAGIC VC Cooler 6 Pro.
 *
 * Fields are null until the corresponding characteristic has been read at least once.
 *
 * DE: Momentaufnahme aller aktuell bekannten Werte eines verbundenen REDMAGIC VC Cooler 6 Pro.
 * Felder sind null, solange die zugehoerige Characteristic noch nicht mindestens einmal
 * gelesen wurde.
 */
data class CoolerState(
    /** Whether a cooler is currently connected. / DE: Ob aktuell ein Kuehler verbunden ist. */
    val connected: Boolean = false,

    /** Cooling switch state. / DE: Zustand des Kuehlschalters. */
    val coolingEnabled: Boolean? = null,

    /**
     * Raw fan level byte (40..80). Use [fanStep] for Goper's 1..9 numbering.
     *
     * DE: Roher Kuehlstufen-Bytewert (40..80). Fuer die Goper-Nummerierung 1..9 siehe [fanStep].
     */
    val fanLevel: Int? = null,

    /** Diablo / "destruction god" mode. / DE: Zerstoerungsgott-/Diablo-Modus. */
    val diabloMode: Boolean? = null,

    /** Device-side automatic fan control. / DE: Geraeteinterne automatische Temperaturregelung. */
    val autoTemperatureControl: Boolean? = null,

    /** Temperature in degrees Celsius. / DE: Temperatur in Grad Celsius. */
    val temperatureCelsius: Int? = null,

    /** LED ring state. / DE: Zustand des LED-Rings. */
    val ledOn: Boolean? = null,
) {
    /**
     * The fan level expressed as Goper's step number (1..9), or null if the raw value is not one
     * of the nine values Goper uses.
     *
     * DE: Die Kuehlstufe als Goper-Stufennummer (1..9), oder null, wenn der Rohwert keiner der
     * neun von Goper verwendeten Werte ist.
     */
    val fanStep: Int?
        get() {
            val level = fanLevel ?: return null
            val index = RedMagicCooler6Pro.FAN_LEVELS.indexOfFirst { (it.toInt() and 0xFF) == level }
            return if (index >= 0) index + 1 else null
        }
}
