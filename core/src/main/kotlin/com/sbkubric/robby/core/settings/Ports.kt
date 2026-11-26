package com.sbkubric.robby.core.settings

/** Port: the server phone's name as typed in Settings for `{device}`. */
interface ServerPhoneNameSetting {
    /** `null` while the phone's own name is used. */
    fun customName(): String?

    /** `null` goes back to the phone's own name. */
    fun setCustomName(name: String?)
}

/** Port: a connector's account and setup (e.g. the Telegram bot and group). */
fun interface ConnectorSetup {
    /** Forgets the setup, the token too: the connector (an applet id, e.g. `telegram`) becomes «Не настроен». */
    suspend fun reset(connectorId: String)
}
