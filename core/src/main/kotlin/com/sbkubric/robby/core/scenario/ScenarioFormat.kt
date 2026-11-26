package com.sbkubric.robby.core.scenario

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one JSON format of a scenario: a flat document with `schemaVersion`,
 * used by the database and, as is, inside the export file (`SettingsFormat`).
 *
 * Versions: 1 — trigger and actions; 2 — adds `conditions` and `cooldownMinutes`.
 * An older document reads with the defaults of the fields it lacks (no conditions, no pause).
 */
object ScenarioFormat {
    const val SCHEMA_VERSION = 2
    private const val VERSION_KEY = "schemaVersion"

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** A document written by a newer Robby: this version cannot know what it lacks. */
    class NewerVersionException(version: Int) : IllegalArgumentException("Newer scenario schemaVersion: $version")

    fun encode(scenario: Scenario): String = toJson(scenario).toString()

    /**
     * @throws NewerVersionException if the document comes from a newer format;
     * @throws IllegalArgumentException if the text is not a scenario document this version can read.
     */
    fun decode(text: String): Scenario = fromJson(json.parseToJsonElement(text).jsonObject)

    fun toJson(scenario: Scenario): JsonObject {
        val fields = json.encodeToJsonElement(Scenario.serializer(), scenario).jsonObject
        return JsonObject(mapOf(VERSION_KEY to JsonPrimitive(SCHEMA_VERSION)) + fields)
    }

    /** @throws IllegalArgumentException as [decode]. */
    fun fromJson(document: JsonObject): Scenario {
        val version = document[VERSION_KEY]?.jsonPrimitive?.int
        require(version != null && version >= 1) { "Unsupported scenario schemaVersion: $version" }
        if (version > SCHEMA_VERSION) throw NewerVersionException(version)
        return json.decodeFromJsonElement(Scenario.serializer(), JsonObject(document - VERSION_KEY))
    }
}
