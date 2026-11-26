package com.sbkubric.robby.core.settings

import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.engine.DeliverRuns
import com.sbkubric.robby.core.log.RunReason
import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioProblem
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.scenario.ScenarioValidator

/** The export file of this phone's setup ([SettingsFormat]): every scenario and the server phone's name. */
class ExportSettings(
    private val scenarios: ScenarioRepository,
    private val phoneName: ServerPhoneNameSetting,
) {
    suspend operator fun invoke(): String = SettingsFormat.encode(SettingsExport(scenarios.all(), phoneName.customName()))
}

/**
 * Import of an export file, in two steps: [read] checks the file and says what it would do, the user confirms,
 * [apply] replaces everything.
 *
 * A scenario this build could not save as it is — most often one with an element of an applet it lacks
 * ([ScenarioProblem.UnknownElements]) — is imported switched off; the marker «апплет недоступен» is not stored but
 * computed from the catalog wherever it is shown ([ScenarioValidator]), and switching such a scenario on is refused
 * (`SwitchScenario`). Connector accounts are not in the file: every connector becomes «Не настроен».
 */
class ImportSettings(
    private val scenarios: ScenarioRepository,
    private val phoneName: ServerPhoneNameSetting,
    private val validator: ScenarioValidator,
    private val catalog: AppletCatalog,
    private val delivery: DeliverRuns,
    private val connectors: ConnectorSetup,
) {
    sealed interface Read {
        data class Ready(val plan: Plan) : Read

        /** Written by a newer Robby: update Robby first. */
        data object NewerVersion : Read

        /** Not an export file of Robby, or a damaged one. */
        data object NotSettings : Read
    }

    /** What [apply] will store, and what the user is told after it. */
    data class Plan(
        val scenarios: List<Scenario>,
        val serverPhoneName: String?,
        /** Scenarios with problems, switched off whatever the file said. */
        val disabled: List<DisabledScenario>,
        /** Names of the connectors to set up again, e.g. «Telegram». */
        val connectors: List<String>,
    )

    data class DisabledScenario(val name: String, val problems: List<ScenarioProblem>)

    fun read(text: String): Read = when (val decoded = SettingsFormat.decode(text)) {
        SettingsFormat.Decoded.NewerVersion -> Read.NewerVersion
        SettingsFormat.Decoded.NotSettings -> Read.NotSettings
        is SettingsFormat.Decoded.Read -> {
            val checked = decoded.settings.scenarios.map { it to validator.validate(it) }
            Read.Ready(
                Plan(
                    scenarios = checked.map { (scenario, problems) -> if (problems.isEmpty()) scenario else scenario.copy(enabled = false) },
                    serverPhoneName = decoded.settings.serverPhoneName,
                    disabled = checked.filter { it.second.isNotEmpty() }.map { (scenario, problems) -> DisabledScenario(scenario.name, problems) },
                    connectors = catalog.applets.filter { it.isConnector }.map { it.name },
                ),
            )
        }
    }

    /**
     * Replaces every scenario and the server phone's name with the [plan]'s, resets every connector to «Не настроен»,
     * and cancels the pending runs: they belong to the scenarios being replaced, even one with the same id.
     */
    suspend fun apply(plan: Plan) {
        delivery.cancelPending(RunReason.SETTINGS_IMPORTED)
        scenarios.replaceAll(plan.scenarios)
        phoneName.setCustomName(plan.serverPhoneName)
        catalog.applets.filter { it.isConnector }.forEach { connectors.reset(it.id) }
    }
}
