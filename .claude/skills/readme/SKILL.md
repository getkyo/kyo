---
name: readme
description: Write or review a high-quality README that serves engaged readers, not audit checklists. Cuts bloat by default; only adds when the addition closes a question the reader is actually asking.
argument-hint: <module-path>
---

# /readme: supervise a multi-stage README job

This skill is a **supervisor**. It does not analyze source, draft, diagnose, edit, or verify in this prompt. It dispatches sub-skill agents that do those things, and assembles their outputs into a finished README.

You (the agent reading this prompt) are the top-level agent invoking `/readme`. You have the `Agent` tool. The sub-skill agents you dispatch do NOT have the `Agent` tool, so each sub-skill executes its work inline (no further nesting). Architecture is one level deep, period.

## Inputs

`$ARGUMENTS` is one module path (for example, `kyo-http`) or two paths (`kyo-http kyo-http/README.md`) when the README lives in a non-default location. Resolve to:

- `module_dir`: the source directory.
- `readme_path`: the README path (default `<module_dir>/README.md`).
- `source_analysis_path`: working file at `/tmp/readme-source-analysis-<module>.md`.
- `exemplar_path`: try fetching the README from `main` for this module via `git show origin/main:<module>/README.md > /tmp/readme-exemplar-<module>.md`. Pass this to `readme-critique` when it exists. When it doesn't (new module, no prior README on main), proceed without an exemplar — the critique runs on pure judgment.

## Mode selection

Decide WRITE vs CLEANUP before dispatching anything:

- **WRITE**: no README exists at `readme_path`, OR the existing README is so far off the spine that a full rewrite is cheaper than action-list cleanup.
- **CLEANUP**: a README exists and its structure is recoverable via a focused action list.

When unsure, default to CLEANUP. The diagnosis sub-skill will report `"Out-of-scope findings"` if it determines the README needs a full rewrite, at which point you switch to WRITE.

## Pipeline

### Stage 0: source analysis (both modes)

Dispatch one Opus agent running `readme-analyze-source`. It produces a single artifact at `source_analysis_path`: spine candidate (`[use-case-first|type-first]` tagged), callout candidates, API inventory.

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Analyze module source",
  prompt: "/readme-analyze-source <module_dir>\n\nWrite output to <source_analysis_path>."
})
```

Wait for completion. Read the output. Verify it has the three required sections and the spine has a use-case-first/type-first tag. If the analysis is malformed, redispatch with a tightened prompt; do NOT proceed with a malformed analysis.

### Stage 1: write OR cleanup

**WRITE mode**: dispatch one Opus agent running `readme-draft`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Draft README",
  prompt: "/readme-draft <source_analysis_path> <readme_path>"
})
```

**CLEANUP mode**: dispatch one Opus agent running `readme-analyze-existing`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Diagnose existing README",
  prompt: "/readme-analyze-existing <readme_path> <source_analysis_path>"
})
```

Wait for the action list. If `"Out-of-scope findings"` flags a full-rewrite condition, switch to WRITE mode and dispatch `readme-draft`. Otherwise dispatch `readme-edit` with the action list:

```
Agent({
  subagent_type: "general-purpose",
  model: "sonnet",
  description: "Apply action list",
  prompt: "/readme-edit <readme_path>\n\nAction list:\n<paste the action list from analyze-existing>"
})
```

`readme-edit` is mechanical, so it runs on Sonnet. The other five sub-skills make judgment calls (spine selection, curriculum order, action-list diagnosis, gestalt critique, line-grounded spine verification) and run on Opus.

### Stage 1.5: critique (gestalt judgment)

After Stage 1 produces a draft (whether by WRITE or CLEANUP), and BEFORE Stage 2's coverage verification, dispatch `readme-critique` for the whole-document gestalt review. This catches the catalog-vs-teaching, composition-vs-enumeration, and running-domain-vs-ad-hoc failures that the verify gates pass on.

If a known-good exemplar exists (typically the version of this README on main, fetched via `git show origin/main:<module>/README.md`), pass its path as a third argument. Otherwise pass two arguments.

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Critique README shape",
  prompt: "/readme-critique <readme_path> <source_analysis_path> [<exemplar_path>]"
})
```

Read the critique report. Possible outcomes:

- **PASS** (≤1 axis WEAK, 0 FAIL): proceed to Stage 2 verify.
- **REWORK** (2-4 axes WEAK or FAIL): re-dispatch `readme-draft` with the critique directives in the prompt. The drafter applies the structural directives (cluster grouping, composition opening, running domain, WHY openers, cross-API teaching paragraphs) and re-emits the README. Then re-dispatch `readme-critique` on the new draft. Max 2 rework iterations.
- **REJECT** (5+ axes WEAK or FAIL): the draft has fundamental shape issues that piecemeal directives cannot fix. Re-dispatch `readme-draft` from the source-analysis output with the full structural directives in the prompt. If the second draft also gets REJECT, escalate to the user.

### Stage 2: verify

