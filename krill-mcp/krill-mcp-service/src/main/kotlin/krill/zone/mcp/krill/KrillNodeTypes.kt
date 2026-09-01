package krill.zone.mcp.krill

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import krill.zone.shared.krillapp.datapoint.DataPointMetaData
import krill.zone.shared.krillapp.datapoint.filter.FilterMetaData
import krill.zone.shared.krillapp.datapoint.graph.GraphMetaData
import krill.zone.shared.krillapp.executor.ExecuteMetaData
import krill.zone.shared.krillapp.executor.calculation.CalculationEngineNodeMetaData
import krill.zone.shared.krillapp.executor.compute.ComputeMetaData
import krill.zone.shared.krillapp.executor.lambda.LambdaMetaData
import krill.zone.shared.krillapp.executor.logicgate.LogicGateMetaData
import krill.zone.shared.krillapp.executor.mqtt.MqttMetaData
import krill.zone.shared.krillapp.executor.smtp.SMTPMetaData
import krill.zone.shared.krillapp.executor.webhook.WebHookOutMetaData
import krill.zone.shared.krillapp.project.ProjectMetaData
import krill.zone.shared.krillapp.project.camera.CameraMetaData
import krill.zone.shared.krillapp.project.diagram.DiagramMetaData
import krill.zone.shared.krillapp.project.journal.JournalMetaData
import krill.zone.shared.krillapp.project.tasklist.TaskListMetaData
import krill.zone.shared.krillapp.server.backup.BackupMetaData
import krill.zone.shared.krillapp.server.llm.LLMMetaData
import krill.zone.shared.krillapp.server.pin.PinMetaData
import krill.zone.shared.krillapp.server.serialdevice.SerialDeviceMetaData
import krill.zone.shared.krillapp.swarm.SwarmBatchMetaData
import krill.zone.shared.krillapp.swarm.SwarmWorkMetaData
import krill.zone.shared.krillapp.trigger.TriggerMetaData
import krill.zone.shared.krillapp.trigger.button.ButtonMetaData
import krill.zone.shared.krillapp.trigger.color.ColorTriggerMetaData
import krill.zone.shared.krillapp.trigger.cron.CronMetaData
import krill.zone.shared.krillapp.trigger.timer.TimerMetaData
import krill.zone.shared.krillapp.trigger.webhook.IncomingWebHookMetaData

/**
 * Static registry of every createable KrillApp node type.
 *
 * Each entry carries the two polymorphic discriminator strings the Krill server
 * expects on the wire, a default `meta` skeleton, and parent/child hints lifted
 * from the real `krill` repo's `KrillApp.kt` + `Serializer.kt`.
 *
 * Mapping rules:
 *   - `typeFqn`  — fully qualified Kotlin class name of the `KrillApp.*` data
 *     object, which is what kotlinx.serialization emits as the `type.type`
 *     discriminator (no `@SerialName` overrides exist).
 *   - `metaFqn`  — FQN of the MetaData class used for that type. Several
 *     KrillApp types share a MetaData class (all Triggers → TriggerMetaData;
 *     all Filters → FilterMetaData).
 *   - `defaultMeta` — serialized **from the krill-sdk MetaData data class
 *     itself** (`encodeDefaults = true`), so the skeleton can never drift from
 *     the wire schema the server compiles against. Display-name overlays are
 *     the only hand-set values. When the SDK schema changes, bumping the
 *     `krill-sdk` pin in `gradle/libs.versions.toml` updates every skeleton.
 *
 * ## The wiring model (unify-source-verb-wiring)
 *
 * Nodes are independent observers — there is no parent-executes-children push
 * path. Three meta fields drive all data/activity flow:
 *
 *   - `sources`            — nodes this node OBSERVES. When a source completes
 *                            its work, this node is invoked (pull model).
 *   - `invocationTriggers` — which events invoke this node:
 *                            `SOURCE_INVOKED` (a source completed) or
 *                            `ON_CLICK` (manual tap). Empty = never auto-fires.
 *   - `inputs`             — nodes whose last result (`meta.snapshot`) this
 *                            node READS at execution time. Inputs never wake a
 *                            node; they are the values it consumes when woken.
 *
 * Every node stores its own last result in `meta.snapshot`; downstream
 * observers pull it from there. The `nodeAction` verb (EXECUTE | RESET)
 * cascades source → observer, so one RESET upstream stands down a whole chain.
 *
 * `validParentTypes` / `validChildTypes` describe how swarms are conventionally
 * ORGANIZED in the UI tree — parent/child is visual grouping only and carries
 * no execution semantics. On creation the server wires the new node's parent
 * into `sources` + `SOURCE_INVOKED` when `sources` is empty (and the node or
 * parent isn't a Project/Server container), so a child dropped under a parent
 * observes it by default. Rewire freely afterwards with `set_node_wiring`.
 *
 * When the upstream `krill` repo adds a new KrillApp subtype, add an entry
 * here and bump the mcp/skill version.
 */
