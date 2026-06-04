---
name: contributing-dedup
description: Tag each mined facet item DEFER (already global in the root CONTRIBUTING.md, becomes a one-line pointer) or KEEP (module-specific, gets documented), preserving every citation. Mechanical diff against root headings; no new analysis.
argument-hint: <facets-path> <root-contributing-path>
---

# contributing-dedup

Read the concatenated facet artifacts (first argument) and the repo-root
`CONTRIBUTING.md` (second argument), and decide, per item, whether it belongs in
the module guide or is already covered globally. This skill adds no new analysis
and writes no prose; it tags and forwards. A module `CONTRIBUTING.md` that
re-explains global conventions is noise, and a contributor reads two documents
where one would do.

## The decision

For each mined item, tag it:

- **KEEP**: the item is specific to this module. It is true here because of how
  this module is built, not because of a repo-wide rule. Module-specific
  mechanisms, invariants, traps, conventions that refine or specialize a global
  one, and extension recipes are almost always KEEP.
- **DEFER**: the item restates a rule that already lives in the root
  `CONTRIBUTING.md` and applies to every module identically (the global naming
  rules, the `using`-clause ordering, the generic test base-trait hierarchy, the
  unsafe-boundary tiers, the no-em-dash rule). A DEFER item does not get its own
  section; it collapses into a single pointer back to the root guide.

The discriminator: would this sentence be equally true and equally worth stating
in every other module's guide? If yes, DEFER. If it is true only because of this
module's design, KEEP. When an item is a module-specific REFINEMENT of a global
rule (the root says "public APIs have explicit return types"; the module says
"and a selector read picks `Maybe` vs abort by this rule"), KEEP the refinement
and DEFER the generality.

Read the root `CONTRIBUTING.md` headings and skim the sections so you know what is
genuinely covered globally. Do not DEFER an item just because it sounds generic;
DEFER it only when you can point to where the root guide already says it.

## Output contract

Reproduce every item from the input, verbatim including its tag, citation, and
quote, regrouped under KEEP and DEFER, and emit the pointer set:

```
## Deduped model

### Headline candidate
<the core-model facet's headline candidate, copied through>

### KEEP (document these)
<every KEEP item, verbatim from the facets, grouped by source facet, tag and
citation and quote intact>

### DEFER (collapse to a root pointer)
- <one line per DEFER item: the topic> -> root CONTRIBUTING.md (<section heading>)

### Root pointer
<one sentence the draft will place near the top of the module guide, telling the
reader to read the root CONTRIBUTING.md for global conventions and listing the
DEFER topic areas by name.>
```

Never drop an item. Never alter a citation or quote. If you are unsure whether an
item is global, default to KEEP and note the uncertainty in one trailing line; a
KEEP that should have been DEFER is a small redundancy, a DEFER that should have
been KEEP silently loses module-specific knowledge.

## Execution

This sub-skill runs as a single Sonnet agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Read the two inputs, tag every item, and emit the
deduped model. This is a mechanical diff: tag and regroup, do not rewrite the
items and do not add analysis the miners did not produce.
