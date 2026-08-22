---
issue: Sautner-Studio-LLC/krill-oss#238
pr: Sautner-Studio-LLC/krill-oss#239
date: 2026-08-22
module: krill-mcp
category: error-handling
---

## What happened

The kraken demo pipeline's `create_node` call for a `KrillApp.Trigger.Button` (scene
`refresh_add` in `kraken-vitals`, parent a freshly-created `KrillApp.Project`) failed with:

```
create_node: ERROR: null
```

That text is completely undiagnosable — it names no field, no status code, no exception
type. The demo pipeline has no way to decide whether to retry, fix its args, or file a bug,
and neither did this investigation: extensive code review of `CreateNodeTool`, `KrillClient`,
and `KrillRegistry` found every explicit failure path in the tool (`error(...)`, thrown
`IllegalArgumentException`/`IllegalStateException`) constructs a descriptive message from a
string template, which can never evaluate to the literal string `null`.

## Root cause

`McpServer.toolsCall` builds the tool-level error text directly from the caught exception's
`message` property:

```kotlin
"ERROR: ${it.message}"
```

Several ordinary JVM/platform exceptions have a `null` `.message` by design — most notably
`java.nio.channels.UnresolvedAddressException`, thrown when a hostname fails to resolve (a
known Ktor/CIO gotcha), but also bare no-arg `RuntimeException`/`IllegalStateException`
constructions anywhere in the dependency graph. When one of those propagates out of
`tool.execute()`, `it.message` is `null`, and the Kotlin string template renders it as the
literal text `"null"` — producing exactly the unhelpful `ERROR: null` the demo pipeline
observed. The full exception (with type and stack trace) was already being logged
server-side via `log.warn("Tool '$name' failed", it)`, but that context never reached the
caller, who only sees the `content[0].text` field.

## Fix

- `krill-mcp/krill-mcp-service/src/main/kotlin/krill/zone/mcp/mcp/McpServer.kt` — fall back
  to the exception's simple class name when `.message` is null, so a caller always gets a
  diagnosable string (`"ERROR: UnresolvedAddressException"` instead of `"ERROR: null"`).
- `krill-mcp/krill-mcp-service/src/test/kotlin/krill/zone/mcp/mcp/McpServerTest.kt` — new
  regression test: a tool that throws an exception with a `null` message must surface
  `"ERROR: <ExceptionClassName>"`, not `"ERROR: null"`.

## Prevention

- **Never format `it.message` into a user-facing string without a fallback.** Several
  everyday JVM exceptions (`UnresolvedAddressException`, bare no-arg `RuntimeException`) have
  a `null` message, and Kotlin string templates silently render that as the literal text
  `"null"` rather than failing loudly. Always fall back to something diagnosable — the
  exception's class name at minimum — anywhere a caught `Throwable`'s message crosses a
  process boundary (an MCP tool result, an HTTP error body, a log line meant for a human who
  isn't attached to the server's own logs).
- **An agent-facing error string is a debugging tool for whichever agent reads it next.**
  The demo pipeline (and any Claude agent driving `create_node`) can only act on what's in
  `content[0].text` — it has no access to the server's `log.warn` stack trace. Treat that
  string's diagnosability as part of the tool's contract, not an afterthought.