data class KrillNodeType(
    val shortName: String,
    val typeFqn: String,
    val metaFqn: String,
    val defaultMeta: JsonObject,
    val role: String,
    val sideEffect: String,
    val description: String,
    val validParentTypes: List<String>,
    val validChildTypes: List<String>,
    val notes: String? = null,
    /**
     * Field-shape disambiguation for meta fields whose JSON type alone doesn't
     * convey enough — empty arrays (`sources: []`) don't show their element
     * shape, enum fields look like bare strings. Keyed by meta field name.
     * Only populated for fields where the default doesn't already make the
     * shape obvious.
     */
    val metaFieldHints: Map<String, String> = emptyMap(),
)

private val NODE_IDENTITY_HINT =
    "List<{nodeId: String, hostId: String}> — NodeIdentity. hostId is the server UUID that owns the referenced node."

private val SOURCES_HINT =
    NODE_IDENTITY_HINT + " Nodes this node OBSERVES — when a source completes its work this node is invoked " +
        "(requires SOURCE_INVOKED in `invocationTriggers`). Wiring lives on the observer, not the observed."

private val INPUTS_HINT =
    NODE_IDENTITY_HINT + " Nodes whose last result (`meta.snapshot`) this node READS when it executes. " +
        "Inputs never wake a node — wake-up is `sources` + `invocationTriggers`."

private val INVOCATION_TRIGGERS_HINT =
    "List<enum: SOURCE_INVOKED | ON_CLICK> — events that invoke this node. SOURCE_INVOKED: a node listed in " +
        "`sources` completed its work. ON_CLICK: manual user tap in the UI. Empty list = never auto-fires."

private val NODE_ACTION_HINT =
    "enum: EXECUTE | RESET — the verb this node sends downstream when it fires. EXECUTE runs the observer's " +
        "primary logic; RESET reverts observers to initial/cleared state (TaskList: reopens all tasks; Trigger: " +
        "clears alarm WARN→NONE). The verb cascades — receivers apply the source's verb, so one RESET upstream " +
        "stands down the whole chain. Use `set_node_wiring` (or `set_node_action`) to update an existing node."

private val SNAPSHOT_HINT =
    "{timestamp: Long (epoch ms), value: String} — this node's own last result. Observers read it via `inputs`."

object KrillNodeTypes {

    /** `meta.type` polymorphic discriminator key — kotlinx.serialization default. */
    const val META_TYPE_KEY = "type"

    /**
     * Encoder for the SDK-derived skeletons. `encodeDefaults = true` so every
     * wire field appears in the skeleton agents see via `list_node_types`.
     */
    private val skeletonJson = Json { encodeDefaults = true }

    /**
     * Serialize an SDK MetaData instance into the registry's `defaultMeta`
     * shape: discriminator first, then the class's own defaulted fields, then
     * any display-default overlays (e.g. a stable `name`).
     */
    private fun <T> sdkMeta(
        metaFqn: String,
        serializer: KSerializer<T>,
        defaults: T,
        vararg overlay: Pair<String, JsonElement>,
    ): JsonObject {
        val encoded = skeletonJson.encodeToJsonElement(serializer, defaults).jsonObject
        return buildJsonObject {
            put(META_TYPE_KEY, metaFqn)
            encoded.forEach { (k, v) -> if (k != META_TYPE_KEY) put(k, v) }
            overlay.forEach { (k, v) -> put(k, v) }
        }
    }

    /** Deterministic zero snapshot — replaces SDK defaults that bake in `Clock.now()`. */
    private fun zeroSnapshot(value: String = ""): JsonObject = buildJsonObject {
        put("timestamp", 0L)
        put("value", value)
    }

