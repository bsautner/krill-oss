---
issue: Sautner-Studio-LLC/krill-oss#236
pr: Sautner-Studio-LLC/krill-oss#237
date: 2026-08-22
module: krill-mcp
category: mcp-tools
---

## What happened

The kraken demo pipeline called `create_node` with `type: 'KrillApp.CronTimer'` while
building a `kraken-vitals` dashboard. The call failed:

```
create_node: ERROR: Unknown node type 'KrillApp.CronTimer'. Call `list_node_types` for
the catalog, or pass a full FQN like `krill.zone.shared.KrillApp.DataPoint`.
```

The registered short name is `KrillApp.Trigger.CronTimer` — the caller dropped the
`Trigger.` category segment. Every demo run is also an MCP integration test, so this
blocked an end-to-end recording rather than just annoying a human operator who could
shrug and retype it.

## Root cause

`KrillNodeTypes.resolve()` only tried three forms of the selector: exact `shortName`,
exact `typeFqn`, and the selector with a `KrillApp.` prefix bolted on if missing. None of
those tolerate a selector that keeps the `KrillApp.` prefix and the leaf type name but
omits an intermediate category segment (`Trigger`, `Executor`, `DataPoint.Filter`, …) —
a natural mistake for both a human and an LLM guessing a type name from context, since
the leaf name (`CronTimer`) is the salient, memorable part and the category is an
implementation detail of how the catalog is organized.

## Fix

- `krill-mcp/krill-mcp-service/.../krill/KrillNodeTypes.kt` — `resolve()` now falls back
  to a leaf-name index (`shortName` substring after the last `.`) when the earlier exact
  matches fail. The fallback only fires when the leaf uniquely identifies one node type in
  the catalog — an ambiguous leaf still returns `null` and the caller gets the existing
  "Unknown node type" error pointing at `list_node_types`, rather than silently guessing
  wrong.
- `krill-mcp/krill-mcp-service/.../mcp/tools/CreateNodeToolTest.kt` — regression tests for
  `KrillApp.CronTimer` and bare `CronTimer` resolving to `KrillApp.Trigger.CronTimer`, the
  exact-match path still winning over the fallback, and an unmatched selector returning
  `null`.

No version bump — bug-fix PR, not a `krill-mcp` release.

## Prevention

- **When a catalog is organized by category/leaf, expect callers to address it by leaf
  alone.** A resolver that only accepts the fully-qualified path pushes the burden of
  memorizing internal taxonomy onto every caller, human or LLM. Add a leaf/suffix fallback
  up front for any hierarchical name catalog, gated on uniqueness so it never silently
  resolves an ambiguous name to the wrong type.
- **Demo pipelines double as MCP integration tests.** A gap that only shows up when an
  external caller (not the test suite) exercises the tool with a plausible-but-wrong
  argument is exactly the kind of bug unit tests miss — the fix here is tested by asserting
  the *forgiving* input works, not just the canonical one.
