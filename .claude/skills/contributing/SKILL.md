---
name: contributing
description: Write or review a module-level CONTRIBUTING.md, a grounded contributor guide to a module's invariants, mechanisms, conventions, and extension recipes. Every claim is verified against the source; nothing invented, nothing duplicated from the root guide.
argument-hint: <module-path>
---

# /contributing: supervise a grounded module contributor-guide job

This skill is a **supervisor**. It does not mine source, draft, critique, edit,
or verify in this prompt. It dispatches sub-skill agents that do, and assembles
their outputs into a finished `CONTRIBUTING.md`.

You (the agent reading this prompt) hold the `Agent` tool. The sub-skill agents
you dispatch do NOT, so each executes inline; architecture is one level deep.
The supervisor is the only entity that fans out.

A module README serves a reader; this serves a contributor. Its content is the
module's internal invariants, mechanisms, conventions, and "how to add X"
recipes. The dominant failure mode is NOT bad shape, it is **confidently-wrong
or invented conventions**. So this pipeline is built around grounding: every
factual claim is mined with a `file:line` citation and re-verified against the
code before it ships. Treat an unverified claim as a defect, not a draft.

## Inputs

`$ARGUMENTS` is one module path (for example `kyo-browser`) or two
(`kyo-browser kyo-browser/CONTRIBUTING.md`) when the file lives elsewhere.
Resolve to:

- `module_dir`: the module source directory (for example `kyo-browser`).
- `doc_path`: the CONTRIBUTING path (default `<module_dir>/CONTRIBUTING.md`).
- `root_contributing`: the repo-root `CONTRIBUTING.md`.
- `model_dir`: working dir at `/tmp/contributing-<module>/`. The facet artifacts
  land in `model_dir/facet-<name>.md`; the deduped model at `model_dir/model.md`.

`mkdir -p` the `model_dir` before dispatching anything.

## Mode selection

Decide WRITE vs CLEANUP before dispatching downstream of the model:

- **WRITE**: no `CONTRIBUTING.md` exists at `doc_path`, or the existing one is so
  far off that a rewrite is cheaper than a fix list.
- **CLEANUP**: a `CONTRIBUTING.md` exists and is recoverable via an action list.

When unsure, default to CLEANUP. The verify gate reports a full-rewrite verdict
when the existing doc is more invented than grounded, at which point switch to
WRITE. Stage 0 and 0.5 (the grounded model) run identically in both modes.

## Pipeline

### Stage 0: facet mining (parallel fan-out, both modes)

The supervisor fans out FIVE `contributing-mine` agents in a single message, one
per facet. The facets partition a module's contributor surface and are disjoint,
so they run concurrently with no conflict:

- `architecture`: layering, dependencies, data/control flow, cross-platform split.
- `core-model`: the central abstractions and the load-bearing behavioral
  invariants and traps a contributor MUST understand (this is where a module's
  headline invariant lives).
- `conventions`: error and return-type conventions, naming, recurring code
  patterns, API-shape rules.
- `extension`: the extension points and the "how to add a new X" recipes.
- `testing`: how to test the module correctly (base traits, fixtures, platform
  split, deterministic-timing techniques, gates).

```
For each facet in [architecture, core-model, conventions, extension, testing]:
  Agent({
    subagent_type: "general-purpose",
    model: "opus",
    description: "Mine <facet>",
    prompt: "/contributing-mine <module_dir> <facet>\n\nWrite output to <model_dir>/facet-<facet>.md."
  })
```

Dispatch all five at once. Wait for all. Each artifact is a grounded facet: every
mechanism, convention, invariant, trap, and extension point with a `file:line`
citation and a short quote. Read each output and confirm it carries citations; if
a facet came back with uncited assertions, redispatch that one facet with a
tightened prompt. Do NOT proceed on an ungrounded facet.

### Stage 0.5: dedup against the root guide

Concatenate the five facet files into `model_dir/facets.md` and dispatch
`contributing-dedup`:

```
Agent({
  subagent_type: "general-purpose",
  model: "sonnet",
  description: "Dedup against root guide",
  prompt: "/contributing-dedup <model_dir>/facets.md <root_contributing>\n\nWrite the deduped model to <model_dir>/model.md."
})
```

It tags every mined item DEFER (already global in the root guide, becomes a
one-line pointer) or KEEP (module-specific, gets documented), preserving every
citation. The result `model_dir/model.md` is the contract every downstream stage
reads.

### Stage 1: write OR cleanup-diagnose

**WRITE mode**: dispatch `contributing-draft`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Draft contributor guide",
  prompt: "/contributing-draft <model_dir>/model.md <doc_path>"
})
```

The drafter names the module's headline invariant (the one judgment call that
needs the whole model), structures the guide, and writes it, carrying every
citation through and marking anything that is a target convention rather than
implemented fact.

**CLEANUP mode**: skip the draft. The existing doc is diagnosed by the verify
gate (Stage 2) against the fresh model, which emits a precise action list; then
`contributing-edit` applies it. If verify returns a full-rewrite verdict, switch
to WRITE and dispatch `contributing-draft`.

### Stage 1.5: critique (gestalt judgment)

After a draft exists (WRITE) or before editing a recoverable existing doc
(CLEANUP), dispatch `contributing-critique`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Critique contributor guide",
  prompt: "/contributing-critique <doc_path> <model_dir>/model.md"
})
```

