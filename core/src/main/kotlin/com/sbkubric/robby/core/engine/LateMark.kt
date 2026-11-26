package com.sbkubric.robby.core.engine

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «⏱ HH:MM»: a message performed well after its event (it waited for the network, or for Robby to start again)
 * says when the event happened, so that the reader does not take it for news.
 */
object LateMark {
    /** Up to this long after the event a message counts as on time. */
    val THRESHOLD: Duration = Duration.ofMinutes(2)

    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun isLate(eventTime: Instant, performedAt: Instant): Boolean = Duration.between(eventTime, performedAt) > THRESHOLD

    /** `⏱ 14:02 SMS от 900: …` — the event's time of day in [zone] before the [text]. */
    fun mark(text: String, eventTime: Instant, zone: ZoneId): String = "⏱ ${TIME.format(eventTime.atZone(zone))} $text"
}
