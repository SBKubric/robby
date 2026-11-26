package com.sbkubric.robby.core.settings

import com.sbkubric.robby.core.scenario.Scenario
import com.sbkubric.robby.core.scenario.ScenarioFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an export file carries: the user's own setup of the server phone, and nothing secret.
 * Connector accounts (the Telegram token, bot and group) stay out: after an import each connector is set up again.
 */
data class SettingsExport(
    val scenarios: List<Scenario>,
    /** The name typed in Settings for `{device}`; `null` if the phone's own name was used. */
    val serverPhoneName: String?,
)

/**
 * The export file: `{"format":"robby-settings","schemaVersion":1,"serverPhoneName":…,"scenarios":[…]}`,
 * each scenario as its own [ScenarioFormat] document with its own `schemaVersion`.
 * Unknown fields are ignored, so a later version may add some without a new `schemaVersion`.
 */
object SettingsFormat {
    const val SCHEMA_VERSION = 1
    private const val FORMAT = "robby-settings"

    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "schemaVersion"
    private const val NAME_KEY = "serverPhoneName"
    private const val SCENARIOS_KEY = "scenarios"

    sealed interface Decoded {
        data class Read(val settings: SettingsExport) : Decoded

        /** Written by a newer Robby (the file or a scenario in it): update Robby first. */
        data object NewerVersion : Decoded

        /** Not an export file of Robby, or a damaged one. */
        data object NotSettings : Decoded
    }

    fun encode(settings: SettingsExport): String = JsonObject(
        mapOf(
            FORMAT_KEY to JsonPrimitive(FORMAT),
            VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
            NAME_KEY to (settings.serverPhoneName?.let(::JsonPrimitive) ?: JsonNull),
            SCENARIOS_KEY to JsonArray(settings.scenarios.map(ScenarioFormat::toJson)),
        ),
    ).toString()

    fun decode(text: String): Decoded = try {
        val document = Json.parseToJsonElement(text).jsonObject
        val version = document[VERSION_KEY]?.jsonPrimitive?.int
        when {
            document[FORMAT_KEY]?.jsonPrimitive?.contentOrNull != FORMAT || version == null || version < 1 -> Decoded.NotSettings
            version > SCHEMA_VERSION -> Decoded.NewerVersion
            else -> {
                val scenarios = document[SCENARIOS_KEY]?.jsonArray.orEmpty().map { ScenarioFormat.fromJson(it.jsonObject) }
                val name = document[NAME_KEY]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val unique = scenarios.map { it.id }.toSet().size == scenarios.size
                if (unique) Decoded.Read(SettingsExport(scenarios, name)) else Decoded.NotSettings
            }
        }
    } catch (e: ScenarioFormat.NewerVersionException) {
        Decoded.NewerVersion
    } catch (e: IllegalArgumentException) {
        Decoded.NotSettings
    }
}