Outcomes:
- **PASS** (at most 1 axis WEAK, 0 FAIL): proceed to Stage 2 verify.
- **REWORK** (2-4 axes WEAK or FAIL): re-dispatch `contributing-draft` with the
  critique directives in the prompt; then re-critique. Max 2 rework iterations.
- **REJECT** (5+ axes WEAK or FAIL): re-dispatch `contributing-draft` from the
  model with the full directives. A second REJECT escalates to the user.

### Stage 2: grounding verification (the load-bearing gate)

Dispatch `contributing-verify`:

```
Agent({
  subagent_type: "general-purpose",
  model: "opus",
  description: "Verify grounding",
  prompt: "/contributing-verify <doc_path> <model_dir>/model.md <module_dir>"
})
```

This is the centerpiece. It re-opens every cited `file:line` and adjudicates each
claim CONFIRMED / WRONG-CITATION / INVENTED / MISLABELED-AS-REAL, checks every
KEEP item from the model is present, and runs the companion script. Outcomes:

- **PASS** (all gates): report to the user. The guide is ready.
- **BLOCK with WRONG-CITATION / MISLABELED / MISSING-ITEM findings**: dispatch
  `contributing-edit` with one action per finding, then re-verify.
- **BLOCK with INVENTED findings (a claim with no support in the code)**: an
  invented claim is the worst outcome. Re-dispatch the responsible upstream
  stage: if the claim traces to a facet the miner fabricated, re-run that
  `contributing-mine` facet with the orphan named; otherwise re-dispatch
  `contributing-draft`. Then re-critique and re-verify. Never just delete the
  sentence and move on without understanding where the invention entered.
- **BLOCK with a full-rewrite verdict (CLEANUP only)**: switch to WRITE, dispatch
  `contributing-draft`, then critique and verify.

```
Agent({
  subagent_type: "general-purpose",
  model: "sonnet",
  description: "Apply action list",
  prompt: "/contributing-edit <doc_path>\n\nAction list:\n<paste verify findings>"
})
```

Loop control: stop on stall, not a cycle count. Stop and surface to the user when
verify repeats the same finding (skill bug, not content), or BLOCKs with no new
finding (the edit is not moving the needle), or after 3 cycles you cannot name
what the next cycle clears.

## Model selection rationale

- `contributing-mine`: Opus. Deciding what is load-bearing vs incidental, and
  grounding it precisely, is the pivotal judgment of the whole job.
- `contributing-dedup`: Sonnet. Mechanical DEFER/KEEP tagging against root headings.
- `contributing-draft`: Opus. Naming the headline invariant, structure, the
  depth-vs-breadth call, and the decision-rule synthesis.
- `contributing-critique`: Opus. Whole-document gestalt along CONTRIBUTING axes.
- `contributing-verify`: Opus. Claim-by-claim grounding against the source is the
  highest-judgment gate; the report-writing is judgment even where the script is
  mechanical.
- `contributing-edit`: Sonnet. Mechanical action application.

## Anti-patterns (the supervisor's own behavior)

- **Do not mine, draft, or verify yourself.** Dispatch a sub-skill.
- **Do not fan out the facets sequentially.** Stage 0 is one parallel dispatch of
  five; they are disjoint by construction.
- **Do not let an ungrounded facet proceed.** A facet with uncited claims poisons
  every downstream stage; redispatch it.
- **Do not paper over an INVENTED finding by deleting the sentence.** Trace it to
  the stage that introduced it and fix the source of the invention.
- **Do not skip verify**, even when draft and critique both report success. The
  grounding gate is the reason this skill exists.
- **Do not duplicate the root guide.** Module-specific content only; global
  conventions become one-line pointers via the dedup stage.
- **No em-dashes or en-dashes.** Rewrite the sentence, never substitute punctuation.
- **No marketing copy.** A contributor guide states mechanisms and rules.

## Companion script

`.claude/skills/contributing/contributing-check.sh` runs the mechanical checks the
sub-skills invoke: every `file:line` citation resolves to a real file and line,
the required structure is present, the defer-to-root pointer exists, and there are
no em-dashes or placeholders. The supervisor does not run it; the sub-skills do.

## End-of-session

Report to the user:
- Mode (WRITE | CLEANUP) and rationale.
- The headline invariant the drafter named.
- Final verify result (and the CONFIRMED / total claim count).
- Path to the finished `CONTRIBUTING.md` and to the model artifact.

Do NOT propose follow-up work (commits, pushes, PRs). The user owns those.
