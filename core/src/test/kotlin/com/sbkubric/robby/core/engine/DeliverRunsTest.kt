package com.sbkubric.robby.core.engine

import com.sbkubric.robby.core.FakeActivityLog
import com.sbkubric.robby.core.FakeConnectorHealth
import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.MutableClock
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.log.Run
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.log.RunStatus
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Delivery: performing pending runs from the activity log, in order, with retries. Deciding is [HandleEventTest]. */
class DeliverRunsTest {

    // Thursday 2026-11-26, 14:02 in Moscow.
    private val eventTime = Instant.parse("2026-11-26T11:02:00Z")
    private val clock = MutableClock(eventTime)
    private val log = FakeActivityLog()
    private val repository = FakeScenarioRepository()
    private val health = FakeConnectorHealth()

    /** What reached Telegram and the phone's notifications, in order. */
    private val telegramSent = mutableListOf<String>()
    private val notified = mutableListOf<String>()
    private val keys = mutableListOf<String>()

    /** What Telegram answers next; done by default. */
    private var answer: suspend (String) -> ActionResult = { ActionResult.Done("Доставлено в «Дом»") }

    private val performers = mutableMapOf(
        "telegram.send" to ActionPerformer { params, key ->
            keys += key
            answer(params.getValue("text")).also { if (it is ActionResult.Done) telegramSent += params.getValue("text") }
        },
        "system.notify" to ActionPerformer { params, key ->
            keys += key
            notified += "${params["title"]}: ${params["text"]}"
            ActionResult.Done("Показано уведомление")
        },
    )

    private val engine = HandleEvent(
        scenarios = repository,
        catalog = BuiltInApplets.catalog,
        log = log,
        deviceName = { "Pixel дома" },
        battery = { null },
        call = { null },
        clock = clock,
    )

    private val deliver = DeliverRuns(log, repository, BuiltInApplets.catalog, performers, health, clock)

    private fun scenario(id: String, vararg actions: Action) = Scenario(
        id = id,
        name = "Сценарий $id",
        trigger = Trigger("sms.in"),
        actions = actions.toList(),
    )

    private fun telegram(text: String = "SMS: {sms.text}") = Action("telegram.send", mapOf("text" to text))
    private fun notify(title: String = "SMS", text: String = "{sms.text}") = Action("system.notify", mapOf("title" to title, "text" to text))

    private suspend fun given(vararg scenarios: Scenario) = scenarios.forEach { repository.save(it) }

    /** An SMS with [text] arrives now: the engine records the runs of the matching scenarios. */
    private suspend fun sms(text: String): List<Run> = engine(Event("sms.in", mapOf("sms.sender" to "900", "sms.text" to text), clock.now))

    private fun Run.now() = log.get(id)

    private fun passMinutes(minutes: Long) { clock.now = clock.now.plusSeconds(minutes * 60) }
    private fun passSeconds(seconds: Long) { clock.now = clock.now.plusSeconds(seconds) }

    @Test
    fun `pending run is performed from its snapshot and becomes done with the applets' notes`() = runBlocking {
        given(scenario("a", notify(), telegram()))
        val run = sms("Код 1234").single()

        deliver()

        assertEquals(listOf("SMS: Код 1234"), telegramSent)
        assertEquals(listOf("SMS: Код 1234"), notified)
        assertEquals(RunStatus.DONE to "Показано уведомление; Доставлено в «Дом»", run.now().let { it.status to it.message })
        assertEquals(emptyList(), log.pending())
    }

    @Test
    fun `runs are delivered in the order of their events`() = runBlocking {
        given(scenario("a", telegram()))
        sms("первое")
        passSeconds(1)
        sms("второе")

        deliver()

        assertEquals(listOf("SMS: первое", "SMS: второе"), telegramSent)
    }

    @Test
    fun `run is performed as the scenario was at the time of the event, whatever edits came later`() = runBlocking {
        given(scenario("a", telegram("Было: {sms.text}")))
        val run = sms("Код").single()
        repository.save(scenario("a", telegram("Стало: {sms.text}")).copy(name = "Новое имя"))

        deliver()

        assertEquals(listOf("Было: Код"), telegramSent)
        assertEquals("Сценарий a", run.now().scenarioName)
    }

