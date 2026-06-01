---
name: readme-edit
description: Apply a structured action list (CUT/RELOCATE/RESTRUCTURE/REWRITE-HEADING/REORDER/ADD-CALLOUT) to a README. Mechanical only; no diagnosis. Em-dash removal must rewrite the sentence, not substitute punctuation.
argument-hint: <readme-path> <action-list>
---

# /readme-edit: apply a structured action list to a README

The task is:

$ARGUMENTS

**Inputs required:**
- `readme_path`: path to the README file to edit.
- `action_list`: structured findings document produced by `readme-analyze-existing`, containing one or more entries of the types listed below.

This sub-skill is mechanical. The action list is already approved. Do not re-diagnose, re-evaluate, or second-guess entries. Apply every entry in the list, in order, unless an entry requests an addition that is NOT in the allowed list (see "Out-of-scope additions" below).

---

## Action type semantics

| Type | What it means | What to do |
|---|---|---|
| `[CUT]` | Delete the line range entirely. | Remove those lines. No replacement. |
| `[RELOCATE]` | Move the line range to the target location specified in the entry. | Cut from source; insert at target. Preserve the content verbatim. |
| `[RESTRUCTURE]` | Replace the line range with the description-of-replacement in the entry. | Synthesize replacement content from the entry's rationale and the existing README material. Do NOT invent new content beyond what the rationale describes. |
| `[REWRITE-HEADING]` | Change heading text from `current` to `proposed`. | Replace only the heading text. Keep the heading level (`##`, `###`, etc.) unchanged. |
| `[REORDER]` | Move the entire section (from the heading at `from_line` to just before the next `##` heading at the same level) to immediately before the section at `to_line`. | Relocate the whole section block as a unit. |
| `[ADD-CALLOUT]` | Insert a callout paragraph at the specified location, sourced from the entry's source reference. | Insert one or two sentences at the specified line. Content must come from the source reference named in the entry (scaladoc, test, implementation comment). Do not invent. |

---

## Em-dash rewriting (special case)

When the action list contains `[CUT]` entries on em-dashes, do NOT mechanically substitute `;` or `-`. The fix is to REWRITE the sentence so the em-dash is not needed.

Per-context options (lifted verbatim from the project rule):

- **Incidental clause** ("kyo-compat — built for library authors — supports all 6 backends"): use commas, or restructure so the clause is no longer parenthetical. "kyo-compat supports all 6 backends. Library authors are the target audience."
- **Parenthetical aside** ("the runtime — ZIO, CE, or Future — is chosen at deploy time"): use parentheses, or fold the aside into the main clause. "the runtime (ZIO, CE, or Future) is chosen at deploy time" OR "the runtime is chosen at deploy time: ZIO, CE, or Future."
- **Joining two thoughts** ("Flow is a plan — not an execution"): use a period and start a new sentence, or use a colon if the second thought elaborates the first. "Flow is a plan, not an execution." OR "Flow is a plan: not an execution."
- **Setting off a list or example** ("supported runtimes — ZIO, Cats Effect, Future"): use a colon. "supported runtimes: ZIO, Cats Effect, Future."
- **DO NOT** mechanically substitute `;` for `—`. A semicolon is correct only when joining two complete, independent clauses; most em-dash usages are not that case. Overusing `;` produces awkward staccato prose.
- **DO NOT** substitute a hyphen (`-`) for an em-dash. It looks wrong and reads worse.
- After removing an em-dash, READ THE SENTENCE OUT LOUD (or simulate the read). If it sounds awkward or staccato, rewrite further.

---

## Out-of-scope additions

If the action list contains an entry requesting an addition that is NOT in the allowed list below, stop and report it as out-of-scope. Do not silently apply.

**Allowed** (these may appear in `[RESTRUCTURE]` or `[ADD-CALLOUT]` entries):
- Synthesize a 1-2 paragraph spine from existing material already in the README.
- Rewrite a flat heading to a content-specific one based on what the section already discusses.
- Reshape a capability list into hooks derived from existing material.
- Add an inline callout from a named source: `Note:` / `Unlike` / `Caution:` annotations in scaladoc, tests, or implementation comments.

**NOT allowed** (reject and report if the action list requests these):
- A new section documenting an API surface the existing README did not cover.
- New code examples demonstrating something new.
- "Frequently asked questions" or similar new sections with no basis in existing content.

---

## Execution order

1. Read `readme_path` in full.
2. Apply actions in the order given in the action list. Use `Edit` for each entry.
3. After all actions are applied, run the post-edit check (below).

---

## Post-edit check

After applying all actions:

1. **Diff size sanity.** Confirm the diff is roughly stable or a decrease. A small increase is acceptable when a `[RESTRUCTURE]` promoted a buried spine (relocated text now appears earlier, not duplicated). A large increase signals scope creep: a `RESTRUCTURE` entry was interpreted too broadly. Stop and audit what was added.

2. **Banned phrase scan.** Confirm no banned phrases survived: audience disclaimers used defensively, "Why not X?" in the opening, competitor-suggestion paragraphs, em-dashes. Flag any that remain so downstream `readme-verify` has them on record.

3. **Script check.** Run:
   ```
   bash .claude/skills/readme/readme-check.sh <readme-path>
   ```
   If STRONG findings remain that the action list should have addressed, stop and report them. Do not silently pass.
