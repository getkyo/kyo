# Live review: EffectTrace and the exit law

Built and verified in the isolated worktree (`kyo-root-impl`, branch `proto-lift-fix`), on top of
your `2100405d49`. Two commits there: `21030bc944` (EffectTrace) and `7705ecfece` (the exit law).
Clean batch build, 37 suites, 1464 tests, 0 failed. The walk applies the edits into this tree with
the Edit tool, one unit at a time.

## The walk, in order

### Unit 1: the exit law (`Context`, `Stack`, `Eval`, EvalTest pins)

The conformance fix B1 was gated on. The reference writes context updates at the binding's own
stack entry (`Stack.rebind`: "in the scopes that own them"), so an inner region's exit cannot
revert an update to an outer binding. This kernel restored the install-time context at every exit,
against the reference and against its own extent doc.

1. `Context` gains erased `updateErased` and `remove` (over `TypeMap.removeExact`).
2. `Stack` entries carry a `prior` alongside the install context: `NoPrior` for plain regions,
   `NoBinding` for a context region whose tag was unbound, else the previous value.
3. `Eval` gains `exitContext`: a completing exit hands the interior's context outward and reverts
   only the exiting region's own binding. Three exit sites use it: the crossing-out pop, the
   `Loop.done` pop, and the settled done pop. The Handle arm and the Park resume arm record the
   prior at installation.
4. Four pins, one per lane: outer-binding updates survive an inner exit; a region's own binding
   reverts to the enclosing one; an unbound one is removed, so the read after falls to the
   boundary default; and the failed-extent lane below.

**Decision point 1, the transactional lane.** A failed extent's interior context is gone with the
Java unwind (the central guard model), so a recovery resumes at the recovering region's
install-time context: updates inside a failed extent roll back. The reference keeps them (its
writes land in place). This is a real divergence with a coherent reading, a transaction that did
not commit, stated in the code and pinned. Your call whether that reading stands or the guard must
learn to carry the interior context (which costs a write per context update at minimum).

### Unit 2: EffectTrace (`EffectTrace.scala`, `Eval` hooks, `Stack` read accessors, EffectTraceTest)

The reference attaches at each throw edge with the failing node in hand. This kernel unwinds
centrally, so the reconstruction runs at guarded's one catch over what survives: the standing
regions and the continuations they hold, innermost first. The hot loop carries no try per arm.

1. `EffectTrace`: the suppressed-exception carrier, the bounded worklist walk (regions,
   continuations, chains, parks, nested payloads), region labels as `tag.show`/`handle`, and the
   splice that leads with synthesized frames over the plumbing-filtered physical trace.
2. `Eval`: attach and splice at guarded's catch, before the unwind so every recover inspects the
   enriched exception; a failure born in a recovery is attached from the regions still standing
   under it; the eval-boundary rethrow splices, so nested evals accumulate into one carrier.
3. `Stack`: three read accessors for the walk (`depth`, `handlerAt`, `continuationAt`).
4. Eleven pins: region labels, innermost-first splicing, folded-continuation frames, nested-eval
   accumulation, recover-time enrichment, recovery-born failures, NoStackTrace, fatality, the cap
   and its drop count, message rendering, and the suppression-disabled interplay below.

**Decision point 2, the register frames.** The loop's own registers (the folded continuations of
the step that threw) are gone by the catch, so the innermost pending frames are not synthesized;
the physical trace covers that ground because the per-site transform a user's `map` mints is an
anonymous class in the user's own compilation unit and is never filtered. The deliberate trade
against the reference: no try per hot-loop arm, paid for with the register frames. Say if you want
the per-arm attach instead; it is hot-loop surgery and a measured decision.

**Decision point 3, the plumbing filter.** One prefix, `kyo.proto.`, removes everything that
describes evaluation and nothing that describes the program, where the reference enumerates ten
class prefixes. Simpler and strictly broader; flag if too broad.

**A finding worth knowing.** The carrier rides `addSuppressed`, and an exception constructed with
suppression disabled (`Exception(msg, cause, false, false)`, the stackless shared-instance
pattern this codebase uses for `Discarded` and test failures) defeats it silently: the mechanism
degrades to nothing rather than failing. Pinned as designed degradation. It also means anything
kyo itself throws as a shared stackless instance will never carry an effect trace.

## What is deliberately not in this round

**B1, Isolate.** Its first gate, the context-update scoping, is resolved above by conformance.
Its second gate stands: the capture primitive's shape. What the reference has and the proto lacks:
`Isolate` itself with `capture`/`isolate`/`restore` and `andThen`, the derivation macro, and the
whole-context machinery (`Contextual`: enumeration of what is bound, freezing with origin
identity, origin-matched write-back). The proto's `HandlerContext.fork`/`join`/`resolve` is the
per-binding strategy surface only, with zero callers today. The reference restores by building a
`Park`; this kernel's parallel is values, and with the exit law in place the write-back half has
its semantics. The capture surface itself is the design to rule on at this review, then B1 gets
built on the rulings.
