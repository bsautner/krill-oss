---
issue: Sautner-Studio-LLC/krill-oss#234
pr: Sautner-Studio-LLC/krill-oss#235
date: 2026-08-22
module: krill-sdk
category: serialization
---

## What happened

A `KrillApp.MQTT` node created with a name rendered its `NamePill` on the swarm canvas
showing the type (`MQTT`) instead of the given name. Every sibling executor/source type
created the same way (`DataPoint`, `Trigger.CronTimer`, `Executor.Calculation`,
`Server.Pin`) rendered its name correctly. Setting `name` in the `create_node` arg, in
`meta.name`, or both made no difference — the value had nowhere to go.

## Root cause

`Node.name()` falls back to the type string when `meta.displayName()` is empty:

```kotlin
fun Node.name(): String = this.meta.displayName().ifEmpty { this.type.toString() }
```

`MqttMetaData` carried no `name` field at all, and its `displayName()` override was an
unconditional `""`. Any `name` supplied at `create_node` time was silently dropped by
kotlinx serialization's `ignoreUnknownKeys`, and the canvas always fell through to `MQTT`.

This was a deliberate call made in `krill-oss#195` (PR #197), which made `displayName()`
abstract across all metas and added `name` fields to the twelve types that already had a
name-shaped field. `MqttMetaData` was grouped with `SMTPMetaData`/`ComputeMetaData` as
"no human-readable name" at the time — but unlike SMTP/Compute, an MQTT executor is a
natural candidate for a user label (a swarm can have several MQTT bridges to different
brokers, same as it can have several DataPoints or Pins), and the downstream editor never
grew a Name input either, so the feature was never built end-to-end for this type.

## Fix

- `krill-sdk/.../executor/mqtt/MqttMetaData.kt` — added `val name: String = ""` following
  the `DataPointMetaData` pattern, and changed `override fun displayName() = ""` to
  `override fun displayName() = name`.
- `krill-sdk/.../executor/mqtt/MqttMetaDataTest.kt` — new regression test covering: default
  `name`/`displayName()` are both `""`, `displayName()` reflects an explicit `name`, an old
  wire payload missing `name` still deserializes (defaulting to `""`), and a full JSON
  round-trip with `name` set.
- Bumped `krill-sdk` `0.0.65 → 0.0.66` so CI republishes to Maven Central.
- Left `EditMqtt.kt` (the `krill` repo's editor form) untouched — it has no Name input yet,
  so the field is settable via `create_node`/MCP today but not yet from the UI. Tracked as
  a downstream follow-up in `krill#1070`, filed separately since this repo owns only the SDK.

## Prevention

- **A meta with no `name` field silently drops any `name` the caller sends.**
  `ignoreUnknownKeys = true` means a missing field isn't a deserialization error — it's a
  quiet no-op. When adding a new meta type, decide the name story up front rather than
  defaulting to `displayName() = ""` "for now."
- **"No human-readable name" is a product call, not a technical one, and it ages.** The
  original `SMTPMetaData`/`ComputeMetaData`/`MqttMetaData` grouping in #195 was reasonable
  at the time; it stopped being reasonable once MQTT executors became a multi-broker,
  multi-instance node type. Revisit these groupings when a type's usage pattern changes,
  rather than assuming the original call still holds.
- **A backend field with no matching editor input is only half a fix.** `name` is now
  settable via `create_node`/MCP but has no UI path — check the downstream editor form
  (`krill`'s `composeApp/.../EditX.kt`) whenever adding a settable field, and file the
  follow-up explicitly rather than assuming it'll be noticed.
