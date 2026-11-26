package com.sbkubric.robby.core.template

/** Variables every scenario can use, whatever its trigger. */
object CommonVariables {
    /** Time of the event, `dd.MM.yyyy HH:mm` in the phone's time zone. */
    const val NOW = "now"

    /** Name of the server phone. */
    const val DEVICE = "device"

    val ALL = listOf(NOW, DEVICE)
}
