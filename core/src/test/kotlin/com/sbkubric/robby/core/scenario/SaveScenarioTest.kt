package com.sbkubric.robby.core.scenario

import com.sbkubric.robby.core.FakeScenarioRepository
import com.sbkubric.robby.core.applets.BuiltInApplets
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SaveScenarioTest {

    private val repository = FakeScenarioRepository()
    private val save = SaveScenario(repository, ScenarioValidator(BuiltInApplets.catalog), newId = { "id-1" })

    private val draft = ScenarioDraft(
        name = " SMS от банка ",
        trigger = Trigger("sms.in", mapOf("sender" to "900")),
        actions = listOf(Action("telegram.send", mapOf("text" to "{sms.text}"))),
    )

    @Test
    fun `new valid scenario is stored under a new id`() = runBlocking {
        val result = save(draft)

        val expected = Scenario(
            id = "id-1",
            name = "SMS от банка",
            trigger = Trigger("sms.in", mapOf("sender" to "900")),
            actions = listOf(Action("telegram.send", mapOf("text" to "{sms.text}"))),
        )
        assertEquals(SaveScenario.Result.Saved(expected), result)
        assertEquals(listOf(expected), repository.all())
    }

    @Test
    fun `edited scenario keeps its id`() = runBlocking {
        save(draft.copy(id = "old", enabled = false))

        assertEquals(listOf("old" to false), repository.all().map { it.id to it.enabled })
    }

    @Test
    fun `invalid scenario is not stored`() = runBlocking {
        val result = save(draft.copy(actions = emptyList()))

        assertEquals(SaveScenario.Result.Invalid(listOf(ScenarioProblem.NoActions)), result)
        assertEquals(emptyList(), repository.all())
    }
}