    @Test
    fun `each action of each run has its own stable key`() = runBlocking {
        given(scenario("a", notify(), telegram()))
        val first = sms("1").single()
        val second = sms("2").single()

        deliver()

        assertEquals(listOf("${first.id}.0", "${first.id}.1", "${second.id}.0", "${second.id}.1"), keys)
    }

    @Test
    fun `action without a performer fails the run`() = runBlocking {
        performers.remove("telegram.send")
        given(scenario("a", telegram()))
        val run = sms("Код").single()

        deliver()

        assertEquals(Triple(RunStatus.FAILED, "telegram.send", RunReason.NO_PERFORMER), run.now().let { Triple(it.status, it.message, it.reason) })
    }

    @Test
    fun `performer that throws fails only its own run`() = runBlocking {
        answer = { text -> if (text == "SMS: boom") error("Сбой адаптера") else ActionResult.Done() }
        given(scenario("a", telegram()))
        val boom = sms("boom").single()
        val ok = sms("ok").single()

        deliver()

        assertEquals(RunStatus.FAILED to "Сбой адаптера", boom.now().let { it.status to it.message })
        assertEquals(RunStatus.DONE, ok.now().status)
    }

    // Temporary failures

    private val offline: suspend (String) -> ActionResult = { ActionResult.Retry("Нет связи с Telegram") }

