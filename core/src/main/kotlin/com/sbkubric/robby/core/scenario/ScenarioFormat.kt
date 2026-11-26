package com.sbkubric.robby.core.scenario

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The one JSON format of a scenario: a flat document with `schemaVersion`,
 * used by the database now and by the export file later.
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

    fun encode(scenario: Scenario): String {
        val fields = json.encodeToJsonElement(Scenario.serializer(), scenario).jsonObject
        return JsonObject(mapOf(VERSION_KEY to JsonPrimitive(SCHEMA_VERSION)) + fields).toString()
    }

    /** @throws IllegalArgumentException if the text is not a scenario document this version can read. */
    fun decode(text: String): Scenario {
        val document = json.parseToJsonElement(text).jsonObject
        val version = document[VERSION_KEY]?.jsonPrimitive?.int
        require(version != null && version in 1..SCHEMA_VERSION) { "Unsupported scenario schemaVersion: $version" }
        return json.decodeFromJsonElement(Scenario.serializer(), JsonObject(document - VERSION_KEY))
    }
}