    private val TYPE_TABLE: List<KrillNodeType> = listOf(
        // ── Project container tree ─────────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.Project",
            typeFqn = "krill.zone.shared.KrillApp.Project",
            metaFqn = "krill.zone.shared.krillapp.project.ProjectMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.project.ProjectMetaData",
                ProjectMetaData.serializer(),
                ProjectMetaData(),
            ),
            role = "container",
            sideEffect = "none",
            description = "Organizational container for Diagrams, TaskLists, Journals, Cameras.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = listOf(
                "KrillApp.Project.Diagram",
                "KrillApp.Project.TaskList",
                "KrillApp.Project.Journal",
                "KrillApp.Project.Camera",
            ),
            notes = "Prefer the `create_project` tool — it sets the same fields and is self-documenting.",
        ),
        KrillNodeType(
            shortName = "KrillApp.Project.Diagram",
            typeFqn = "krill.zone.shared.KrillApp.Project.Diagram",
            metaFqn = "krill.zone.shared.krillapp.project.diagram.DiagramMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.project.diagram.DiagramMetaData",
                DiagramMetaData.serializer(),
                DiagramMetaData(),
            ),
            role = "display",
            sideEffect = "none",
            description = "SVG diagram with anchored nodes. Use the `create_diagram` tool — it uploads the SVG file and computes meta.source.",
            validParentTypes = listOf("KrillApp.Project"),
            validChildTypes = emptyList(),
            notes = "Do not create via `create_node` — use `create_diagram` to get the SVG upload + URL round-trip.",
        ),
        KrillNodeType(
            shortName = "KrillApp.Project.TaskList",
            typeFqn = "krill.zone.shared.KrillApp.Project.TaskList",
            metaFqn = "krill.zone.shared.krillapp.project.tasklist.TaskListMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.project.tasklist.TaskListMetaData",
                TaskListMetaData.serializer(),
                TaskListMetaData(),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Stateful checklist that invokes its observers when tasks become overdue.",
            validParentTypes = listOf("KrillApp.Project"),
            validChildTypes = listOf("KrillApp.Executor"),
            notes = "`priority` ∈ {NONE, LOW, MEDIUM, HIGH}. `tasks[]` entries carry their own cron/dueAt fields — overlay via the `meta` argument. " +
                "A RESET verb arriving from a source reopens all tasks.",
            metaFieldHints = mapOf(
                "priority" to "enum: NONE | LOW | MEDIUM | HIGH",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
                "tasks" to "List<Task> — each entry is the shape Krill's TaskList stores; consult the Krill source or an existing TaskList node via `get_node` for the full Task shape before authoring.",
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Project.Journal",
            typeFqn = "krill.zone.shared.KrillApp.Project.Journal",
            metaFqn = "krill.zone.shared.krillapp.project.journal.JournalMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.project.journal.JournalMetaData",
                JournalMetaData.serializer(),
                JournalMetaData(),
            ),
            role = "state",
            sideEffect = "none",
            description = "Chronological journal for project progress and observations.",
            validParentTypes = listOf("KrillApp.Project"),
            validChildTypes = emptyList(),
        ),
        KrillNodeType(
            shortName = "KrillApp.Project.Camera",
            typeFqn = "krill.zone.shared.KrillApp.Project.Camera",
            metaFqn = "krill.zone.shared.krillapp.project.camera.CameraMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.project.camera.CameraMetaData",
                CameraMetaData.serializer(),
                CameraMetaData(),
            ),
            role = "sensor",
            sideEffect = "low",
            description = "Live camera feed from a Pi camera module or USB camera.",
            validParentTypes = listOf("KrillApp.Project"),
            validChildTypes = emptyList(),
        ),

        // ── DataPoint ──────────────────────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.DataPoint",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint",
            metaFqn = "krill.zone.shared.krillapp.datapoint.DataPointMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.DataPointMetaData",
                DataPointMetaData.serializer(),
                DataPointMetaData(),
                // The SDK defaults bake in Clock.now() — pin them for a stable skeleton.
                "name" to JsonPrimitive("data-point"),
                "snapshot" to zeroSnapshot("0.0"),
            ),
            role = "state",
            sideEffect = "low",
            description = "Stores time-series snapshot values; nodes observing it as a source are invoked on each accepted update.",
            validParentTypes = listOf("KrillApp.Server", "KrillApp.Server.SerialDevice"),
            validChildTypes = listOf(
                "KrillApp.DataPoint.Filter",
                "KrillApp.DataPoint.Graph",
                "KrillApp.Trigger",
                "KrillApp.Trigger.HighThreshold",
                "KrillApp.Trigger.LowThreshold",
                "KrillApp.Trigger.CronTimer",
                "KrillApp.Trigger.Timer",
                "KrillApp.Trigger.Button",
                "KrillApp.Executor",
            ),
            notes = "`dataType` ∈ {TEXT, JSON, DIGITAL, DOUBLE, COLOR}. Use `record_snapshot` to write values. " +
                "To make this DataPoint store another node's result (e.g. a Calculation), wire that node into BOTH " +
                "`sources` (with SOURCE_INVOKED, so this point wakes) AND `inputs` (so it reads the result on " +
                "ingest) — when invoked by an observed source, the DataPoint pulls the snapshot of its first " +
                "data-source input, runs it through child Filters, and stores it.",
            metaFieldHints = mapOf(
                "dataType" to "enum: TEXT | JSON | DIGITAL | DOUBLE | COLOR",
                "snapshot" to "{timestamp: Long (epoch ms), value: String} — current value; format depends on dataType; see `record_snapshot` docs.",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Filter",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Filter",
            metaFqn = "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
                FilterMetaData.serializer(),
                FilterMetaData(),
            ),
            role = "container",
            sideEffect = "none",
            description = "Container for filter nodes that validate incoming DataPoint snapshots.",
            validParentTypes = listOf("KrillApp.DataPoint"),
            validChildTypes = listOf(
                "KrillApp.DataPoint.Filter.DiscardAbove",
                "KrillApp.DataPoint.Filter.DiscardBelow",
                "KrillApp.DataPoint.Filter.Deadband",
                "KrillApp.DataPoint.Filter.Debounce",
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Filter.DiscardAbove",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Filter.DiscardAbove",
            metaFqn = "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
                FilterMetaData.serializer(),
                FilterMetaData(name = "DiscardAbove"),
            ),
            role = "logic",
            sideEffect = "none",
            description = "Discards snapshots with values above the threshold in `meta.snapshot.value`.",
            validParentTypes = listOf("KrillApp.DataPoint.Filter"),
            validChildTypes = emptyList(),
            notes = "The threshold lives in `meta.snapshot.value` (stringified number) — FilterMetaData has no separate `value` field.",
            metaFieldHints = mapOf("snapshot" to "{timestamp: Long, value: String} — `value` holds the filter threshold."),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Filter.DiscardBelow",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Filter.DiscardBelow",
            metaFqn = "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
                FilterMetaData.serializer(),
                FilterMetaData(name = "DiscardBelow"),
            ),
            role = "logic",
            sideEffect = "none",
            description = "Discards snapshots with values below the threshold in `meta.snapshot.value`.",
            validParentTypes = listOf("KrillApp.DataPoint.Filter"),
            validChildTypes = emptyList(),
            notes = "The threshold lives in `meta.snapshot.value` (stringified number) — FilterMetaData has no separate `value` field.",
            metaFieldHints = mapOf("snapshot" to "{timestamp: Long, value: String} — `value` holds the filter threshold."),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Filter.Deadband",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Filter.Deadband",
            metaFqn = "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
                FilterMetaData.serializer(),
                FilterMetaData(name = "Deadband"),
            ),
            role = "logic",
            sideEffect = "none",
            description = "Discards snapshots whose |value − previous| is below the threshold in `meta.snapshot.value`.",
            validParentTypes = listOf("KrillApp.DataPoint.Filter"),
            validChildTypes = emptyList(),
            notes = "The threshold lives in `meta.snapshot.value` (stringified number) — FilterMetaData has no separate `value` field.",
            metaFieldHints = mapOf("snapshot" to "{timestamp: Long, value: String} — `value` holds the filter threshold."),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Filter.Debounce",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Filter.Debounce",
            metaFqn = "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.filter.FilterMetaData",
                FilterMetaData.serializer(),
                FilterMetaData(name = "Debounce"),
            ),
            role = "logic",
            sideEffect = "none",
            description = "Discards snapshots arriving within `meta.snapshot.value` ms of the previous one.",
            validParentTypes = listOf("KrillApp.DataPoint.Filter"),
            validChildTypes = emptyList(),
            notes = "The interval (ms) lives in `meta.snapshot.value` (stringified number) — FilterMetaData has no separate `value` field.",
            metaFieldHints = mapOf("snapshot" to "{timestamp: Long, value: String} — `value` holds the debounce interval in ms."),
        ),
        KrillNodeType(
            shortName = "KrillApp.DataPoint.Graph",
            typeFqn = "krill.zone.shared.KrillApp.DataPoint.Graph",
            metaFqn = "krill.zone.shared.krillapp.datapoint.graph.GraphMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.datapoint.graph.GraphMetaData",
                GraphMetaData.serializer(),
                GraphMetaData(),
            ),
            role = "display",
            sideEffect = "none",
            description = "Renders historical DataPoint values as a graph over `meta.timeRange`.",
            validParentTypes = listOf("KrillApp.DataPoint"),
            validChildTypes = emptyList(),
            notes = "`timeRange` ∈ {NONE, HOUR, DAY, WEEK, MONTH, YEAR}. Add the graphed DataPoint to `sources`. " +
                "Default `name` is empty — `create_node` derives `\"<parent DataPoint name> graph\"` " +
                "from the parent when no `name` is supplied, so siblings under different DataPoints don't collide.",
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT + " For Graph, populate with a single entry: the graphed DataPoint's id + host.",
                "timeRange" to "enum: NONE | HOUR | DAY | WEEK | MONTH | YEAR",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),

        // ── Trigger ────────────────────────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.Trigger",
            typeFqn = "krill.zone.shared.KrillApp.Trigger",
            metaFqn = "krill.zone.shared.krillapp.trigger.TriggerMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.TriggerMetaData",
                TriggerMetaData.serializer(),
                TriggerMetaData(),
            ),
            role = "container",
            sideEffect = "none",
            description = "Container for trigger nodes that evaluate conditions or events.",
            validParentTypes = listOf("KrillApp.DataPoint"),
            validChildTypes = listOf(
                "KrillApp.Trigger.Button",
                "KrillApp.Trigger.CronTimer",
                "KrillApp.Trigger.Timer",
                "KrillApp.Trigger.HighThreshold",
                "KrillApp.Trigger.LowThreshold",
                "KrillApp.Trigger.IncomingWebHook",
                "KrillApp.Trigger.Color",
            ),
            metaFieldHints = mapOf(
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.HighThreshold",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.HighThreshold",
            metaFqn = "krill.zone.shared.krillapp.trigger.TriggerMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.TriggerMetaData",
                TriggerMetaData.serializer(),
                TriggerMetaData(name = "HighThreshold"),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Fires when an observed DataPoint's value reaches or exceeds the threshold in `meta.snapshot.value`; its own observers are then invoked.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf(
                "KrillApp.Executor",
                "KrillApp.Executor.LogicGate",
                "KrillApp.Executor.OutgoingWebHook",
                "KrillApp.Executor.Lambda",
            ),
            notes = "The threshold lives in `meta.snapshot.value` (stringified number) — TriggerMetaData has no separate `value` field. " +
                "Watch a DataPoint by listing it in `sources` with SOURCE_INVOKED (the creation default when dropped under one).",
            metaFieldHints = mapOf(
                "snapshot" to "{timestamp: Long, value: String} — `value` holds the threshold to compare against.",
                "sources" to SOURCES_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.LowThreshold",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.LowThreshold",
            metaFqn = "krill.zone.shared.krillapp.trigger.TriggerMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.TriggerMetaData",
                TriggerMetaData.serializer(),
                TriggerMetaData(name = "LowThreshold"),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Fires when an observed DataPoint's value falls at or below the threshold in `meta.snapshot.value`; its own observers are then invoked.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf(
                "KrillApp.Executor",
                "KrillApp.Executor.LogicGate",
                "KrillApp.Executor.OutgoingWebHook",
                "KrillApp.Executor.Lambda",
            ),
            notes = "The threshold lives in `meta.snapshot.value` (stringified number) — TriggerMetaData has no separate `value` field. " +
                "Watch a DataPoint by listing it in `sources` with SOURCE_INVOKED (the creation default when dropped under one).",
            metaFieldHints = mapOf(
                "snapshot" to "{timestamp: Long, value: String} — `value` holds the threshold to compare against.",
                "sources" to SOURCES_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.Button",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.Button",
            metaFqn = "krill.zone.shared.krillapp.trigger.button.ButtonMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.button.ButtonMetaData",
                ButtonMetaData.serializer(),
                ButtonMetaData(),
            ),
            role = "trigger",
            sideEffect = "none",
            description = "Fires on user click; nodes observing it as a source are invoked with its `nodeAction` verb.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf("KrillApp.Executor"),
            notes = "Set `nodeAction` to RESET to make a \"stand down\" button — the RESET verb cascades through every observer downstream.",
            metaFieldHints = mapOf(
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.CronTimer",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.CronTimer",
            metaFqn = "krill.zone.shared.krillapp.trigger.cron.CronMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.cron.CronMetaData",
                CronMetaData.serializer(),
                CronMetaData(name = "CronTimer"),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Fires on a cron schedule; nodes observing it as a source are invoked each time it fires.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf("KrillApp.Executor"),
            notes = "`expression` supports the 6-field format `SEC MIN HOUR DOM MON DOW` (e.g. `*/5 * * * * *` = every " +
                "5 seconds) and the legacy 5-field format `MIN HOUR DOM MON DOW` (seconds assumed 0). The server " +
                "polls every second. `name` is REQUIRED on the wire — CronMetaData has no default for it. " +
                "`timestamp` is server-maintained (last fire time); leave 0.",
            metaFieldHints = mapOf(
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.Timer",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.Timer",
            metaFqn = "krill.zone.shared.krillapp.trigger.timer.TimerMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.timer.TimerMetaData",
                TimerMetaData.serializer(),
                TimerMetaData(name = "Timer"),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "One-shot countdown timer: EXECUTE starts the countdown, RESET cancels it. " +
                "Fires once after `delay` ms; nodes observing it as a source are invoked when it fires.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf("KrillApp.Executor"),
            notes = "`delay` is the countdown duration in milliseconds (default 1000). `name` is REQUIRED on the wire. " +
                "`timestamp` is server-maintained (last fire time); leave 0. " +
                "Send EXECUTE via `set_node_action` to start the countdown; RESET to cancel.",
            metaFieldHints = mapOf(
                "delay" to "Long — countdown duration in milliseconds (default 1000).",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
                "sources" to SOURCES_HINT,
                "snapshot" to SNAPSHOT_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.IncomingWebHook",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.IncomingWebHook",
            metaFqn = "krill.zone.shared.krillapp.trigger.webhook.IncomingWebHookMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.webhook.IncomingWebHookMetaData",
                IncomingWebHookMetaData.serializer(),
                IncomingWebHookMetaData(),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Fires when an HTTP request hits `meta.path`; stores the request payload in its own `meta.snapshot` for observers to read.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf("KrillApp.Executor"),
            notes = "To persist incoming payloads, create a DataPoint that lists this node in both `sources` and `inputs`.",
            metaFieldHints = mapOf(
                "method" to "enum: GET | POST | PUT | DELETE | PATCH",
                "snapshot" to SNAPSHOT_HINT,
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Trigger.Color",
            typeFqn = "krill.zone.shared.KrillApp.Trigger.Color",
            metaFqn = "krill.zone.shared.krillapp.trigger.color.ColorTriggerMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.trigger.color.ColorTriggerMetaData",
                ColorTriggerMetaData.serializer(),
                ColorTriggerMetaData(),
            ),
            role = "trigger",
            sideEffect = "low",
            description = "Fires when an observed COLOR DataPoint's value falls inside the configured RGB range.",
            validParentTypes = listOf("KrillApp.Trigger", "KrillApp.DataPoint"),
            validChildTypes = listOf("KrillApp.Executor"),
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),

        // ── Executor ───────────────────────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.Executor",
            typeFqn = "krill.zone.shared.KrillApp.Executor",
            metaFqn = "krill.zone.shared.krillapp.executor.ExecuteMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.ExecuteMetaData",
                ExecuteMetaData.serializer(),
                ExecuteMetaData(name = "Executor"),
            ),
            role = "container",
            sideEffect = "none",
            description = "Container for executor nodes that perform actions when invoked.",
            validParentTypes = listOf(
                "KrillApp.Trigger",
                "KrillApp.Trigger.HighThreshold",
                "KrillApp.Trigger.LowThreshold",
                "KrillApp.Trigger.Timer",
                "KrillApp.Trigger.Button",
                "KrillApp.Trigger.CronTimer",
                "KrillApp.Trigger.IncomingWebHook",
                "KrillApp.Trigger.Color",
                "KrillApp.Project.TaskList",
                "KrillApp.DataPoint",
            ),
            validChildTypes = listOf(
                "KrillApp.Executor.LogicGate",
                "KrillApp.Executor.OutgoingWebHook",
                "KrillApp.Executor.Lambda",
                "KrillApp.Executor.Calculation",
                "KrillApp.Executor.Compute",
                "KrillApp.Executor.SMTP",
            ),
            notes = "`name` is REQUIRED on the wire — ExecuteMetaData has no default for it.",
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.LogicGate",
            typeFqn = "krill.zone.shared.KrillApp.Executor.LogicGate",
            metaFqn = "krill.zone.shared.krillapp.executor.logicgate.LogicGateMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.logicgate.LogicGateMetaData",
                LogicGateMetaData.serializer(),
                LogicGateMetaData(),
            ),
            role = "logic",
            sideEffect = "medium",
            description = "Applies a boolean gate across its input values and stores the result in its own `meta.snapshot` for observers to pull.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "`gateType` ∈ {BUFFER, NOT, AND, OR, NAND, NOR, XOR, XNOR}. `inputs` are the gate's operands; " +
                "`sources` + SOURCE_INVOKED decide when it re-evaluates.",
            metaFieldHints = mapOf(
                "gateType" to "enum: BUFFER | NOT | AND | OR | NAND | NOR | XOR | XNOR",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " The gate's boolean operands.",
                "snapshot" to SNAPSHOT_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.OutgoingWebHook",
            typeFqn = "krill.zone.shared.KrillApp.Executor.OutgoingWebHook",
            metaFqn = "krill.zone.shared.krillapp.executor.webhook.WebHookOutMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.webhook.WebHookOutMetaData",
                WebHookOutMetaData.serializer(),
                WebHookOutMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "Sends an HTTP request to an external URL when invoked; stores the response in its own `meta.snapshot` for observers to pull.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "To persist responses, create a DataPoint that lists this node in both `sources` and `inputs`.",
            metaFieldHints = mapOf(
                "method" to "enum: GET | POST | PUT | DELETE | PATCH",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " Values interpolated into the request.",
                "snapshot" to SNAPSHOT_HINT + " Holds the last HTTP response body.",
                "params" to "List<{first: String, second: String}> — query-string pairs.",
                "headers" to "List<{first: String, second: String}> — request-header pairs.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.Lambda",
            typeFqn = "krill.zone.shared.KrillApp.Executor.Lambda",
            metaFqn = "krill.zone.shared.krillapp.executor.lambda.LambdaMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.lambda.LambdaMetaData",
                LambdaMetaData.serializer(),
                LambdaMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "Runs a sandboxed Python script when invoked; reads its `inputs` and stores its result in its own `meta.snapshot`.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "`filename` identifies the script on the server. `name` falls back to the script filename (minus `.py`) when empty.",
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " Values the Python script reads.",
                "snapshot" to SNAPSHOT_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.Calculation",
            typeFqn = "krill.zone.shared.KrillApp.Executor.Calculation",
            metaFqn = "krill.zone.shared.krillapp.executor.calculation.CalculationEngineNodeMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.calculation.CalculationEngineNodeMetaData",
                CalculationEngineNodeMetaData.serializer(),
                CalculationEngineNodeMetaData(name = "Calculation"),
            ),
            role = "transform",
            sideEffect = "low",
            description = "Evaluates a formula over its input DataPoints when invoked and stores the result in its own `meta.snapshot` for observers to pull.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "Formula variables come from `inputs`, NOT `sources`: `sources` + SOURCE_INVOKED decide when the calc " +
                "re-evaluates; `inputs` are the DataPoints whose values substitute into the formula. To store the " +
                "result as a time series, create a DataPoint that lists this calc in both `sources` and `inputs`.",
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " The DataPoints referenced by `formula` bracket tokens.",
                "formula" to "Infix math expression with bracket tokens naming INPUT nodes as `[hostId:nodeId]` " +
                    "(NodeIdentity string form). Supports + - * / % ^, sin/cos/tan/sqrt/abs/ln, parentheses. " +
                    "Example: `[348f…:ab12…] * 1.8 + 32`. Every referenced node must appear in `inputs`.",
                "snapshot" to SNAPSHOT_HINT + " Holds the last computed value.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.Compute",
            typeFqn = "krill.zone.shared.KrillApp.Executor.Compute",
            metaFqn = "krill.zone.shared.krillapp.executor.compute.ComputeMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.compute.ComputeMetaData",
                ComputeMetaData.serializer(),
                ComputeMetaData(),
            ),
            role = "transform",
            sideEffect = "low",
            description = "Computes a statistical summary of an input DataPoint's history when invoked; stores the result in its own `meta.snapshot`.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "`operation` ∈ {AVERAGE, MIN, MAX, SUM, COUNT, MEDIAN}. `range` ∈ {NONE, HOUR, DAY, WEEK, MONTH, YEAR}.",
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " The DataPoint whose history gets aggregated.",
                "snapshot" to SNAPSHOT_HINT + " Holds the last aggregate result.",
                "range" to "enum: NONE | HOUR | DAY | WEEK | MONTH | YEAR",
                "operation" to "enum: AVERAGE | MIN | MAX | SUM | COUNT | MEDIAN",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Executor.SMTP",
            typeFqn = "krill.zone.shared.KrillApp.Executor.SMTP",
            metaFqn = "krill.zone.shared.krillapp.executor.smtp.SMTPMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.smtp.SMTPMetaData",
                SMTPMetaData.serializer(),
                SMTPMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "Sends an email via SMTP when invoked by an observed source.",
            validParentTypes = listOf("KrillApp.Executor", "KrillApp.Trigger"),
            validChildTypes = emptyList(),
            notes = "SMTPMetaData has no `name` field — any `name` arg is dropped on the server.",
            metaFieldHints = mapOf(
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " Values that can be interpolated into the email.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),

        // ── MQTT ───────────────────────────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.MQTT",
            typeFqn = "krill.zone.shared.KrillApp.MQTT",
            metaFqn = "krill.zone.shared.krillapp.executor.mqtt.MqttMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.executor.mqtt.MqttMetaData",
                MqttMetaData.serializer(),
                MqttMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "Publishes to or subscribes from an MQTT broker topic; subscribed messages land in its own `meta.snapshot`.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = emptyList(),
            notes = "`action` ∈ {PUB, SUB}. MqttMetaData has no `name` field. For PUB, `inputs` supply the published value; " +
                "for SUB, persist messages by wiring a DataPoint to observe this node (`sources` + `inputs`).",
            metaFieldHints = mapOf(
                "action" to "enum: PUB | SUB",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " For PUB: the value published to `topic`.",
                "snapshot" to SNAPSHOT_HINT + " For SUB: the last message received from `topic`.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),

        // ── Server-level peripherals ───────────────────────────────────────
        KrillNodeType(
            shortName = "KrillApp.Server.Pin",
            typeFqn = "krill.zone.shared.KrillApp.Server.Pin",
            metaFqn = "krill.zone.shared.krillapp.server.pin.PinMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.server.pin.PinMetaData",
                PinMetaData.serializer(),
                PinMetaData(),
            ),
            role = "target",
            sideEffect = "high",
            description = "Controls a Raspberry Pi GPIO pin with configurable mode and startup/shutdown behavior.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = emptyList(),
            notes = "`pinNumber` > 0 and a non-empty `hardwareId` are required before Pi4J will register the pin. `mode` ∈ {IN, OUT, PWM}. " +
                "An IN pin is itself a data source — wire it into another node's `sources` (with nodeAction RESET on the pin " +
                "to make a hardware stand-down switch).",
            metaFieldHints = mapOf(
                "mode" to "enum: IN | OUT | PWM",
                "state" to "enum: ON | OFF",
                "initialState" to "enum: ON | OFF",
                "shutdownState" to "enum: ON | OFF",
                "hardwareType" to "enum: GROUND | POWER_3V3 | POWER_5V | DIGITAL | I2C | SPI | SERIAL | PWM | GPCLK",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " For an OUT pin: the value that drives the pin state when invoked.",
                "snapshot" to SNAPSHOT_HINT + " For an IN pin: the last read pin state.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Server.SerialDevice",
            typeFqn = "krill.zone.shared.KrillApp.Server.SerialDevice",
            metaFqn = "krill.zone.shared.krillapp.server.serialdevice.SerialDeviceMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.server.serialdevice.SerialDeviceMetaData",
                SerialDeviceMetaData.serializer(),
                SerialDeviceMetaData(),
            ),
            role = "target",
            sideEffect = "high",
            description = "Reads from / writes to a serial port on the host.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = listOf("KrillApp.DataPoint"),
            metaFieldHints = mapOf(
                "operation" to "enum: READ | WRITE",
                "status" to "enum (NodeState): PAUSED | INFO | WARN | SEVERE | ERROR | PAIRING | NONE | PROCESSING | EXECUTED | DELETING | CREATE_OR_OVERWRITE | USER_EDIT | USER_SUBMIT | SNAPSHOT_UPDATE | UNAUTHORISED | EDITING",
                "dataBits" to "enum: FIVE | SIX | SEVEN | EIGHT",
                "parity" to "enum: NONE | ODD | EVEN | MARK | SPACE",
                "stopBits" to "enum: ONE | ONE_POINT_FIVE | TWO",
                "flowControl" to "enum: NONE | RTS_CTS | XON_XOFF",
                "encoding" to "enum: ASCII | UTF_8 | ISO_8859_1 | BINARY",
                "terminator" to "enum: CR | LF | CRLF | NONE",
                "hardwareType" to "enum: SERIAL (fixed for this type)",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "snapshot" to SNAPSHOT_HINT + " Holds the last serial read.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Server.Backup",
            typeFqn = "krill.zone.shared.KrillApp.Server.Backup",
            metaFqn = "krill.zone.shared.krillapp.server.backup.BackupMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.server.backup.BackupMetaData",
                BackupMetaData.serializer(),
                BackupMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "Automated server backup with retention and one-click restore.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = emptyList(),
            metaFieldHints = mapOf(
                "action" to "enum: BACKUP | RESTORE",
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Server.LLM",
            typeFqn = "krill.zone.shared.KrillApp.Server.LLM",
            metaFqn = "krill.zone.shared.krillapp.server.llm.LLMMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.server.llm.LLMMetaData",
                LLMMetaData.serializer(),
                LLMMetaData(),
            ),
            role = "action",
            sideEffect = "medium",
            description = "Connects to a local Ollama LLM service for AI-assisted automation; the response lands in its own `meta.snapshot`.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = emptyList(),
            notes = "LLMMetaData has no `name` field — any `name` arg is dropped on the server.",
            metaFieldHints = mapOf(
                "backend" to "enum: OLLAMA | OPENAI_COMPATIBLE",
                "responseFormat" to "enum: NATURAL_LANGUAGE | JSON",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT + " Node values the LLM reads as context.",
                "snapshot" to SNAPSHOT_HINT + " Holds the last LLM response.",
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Swarm.Work",
            typeFqn = "krill.zone.shared.KrillApp.Swarm.Work",
            metaFqn = "krill.zone.shared.krillapp.swarm.SwarmWorkMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.swarm.SwarmWorkMetaData",
                SwarmWorkMetaData.serializer(),
                SwarmWorkMetaData(prompt = ""),
            ),
            role = "action",
            sideEffect = "high",
            description = "A single unit of LLM work advertised to the swarm for a remote `Server.LLM` node to claim and execute. " +
                "Prefer the `submit_swarm_work` MCP tool over creating this directly — it also invokes EXECUTE (by=self) " +
                "after create, which is required to publish the OPEN state to swarm peers (a bare create_node leaves it inert).",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = emptyList(),
            notes = "`prompt` is REQUIRED on the wire — SwarmWorkMetaData has no default for it. Lifecycle: OPEN → ASSIGNED → " +
                "RUNNING → DONE|FAILED (see `phase`). Use `get_swarm_work_status` to poll instead of raw `get_node`.",
            metaFieldHints = mapOf(
                "phase" to "enum: OPEN | ASSIGNED | RUNNING | DONE | FAILED",
                "images" to "List<String> — small inline base64 images (camera-frame scale). Larger content goes through `fileRefs`.",
                "fileRefs" to "List<{hash: String, sizeBytes: Long, mime: String, host: String, name: String}> — claim-check " +
                    "references to blob-store content; bytes are pulled lazily by the winner. File paths in `prompt` are inert " +
                    "(no filesystem on the inference path) — use fileRefs instead.",
                "assignee" to "{nodeId: String, hostId: String} | null — winner LLM node once ASSIGNED.",
                "result" to "{timestamp: Long, value: String} — the winner's answer text, or a serialized FileRef, once phase=DONE.",
                "maxPayloadBytes" to "Enforced at creation AND at claim-assignment — the server rejects rather than truncating.",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
        KrillNodeType(
            shortName = "KrillApp.Swarm.Batch",
            typeFqn = "krill.zone.shared.KrillApp.Swarm.Batch",
            metaFqn = "krill.zone.shared.krillapp.swarm.SwarmBatchMetaData",
            defaultMeta = sdkMeta(
                "krill.zone.shared.krillapp.swarm.SwarmBatchMetaData",
                SwarmBatchMetaData.serializer(),
                SwarmBatchMetaData(),
            ),
            role = "action",
            sideEffect = "high",
            description = "A group of related LLM work items dispatched together as child `Swarm.Work` nodes — one auction per " +
                "item. Prefer the `submit_swarm_batch` MCP tool over creating this directly — it also invokes EXECUTE (by=self) " +
                "after create, which is required to trigger server-side fan-out.",
            validParentTypes = listOf("KrillApp.Server"),
            validChildTypes = listOf("KrillApp.Swarm.Work"),
            notes = "The server caps children at `maxChildren` with an explicit error, never silent truncation. " +
                "Use `get_swarm_work_status` on individual child ids for per-item detail; this node's own `meta.completedCount` " +
                "/ `meta.failedCount` track aggregate progress.",
            metaFieldHints = mapOf(
                "items" to "List<{prompt: String, images: List<String>, fileRefs: List<FileRef>}> — one child Swarm.Work per item.",
                "completedCount" to "Count of items whose child work reached phase=DONE.",
                "failedCount" to "Count of items whose child work reached phase=FAILED.",
                "sources" to SOURCES_HINT,
                "inputs" to INPUTS_HINT,
                "invocationTriggers" to INVOCATION_TRIGGERS_HINT,
                "nodeAction" to NODE_ACTION_HINT,
            ),
        ),
    )

    val byShortName: Map<String, KrillNodeType> = TYPE_TABLE.associateBy { it.shortName }
    val byTypeFqn: Map<String, KrillNodeType> = TYPE_TABLE.associateBy { it.typeFqn }
    private val byLeafName: Map<String, List<KrillNodeType>> =
        TYPE_TABLE.groupBy { it.shortName.substringAfterLast('.') }

    fun all(): List<KrillNodeType> = TYPE_TABLE

    /**
     * Resolve a user-supplied selector. Accepts `KrillApp.X.Y`, the FQN, `X.Y`, or a bare/
     * skipped-category leaf name (`CronTimer`, `KrillApp.CronTimer` — dropping the `Trigger.`
     * category segment) as long as that leaf uniquely identifies one node type.
     */
    fun resolve(selector: String): KrillNodeType? {
        byShortName[selector]?.let { return it }
        byTypeFqn[selector]?.let { return it }
        val prefixed = "KrillApp.${selector.removePrefix("KrillApp.")}"
        byShortName[prefixed]?.let { return it }
        val leaf = selector.substringAfterLast('.')
        val leafMatches = byLeafName[leaf] ?: return null
        return leafMatches.singleOrNull()
    }
}
