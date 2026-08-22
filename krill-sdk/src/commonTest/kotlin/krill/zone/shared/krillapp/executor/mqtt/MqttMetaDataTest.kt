package krill.zone.shared.krillapp.executor.mqtt

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for `MqttMetaData.name` (issue #234).
 *
 * Covers:
 *  - `displayName()` falls back to the empty string when `name` is unset,
 *    so `Node.name()` still falls through to the type string.
 *  - `displayName()` returns `name` once set, matching `DataPointMetaData`.
 *  - Old wire payloads missing `name` still deserialize, defaulting to `""`.
 *  - `MqttMetaData` round-trips through JSON with `name` set.
 */
class MqttMetaDataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `MqttMetaData defaults name to empty string`() {
        val meta = MqttMetaData()
        assertEquals("", meta.name)
        assertEquals("", meta.displayName())
    }

    @Test
    fun `MqttMetaData displayName returns name once set`() {
        val meta = MqttMetaData(name = "broker-1")
        assertEquals("broker-1", meta.displayName())
    }

    @Test
    fun `MqttMetaData back-compat round-trip ignores missing name`() {
        val oldPayload = """
            {
              "address": "tcp://broker:1883",
              "topic": "sensors/#",
              "action": "SUB",
              "error": "",
              "sources": [],
              "snapshot": {"timestamp": 0, "value": ""},
              "invocationTriggers": [],
              "nodeAction": "EXECUTE",
              "inputs": []
            }
        """.trimIndent()

        val meta = json.decodeFromString<MqttMetaData>(oldPayload)

        assertEquals("tcp://broker:1883", meta.address)
        assertEquals("", meta.name)
    }

    @Test
    fun `MqttMetaData round-trips with name set`() {
        val original = MqttMetaData(
            name = "external-broker",
            address = "tcp://broker:1883",
            topic = "sensors/#",
            action = MqttAction.SUB,
        )
        val encoded = json.encodeToString(MqttMetaData.serializer(), original)
        val decoded = json.decodeFromString<MqttMetaData>(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.name.isNotEmpty())
    }
}
