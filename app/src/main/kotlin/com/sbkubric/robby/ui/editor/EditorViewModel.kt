package com.sbkubric.robby.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbkubric.robby.core.applet.ActionSpec
import com.sbkubric.robby.core.applet.ConditionSpec
import com.sbkubric.robby.core.applet.ParamSpec
import com.sbkubric.robby.core.applet.TriggerSpec
import com.sbkubric.robby.core.engine.TestScenario
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.Condition
import com.sbkubric.robby.core.scenario.SaveScenario
import com.sbkubric.robby.core.scenario.ScenarioDraft
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.launch

/** Holds the draft of one scenario while the «Когда / Если / То» form edits it. */
class EditorViewModel(
    private val scenarios: ScenarioRepository,
    private val saveScenario: SaveScenario,
    private val testScenario: TestScenario,
    /** Asks delivery to perform a recorded run now. */
    private val deliverNow: () -> Unit,
    scenarioId: String?,
) : ViewModel() {

    val isNew = scenarioId == null

    var draft by mutableStateOf(ScenarioDraft())
        private set
    var loaded by mutableStateOf(isNew)
        private set
    var problems by mutableStateOf(emptyList<ScenarioProblem>())
        private set

    /** «Тест» has recorded a run of the draft as it was then. */
    var tested by mutableStateOf(false)
        private set

    init {
        if (scenarioId != null) {
            viewModelScope.launch {
                scenarios.get(scenarioId)?.let { draft = ScenarioDraft.of(it) }
                loaded = true
            }
        }
    }

    fun setName(name: String) = update { copy(name = name) }

    fun pickTrigger(spec: TriggerSpec) = update { copy(trigger = Trigger(spec.id, spec.params.defaults())) }

    fun setTriggerParam(key: String, value: String) = update {
        copy(trigger = trigger?.let { it.copy(params = it.params + (key to value)) })
    }

    fun addCondition(spec: ConditionSpec) = update { copy(conditions = conditions + Condition(spec.id, spec.params.defaults())) }

    fun removeCondition(index: Int) = update { copy(conditions = conditions.filterIndexed { i, _ -> i != index }) }

    fun setConditionParam(index: Int, key: String, value: String) = update {
        copy(conditions = conditions.mapIndexed { i, c -> if (i == index) c.copy(params = c.params + (key to value)) else c })
    }

    fun setCooldown(minutes: Int) = update { copy(cooldownMinutes = minutes) }

    fun addAction(spec: ActionSpec) = update { copy(actions = actions + Action(spec.id, spec.params.defaults())) }

    fun removeAction(index: Int) = update { copy(actions = actions.filterIndexed { i, _ -> i != index }) }

    fun setActionParam(index: Int, key: String, value: String) = update {
        copy(actions = actions.mapIndexed { i, a -> if (i == index) a.copy(params = a.params + (key to value)) else a })
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = saveScenario(draft)) {
                is SaveScenario.Result.Saved -> onSaved()
                is SaveScenario.Result.Invalid -> problems = result.problems
            }
        }
    }

    /** «Тест»: one run of the draft as it is now, unsaved changes included, with sample values. */
    fun test() {
        viewModelScope.launch {
            when (val result = testScenario(draft)) {
                is TestScenario.Result.Recorded -> {
                    problems = emptyList()
                    tested = true
                    deliverNow()
                }
                is TestScenario.Result.Invalid -> {
                    problems = result.problems
                    tested = false
                }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = draft.id ?: return
        viewModelScope.launch {
            scenarios.delete(id)
            onDeleted()
        }
    }

    private fun List<ParamSpec>.defaults() = filter { it.default.isNotEmpty() }.associate { it.key to it.default }

    private fun update(change: ScenarioDraft.() -> ScenarioDraft) {
        draft = draft.change()
        tested = false
    }
}
