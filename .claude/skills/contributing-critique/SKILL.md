---
name: contributing-critique
description: Whole-document gestalt review of a module CONTRIBUTING.md along seven contributor-guide axes (works-on vs generic, headline depth, decision-rule actionability, defers-to-root, exists-vs-convention honesty, completeness of load-bearing items, navigability). Verdict PASS / REWORK / REJECT.
argument-hint: <doc-path> <model-path>
---

# contributing-critique

Read the drafted `CONTRIBUTING.md` (first argument) against the deduped model
(second argument) and judge its shape. This is the gestalt gate: it catches the
failures that a per-claim verifier passes on, a guide where every sentence is
individually true but the document as a whole is a generic checklist, skims the
headline invariant, describes without telling the reader what to do, or restates
the root guide. This skill makes the shape judgment; the grounding judgment
(is each claim true) belongs to `contributing-verify`.

Do not re-verify citations here. Assume the claims are true; judge whether the
document teaches a contributor how to work on this module.

## The seven axes

Rate each PASS / WEAK / FAIL with one sentence of evidence (a quote or a section
name), not a verdict alone.

1. **Works-on vs generic.** Does the guide teach what is specific to working on
   THIS module, or does it read as a generic engineering checklist that would fit
   any module? FAIL if the bulk is global advice that belongs in the root guide.
2. **Headline depth.** Is the module's headline invariant given the deepest,
   most-elaborated treatment, with its goal, every enforcing mechanism, when each
   applies, the constraints, and the decision rules? FAIL if the headline gets the
   same shallow paragraph as a minor convention. This is the highest-weight axis;
   the headline section is the reason the guide exists.
3. **Decision-rule actionability.** Does the guide give a contributor rules they
   can apply (a return-type discriminator, an "adding a new X" checklist,
   when-to-use-which-mechanism), or only describe how things currently work? A
   guide that describes but never tells the reader what to do is WEAK at best.
4. **Defers to root.** Does it point to the root `CONTRIBUTING.md` for global
   conventions instead of re-explaining them? FAIL if it duplicates whole global
   sections.
5. **Exists vs convention honesty.** Is every behavior stated as implemented fact
   actually presented as fact, and every target convention clearly marked as a
   target (not yet universally implemented)? FAIL if a target is dressed as fact,
   or fact as aspiration.
6. **Completeness of load-bearing items.** Cross-check the KEEP items in the model
   against the guide. Is every load-bearing mechanism, invariant, and trap present?
   FAIL if a `[INVARIANT]` or headline `[MECHANISM]` from the model is missing.
   (This is a shape check against the model, not a fresh source mine.)
7. **Navigability.** Can a contributor with a specific task (add a method, add a
   CDP binding, write a settlement test) find the relevant section from the
   headings and the checklist quickly? WEAK if the structure buries the recipes.

## Verdict

- **PASS**: at most 1 axis WEAK, 0 FAIL.
- **REWORK**: 2 to 4 axes WEAK or FAIL. Emit directives the drafter applies
  structurally (deepen the headline, convert description to decision rules, add the
  missing invariant, collapse the duplicated-from-root section, fix the
  exists-vs-convention mislabel).
- **REJECT**: 5+ axes WEAK or FAIL. The shape is fundamentally off; the drafter
  rewrites from the model with the full directive set.

## Output

```
## Critique: <doc-path>

### Axes
1. Works-on vs generic: <PASS|WEAK|FAIL> -- <evidence>
2. Headline depth: <PASS|WEAK|FAIL> -- <evidence>
3. Decision-rule actionability: <PASS|WEAK|FAIL> -- <evidence>
4. Defers to root: <PASS|WEAK|FAIL> -- <evidence>
5. Exists vs convention: <PASS|WEAK|FAIL> -- <evidence>
6. Completeness of load-bearing items: <PASS|WEAK|FAIL> -- <evidence; name any missing KEEP item>
7. Navigability: <PASS|WEAK|FAIL> -- <evidence>

### Verdict
<PASS | REWORK | REJECT>

### Directives (REWORK/REJECT only)
- <structural directive the drafter applies>
- ...
```

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Read the guide whole (not chunked; this is a
gestalt judgment), rate the seven axes against the model, and emit the report. Do
not propose line edits; propose structural directives. The headline-depth axis is
where module contributor guides most often fail; weight it accordingly.
