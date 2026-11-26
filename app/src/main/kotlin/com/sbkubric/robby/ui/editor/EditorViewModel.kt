package com.sbkubric.robby.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbkubric.robby.core.applet.ActionSpec
import com.sbkubric.robby.core.applet.TriggerSpec
import com.sbkubric.robby.core.scenario.Action
import com.sbkubric.robby.core.scenario.SaveScenario
import com.sbkubric.robby.core.scenario.ScenarioDraft
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.scenario.Trigger
import kotlinx.coroutines.launch

/** Holds the draft of one scenario while the «Когда / То» form edits it. */
class EditorViewModel(
    private val scenarios: ScenarioRepository,
    private val saveScenario: SaveScenario,
    scenarioId: String?,
) : ViewModel() {

    val isNew = scenarioId == null

    var draft by mutableStateOf(ScenarioDraft())
        private set
    var loaded by mutableStateOf(isNew)
        private set
    var problems by mutableStateOf(emptyList<ScenarioProblem>())
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

    fun pickTrigger(spec: TriggerSpec) = update { copy(trigger = Trigger(spec.id)) }

    fun setTriggerParam(key: String, value: String) = update {
        copy(trigger = trigger?.let { it.copy(params = it.params + (key to value)) })
    }

    fun addAction(spec: ActionSpec) = update { copy(actions = actions + Action(spec.id)) }

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

    fun delete(onDeleted: () -> Unit) {
        val id = draft.id ?: return
        viewModelScope.launch {
            scenarios.delete(id)
            onDeleted()
        }
    }

    private fun update(change: ScenarioDraft.() -> ScenarioDraft) {
        draft = draft.change()
    }
}
