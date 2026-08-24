package krill.zone.mcp.mcp

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Minimal Streamable HTTP MCP dispatcher, JSON-RPC 2.0 over a single POST endpoint.
 *
 * Implements the subset required for Claude Desktop / Claude Code connector flow:
 *   - initialize             → server info + capabilities
 *   - notifications/initialized (no response, per JSON-RPC notification semantics)
 *   - ping                   → empty result
 *   - tools/list             → tool catalogue
 *   - tools/call             → dispatch to registered Tool
 *
 * Streaming tool progress (SSE) and resources/prompts are intentionally absent
 * for v1; the framework accepts `text/event-stream` in Accept headers but only
 * returns plain JSON-RPC responses today.
 */
class McpServer(
    private val serverName: String,
    private val serverVersion: String,
    private val protocolVersion: String = PROTOCOL_VERSION,
    tools: Collection<Tool>,
) {
    private val log = LoggerFactory.getLogger(McpServer::class.java)

    private val tools: Map<String, Tool> = tools.associateBy { it.name }

    /**
     * Handle a single JSON-RPC payload. Returns `null` for notifications (no response
     * should be sent on the wire). Returns a JSON-RPC response object otherwise.
     */
    suspend fun handle(request: JsonElement): JsonElement? {
        val obj = request as? JsonObject
            ?: return errorResponse(null, PARSE_ERROR, "Expected JSON object")
        val id = obj["id"]
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: return errorResponse(id, INVALID_REQUEST, "Missing method")
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())

        // Notifications carry no id; responses are forbidden.
        val isNotification = id == null

        return try {
            when (method) {
                "initialize" -> if (isNotification) null else successResponse(id, initializeResult())
                "notifications/initialized" -> null
                "ping" -> if (isNotification) null else successResponse(id, JsonObject(emptyMap()))
                "tools/list" -> if (isNotification) null else successResponse(id, toolsListResult())
                "tools/call" -> if (isNotification) null else successResponse(id, toolsCall(params))
                else -> if (isNotification) null
                else errorResponse(id, METHOD_NOT_FOUND, "Unknown method: $method")
            }
        } catch (e: Exception) {
            log.warn("Error handling '$method'", e)
            if (isNotification) null
            else errorResponse(id, INTERNAL_ERROR, e.message ?: "Internal error")
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", protocolVersion)
        putJsonObject("capabilities") {
            putJsonObject("tools") {
                put("listChanged", false)
            }
        }
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", serverVersion)
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            tools.values.forEach { t ->
                addJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                }
            }
        }
    }

    private suspend fun toolsCall(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Missing tool name")
        val tool = tools[name] ?: throw IllegalArgumentException("Unknown tool: $name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        // Reject arguments the tool's inputSchema doesn't declare. Pre-fix,
        // unknown args were silently dropped (krill-oss#51) — a caller passing
        // `parent: <id>` to list_nodes got the full unfiltered tree back, with
        // no signal that the arg was ignored. Validating against
        // inputSchema.properties means a typo or stale-skill call surfaces as
        // an isError instead of a wrong-but-plausible result.
        val knownProps = (tool.inputSchema["properties"] as? JsonObject)?.keys.orEmpty()
        val unknownArgs = (args.keys - knownProps).sorted()
        val result: Result<JsonElement> = if (unknownArgs.isNotEmpty()) {
            Result.failure(
                IllegalArgumentException(
                    "Unknown argument(s) for $name: ${unknownArgs.joinToString(", ")}. " +
                        "Allowed: ${knownProps.sorted().joinToString(", ").ifEmpty { "<none>" }}.",
                ),
            )
        } else {
            runCatching { tool.execute(args) }
        }
        val text = result.fold(
            onSuccess = { it.toStringCompact() },
            onFailure = {
                log.warn("Tool '$name' failed", it)
                // Some JVM/platform exceptions (e.g. java.nio.channels.UnresolvedAddressException on a
                // DNS failure) carry a null `.message` — surfacing that verbatim produced the
                // undiagnosable "ERROR: null" the kraken demo pipeline hit on krill-oss#238. Fall back to
                // the exception's class name so callers always get *something* to act on or report.
                "ERROR: ${it.message ?: it::class.simpleName ?: "unknown error"}"
            },
        )
        return buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("isError", result.isFailure)
        }
    }

    private fun successResponse(id: JsonElement?, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id) else put("id", JsonNull)
        put("result", result)
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id) else put("id", JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun JsonElement.toStringCompact(): String =
        if (this is JsonPrimitive && isString) content else toString()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