Dispatch one Opus agent running `readme-verify`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Verify README",
  prompt: "/readme-verify <readme_path> <source_analysis_path>"
})
```

Read the verify report. Possible outcomes:

- **PASS** (all six gates including Gate 0 sbt doctest): report the result to the user. The README is ready.
- **BLOCK on Gate 0 (sbt doctest failure)**: HARD BLOCK. The README contains code that does not compile. Read the compiler errors quoted in Gate 0's report. For each error: if it cites a fabricated method/type name (`Not found: X`), the drafter invented it ; re-dispatch `readme-edit` with the failing block and the correct API. If it cites a permission error (`X cannot be accessed`, `X is private[Y]`), the source's visibility was wrong ; the supervisor either opens the visibility in source (small, audit-document the change) or removes the example. If it cites a syntax error (`uninitialized`, keyword conflicts, `?=>` in unexpected position), the drafter hallucinated syntax ; re-dispatch `readme-edit` with the correct shape. NEVER declare PASS while Gate 0 reports a failure.
- **BLOCK on Gate 1, 2, 3, or with 1-5 `[DRAFT-OMISSION]` / any `[CALLOUT-MISSING]`**: re-dispatch `readme-edit` with a synthesized action list from the verify findings (one entry per finding). Then re-verify.
- **BLOCK with NO-SPINE or severe forward-reference cascade or >5 `[DRAFT-OMISSION]`**: severe drafter drift. Switch to WRITE mode: dispatch `readme-draft` to rewrite from the source analysis, then re-critique, then re-verify.
- **BLOCK with >5 `[INVENTORY-GAP]` entries**: analyzer slot-dropped real public surface. Re-dispatch `readme-analyze-source` with a steering note listing the orphan names that must appear in the regenerated inventory. After the new analysis lands, re-dispatch `readme-draft`, then re-critique, then re-verify.
- **BLOCK on `[CATALOG-SUSPECT]` from Gate 5**: route to `readme-critique` (Stage 1.5) for the gestalt judgment. If critique returns PASS despite the suspect signal, the README is genuinely shaped well and the mechanical signal was a false positive (e.g. a flat-API module). If critique returns REWORK or REJECT, follow the critique routing above.

Loop control: stop when progress stalls, not at a cycle count. The common case ships in ≤3 edit/verify cycles; going over is allowed when each cycle clears real issues and surfaces new ones, since cascade-discovery (Effect.defer → fabricated API name → wrong type signature) is legitimate and bailing early ships a partially-broken README.

Stop and surface to the user when:
- The current verify finds the **same** STRONG finding (or a strict subset of last cycle's findings) → skill or analyzer bug, not a content fix.
- The current verify finds **no new STRONG findings** but still BLOCKs → reward-hacking risk; the edit isn't moving the needle.
- After 3 cycles you cannot justify what the next cycle will clear → write up the remaining findings and let the user steer.

Otherwise continue: each cycle that surfaces a previously-undetected genuine bug (fabricated method, wrong effect row, broken doctest block) earns another iteration. Re-dispatching `readme-analyze-source` or `readme-critique` counts as one iteration since each implies a downstream `readme-draft` re-run.

## Model selection rationale

- `readme-analyze-source`: Opus. The use-case-first vs type-first decision and the cluster grouping are the pivotal judgment calls for the entire job.
- `readme-draft`: Opus. Curriculum order, worked-example selection, callout placement, running-domain choice.
- `readme-analyze-existing`: Opus. Spine differential, action-list mining, REORDER discipline.
- `readme-edit`: **Sonnet**. Mechanical action application; em-dash sentence rewrites are the most subtle thing it does.
- `readme-critique`: Opus. The whole-document gestalt judgment along eight axes (composition, running domain, section naming, opener style, cluster grouping, cross-API teaching, design rationale, discovery path). This is the highest-judgment sub-skill in the pipeline.
- `readme-verify`: Opus. The line-grounded spine test, skim-reader walkthrough, and source-coverage classification need judgment; Gates 1 and 5 are mechanical signal but the report-writing is judgment.

## Anti-patterns (the supervisor's own behavior)

- **Do not write code yourself.** Dispatch a sub-skill. The supervisor reads outputs and decides what to dispatch next.
- **Do not re-read the README** at every stage. Trust the sub-skill outputs (they are the experts in their own domain). Only re-read when the verify report flags something specific that requires inspection.
- **Do not modify the action list** between `readme-analyze-existing` and `readme-edit`. If you disagree with an action, redispatch the analyzer with a steering note, do not edit the action list yourself.
- **Do not parallelize sub-skills** that are sequential by design. The pipeline is strictly Stage 0 → Stage 1 → Stage 1.5 (critique) → Stage 2 (verify); no overlap.
- **Do not skip critique**. The critique is the only stage that catches the catalog-vs-teaching failure mode; the verify gates pass on internally-consistent catalogs.
- **Do not skip verify**. Even when the cleanup, draft, or critique agent reports success.
- **Do not invent your own "verification."** The verify sub-skill is the gate; don't second-guess it with your own ad-hoc read. Likewise the critique: don't second-guess its axes with your own ad-hoc judgment.
- **No em-dashes anywhere.** Per project rule [[feedback_no_em_dashes]]. Rewrite the sentence, never substitute punctuation.
- **No marketing copy.** Per project rule. The spine and worked examples are the README; pitch language is not.

## Companion script

`.claude/skills/readme/readme-check.sh` provides structural checks the sub-skills invoke directly. The supervisor does not run it; the sub-skills do.

## End-of-session

Report to the user:
- Mode (WRITE | CLEANUP) and rationale.
- Final verify result.
- Path to the finished README.
- Path to the source-analysis artifact (kept at `/tmp/readme-source-analysis-<module>.md` for downstream inspection).

Do NOT propose follow-up work (commits, pushes, PRs). The user owns those.
