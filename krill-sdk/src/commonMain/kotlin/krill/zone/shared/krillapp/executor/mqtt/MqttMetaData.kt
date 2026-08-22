/**
 * Metadata for the `MQTT` executor — publishes to or subscribes from a topic
 * on a configured MQTT broker. Direction is selected via [MqttAction]; the
 * actual broker connection is owned by the server-side `MqttManager`.
 */
package krill.zone.shared.krillapp.executor.mqtt

import kotlinx.serialization.*
import krill.zone.shared.krillapp.datapoint.Snapshot
import krill.zone.shared.node.InvocationTrigger
import krill.zone.shared.node.NodeAction
import krill.zone.shared.node.NodeIdentity
import krill.zone.shared.node.SourceMetaData

/**
 * Direction of the MQTT executor.
 */
enum class MqttAction {
    /** Publish source values to [MqttMetaData.topic] when the executor fires. */
    PUB,

    /** Subscribe to [MqttMetaData.topic] and write incoming messages to targets. */
    SUB,
}

/**
 * Payload for an `MQTT` executor node.
 */
@Serializable
data class MqttMetaData(
    /** User-chosen name for this node, surfaced on the canvas. Empty falls back to the type string (`MQTT`). */
    val name: String = "",
    override val sources: List<NodeIdentity> = emptyList(),
    override val snapshot: Snapshot = Snapshot(),
    /** Broker address (host:port or URI). */
    val address: String = "",
    /** Topic pattern to publish to or subscribe from. */
    val topic: String = "",

    /** Whether this node is in publish or subscribe mode. */
    val action: MqttAction = MqttAction.PUB,
    override val invocationTriggers: List<InvocationTrigger> = emptyList(),
    override val nodeAction: NodeAction = NodeAction.EXECUTE,
    override val error: String = "",
override val inputs: List<NodeIdentity> = emptyList(),
) : SourceMetaData {
    override fun withError(error: String) = copy(error = error)
    override fun displayName() = name
}
