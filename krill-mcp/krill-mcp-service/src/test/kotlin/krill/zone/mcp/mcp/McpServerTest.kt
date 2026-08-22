package krill.zone.mcp.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for argument validation on `tools/call`.
 *
 * Pre-fix (krill-oss#51), unknown arguments to MCP tools were silently dropped.
 * `list_nodes parent="<id>"` returned the full unfiltered tree instead of an
 * error, and a caller had no in-band way to tell the filter wasn't applied.
 * The fix validates `arguments` keys against `tool.inputSchema.properties`
 * before invoking `tool.execute`.
 */
class McpServerTest {

    @Test
    fun `unknown arguments are rejected with isError and a message naming the bad arg`() = runBlocking {
        val server = McpServer(
            serverName = "test",
            serverVersion = "0.0.0",
            tools = listOf(EchoTool()),
        )
        val response = server.handle(toolsCall("echo", buildJsonObject {
            put("text", "hi")
            put("bogus", "ignored-pre-fix")
        }))
        val result = (response as? JsonObject)?.get("result")?.jsonObject
            ?: error("Expected a result object, got: $response")
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Unknown argument" in text, "Message names the family: $text")
        assertTrue("bogus" in text, "Message names the offending key: $text")
        assertTrue("echo" in text, "Message names the tool: $text")
        assertTrue("Allowed:" in text && "text" in text, "Message lists what IS accepted: $text")
    }

    @Test
    fun `multiple unknown args are sorted and joined for predictable output`() = runBlocking {
        val server = McpServer("test", "0.0.0", tools = listOf(EchoTool()))
        val response = server.handle(toolsCall("echo", buildJsonObject {
            put("text", "hi")
            put("zeta", "x")
            put("alpha", "x")
        }))
        val result = (response as? JsonObject)?.get("result")?.jsonObject!!
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        // Sorted alphabetically.
        assertTrue("alpha, zeta" in text, "Unknown args should be alphabetical: $text")
    }

    @Test
    fun `known arguments only path executes the tool normally`() = runBlocking {
        val server = McpServer("test", "0.0.0", tools = listOf(EchoTool()))
        val response = server.handle(toolsCall("echo", buildJsonObject { put("text", "hello") }))
        val result = (response as? JsonObject)?.get("result")?.jsonObject!!
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("hello" in text, "Echo should pass the text through: $text")
    }

    @Test
    fun `a tool failure with a null exception message falls back to the exception class name`() = runBlocking {
        // krill-oss#238: the kraken demo pipeline's create_node call failed with the
        // undiagnosable text "ERROR: null" because some JVM exceptions (e.g.
        // java.nio.channels.UnresolvedAddressException on a DNS failure) have a null
        // `.message`. Reproduce with any exception carrying a null message.
        val server = McpServer("test", "0.0.0", tools = listOf(NullMessageFailingTool()))
        val response = server.handle(toolsCall("boom", buildJsonObject {}))
        val result = (response as? JsonObject)?.get("result")?.jsonObject!!
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("ERROR: IllegalStateException", text)
    }

    @Test
    fun `tool with no declared properties rejects any argument`() = runBlocking {
        val server = McpServer("test", "0.0.0", tools = listOf(NoArgTool()))
        val response = server.handle(toolsCall("noarg", buildJsonObject {
            put("anything", "at all")
        }))
        val result = (response as? JsonObject)?.get("result")?.jsonObject!!
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Allowed: <none>" in text, "No-prop tool should report empty allowed set: $text")
    }

    private fun toolsCall(name: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", name)
            put("arguments", arguments)
        }
    }

    private class EchoTool : Tool {
        override val name = "echo"
        override val description = "echo for tests"
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
            }
            putJsonArray("required") { add("text") }
        }
        override suspend fun execute(arguments: JsonObject): JsonElement = buildJsonObject {
            put("echoed", arguments["text"]!!.jsonPrimitive.content)
        }
    }

    private class NullMessageFailingTool : Tool {
        override val name = "boom"
        override val description = "always fails with a null-message exception, for tests"
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override suspend fun execute(arguments: JsonObject): JsonElement =
            throw IllegalStateException(null as String?)
    }

    private class NoArgTool : Tool {
        override val name = "noarg"
        override val description = "no-arg tool for tests"
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        }
        override suspend fun execute(arguments: JsonObject): JsonElement = buildJsonObject {}
    }
}
