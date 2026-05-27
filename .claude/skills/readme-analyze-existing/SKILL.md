---
name: readme-analyze-existing
description: Diagnose an existing README against the source-analysis output and produce a structured action list (CUT/RELOCATE/RESTRUCTURE/REWRITE-HEADING/REORDER/ADD-CALLOUT) consumed by readme-edit. Splits its work into 0(a)(i) source-only draft, 0(a)(ii) anchored diagnosis.
argument-hint: <readme-path> <source-analysis-path>
---

# readme-analyze-existing

Input: an existing README and the structured output of `readme-analyze-source` (spine candidate, callouts, API inventory).

Output: a structured action list consumed by `readme-edit`. Every action is one of: `[CUT]`, `[RELOCATE]`, `[RESTRUCTURE]`, `[REWRITE-HEADING]`, `[REORDER]`, `[ADD-CALLOUT]`. Each entry names the line range it targets, the rationale (one sentence), and the replacement or destination.

This skill does NOT edit the README; it produces the diagnosis. `readme-edit` applies the actions. Keeping diagnosis and application separate prevents the common failure mode where an agent edits as it diagnoses and produces inconsistent or partial output.

## Anchoring-bias mitigation (the 0(a)(i)/0(a)(ii) split)

A naive cleanup agent reads the existing README first, then proposes the spine, then evaluates the existing structure. This anchors the proposed spine on the existing README's wording. The fix: produce the spine BEFORE reading the existing README, then compare.

You must do these steps in this order:

1. **Step 0(a)(i): source-only spine.** Already done by `readme-analyze-source` — it produced the spine candidate without ever reading the existing README. Use that spine as the unbiased reference.
2. **Step 0(a)(ii): comparison.** Now read the existing README. Compare its opening to the source-only spine. The spine differential drives the cleanup actions.

If the existing README's spine ALREADY matches the source-only spine, you write FEWER actions. The cleanup target is the deviation from the source-only spine, not a fixed-percentage cut.

## Action types and semantics

| Type | When to use | Required fields |
|---|---|---|
| `[CUT]` | The lines are noise, banned phrases, em-dashes, audience disclaimers, marketing copy, redundant prose with no curriculum value. | `lines: A-B`, `rationale: <one sentence>` |
| `[RELOCATE]` | The lines contain content that belongs elsewhere in the README (buried spine, misplaced callout). | `lines: A-B`, `target: <line N, or 'after heading X'>`, `rationale: <one sentence>` |
| `[RESTRUCTURE]` | The lines need replacement with a description-of-replacement (synthesize a spine from buried fragments; reshape a list into hooks). | `lines: A-B`, `rationale: <one sentence>`, `replacement_description: <what to synthesize, sourced from where>` |
| `[REWRITE-HEADING]` | A heading is generic or misleading and should reflect the section's actual content. | `line: N`, `current: <text>`, `proposed: <text>` |
| `[REORDER]` | A whole `##` section is in the wrong curriculum position. | `from_line: N`, `to_line: M` (move section starting at N to immediately before section at M), `rationale: <one sentence>` |
| `[ADD-CALLOUT]` | A surface in the inventory has a callout candidate but no inline qualifier in the README. | `at_line: N`, `source_ref: <file:line>`, `text: <one or two sentences>` |

Do not use any other action types. If a finding does not fit one of the above, it is out of scope for cleanup mode and belongs to a full rewrite via `readme-draft`.

## The diagnosis procedure

### Step 1: Map the existing README

Produce a section index for your own use (not part of the output):
- Heading at line N → topic / inventory entry it covers.
- For each section: 1-line summary of its contribution.

This lets you reason about REORDER actions and identify duplicated material.

### Step 2: Spine differential

Compare the existing README's opening (typically the first 30-60 lines) to the source-only spine candidate from the analysis output.

Three outcomes:

- **Match**: existing opening matches the spine candidate's mental model. No spine actions; proceed to other findings.
- **Drift**: existing opening covers some of the spine but is buried, padded, or misordered. Action: `[RESTRUCTURE]` the opening, `[RELOCATE]` buried material to the front, or both.
- **Wrong shape**: existing opening is a flat capability list, pure marketing pitch, or premature deep-spine. Action: `[RESTRUCTURE]` with replacement_description: "Replace with the source-only spine candidate from analysis output."

