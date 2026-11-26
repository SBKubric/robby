package com.sbkubric.robby.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.settings.ExportSettings
import com.sbkubric.robby.core.settings.ImportSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export to and import from a file the user picks with the system's file picker; what the dialogs show. */
class SettingsTransferViewModel(
    private val resolver: ContentResolver,
    private val exportSettings: ExportSettings,
    private val importSettings: ImportSettings,
    private val scenarios: ScenarioRepository,
    /** The import itself outlives the screen: it must not stop half way. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Exported : State
        data class ExportFailed(val reason: String) : State
        data class ReadFailed(val reason: String) : State
        data object NewerVersion : State
        data object NotSettings : State

        /** The file is read: asks before replacing [current] scenarios. */
        data class Confirm(val plan: ImportSettings.Plan, val current: Int) : State
        data class Imported(val plan: ImportSettings.Plan) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    fun dismiss() {
        state = State.Idle
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val text = exportSettings()
            state = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(resolver.openOutputStream(uri, "wt")) { uri.toString() }.use { it.write(text.toByteArray()) }
                }
                State.Exported
            } catch (e: Exception) {
                State.ExportFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun read(uri: Uri) {
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    checkNotNull(resolver.openInputStream(uri)) { uri.toString() }.use { it.readBytes().decodeToString() }
                }
            } catch (e: Exception) {
                state = State.ReadFailed(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            state = when (val read = importSettings.read(text)) {
                is ImportSettings.Read.Ready -> State.Confirm(read.plan, scenarios.all().size)
                ImportSettings.Read.NewerVersion -> State.NewerVersion
                ImportSettings.Read.NotSettings -> State.NotSettings
            }
        }
    }

    fun confirm(plan: ImportSettings.Plan) {
        state = State.Idle
        appScope.launch {
            importSettings.apply(plan)
            withContext(Dispatchers.Main) { state = State.Imported(plan) }
        }
    }
}
