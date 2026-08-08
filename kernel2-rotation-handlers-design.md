# kernel2 design: rotation, real handlers, and the evidence environment

The single design covering the rotation ruling and proper support for the handler
formats, per your direction: "reflect on the new Handler impls... they'd allow
cheaper handling not even producing a continuation in some cases... I think we
might add a Handlers param like Context. Or Context can contain Handlers."

Status: exploration and experiments COMPLETE (E1, E2, E2b, E3). The design below
is ready for your validation. Nothing gets implemented until you approve it.
The E3 executable model (`RotationProbeMain.scala`, temporary) reproduces the old
kernel's semantics on all 11 reference programs, including the three known
defect cases, so every mechanism described here is validated code, not sketch.

# 1. What the handler formats promise, and what today delivers

The format hierarchy exists because most handling does not need a continuation:

| format | continuation need | what it should cost | what it costs today |
|--------|-------------------|---------------------|---------------------|
| Resume | none: answers the operation in place | a typed call at the suspension site | Suspend node + Continue + park + erased chain search + clause + re-drive |
| Stop | none: discards the region's rest | an unwind to the region boundary | same park + search, plus reinstall |
| Cont | the delimited continuation, as a function | capture exactly the region remainder | park + search + erased prefix fold (`prefixArrow`) + `asInstanceOf` resumes |
| First | one Cont capture, then the handler leaves | one capture | same as Cont, always |
| Loop | Cont capture + state evolution | capture + a replacement entry | same as Cont + `replaceState` |

Today every operation pays the maximum path, and the "handlers" are passive chain
markers whose logic lives in `evalOperation`'s erased search-and-act. The formats'
cheapness was designed but never implemented. Context reads are the degenerate
Resume case, which is why threading the context made them O(1): the same move
applies to every Resume-format handler.

# 2. The synthesis: region nodes + threaded evidence + rotation on park

Three pieces that only work together:

## 2.1 Regions are nodes, not chain markers

Every wrapper with a scope (the five ArrowHandler formats, Catching, Observe,
ContextBinding) installs by wrapping a typed region node, purely:

```scala
final private[kyo] class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S](
    val inner: A < (E & S),                 // the delimited region
    val handler: Handler[I, O, E, A, S],    // clause at its public types
    val cont: Arrow[A, B, S]                // the continuation OUTSIDE the region
) extends Kyo[B, S]
```

`map` appends to `cont`: outside the region. The prepend/Interceptor machinery and
its pass-through cast are deleted; the encoding cannot lose a region's entry OR
exit anymore. This kills the three scope defects at the root (the clause-scope
red, the confirmed Catching over-guard, Observe's identical over-observe), because
scope is structural again, exactly the old kernel's wrapper nesting with types.

## 2.2 The evidence environment is threaded, like the context, or in it

When the drive enters a `Handled` node it extends the threaded environment with
the handler's evidence and drives `inner` under it. An operation then resolves at
its own execution point with one lookup, no park:

- **Resume**: `clause(input)` runs immediately; the operation is a typed call.
  No Suspend node, no Continue, no dispatch. This is the format's designed cost,
  and the common case (Env/Local-style effects, Var reads, IO, Emit).
- **Stop**: unwind to the owning node, discarding the in-between: no capture.
- **Cont/First/Loop**: park to the owning node; the continuation is assembled
  structurally on the way out (2.3), typed by the node.

The two structures you named, with the trade-off to settle empirically:

1. **A separate `Handlers` parameter** next to `context`: clean separation of
   values (copyable across forks) from interpreters (structural, never inherited),
   at the cost of a second parameter through every `run`.
2. **`Context` contains the handlers**: one environment, one parameter (already
   threaded this round), one namespace (a `Tag` is either a ContextEffect or an
   ArrowEffect, never both), with the existing Noninheritable machinery
   generalized to "handlers never cross the fork boundary". `handlePartial`'s
   context parameter then carries the runtime's boundary evidence naturally.

A subtlety the design must get right in either shape: a clause runs in the scope
OUTSIDE its own region. The evidence entry therefore carries the environment
captured at region ENTRY (the drive has it in hand when it enters the node), so
in-place clause execution runs under the correct outer environment with zero
lookup gymnastics.

## 2.3 Rotation is the park behavior

When an operation cannot resolve in place (Cont-family, or no evidence in scope:
the op belongs to an outer/boundary handler), the park bubbles outward and every
`Handled` node it crosses re-wraps itself around the remainder: your gist's law,

```
handle(t1, suspend(t2, in, cont), f) == suspend(t2, in, x => handle(t1, cont(x), f))   where t1 != t2
```

as the node re-wrap. Re-entry through the nodes re-establishes the evidence, which
is what makes multi-shot and out-of-band resumption sound with no re-search: the
structure IS the environment. When the park reaches its owning node, that node
captures the accumulated remainder as the delimited continuation, at the node's
own types.

## 2.4 The typed dispatch shape (settled by E3)

Dispatch stops being an erased search-and-act and becomes method calls ON the
handler, where all its types are bound. Each format defines its semantics as a
typed method; the drive reaches them through final bridge methods that carry the
kernel's ONLY two casts (the tag-keyed input recovery, justified because the
drive matched the tag first, and the trampoline currency of the accumulated
remainder):