When in doubt: the source-only spine wins. Do not preserve the existing opening's framing to "be conservative."

### Step 3: Section-by-section action mining

Walk each section. For each, identify findings of these kinds:

- **Banned phrases**: audience disclaimers, "Why not X?" openings, competitor-suggestion paragraphs, hedging, marketing language, em-dashes. → `[CUT]`.
- **Generic heading**: a heading like "Usage" or "Examples" that masks specific content. → `[REWRITE-HEADING]`.
- **Curriculum drift**: a section is in the wrong place (advanced surface introduced before basic, callout collected at the end). → `[REORDER]` or `[RELOCATE]`.
- **Missing callouts**: an API in the inventory has a callout candidate in source but no inline qualifier in the README. → `[ADD-CALLOUT]`.
- **Buried spine**: spine material exists in the README but appears after a wall of capability bullets or a pitch paragraph. → `[RELOCATE]` to top, or `[RESTRUCTURE]` opening + relocate.
- **Capability bullets** with no curriculum value (a flat list of features with no worked example introduction). → `[RESTRUCTURE]` into hook paragraphs or `[CUT]` if redundant with a later worked section.

### Step 4: Inventory coverage check

Every entry in the API surface inventory should be present in the README. For each missing entry:

- If the entry is small (one method, one type) and the README has a section it logically belongs in: this is OUT OF SCOPE for cleanup mode. Note it for the supervisor; do not emit an action.
- If the entry is large (a whole new surface area): the README needs full-rewrite mode (`readme-draft`), not cleanup. Note it for the supervisor.

Cleanup does not ADD new sections documenting new surfaces. That is the rewrite path.

### Step 5: Em-dash inventory

Run a final pass for `—` characters. Each one is a `[CUT]` entry with rationale "em-dash; rewrite sentence per [[feedback_no_em_dashes]]". The `readme-edit` skill knows to rewrite the sentence rather than substitute punctuation; you do not need to specify how.

## Reorder discipline

A `[REORDER]` action is disruptive. Use it sparingly:

- Only when the existing section's content is correct but in the wrong curriculum position.
- Never to move a section just because "it would flow better" — flow is the writer's craft, not a mechanical action.
- When in doubt, decompose into `[CUT]` + `[RESTRUCTURE]` (write the section in place) rather than `[REORDER]`.

A README that needs more than 2-3 reorders is a candidate for full rewrite via `readme-draft`, not cleanup.

## Output contract

Produce a single Markdown document with this structure:

```
## Spine assessment
<one paragraph: match | drift | wrong shape; what the differential is>

## Actions
1. [TYPE] lines: A-B (or line: N, or from_line/to_line, or at_line)
   rationale: <one sentence>
   <type-specific fields, e.g. target / replacement_description / current/proposed / source_ref/text>
2. ...

## Out-of-scope findings (note for supervisor)
- <finding>
- ...
```

The `## Actions` list is the contract consumed by `readme-edit`. Entries must be numbered, line-anchored, and type-tagged. No prose narrative between entries — the rationale field is the only prose per action.

## Length expectations

A clean README needs 5-15 actions. A bloated README typically needs 30-60. If your action list exceeds 80 entries, the README probably warrants full rewrite via `readme-draft` rather than cleanup; flag this in the "Out-of-scope findings" section.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do NOT dispatch further sub-agents. All diagnosis happens in your own context:

1. Read the source-analysis output (spine, callouts, inventory). Internalize the spine candidate BEFORE Step 2.
2. Read the existing README in full.
3. Run the script: `bash .claude/skills/readme/readme-check.sh <readme-path>` and `--callouts` mode. Use STRONG findings as automatic candidates for `[CUT]` actions.
4. Walk through Steps 1-5 above in order. Do not edit the README; only diagnose.
5. Emit the structured action list per the output contract.

The diagnosis is one cohesive document. Do not split it into multiple files or dispatch parallel agents per section — section-by-section actions are interdependent (a `[RELOCATE]` from Section 3 to Section 1 must be aware of what `[RESTRUCTURE]` actions modified Section 1).
