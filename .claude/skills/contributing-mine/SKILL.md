---
name: contributing-mine
description: Mine one facet of a module's contributor surface into grounded items (mechanisms, conventions, invariants, traps, extension points), each with a file:line citation and a quote. One facet per invocation; never invents.
argument-hint: <module-dir> <facet>
---

# contributing-mine

Read the module at `$ARGUMENTS`'s first argument and mine ONE facet (the second
argument) into a grounded artifact for `contributing-draft`. This skill does not
draft, dedup, critique, or verify, and it does not mine any facet other than the
one named. The supervisor invokes this skill five times in parallel, once per
facet; each invocation owns exactly one.

The output is consumed downstream as fact. Therefore the single rule that
overrides everything else: **every item you record carries a `file:line`
citation and a short verbatim quote of the code or comment that supports it. An
item you cannot cite does not get recorded.** A confidently-stated but uncited or
wrong claim is the exact failure this whole skill exists to prevent. When you are
unsure whether the code does what you think, open the file and read it; do not
guess from a name.

## The facets

You mine exactly one of these. The argument tells you which.

- **architecture**: the module's layering (public surface down to its lowest
  internal layer), what each layer or major internal module is responsible for
  (one line each), the dependency direction, the data/control flow of one
  representative operation end to end, and the cross-platform split (what lives in
  `shared/` vs `jvm/js/native`, and exactly what is platform-specific and why).
- **core-model**: the central abstractions and, most importantly, the
  load-bearing behavioral invariants a contributor MUST understand to not break
  the module. This is where a module's headline property lives (for kyo-browser,
  transparent settlement; for an STM module, transaction isolation; for an actor
  module, mailbox and supervision semantics). For each invariant, record the
  mechanism that enforces it, when it applies, and the trap that follows from
  getting it wrong. Quote the comment or code that proves the invariant is real.
- **conventions**: the module-specific code conventions a new method must follow.
  Error and exception hierarchy, the rule for choosing a return type (when to
  return `Maybe`, when to abort, when `Boolean`/`Int`/a collection), naming
  conventions specific to the module, recurring code patterns (scoped wrappers,
  recorders, builders), and any API-shape rule the existing surface follows
  consistently. Derive each rule from at least two existing examples and cite both.
- **extension**: the extension points and the concrete "how to add a new X"
  recipes, mined from how existing instances were added. For each recipe, the
  exact steps and the real example in the code they are derived from (the files a
  contributor edits, in order).
- **testing**: how to write a correct test for this module. The base trait(s) a
  suite extends, how a test obtains the system under test (fixtures, shared
  resources), the platform split for tests, the build config that governs test
  execution (forking, parallelism, slowness) with the comment explaining why, and
  the deterministic-timing techniques the existing tests use instead of sleeps,
  with concrete cited examples.

## What an item looks like

Every item, in every facet, has this shape:

```
- <claim, one sentence> [<path-or-Name.scala>:<line(-range)>]
  > <short verbatim quote from the cited code or comment>
```

The claim is what a contributor needs to know. The citation is where it is true.
The quote is the proof. If you would write a claim without a quote you can stand
behind, cut the claim.

Mark each item with a tag the downstream stages key on:

- `[MECHANISM]`: a piece of machinery a contributor must understand.
- `[INVARIANT]`: a rule that must hold; breaking it breaks the module.
- `[TRAP]`: a specific way a contributor gets it wrong, and the consequence.
- `[CONVENTION]`: a pattern the existing code follows that new code must match.
- `[RECIPE]`: an ordered "how to add X" derived from a real example.
- `[CONFIG]`: a knob or default, with its value and what it governs.

One item can carry one tag. Most facets produce a mix; `core-model` is mostly
`[INVARIANT]`/`[MECHANISM]`/`[TRAP]`, `extension` is mostly `[RECIPE]`,
`conventions` is mostly `[CONVENTION]`.

## Honesty: implemented fact vs target convention

Some of what belongs in a contributor guide is a convention the module is moving
toward, not yet uniformly implemented. You MUST distinguish them. If a claim
describes how the code actually behaves today, it is fact (cite the code). If it
describes a rule that SHOULD be followed but the current code does not yet
universally follow, mark the item `[CONVENTION: target]` and cite both the
intended pattern and a place the code does not yet follow it. Never present a
target as implemented fact. This is the precise mistake that erodes trust in a
contributor guide.

## How to read for your facet

1. Start at the module's public entry surface (the top-level `object` / package
   object) and its scaladoc.
2. For your facet, follow the relevant internal modules. Use `Grep` for the
   signals that map to your facet:
   - architecture: `dependsOn`, `crossProject`, `private[kyo] object`, `def send`,
     `Exchange`, platform dir contents.
   - core-model: the central type's scaladoc, `// Constraint:`, `// Note:`,
     `// IMPORTANT`, the comment density around the headline mechanism.
   - conventions: `extends.*Exception`, `Maybe\[`, `Abort.fail`, repeated wrapper
     shapes (`Scope.acquireRelease`, `Retry\[`), return types of sibling methods.
   - extension: every existing instance of the thing being added (every
     `case class ... derives Schema`, every `with*` wrapper, every event arm).
   - testing: `extends.*Test`, `build.sbt` module settings, `Schedule.fixed`,
     `Clock.now`, `Promise.init`, `withConfig` in tests.
3. Read the actual code at each hit before recording the item. The grounding
   discipline is non-negotiable: open the file, confirm the line, quote it.

## Deriving a convention from examples (conventions facet)

A convention stated from one example is a guess. State a convention only when at
least two existing surfaces follow it, and cite both. The most valuable
convention is a discriminator a contributor can apply mechanically (for example:
"a geometry read returns `Maybe` like `boundingBox`; a property read aborts like
`attribute`"). Frame conventions as a rule plus the twin examples that prove it,
not as prose.

## Output contract

Produce exactly one facet block, using the facet name as the heading, and nothing
else:

```
## <facet>

### Summary
<2-4 sentences: the one thing a contributor most needs to take from this facet.>

### Items
- [<TAG>] <claim> [<path-or-Name.scala>:<line(-range)>]
  > <verbatim quote>
- ...

### Open
<anything you could not ground, named explicitly as ungrounded, for the
supervisor to route. Empty if none. Never smuggle an ungrounded claim into Items.>
```

For the `core-model` facet only, add one line at the end naming your candidate for
the module's single headline invariant (the one a contributor must internalize
above all): `### Headline candidate: <name> (<why, one sentence>)`.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do
NOT dispatch further sub-agents. Read source directly with `Read` and `Grep`,
ground every item, and emit the single facet block. Mine only your facet; if you
notice something load-bearing that belongs to another facet, note it in one line
under `### Open` so it is not lost, but do not write it up. The whole pipeline's
accuracy rests on this stage: an ungrounded item here becomes a confidently-wrong
sentence in the finished guide.