```scala
sealed trait MHandler[I[_], O[_], E <: Eff[I, O], A, B, S]:
    def tag: Tag[E]
    def onComplete(a: A): MK[B, S]                                // region finished
    def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[B, S] // op parked here

    final def parkErased(input: Any, k: Any => MK[Any, Any]): MK[Any, Any] =
        onPark(input.asInstanceOf[I[Any]], o => k(o).asInstanceOf[MK[A, E & S]]).asInstanceOf[MK[Any, Any]]
    final def completeErased(a: Any): MK[Any, Any] = ...
    final def rewrapErased(inner: MK[Any, Any]): MK[Any, Any] = ...
```

Inside `onPark` every format's semantics type-checks with no cast at all:

```scala
// Cont: deep, the clause result re-enters the region (spliced deep handler)
def onPark(input, k) = Handled(clause[Any](input, k), this)
// First: shallow, the handler leaves; k is the raw unhandled remainder
def onPark(input, k) = clause[Any](input, k)
// Loop: state evolves by replacement node, no mutation anywhere
def onPark(input, k) = clause[Any](input, state, k).flatMap {
    case Continue(st, next) => Handled(next, copy(state = st))
    case Done(b)            => Pure(b)
}
```

Today's `evalOperation` erased search, its `[C]`-instantiation casts, the
`prefixArrow` folds, and `Interceptor.as` are all deleted; their replacements are
the typed methods above.

## 2.5 The clause bracket and the deep-handler knot (settled by E3)

Two mechanisms make in-place Resume execution scope-correct:

1. **The Under bracket.** A Resume clause runs at the operation's site but under
   the environment captured at its region's ENTRY. The drive brackets the clause
   with an `Under(entryScope, clause)` node; when the clause completes, the site
   environment is restored and the value flows into the untouched remainder.
   The bracket is itself a node, so a park from INSIDE the clause re-wraps it
   (rotation) and the rest of the clause still runs at clause scope after
   resumption. Program p9 pins exactly this and matches the old kernel.
