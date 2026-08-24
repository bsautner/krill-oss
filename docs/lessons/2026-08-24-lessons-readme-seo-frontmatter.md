---
issue: Sautner-Studio-LLC/krill-oss#242
pr: Sautner-Studio-LLC/krill-oss#243
date: 2026-08-24
module: docs
category: documentation-drift
title: "docs/lessons/ had no README, schema, or SEO front matter"
description: "krill-oss's docs/lessons/ had no schema doc and its ~53 entries used a bold-label format instead of YAML front matter, unlike krill's."
tags: ["docs", "lessons", "documentation-drift", "seo", "front-matter", "ci"]
---

## What happened

While updating `krill-agents/shared/workflow.md`'s lessons-entry step to
require SEO front matter (`title`/`description`/`tags`), a dev agent
(`krill-agents#68`) found that this repo's `docs/lessons/` directory had no
`README.md` at all. `krill`'s `docs/lessons/README.md` documents a YAML
front matter schema (as of `Sautner-Studio-LLC/krill#1093`, including
required `title`/`description`/`tags` SEO fields enforced by a
`DocsSeoTest` guard), but `krill-oss`'s ~53 existing lesson files instead
used a bold-label format (`**Issue:**` / `**Root cause category:**` /
`**Module:**`) with no front matter block whatsoever. `workflow.md` step 6
tells dev agents the schema "lives in `docs/lessons/README.md` of each
repo" — for this repo, that file simply didn't exist, so a new lesson had
to be inferred from inconsistent prior art.

## Fix

- Added `docs/lessons/README.md` mirroring `krill`'s schema (front matter:
  `issue`/`pr`/`date`/`module`/`category`/`title`/`description`/`tags`;
  sections: *What happened* / *Fix* / *Prevention*), with `module`/`category`
  values adapted to this repo's actual `module:*` label set and lesson
  history rather than copied verbatim from `krill`.
- Added a "Require SEO front matter on new lessons entries" step to the
  `lessons-check` job in `.github/workflows/build.yml` that checks any
  **newly added** `docs/lessons/*.md` file (via `git diff --diff-filter=A`)
  for a front matter block declaring `title`, `description`, and `tags`.
- Explicitly grandfathered the ~53 pre-existing entries rather than
  retrofitting them — the CI check only looks at added files, so it does
  not touch them, and the README documents why they weren't backfilled
  (inferring accurate metadata for each from the outside risked introducing
  inaccuracies the original authors didn't intend).

## Prevention

- Any `workflow.md` instruction that says "see `docs/lessons/README.md` of
  each repo" is a promise that file exists in every repo the instruction
  applies to — a cross-repo doc reference that assumes parity between repos
  is itself a drift risk. When such wording is added or changed for one
  repo, check the sibling repos it also applies to actually have the
  referenced file.
- CI guards for "new entries must follow schema X" should key off
  `git diff --diff-filter=A` (added files) rather than scanning the whole
  directory, so schema changes don't retroactively fail on content that
  predates them.
