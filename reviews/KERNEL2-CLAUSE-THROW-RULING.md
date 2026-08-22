# Ruling needed: which scopes answer a handler clause's throw?

Self-contained: everything needed to rule is in this document.

## Background

kernel2's effect handlers install a region on the eval stack. `ArrowEffect.handleCont` gives the
clause the continuation explicitly (`[C] => (input, cont) => ...`); `handleLoop` and
`handleLoopState` answer in place through the `Loop.continue` protocol. `Effect.catching(body)(recover)`
installs a recovery scope as a stack entry: a failure unwinding past it is answered by `recover`.

A recovery can stand in two places relative to a handler:

- **outside** the handled computation: `catching(handleX(...)(clause))(recover)`
- **inside** the region's body, between the suspension and the handler:
  `handleX(catching(body)(recover))(clause)`

The question is what happens when the **clause itself throws** while answering a suspension, and a
recovery stands **inside** the region.

## The divergence (audit finding F4, HIGH; now pinned red)

The same program gives different answers depending on which handler family dispatched the clause:

```scala
val body: Int < Ask = Effect.catching(ask.map(_ + 1))(_ => -1)   // recovery INSIDE the region
def viaLoop = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => throw Boom(), a => a)
def viaCont = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => throw Boom(), a => a)

Eval(Effect.catching(viaLoop)(_ => -2))   // -1: the INTERIOR recovery answered the clause throw
Eval(Effect.catching(viaCont)(_ => -2))   // -2: the throw escaped to the EXTERIOR recovery
```

Mechanism: the loop family (and every fast path, including the answers loops) throws with the
interior entries still standing on the stack, so the unwind meets the interior `Catching` first.
`handleCont`'s general path dumps the continuation (interior entries included) into a value
*before* running the clause, so the unwind never sees them and the throw escapes to the exterior.
The test "a clause throw meets the same scopes on every dispatch path" (ArrowEffectTest, safety
audit pins) asserts the two are equal and is red until the ruling lands in code.

## Option A: interior scopes answer clause throws

The loop-family behavior becomes the law; `handleCont`'s general path is fixed to keep (or
reattach) the interior entries across the clause call.

- For: matches what most of the code does today (all fast paths, both loop families); no change to
  the hot paths.
- Against: the clause is not part of the region's body. The kernel's own signature law says the
  loop clause lives *outside* the region it serves (its row is `S`, not `E & S`). A recovery
  written to guard the body then also fires for a bug in the handler that was installed around it,
  which the body's author cannot anticipate.

## Option B: clause throws escape the region; only exterior scopes answer them

The cont-general behavior becomes the law; the loop family and the fast paths are fixed so a
clause throw unwinds past interior entries (they are popped or skipped) and is answerable only
outside the handled computation.

- For: consistent with the clause-scope law (the clause is the handler's code, outside the region;
  its failure belongs to whoever installed the handler). The failure surface is predictable: a
  region body's recovery answers body failures only.
- Against: touches every dispatch path's exception lane (the loop general paths, the three answers
  templates, the fast dispatchers); interior finalizers still must run during the escape (the
  unwind must release interior brackets while *skipping* interior recoveries, which is a new
  distinction the unwind does not currently draw).

## Consequences either way

- One family's current behavior changes; programs relying on the other family's accident break.
  Nothing above the kernel depends on this yet (the stack above is mid-migration).
- The fix is confined to kernel dispatch paths plus the pinned test flipping green; the red test
  stays as the law's pin afterward.
- The old kernel's behavior on this axis has not been checked and is worth a look before ruling
  (its clause throws propagate through its own drive loop; whether its interior recoveries answer
  them is checkable in an hour).

## Recommendation

Option B, on the clause-scope law: the clause's row already says it lives outside the region, and
a throw is just its effectless failure mode. The interior-finalizer subtlety (release yes, recover
no, during the escape) is real work but it is the same distinction `finalizeResources` already
draws for abandonment.

## What is needed from you

One line: **A** (interior recoveries answer clause throws) or **B** (clause throws escape to the
handler's installer). The pinned test then flips to assert the chosen value on both paths, and the
non-conforming paths get fixed to it.