2. **The deep-handler knot.** The environment a clause runs under is the entry
   environment EXTENDED WITH THE REGION ITSELF:

   ```scala
   final class REntry(val handler: MHandler[?, ?, ?, ?, ?, ?], val entryEnv: Evidence):
       lazy val clauseEnv: Evidence = entryEnv.set(handler.tag.erased, this)
   ```

   So an operation of the region's own effect inside a clause is interpreted by
   the same handler (deep semantics, as the old kernel's handler loop re-handling
   the clause result), while everything else resolves OUTSIDE the region. The
   same `clauseEnv` is what the drive installs when it enters the region, so
   region code and clause code share one environment value and inner rebinds
   (p2's `Env=42` inside the region) shadow reads inside the region without ever
   leaking into the clause.

## 2.6 One frame stack, three walks

The drive's stack has four frame kinds (continuation, region, guard, bracket),
and every non-local transfer is a walk over it:

1. **The park walk** (Cont/First/Loop, and the boundary miss): accumulates the
   remainder; continuation frames compose into it, region frames re-wrap their
   node around it (rotation), guard frames re-wrap their `Catching`, bracket
   frames re-wrap their `Under`. The owning region's `onPark` receives the
   result; at the root it becomes the boundary park handed to the runtime.
2. **The stop walk** (Stop format): discards to the owning region without
   composing anything. Crossed regions and guards are dropped and their
   completion steps never run (p8 pins this against the old kernel).
3. **The throw unwind**: discards to the nearest guard. The rescue is re-entered
   THROUGH the computation (deferred via the chain) rather than called on the
   spot, so a throwing rescue is seen by the remaining outer guards, and a value
   flowing out of a guarded region simply expires the guard (p3, p4, p5).

Stop-unwind and throw-unwind are the same walk with different targets, which
answers open question 1: `Catching` needs no special case in either.

# 3. Typing discipline

Non-negotiable per your instruction, and the synthesis enables it:

1. Handlers store clauses at their public types (already true) AND are invoked at
   their types: the `Handled` node knows `I, O, E, A, S`, so clause invocation,
   continuation capture, and resumption type-check. The erased `act` with its
   `[C]`-instantiation casts and `prefixArrow` folds is deleted.
2. Erasure survives at exactly two justified boundaries: the evidence lookup
   (tag-keyed map recovery, the same single documented cast `Context.get` has
   today) and the drive's trampoline currency (`Any < Any` at the loop, as the
   old kernel's OX/IX).
3. The `Interceptor.as` pass-through cast is deleted with prepend.

# 4. Experiments (E1 first: the load-bearing claim)

1. **E1, in-place Resume ceiling**: a minimal prototype of the fused-chain +
   threaded-evidence execution answering Resume ops in place, measured against
   the real kernel2 on the suspension-bench workload (10 ops + maps), time and
   allocation. Validates "cheaper handling not even producing a continuation"
   with numbers before any kernel surgery. Expected: order-of-magnitude on the
   op path (no Suspend/Continue/dispatch allocations at all).
2. **E2, environment representation**: immutable map (Context reuse) vs a small
   handler stack for entry/lookup cost at realistic region depths (1-4 handlers).
   Decides "Handlers param vs Context contains handlers" on data.
3. **E3, capture-path correctness probe**: Cont-format capture through nodes with
   rotation: the clause-scope program, the Catching-scope program, and the
   terminating multi-shot replay, all against old-kernel reference results.

# 4a. Experiment results so far

## E1: the in-place Resume ceiling, validated

Both workloads on the REAL kernel2 machinery (real fused chains, real eval), 10
operations interleaved with plain maps, identical shapes; only the operation
differs. Interleaved measurement rounds; time via nanoTime batches, allocation via
ThreadMXBean:

| workload | ns/op | B/op | per operation |
|----------|-------|------|---------------|
| chain baseline (no ops) | 13 | 0 | |
| real handleResume (park + dispatch) | 278-403 | 1,360 | ~26 ns, 136 B |
| in-place typed evidence (uncached lookup per op) | 30 | 8 | ~1.7 ns, ~1 B |

The formats' designed cost is real: a Resume-handled operation as a typed evidence
call is ~15x faster and two orders of magnitude leaner than today's
park-and-dispatch, with the lookup paid on every call and no caching.

## E2: the environment representation is a design axis of its own

Adding depth-4 and miss-path variants to the same process degraded the previously
clean numbers across the board (baseline 13 to 95 ns, in-place 30 to 105 ns):
`scala.collection.immutable.Map1..Map4` are different classes, so the lookup site
goes megamorphic the moment environments of different sizes coexist, and Tag
equality is heavy enough that a depth-4 miss costs ~19 ns. Conclusions carried
into the design:

1. The evidence structure must be ONE concrete class regardless of size (no
   small-arity map zoo), with the lookup site monomorphic.
2. Tag comparison on the hot path should be reference-first (interned tags or a
   reference-keyed index), with the structural fallback off the fast path.
3. The miss path is the boundary-fallback path (fiber-context reads, runtime
   handlers), so it must be as cheap as the hit path.
4. E2b (measured): an array-backed evidence prototype with reference-first tag
   comparison, in the SAME process as the map variants (both facing the same JIT
   conditions):

| variant | ns/op | B/op | per operation |
|---------|-------|------|---------------|
| chain baseline | 13.5 | 0 | |
| today's handleResume dispatch | 307-366 | 1,360 | ~30 ns, 136 B |
| Map evidence, depth 1 | 71.8 | 88 | ~5.8 ns |
| Map evidence, depth 4 | 152.7 | 88 | ~14 ns |
| ARRAY evidence, depth 1 | 32.9 | 8 | ~1.9 ns, ~1 B |
| ARRAY evidence, depth 4 | 34.7 | 8 | ~2.1 ns |
| miss, both variants | ~20 ns per miss | 0 | structural Tag equality dominates |

Conclusions, now settled empirically:

1. **The evidence structure is one final array-backed class**: innermost-last
   linear scan, `eq`-first tag comparison with the structural fallback off the
   fast path. Depth-insensitive at realistic handler depths, allocation-free on
   the hot path, and immune to the polymorphism pollution that degrades the map
   variants running beside it.
2. **Misses are made cold by construction**: the runtime installs its own
   boundary evidence (the handlePartial handlers as entries), so every reachable
   operation resolves; a true miss is the unhandled-effect defect path only, and
   its ~20 ns structural comparison cost is irrelevant there.
3. The in-place ceiling stands with the array: ~15x time and two orders of
   magnitude allocation against today's park-and-dispatch for Resume-format
   operations, lookup paid per call.

## E3: the executable model agrees with the old kernel on all 11 programs

`RotationProbeMain.scala` implements the complete synthesis as a small
interpreter (typed nodes, threaded array evidence, the three walks, all five
formats) and runs each reference program against the OLD kernel in the same
process. Every value below is a real execution result from both sides:

| program | model | old kernel | what it pins |
|---------|-------|------------|--------------|
| p1 clause-scope-outer | 7 | 7 | THE red test: a clause's env read resolves outside its region |
| p2 clause-scope-rebind | 200042 | 200042 | inner rebind visible to region reads, invisible to the clause |
| p3 catch-inside | -1 | -1 | throw inside a guarded region is caught |
| p4 catch-outside-escapes | boom escapes | boom escapes | the confirmed kernel2 over-guard, fixed by structure |
| p5 catch-after-resume | -7 | -7 | guard rotation: a post-resume throw inside the region is still caught |
| p6 multi-shot | (6,15,25) | (6,15,25) | captured continuation replays, reads re-resolve; capture is typed |
| p7 rotation-foreign | 1110 | 1110 | a park crosses an unrelated region, which still handles its ops after resume |
| p8 stop-discards | 16 | 16 | Stop drops the remainder without capture, crossing an inner Catching |
| p9 clause-park-resume | 8003 | 8003 | a Resume clause parks mid-clause; its post-resume read is at clause scope |
| p10 boundary-partial | 105 | 105 | the boundary park + out-of-band resumption re-entering re-wrapped regions |
| p11 loop-state | (8,641) | (8,641) | Loop state evolution by replacement nodes, done sees the final state |

Two things the model's construction itself established:

1. **The multi-shot capture is fully typed.** p6 escapes the continuation as a
   first-class value through `First`'s clause (`B` instantiated to the
   continuation's own function type), applies it twice, and never casts. The old
   kernel reference needs `var captured: Any` plus `asInstanceOf` for the same
   program.
2. **A subtlety found and fixed while building**: running a guard's rescue
   directly inside the drive's exception handler would let a THROWING rescue
   escape its outer guards; the rescue must re-enter through the computation.
   This is a real implementation trap the kernel2 port must carry.

# 5. The environment structure: one environment, and Context lives in it

Your question was "a Handlers param like Context, or Context can contain
Handlers". The model answers it: there is ONE threaded environment, and what we
call Context today is the subset of its entries that may cross a fork boundary.

The model's `envRun` IS `ContextEffect.handle`: a binding is the degenerate
Resume-format handler that answers with a constant (your gist's derivation of
`Env` from `ArrowEffect`). Nothing else in the model distinguishes context from
handling, and all 11 programs, including every context-scoping case, hold with
that single mechanism. Concretely for kernel2:

1. **One entry type with two shapes**: `Binding(value)` for context effects and
   `HandlerEntry(handler, entryEnv)` for arrow handlers (the entry-env knot from
   2.5). One final array class per E2b, one monomorphic lookup site.
2. **The fork boundary is a filter, not a second structure**: `inherit` keeps
   bindings minus Noninheritable, and never a handler entry, by construction.
   This generalizes today's Noninheritable machinery to "interpreters never
   cross the boundary".
3. **`handlePartial` receives the fiber's environment**: inherited bindings plus
   the runtime's own handlers installed as boundary evidence. That makes an
   evidence miss the unhandled-effect defect path only (E2b conclusion 2), and
   answers old open question 4: the tag parameters disappear into entries. The
   preempt slice loop at the boundary stays exactly as it is.
4. **The public ContextEffect surface stays.** `handle(tag, value)`, `suspend`,
   `runDetached` keep their signatures; their kernel implementation becomes a
   region node installing a `Binding`. A read is one evidence lookup at the
   execution site (~2ns by E2b, against the current Defer + map read measured at
   contextRead100). The ContextBinding re-arming interceptor from the threading
   round is subsumed and deleted.

The alternative, a separate `Handlers` parameter next to `context`, was the
listed option 1. The model surfaced no correctness need for the separation, and
it costs a second parameter through every run signature and resume closure, two
boundary structures, and the kernel-internal ContextEffect/ArrowEffect split.
Listed for completeness; recommendation is the single environment.

# 6. What this deletes and what it leaves alone in kernel2

1. Deleted: the prepend/Interceptor machinery and its pass-through cast, the
   `evalOperation` erased search-and-act, `prefixArrow`, `hasHandler` (old open
   question 3), the ContextBinding interceptor, and the `asInstanceOf` resumes
   in dispatch.
2. Reshaped: handlers become region nodes with the 2.4 dispatch; `Catching` and
   `Observe` become nodes (which turns the clause-scope red test green and fixes
   the Catching and Observe scope defects by construction); `handlePartial`
   takes boundary evidence instead of tags.
3. Left alone: `Offset` fused chains (old open question 2: they carry only plain
   transforms once handlers are nodes); the preempt slice loop; the eval root.
   A pure chain crosses no node and consults no evidence, so eagerMap5 and the
   plain dispatch rows have no new cost on their path.

# 7. Needs your ruling

1. The synthesis itself: region nodes + one threaded environment + rotation on
   park, per sections 2 and 5, with E1/E2b/E3 as evidence. Approving this opens
   the implementation round.
2. The environment recommendation in section 5 (one environment; Context as the
   inheritable subset) versus the separate Handlers parameter.
3. Both probe mains (`EvidenceProbeMain`, `RotationProbeMain`) are temporary and
   get deleted when the implementation round lands; the E3 reference programs
   become real suite tests at that point.
