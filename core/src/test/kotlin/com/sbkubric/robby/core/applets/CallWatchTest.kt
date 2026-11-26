package com.sbkubric.robby.core.applets

import com.sbkubric.robby.core.FakeCallWatchStore
import com.sbkubric.robby.core.applet.CallState
import com.sbkubric.robby.core.applet.CallState.IDLE
import com.sbkubric.robby.core.applet.CallState.OFFHOOK
import com.sbkubric.robby.core.applet.CallState.RINGING
import com.sbkubric.robby.core.engine.ContactNames
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CallWatchTest {

    private val start = Instant.parse("2026-11-26T11:02:00Z")
    private val store = FakeCallWatchStore()
    private val contacts = ContactNames { number -> mapOf("+79161234567" to "Мама")[number] }

    private fun watch() = CallWatch(store, contacts)

    /** A broadcast: the state, the number it carries (`null`: none) and its time in seconds after [start]. */
    private data class Reading(val state: CallState, val number: String? = null, val second: Long = 0)

    /**
     * Feeds the readings in order and returns what each one reported, as `trigger number contact` (`-` for an empty value).
     * [numberExpected]: Robby may read numbers, so a broadcast with the number follows the one without.
     */
    private fun CallWatch.feed(vararg readings: Reading, numberExpected: Boolean = true): List<List<String>> = runBlocking {
        readings.map { reading ->
            onReading(reading.state, reading.number, start.plusSeconds(reading.second), numberExpected).map { event ->
                assertEquals(start.plusSeconds(reading.second), event.time)
                listOf(event.triggerId, event.values.getValue(CallApplet.NUMBER), event.values.getValue(CallApplet.CONTACT))
                    .joinToString(" ") { it.ifEmpty { "-" } }
            }
        }
    }

    @Test
    fun `ringing that ends without an answer is reported as incoming and then as missed, with the number and the contact`() {
        assertEquals(
            listOf(listOf("call.incoming +79161234567 Мама"), listOf("call.missed +79161234567 Мама")),
            watch().feed(Reading(RINGING, "+79161234567"), Reading(IDLE, second = 20)),
        )
    }

    @Test
    fun `answered call is reported only as incoming`() {
        assertEquals(
            listOf(listOf("call.incoming +79161234567 Мама"), emptyList(), emptyList()),
            watch().feed(Reading(RINGING, "+79161234567"), Reading(OFFHOOK, second = 5), Reading(IDLE, second = 300)),
        )
    }

    @Test
    fun `when Robby may read numbers, incoming waits for the broadcast with the number and duplicates are ignored`() {
        assertEquals(
            listOf(emptyList(), listOf("call.incoming +79161234567 Мама"), listOf("call.missed +79161234567 Мама"), emptyList()),
            watch().feed(Reading(RINGING), Reading(RINGING, "+79161234567"), Reading(IDLE, second = 20), Reading(IDLE, second = 20)),
        )
    }

    @Test
    fun `when Robby may not read numbers, the broadcast without a number is reported at once`() {
        assertEquals(
            listOf(listOf("call.incoming - -"), listOf("call.missed - -")),
            watch().feed(Reading(RINGING), Reading(IDLE, second = 20), numberExpected = false),
        )
    }

    @Test
    fun `when the number never comes, incoming is reported without it as the ringing ends, answered or not`() {
        assertEquals(
            listOf(emptyList(), emptyList(), listOf("call.incoming - -", "call.missed - -"), emptyList()),
            watch().feed(Reading(RINGING), Reading(RINGING), Reading(IDLE, second = 20), Reading(IDLE, second = 20)),
        )
        assertEquals(
            listOf(emptyList(), listOf("call.incoming - -"), emptyList()),
            watch().feed(Reading(RINGING), Reading(OFFHOOK, second = 5), Reading(IDLE, second = 60)),
        )
    }

    @Test
    fun `after a restart the ringing remembered from before is still missed`() {
        watch().feed(Reading(RINGING), Reading(RINGING, "+79161234567"))

        // The process died while it rang; the system started it again for the end of the call.
        assertEquals(listOf(listOf("call.missed +79161234567 Мама")), watch().feed(Reading(IDLE, second = 4 * 60)))
    }

    @Test
    fun `ringing remembered for longer than the limit is stale, e g from before a reboot, and is forgotten`() {
        val limit = CallWatch.RINGING_LIMIT.seconds
        watch().feed(Reading(RINGING, "+79161234567"))
        assertEquals(listOf(emptyList<String>()), watch().feed(Reading(IDLE, second = limit + 1)))

        watch().feed(Reading(RINGING, "+79161234567"))
        assertEquals(
            listOf(listOf("call.incoming 900 -"), listOf("call.missed 900 -")),
            watch().feed(Reading(RINGING, "900", second = limit + 1), Reading(IDLE, second = limit + 30)),
        )
    }

    @Test
    fun `broadcast with the number may come first, the one without it is then a duplicate`() {
        assertEquals(
            listOf(listOf("call.incoming +79161234567 Мама"), emptyList(), listOf("call.missed +79161234567 Мама"), emptyList()),
            watch().feed(Reading(RINGING, "+79161234567"), Reading(RINGING), Reading(IDLE, second = 20), Reading(IDLE, second = 20)),
        )
    }

    @Test
    fun `withheld number comes as an empty number and is not waited for`() {
        assertEquals(
            listOf(emptyList(), listOf("call.incoming - -"), listOf("call.missed - -")),
            watch().feed(Reading(RINGING), Reading(RINGING, ""), Reading(IDLE, second = 20)),
        )
    }

    @Test
    fun `stale ringing is cleared from the store`() {
        watch().feed(Reading(RINGING, "+79161234567"))
        watch().feed(Reading(IDLE, second = CallWatch.RINGING_LIMIT.seconds + 1))

        assertEquals(null, store.ringing)
    }

    @Test
    fun `outgoing call reports nothing`() {
        assertEquals(listOf(emptyList<String>(), emptyList()), watch().feed(Reading(OFFHOOK), Reading(IDLE, second = 60)))
    }

    @Test
    fun `call waiting during a call is incoming, and missed only if the phone goes idle without taking it`() {
        // Answered or rejected while the first call goes on: Android tells neither apart, so it is not missed.
        assertEquals(
            listOf(emptyList(), listOf("call.incoming 900 -"), emptyList(), emptyList()),
            watch().feed(Reading(OFFHOOK), Reading(RINGING, "900", second = 30), Reading(OFFHOOK, second = 40), Reading(IDLE, second = 90)),
        )
        // The first call ended and the waiting one stopped ringing unanswered.
        assertEquals(
            listOf(emptyList(), listOf("call.incoming 900 -"), listOf("call.missed 900 -")),
            watch().feed(Reading(OFFHOOK), Reading(RINGING, "900", second = 30), Reading(IDLE, second = 50)),
        )
    }
}
