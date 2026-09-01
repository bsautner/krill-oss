package krill.zone.mcp.mcp.tools

import krill.zone.mcp.auth.PinProvider
import krill.zone.mcp.config.KrillMcpConfig
import krill.zone.mcp.krill.KrillNodeTypes
import krill.zone.mcp.krill.KrillRegistry
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for:
 *   - krill-oss#13: `create_node` must inject a parent-derived display name when
 *     creating a `KrillApp.DataPoint.Graph` so sibling Graphs don't share a name.
 *   - krill-oss#163: `create_node` with the server id as parent (or no parent)
 *     must synthesize a server-typed stub rather than calling `GET /node/{id}`,
 *     which fails because the server entity is not addressable at that endpoint.
 *   - krill-oss#165: Lambda `meta.type` discriminator must be `LambdaMetaData`
 *     (the serial name the krill server registers), not `LambdaSourceMetaData`
 *     (the stale SDK class name from 0.0.48).
 *   - krill-oss#168: `create_node` must accept a node display name for `parent`,
 *     not just a UUID — demo pipeline passes names like 'B' not UUIDs.
 */
class CreateNodeToolTest {

    private val tool = CreateNodeTool(
        registry = KrillRegistry(
            config = KrillMcpConfig(),
            pin = PinProvider(path = "/dev/null"),
        ),
    )
    private val graphSpec = KrillNodeTypes.resolve("KrillApp.DataPoint.Graph")
        ?: error("Graph spec missing from registry")
    private val dataPointSpec = KrillNodeTypes.resolve("KrillApp.DataPoint")
        ?: error("DataPoint spec missing from registry")

    @Test
    fun `derives parent-aware default for Graph from parent DataPoint name`() {
        val parent = parentNode(typeFqn = "krill.zone.shared.KrillApp.DataPoint", name = "pH")
        assertEquals("pH graph", tool.derivedDefaultName(graphSpec, parent))
    }

    @Test
    fun `returns null for Graph when parent has no usable name`() {
        val parent = parentNode(typeFqn = "krill.zone.shared.KrillApp.DataPoint", name = "")
        assertNull(tool.derivedDefaultName(graphSpec, parent))
    }

    @Test
    fun `returns null for non-Graph specs so other types keep their registry default`() {
        val parent = parentNode(typeFqn = "krill.zone.shared.KrillApp.Server", name = "pi-krill-05")
        assertNull(tool.derivedDefaultName(dataPointSpec, parent))
    }

    @Test
    fun `Graph registry default name is empty so create_node always sources it from the parent or caller`() {
        val defaultName = graphSpec.defaultMeta["name"]?.let { (it as JsonPrimitive).content }
        assertEquals("", defaultName)
    }

    // ── krill-oss#163 — server-parent guard ──────────────────────────────────

