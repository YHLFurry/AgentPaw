package com.paw.agent.data.settings

/**
 * Which design system renders the whole app.
 *
 * `MATERIAL` keeps the original Material 3 look, `MIUIX` swaps every screen over
 * to the Miuix (HyperOS) component set. The choice is presentation-only: both
 * themes read the same [AppSettings] and drive the same ViewModels, so switching
 * never touches LLM configuration.
 */
enum class UiThemeMode(val storageKey: String) {
    MATERIAL("material"),
    MIUIX("miuix"),
    ;

    companion object {
        /** Existing installs must look exactly as they did before the Miuix option existed. */
        val Default: UiThemeMode = MATERIAL

        fun fromName(name: String?): UiThemeMode =
            entries.firstOrNull { it.storageKey == name } ?: Default
    }
}
