package com.sbkubric.robby.core.template

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Variables every scenario can use, whatever its trigger. */
object CommonVariables {
    /** Time of the event, `dd.MM.yyyy HH:mm` in the phone's time zone. */
    const val NOW = "now"

    /** Name of the server phone. */
    const val DEVICE = "device"

    val ALL = listOf(NOW, DEVICE)

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Their values for an event at [time] on the phone named [device]. */
    fun values(time: ZonedDateTime, device: String): Map<String, String> = mapOf(NOW to TIME_FORMAT.format(time), DEVICE to device)
}