    @Test
    fun `serverParentNode carries the canonical KrillApp Server FQN`() {
        val node = tool.serverParentNode("test-server-uuid")
        val fqn = node["type"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
        assertEquals("krill.zone.shared.KrillApp.Server", fqn)
    }

    @Test
    fun `serverParentNode embeds the supplied server id`() {
        val serverId = "deadbeef-0000-0000-0000-000000000001"
        val node = tool.serverParentNode(serverId)
        assertEquals(serverId, node["id"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `serverParentNode has a meta object so derivedDefaultName does not throw`() {
        val node = tool.serverParentNode("any-id")
        // derivedDefaultName must not NPE on a synthesized server parent
        assertNull(tool.derivedDefaultName(graphSpec, node))
    }

    // ── krill-oss#165 — Lambda meta serial-name guard ────────────────────────

    @Test
    fun `Lambda metaFqn is LambdaMetaData not LambdaSourceMetaData`() {
        val lambda = KrillNodeTypes.resolve("KrillApp.Executor.Lambda")
            ?: error("Lambda spec missing from registry")
        assertEquals(
            "krill.zone.shared.krillapp.executor.lambda.LambdaMetaData",
            lambda.metaFqn,
        )
    }

    @Test
    fun `Lambda defaultMeta type discriminator is LambdaMetaData`() {
        val lambda = KrillNodeTypes.resolve("KrillApp.Executor.Lambda")
            ?: error("Lambda spec missing from registry")
        val typeInMeta = lambda.defaultMeta["type"]?.jsonPrimitive?.contentOrNull
        assertEquals(
            "krill.zone.shared.krillapp.executor.lambda.LambdaMetaData",
            typeInMeta,
            "create_node posts this value as meta.type — must match the serial name the krill server registers",
        )
    }

    // ── krill-oss#172 — KrillApp.Trigger.Timer catalog guard ────────────────

    @Test
    fun `Timer type is resolvable by short name`() {
        val timer = KrillNodeTypes.resolve("KrillApp.Trigger.Timer")
        assertTrue(timer != null, "KrillApp.Trigger.Timer missing from registry")
    }

    @Test
    fun `Timer metaFqn points to TimerMetaData`() {
        val timer = KrillNodeTypes.resolve("KrillApp.Trigger.Timer")
            ?: error("KrillApp.Trigger.Timer missing from registry")
        assertEquals(
            "krill.zone.shared.krillapp.trigger.timer.TimerMetaData",
            timer.metaFqn,
        )
    }

    @Test
    fun `Timer defaultMeta type discriminator is TimerMetaData`() {
        val timer = KrillNodeTypes.resolve("KrillApp.Trigger.Timer")
            ?: error("KrillApp.Trigger.Timer missing from registry")
        assertEquals(
            "krill.zone.shared.krillapp.trigger.timer.TimerMetaData",
            timer.defaultMeta["type"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Test
    fun `Timer defaultMeta includes delay field`() {
        val timer = KrillNodeTypes.resolve("KrillApp.Trigger.Timer")
            ?: error("KrillApp.Trigger.Timer missing from registry")
        assertTrue(
            timer.defaultMeta.containsKey("delay"),
            "Timer defaultMeta must include delay field",
        )
    }

    // ── krill-oss#236 — leaf-name fallback resolution ────────────────────────

    @Test
    fun `resolve accepts a selector that drops the category segment when the leaf is unique`() {
        val cron = KrillNodeTypes.resolve("KrillApp.CronTimer")
        assertEquals(
            "KrillApp.Trigger.CronTimer",
            cron?.shortName,
            "KrillApp.CronTimer must fall back to the unique KrillApp.Trigger.CronTimer leaf match",
        )
    }

    @Test
    fun `resolve accepts a bare leaf name when it is unique`() {
        val cron = KrillNodeTypes.resolve("CronTimer")
        assertEquals("KrillApp.Trigger.CronTimer", cron?.shortName)
    }

    @Test
    fun `resolve still returns the exact match when the full short name is given`() {
        val cron = KrillNodeTypes.resolve("KrillApp.Trigger.CronTimer")
        assertEquals("KrillApp.Trigger.CronTimer", cron?.shortName)
    }

    @Test
    fun `resolve returns null for a selector with no matching leaf`() {
        assertNull(KrillNodeTypes.resolve("KrillApp.NotARealType"))
    }

    // ── krill-oss#168 — name-based parent resolution ─────────────────────────

    @Test
    fun `isUuid returns true for a well-formed UUID`() {
        assertTrue(tool.isUuid("3fb3f849-21a7-4eb3-8622-e4b351f18706"))
    }

    @Test
    fun `isUuid returns false for a plain name`() {
        assertFalse(tool.isUuid("B"))
    }

    @Test
    fun `isUuid returns false for a short label`() {
        assertFalse(tool.isUuid("XOR"))
    }

    @Test
    fun `resolveNodeByName finds node by exact name (case-insensitive)`() {
        val nodes = JsonArray(
            listOf(
                nodeElement(id = "uuid-alpha", name = "Alpha"),
                nodeElement(id = "uuid-b", name = "B"),
                nodeElement(id = "uuid-gamma", name = "Gamma"),
            ),
        )
        val result = tool.resolveNodeByName(nodes, "b")
        assertEquals("uuid-b", result?.get("id")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `resolveNodeByName returns null when name not found`() {
        val nodes = JsonArray(listOf(nodeElement(id = "uuid-alpha", name = "Alpha")))
        assertNull(tool.resolveNodeByName(nodes, "B"))
    }

    @Test
    fun `resolveNodeByName returns null for empty node list`() {
        assertNull(tool.resolveNodeByName(JsonArray(emptyList()), "B"))
    }

    @Test
    fun `resolveNodeByName returns first match when multiple nodes share a name`() {
        val nodes = JsonArray(
            listOf(
                nodeElement(id = "uuid-first", name = "B"),
                nodeElement(id = "uuid-second", name = "B"),
            ),
        )
        val result = tool.resolveNodeByName(nodes, "B")
        assertEquals("uuid-first", result?.get("id")?.jsonPrimitive?.contentOrNull)
    }

    private fun nodeElement(id: String, name: String): JsonObject = buildJsonObject {
        put("id", id)
        putJsonObject("type") { put("type", "krill.zone.shared.KrillApp.DataPoint") }
        putJsonObject("meta") { put("name", name) }
    }

    private fun parentNode(typeFqn: String, name: String) = buildJsonObject {
        put(
            "type",
            buildJsonObject { put("type", JsonPrimitive(typeFqn)) },
        )
        put(
            "meta",
            buildJsonObject { put("name", JsonPrimitive(name)) },
        )
    }
}