    @Test
    fun `temporary failure keeps the run pending until its next attempt, and delivery says when to come back`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()))
        val run = sms("Код").single()

        val wake = deliver()

        val waiting = run.now()
        assertEquals(RunStatus.PENDING, waiting.status)
        assertEquals("Нет связи с Telegram", waiting.message)
        assertEquals(1, waiting.attempts)
        assertEquals(eventTime.plusSeconds(30), waiting.nextAttemptAt)
        assertEquals(eventTime.plusSeconds(30), wake)

        answer = { ActionResult.Done("Доставлено") }
        passSeconds(29)
        deliver()
        assertEquals(RunStatus.PENDING, run.now().status, "not before its time")

        passSeconds(1)
        assertEquals(null, deliver())
        assertEquals(RunStatus.DONE, run.now().status)
    }

    @Test
    fun `delay grows from 30 s and stops at 15 min`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()))
        val run = sms("Код").single()

        val delays = List(8) {
            deliver()
            val next = checkNotNull(run.now().nextAttemptAt)
            (java.time.Duration.between(clock.now, next).seconds).also { clock.now = next }
        }

        assertEquals(listOf(30L, 60, 120, 240, 480, 900, 900, 900), delays)
    }

    @Test
    fun `service's retry_after is honoured when it is longer than the delay`() = runBlocking {
        answer = { ActionResult.Retry("Слишком много сообщений", after = java.time.Duration.ofSeconds(45)) }
        given(scenario("a", telegram()))
        val run = sms("Код").single()

        deliver()

        assertEquals(eventTime.plusSeconds(45), run.now().nextAttemptAt)
    }

    @Test
    fun `network coming back retries waiting runs at once`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        deliver()

        answer = { ActionResult.Done() }
        passSeconds(5)
        deliver(networkReturned = true)

        assertEquals(RunStatus.DONE, run.now().status)
    }

    @Test
    fun `network coming back does not cut short the wait the service itself asked for`() = runBlocking {
        answer = { ActionResult.Retry("Слишком много сообщений", after = java.time.Duration.ofSeconds(45)) }
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        deliver()

        answer = { ActionResult.Done() }
        passSeconds(5)
        assertEquals(eventTime.plusSeconds(45), deliver(networkReturned = true), "still due when the service allows")
        assertEquals(RunStatus.PENDING, run.now().status)

        clock.now = eventTime.plusSeconds(45)
        deliver()
        assertEquals(RunStatus.DONE, run.now().status)
    }

    @Test
    fun `network coming back after the service's wait is over retries at once`() = runBlocking {
        answer = { ActionResult.Retry("Слишком много сообщений", after = java.time.Duration.ofSeconds(1)) }
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        deliver()

        answer = { ActionResult.Done() }
        passSeconds(5)
        deliver(networkReturned = true)

        assertEquals(RunStatus.DONE, run.now().status)
    }

    @Test
    fun `run gives up 24 h after its event and is not tried later than that`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        val deadline = eventTime.plus(java.time.Duration.ofHours(24))

        deliver()
        clock.now = deadline.minusSeconds(10)
        deliver()
        assertEquals(deadline, run.now().nextAttemptAt, "the last attempt is at the deadline, not after it")

        clock.now = deadline
        assertEquals(null, deliver())
        assertEquals(
            Triple(RunStatus.FAILED, "Нет связи с Telegram", RunReason.GAVE_UP),
            run.now().let { Triple(it.status, it.message, it.reason) },
        )
    }

    @Test
    fun `after a restart the runs left pending are delivered, one first tried past 24 h gets one attempt`() = runBlocking {
        given(scenario("a", telegram()))
        val old = sms("старое").single()
        passMinutes(60)
        val recent = sms("новое").single()

        // The process died before any delivery; the phone was off for a day.
        clock.now = eventTime.plus(java.time.Duration.ofHours(25))
        answer = { text -> if (text.endsWith("старое")) ActionResult.Retry("Нет сети") else ActionResult.Done() }
        DeliverRuns(log, repository, BuiltInApplets.catalog, performers, health, clock).invoke()

        assertEquals(RunStatus.FAILED to RunReason.GAVE_UP, old.now().let { it.status to it.reason })
        assertEquals(listOf("⏱ 15:02 SMS: новое"), telegramSent)
        assertEquals(RunStatus.DONE, recent.now().status)
    }

    @Test
    fun `permanent failure ends the run and the next one still goes`() = runBlocking {
        answer = { text -> if (text == "SMS: 1") ActionResult.Failed("Telegram: Bad Request") else ActionResult.Done() }
        given(scenario("a", telegram()))
        val first = sms("1").single()
        val second = sms("2").single()

        deliver()

        assertEquals(RunStatus.FAILED to "Telegram: Bad Request", first.now().let { it.status to it.message })
        assertEquals(RunStatus.DONE, second.now().status)
    }

    // Order: one queue per connector

    @Test
    fun `run waiting for Telegram holds back later Telegram runs, which then go in their original order`() = runBlocking {
        val tried = mutableListOf<String>()
        answer = { text -> tried += text; ActionResult.Retry("Нет сети") }
        given(scenario("a", telegram()))
        val first = sms("1").single()
        passSeconds(1)
        val second = sms("2").single()

        deliver()

        assertEquals(listOf("SMS: 1"), tried, "the second run is not tried while the first waits")
        assertEquals(0, second.now().attempts)

        answer = { ActionResult.Done() }
        passSeconds(30)
        deliver()

        assertEquals(listOf("SMS: 1", "SMS: 2"), telegramSent)
        assertEquals(listOf(RunStatus.DONE, RunStatus.DONE), listOf(first.now().status, second.now().status))
    }

    @Test
    fun `runs with only local actions are not held behind a waiting Telegram run`() = runBlocking {
        answer = offline
        given(scenario("tg", telegram()), scenario("local", notify()))

        val runs = sms("Код")
        deliver()

        assertEquals(listOf("SMS: Код"), notified)
        assertEquals(
            mapOf("tg" to RunStatus.PENDING, "local" to RunStatus.DONE),
            runs.associate { it.scenarioId to it.now().status },
        )
    }

    @Test
    fun `local runs are not held behind a Telegram request that is still going on`() = runBlocking {
        val reply = CompletableDeferred<ActionResult>()
        answer = { reply.await() }
        given(scenario("tg", telegram()), scenario("local", notify()))
        sms("Код")

        val pass = launch { deliver() }
        repeat(100) { if (notified.isEmpty()) yield() }

        val shown = notified.toList()
        reply.complete(ActionResult.Done())
        assertEquals(listOf("SMS: Код"), shown, "shown while Telegram has not answered yet")
        pass.join()
        assertEquals(listOf("SMS: Код"), telegramSent)
    }

    @Test
    fun `run with local and Telegram actions waits in the Telegram queue and does not repeat what it already did`() = runBlocking {
        answer = offline
        given(scenario("mixed", notify(), telegram()))
        val run = sms("Код").single()

        deliver()
        assertEquals(1, notified.size)
        assertEquals(RunStatus.PENDING, run.now().status)

        answer = { ActionResult.Done("Доставлено") }
        passSeconds(30)
        deliver()

        assertEquals(1, notified.size, "the notification is not shown twice")
        assertEquals(RunStatus.DONE to "Показано уведомление; Доставлено", run.now().let { it.status to it.message })
    }

    // Broken connector

    @Test
    fun `connector rejecting the credentials fails the run, becomes broken and fails the Telegram runs behind it at once`() = runBlocking {
        var calls = 0
        answer = { calls++; ActionResult.Broken("Telegram: Forbidden: bot was kicked from the group chat") }
        given(scenario("tg", telegram()), scenario("local", notify()))
        val first = sms("1")
        val second = sms("2")

        deliver()

        val broken = Triple(RunStatus.FAILED, "Telegram: Forbidden: bot was kicked from the group chat", RunReason.CONNECTOR_BROKEN)
        assertEquals(mapOf("telegram" to "Telegram: Forbidden: bot was kicked from the group chat"), health.broken)
        assertEquals(1, calls, "Telegram is not asked again once it is broken")
        assertEquals(listOf(broken, broken), (first + second).filter { it.scenarioId == "tg" }.map { it.now().let { r -> Triple(r.status, r.message, r.reason) } })
        assertEquals(listOf(RunStatus.DONE, RunStatus.DONE), (first + second).filter { it.scenarioId == "local" }.map { it.now().status })
    }

    @Test
    fun `new run needing a broken connector fails at once instead of waiting 24 h, and goes again once it is repaired`() = runBlocking {
        health.broken["telegram"] = "Telegram: Unauthorized"
        given(scenario("tg", telegram()))
        val run = sms("Код").single()

        assertEquals(null, deliver())

        assertEquals(Triple(RunStatus.FAILED, "Telegram: Unauthorized", RunReason.CONNECTOR_BROKEN), run.now().let { Triple(it.status, it.message, it.reason) })
        assertEquals(emptyList(), telegramSent)

        health.broken.clear()
        val next = sms("Снова").single()
        deliver()
        assertEquals(RunStatus.DONE, next.now().status)
    }

    // Cancellation

    @Test
    fun `switching a scenario off or deleting it cancels its waiting runs, even behind a waiting one`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()), scenario("b", telegram()), scenario("c", telegram()))
        val runs = sms("Код").associateBy { it.scenarioId }
        deliver()

        repository.save(repository.get("b")!!.copy(enabled = false))
        repository.delete("c")
        deliver()

        assertEquals(
            mapOf(
                "a" to (RunStatus.PENDING to null),
                "b" to (RunStatus.CANCELLED to RunReason.SCENARIO_DISABLED),
                "c" to (RunStatus.CANCELLED to RunReason.SCENARIO_DELETED),
            ),
            runs.mapValues { (_, run) -> run.now().let { it.status to it.reason } },
        )
        answer = { ActionResult.Done() }
        passMinutes(1)
        deliver()
        assertEquals(listOf("SMS: Код"), telegramSent, "cancelled runs are never sent")
    }

    // Late delivery: «⏱ HH:MM»

    @Test
    fun `message delivered more than 2 min after its event is marked with the event time`() = runBlocking {
        answer = offline
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        deliver()

        answer = { ActionResult.Done() }
        passMinutes(5)
        deliver(networkReturned = true)

        assertEquals(listOf("⏱ 14:02 SMS: Код"), telegramSent)
        assertEquals(true, run.now().late)
    }

    @Test
    fun `message delivered within 2 min is not marked`() = runBlocking {
        given(scenario("a", telegram()))
        val run = sms("Код").single()
        passSeconds(120)

        deliver()

        assertEquals(listOf("SMS: Код"), telegramSent)
        assertEquals(false, run.now().late)
    }

    @Test
    fun `late notification gets the mark in its text, not its title`() = runBlocking {
        given(scenario("a", notify(title = "SMS {sms.sender}")))
        sms("Код")
        passMinutes(3)

        deliver()

        assertEquals(listOf("SMS 900: ⏱ 14:02 Код"), notified)
    }
}
