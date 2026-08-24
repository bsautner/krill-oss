# Dev Agent Lessons

Every dev-agent PR that ships a runtime fix adds one file here. Filename:
`YYYY-MM-DD-<slug>.md`.

Purpose: future-me (or the QA agent) reads this list, not the commit log,
when wondering "have we seen this class of bug before?"

## Required sections

```markdown
---
issue: Sautner-Studio-LLC/krill-oss#NN
pr: Sautner-Studio-LLC/krill-oss#NN
date: 2026-08-24
module: krill-sdk | krill-mcp | krill-skill | krill-pi4j | cookbook | docs | other
category: api-design | sdk-mcp-drift | ci-cd | dependency-hygiene | documentation-drift | missing-capability | schema-drift | packaging | build | other
title: "Short, specific, human-readable summary of the bug"
description: "One or two sentences a search result snippet could show — under ~155 characters. Summarize the symptom, not the fix."
tags: ["module-name", "category-name", "2-4 more keywords a reader would search for"]
---

## What happened

The symptom, as observed (QA report, bug hunt finding, or Ben's report).

## Fix

The diff in one paragraph — file paths included.

## Prevention

The rule, lint, test, or assertion that would have caught this earlier.
If "no realistic prevention," say so and explain why.
```

This mirrors `Sautner-Studio-LLC/krill`'s `docs/lessons/README.md` schema
(see `krill-agents/shared/workflow.md` step 6), adapted to this repo's own
`module:*` label set from `cross-repo-protocol.md`.

The `category` field gets aggregated when reviewing recurring failure modes —
keep the values consistent. Add a new value only when nothing existing fits,
and update this README if you do.

## SEO front matter — `title`, `description`, `tags`

Per `workflow.md` step 6, lessons are written for a public audience and
intended to be indexed on krillswarm.com. This repo does not yet have its
own Jekyll/GitHub Pages layout consuming these fields the way `krill`'s
`docs/_layouts/lesson.html` does — so there is no rendering-side reason to
require them today. They are required anyway, ahead of that pipeline
existing, so entries don't need a second retrofit pass later:

- `title` — specific enough that it reads sensibly on its own in a search
  results page, not just "Bug fix."
- `description` — under ~155 characters, summarizes the symptom a reader
  searching for this error would recognize, not the fix.
- `tags` — the `module` and `category` values plus 2-4 more keywords a
  reader would plausibly search for (component names, error strings,
  technology names). Lowercase, short phrases.

The `lessons-check` job in `.github/workflows/build.yml` enforces that a PR
adds a `docs/lessons/*.md` entry (or an explicit `no-lesson-needed` waiver in
the PR body); it now also enforces this front matter — `title`,
`description`, and `tags` — on any **newly added** lesson file.

## Existing entries predate this schema

The ~53 entries already in this directory as of 2026-08-24 use a bold-label
format (`**Issue:**` / `**Root cause category:**` / `**Module:**`) that
predates this README and this repo having a documented schema at all. They
are grandfathered — not retrofitted — because inferring accurate
`title`/`description`/`tags`/`category` for each from the outside would risk
introducing inaccuracies the original authors didn't intend. The
`lessons-check` CI guard only validates newly added files, so it does not
fail on them. New lessons must follow the schema above.

## Publication rules — write for a public audience

Lessons in this repo are written to the same publication bar as `krill`'s,
in anticipation of being indexed on krillswarm.com. Every entry must pass
this checklist before it lands:

- **No credentials, tokens, or key material** — even expired or test values,
  even inside pasted log excerpts. Redact with `<redacted>`.
- **No real hostnames, LAN IPs, or box names.** Use placeholders
  (`192.0.2.x`, `example-host`). Screenshot-fixture values are fine.
- **No personal information** beyond repo-internal issue/PR references.
- **Security bugs:** describe root cause and fix, but do not publish
  step-by-step exploit detail for a vulnerability until the fixed release
  has shipped to the deb repo. If the fix hasn't shipped yet, keep the
  reproduction abstract and tighten it in a follow-up if needed.
- If a lesson genuinely can't pass the checklist without gutting it, add
  `noindex: true` **and** `sitemap: false` to its front matter — this keeps
  the page out of search results (once a rendering pipeline exists) while
  staying linkable. This is the rare exception, not the default.
