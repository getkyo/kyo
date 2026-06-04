---
name: contributing-draft
description: Draft a module CONTRIBUTING.md from the deduped grounded model. Names the module's headline invariant, elaborates it deeply, and writes architecture, conventions, extension recipes, testing, and a decision checklist. Carries every citation through; invents nothing.
argument-hint: <model-path> <output-doc-path>
---

# contributing-draft

Read the deduped model (first argument) and write a finished `CONTRIBUTING.md` to
the path in the second argument. This skill is the assembler. It does not mine new
facts, does not dedup, and does not verify. Its inputs are the KEEP items (each
already grounded with a `file:line` citation and a quote) and the root pointer.

Two hard rules govern everything you write:

1. **Invent nothing.** Every factual sentence in the guide traces to a KEEP item.
   You may rephrase for flow, but you may not add a mechanism, convention, value,
   or behavior that is not in the model. If you feel a gap, leave it; do not fill
   it from imagination. The verify gate will reject any sentence it cannot ground.
2. **Carry the citations.** Each mechanism, invariant, config value, and recipe
   step keeps the `file:line` anchor from its KEEP item, inline in the prose
   (`MutationSettlement.scala:35`), so a reader and the verify gate can both check
   it. A claim without its anchor is an unverifiable claim.

## The headline invariant (the one judgment call)

The model carries a `### Headline candidate` from the core-model facet. Confirm
or correct it: which single invariant must a contributor internalize above all
others to not break this module? That invariant gets the deepest section of the
guide, by a wide margin. For kyo-browser it is transparent settlement; the
settlement section is long, with the goal, every enforcing mechanism, when each
applies, the constraints, the config knobs, and the decision rules for new
methods. A guide that gives the headline invariant the same shallow paragraph as
every minor convention has failed; depth here is the point.

## Structure

Adapt to the module, but this is the spine:

1. **Title and scope.** One short intro: this is module-specific guidance,
   complementing the root `CONTRIBUTING.md`. Place the root pointer here (the
   one-sentence DEFER pointer from the model). State the headline invariant as the
   single most important thing to internalize. A short table of contents if the
   guide is long.
2. **Architecture at a glance.** The layering, one line per layer or major
   internal module, a representative call-flow trace, the cross-platform split.
   From the architecture KEEP items.
3. **The headline invariant (deep).** Goal, mechanisms (each tied to the one
   operation kind it serves), when each fires, the constraints, a config-knob
   table, and the conventions a new method must follow. From the core-model KEEP
   items. This is the largest section.
4. **Conventions.** Error/exception hierarchy, the return-type rule (stated as a
   discriminator with its twin examples), naming, recurring patterns. From the
   conventions KEEP items.
5. **Extension recipes.** The "how to add a new X" sections, each with the ordered
   steps and a real cited example. From the extension KEEP items.
6. **Cross-platform** (if not already covered in architecture) and **Testing**
   (base traits, fixtures, the forking/slowness reality, platform split, the
   deterministic-timing techniques with cited examples). From the testing KEEP items.
7. **A closing "adding a new X" checklist** that distills the decision rules into
   an ordered, scannable list a contributor runs through before writing code.

The decision checklist and the return-type discriminator are the highest-value
parts after the headline section, because they turn description into action. Favor
them over restating mechanisms a second time.

## Exists vs convention (honesty)

The model marks some items `[CONVENTION: target]`: a rule the module is moving
toward but does not yet universally follow. In the draft, present these as the
convention to follow, with an explicit note that the current code predates it or
does not yet follow it everywhere, citing the place it does not. Never write a
target convention as if it were implemented fact. A reader who acts on a
mis-stated "fact" and finds the code does the opposite stops trusting the guide.

## Style

- Defer to the root guide for global conventions; do not re-explain them. If a
  section would just restate a global rule, replace it with a pointer.
- State mechanisms and rules. No marketing, no "powerful"/"seamless", no filler
  openers.
- No em-dashes or en-dashes. Use commas, colons, parentheses, or separate
  sentences. Rewrite, do not substitute punctuation.
- Kyo terminology where relevant: `Maybe`, `Result`, `Chunk`, `Span`.
- Tables for layer maps, config knobs, and the return-type discriminator; prose
  for the headline mechanism's narrative; ordered lists for recipes and the
  checklist.

## On a re-draft (REWORK from critique)

When the supervisor re-dispatches you with critique directives, apply them
structurally, do not patch sentences. The common directives: deepen the headline
section, convert description into decision rules, add a missing load-bearing
mechanism from the model, collapse a duplicated-from-root section into a pointer,
or fix an exists-vs-convention mislabel. Re-emit the whole guide.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Read the model, confirm the headline invariant,
write the guide to `doc_path` with citations carried through, and stop. Do not
verify your own output; the verify gate does that. Length scales with the module's
real surface; a small module gets a short guide, never padding.
