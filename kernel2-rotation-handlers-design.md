# kernel2 design: rotation, real handlers, and the evidence environment

The single design covering the rotation ruling and proper support for the handler
formats, per your direction: "reflect on the new Handler impls... they'd allow
cheaper handling not even producing a continuation in some cases... I think we
might add a Handlers param like Context. Or Context can contain Handlers."

Status: IMPLEMENTED in the real kernel, on your "proceed" ruling. The suite is
623 of 623 green, including the context-scope red this design existed to fix
and new tests pinning the corrected scoping with the old-kernel-validated
reference values. The board comparison after the change:

| row | before | after | delta |
|-----|--------|-------|-------|
| eagerMap5 | 4.62 ns / 0 B | 4.61 ns / 0 B | identical: pure chains untouched |
| deepBind10k | 68.8 us / 160,336 B | 68.9 us / 160,336 B | identical |
| loopPure10k | 19.0 us / 160,008 B | 19.2 us / 160,008 B | noise |
| resumeFused | 28.4 ns / 16 B | 29.8 ns / 16 B | noise |
| suspension | 489 ns / 2,120 B | 111 ns / 616 B | 4.4x faster, 3.4x leaner |
| state10 | 838 ns / 4,560 B | 256 ns / 1,040 B | 3.3x faster, 4.4x leaner |
| loopSuspend1k | 70.4 us / 379,938 B | 23.7 us / 179,904 B | 3.0x faster, half the allocation |
| narrowIter | 2,627 ns / 12,024 B | 949 ns / 4,704 B | 2.8x faster, 2.6x leaner |
| stateMap10k | 2.39 ms / 8.79 MB | 0.56 ms / 3.11 MB | 4.3x faster, 2.8x leaner |
| contextRead100 | 56 ns, 208 B per read | 62 ns, 216 B per read | one rotate node per read bounce; optimization round |

Resolutions of what section 7 left open: handling is eager, the old kernel's
shape (two tests that pinned the superseded lazy installation now pin
construction-time acting); the handlers parameter carries fun-format handlers
only, as a Chunk maintained like the context, per your direction; the deleted
machinery list in section 6 landed in full (search, prefixArrow, hasHandler,
prepend, Interceptor, ContextBinding, the Catching interceptor).

The prototype was removed at your direction (git history `f243819967`). The
reference for the mechanism is your minified kernel's rotation law; the 11
reference programs and their old-kernel-validated results (section 4a) are the
acceptance values, now carried by suite tests.

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

## 2.4 Dispatch: one method per format on the existing Handler classes

Not a new abstraction layer. Kernel2's `Handler.ArrowHandler` subclasses already
store their clause at the public types and already carry the completion step
(`run`). The change is that each format's dispatch semantics moves from
`evalOperation`'s erased search-and-act into one method on the handler, where
its types are bound:

```scala
sealed abstract class ArrowHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S]:
    def effectTag: Tag[E]
    /** completion: today's run step, unchanged */
    def complete(v: A): B < S
    /** a suspension of this region's effect reached its handler: the input and
      * the captured remainder, at the handler's own types */
    def resume(input: I[Any], cont: O[Any] => A < (E & S)): B < S
```

Per format, `resume` IS the semantics, and its body type-checks with no cast:

```scala
// Cont: deep, the clause result re-enters the region (spliced deep handler)
def resume(input, cont) = Handled(clause(input, cont), this)
// First: shallow, the handler leaves; cont is the raw unhandled remainder
def resume(input, cont) = clause(input, cont)
// Loop: the clause returns the EXISTING Loop.Outcome2 (a Continue2, or the bare
// done value: the opaque union O | Continue2), interpreted exactly as today's
// outcomeStep does, with replaceState installing the next iteration's handler
def resume(input, cont) = clause(input, state, cont).map {
    case c: Loop.Continue2[?, ?] => Handled(c._2, replaceState(c._1))
    case done                    => done
}
// Stop: never called; the drive unwinds without capturing (2.6)
// Resume: answered in place at the site (2.5); defined only as the uniform fallback
```

The drive still trades in erased values (the trampoline currency, the old
kernel's OX/IX), so the call into `resume` carries exactly two casts, justified
by the tag match that precedes it: the input recovery and the remainder's type.
Whether they sit at the drive's call site (the old kernel's style) or in one
final erased entry method on the class is a code-layout choice for the
implementation round; either way it is one boundary, the same one `Context.get`
carries today.

Deleted outright: `evalOperation`'s erased search, its `[C]`-instantiation
casts, the `prefixArrow` folds, and `Interceptor.as`. The probe file uses
heavier scaffolding (an `onPark`/`parkErased` method family and `M`-prefixed
names) because its frame stack holds handlers existentially in plain Scala;
that is model scaffolding, not the proposed surface.

## 2.5 Resume clauses run at their region's entry scope (settled by E3)

Two mechanisms make in-place Resume execution scope-correct:

1. **The entry-scope bracket.** A Resume clause runs at the operation's site but
   under the environment captured at its region's ENTRY. The drive brackets the
   clause with a node carrying that entry scope; when the clause completes, the
   site environment is restored and the value flows into the untouched
   remainder. Because the bracket is itself a node, a suspension from INSIDE the
   clause re-wraps it like any region (rotation), so the rest of the clause
   still runs at clause scope after resumption. Program p9 pins exactly this
   and matches the old kernel.
2. **Deep handlers by self-reference.** The environment a clause runs under is
   the entry environment extended with the region itself: an operation of the
   region's own effect inside a clause is interpreted by the same handler (deep
   semantics, the old kernel's handler loop re-handling the clause result),
   while everything else resolves OUTSIDE the region. The same environment
   value is what the drive installs when it enters the region, so region code
   and clause code share it, and an inner rebind (p2's `Env=42` inside the
   region) shadows reads inside the region without ever leaking into the
   clause. In the model this is one lazy self-referential field on the
   environment entry; the kernel encoding is the implementation round's choice.

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

The E3 harness (since deleted with the prototype; git history `f243819967`)
implemented the synthesis as a small semantic model and ran each reference
program against the OLD kernel in the same process. Every value below is a real
execution result from both sides, and the programs themselves are old-kernel
facts, independent of any model:

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
2. **Two implementation traps found and fixed while building**, both of which
   the kernel2 port must carry:
   1. Running a guard's rescue directly inside the drive's exception handler
      would let a THROWING rescue escape its outer guards; the rescue must
      re-enter through the computation.
   2. With the strict path, applying a captured continuation runs its fused
      pure segments immediately, so a crossed guard's re-wrap must DEFER the
      inner application into the re-established guard (the old kernel does
      exactly this with its try-wrapped continuation application in
      KyoContinue); wrapping the applied value is not enough, and a post-resume
      throw would otherwise bypass its guard (p5 catches this).

# 5. The execution parameters: handlers threaded like the context, per your direction

Your direction, stated twice: propagate a collection of handlers the way the
context is propagated, as an execution parameter, so that reaching a Resume
suspension applies the clause locally. That is the plan:

1. **A `Handlers` parameter rides beside `context`** through the drive and the
   `run`/apply signatures, the same move the threading round made for the
   context. Its representation is E2b's: one final array-backed class,
   innermost-last scan, reference-first tag comparison (~2ns, depth-insensitive,
   allocation-free lookups).
2. **Region entry pushes, region exit restores.** An entry carries the handler
   plus the (context, handlers) pair captured at entry, which is what a clause
   runs under (the clause-scope rule, 2.5: a clause executes OUTSIDE its own
   region, deep via the entry's self-extension).
3. **A Resume suspension is applied locally**: resolve the tag in the handlers
   parameter at the suspension's own dispatch site, run the clause under the
   entry scope, feed its value to the suspension's untouched fused continuation.
   No park, no capture, no search: the format's designed cost (E1: ~2ns and
   ~1B per operation against today's ~30ns and 136B).
4. **Rotation is what keeps the parameter derivable.** The parameter is a
   drive-local running value; the durable encoding is the chain's region
   structure (section 6). Parks cross region boundaries, and every re-entry
   re-extends the parameter, exactly as the context re-arms today, so
   multi-shot and out-of-band resumption stay sound with no re-search.
5. **The fork boundary stays trivially correct**: the context parameter crosses
   (minus Noninheritable), the handlers parameter never does; `handlePartial`
   supplies the runtime's boundary handlers as the parameter's initial value,
   its tag parameters disappearing into entries, the preempt slice loop
   unchanged.

Folding the two parameters into one environment remains possible later (a
binding is the degenerate constant handler, the gist's Env derivation); the
separate parameter is your stated direction and keeps "values inherit,
interpreters never do" structural.

# 6. Incorporation into the real implementation, seam by seam

The two known defects of the current implementation are both symptoms of the
missing rotation representation, and the incorporation targets exactly their
seams:

- **prepend**: handlers install as searchable delimiters at the FRONT of the
  chain (`Handler` extends `Arrow.Transform`, `install`, Handler.scala:20,34)
  and re-install by prepending interceptors (`prepend` at Kyo.scala:60, 75, 99,
  132). Front position cannot encode nesting, which is the confirmed scope
  defect class (clause-scope red, Catching over-guard, Observe).
- **context propagation**: `ContextBinding` interceptors sit mid-chain and never
  execute for parked operations; scope information is gone by dispatch time.

Four moves, in the real machinery:

1. **Handlers stop being chain delimiters.** `Handler` leaves `Arrow.Transform`;
   deleted outright: `hasHandler` (Arrow.scala:19), the chain search and its
   prefix collection (`evalOperation`, `search`, `prefixArrow`,
   Pending.scala:461-553), the erased `act` dispatch with its
   `[C]`-instantiation casts, and `act`'s `guarded` prefix scan (the over-guard,
   Pending.scala:483).
2. **Installation appends a ROTATE step at the region's end**: the gist law as
   data. `handle(t1, Continue(susp: t2, cont), f)` becomes
   `Continue(susp, cont.andThen(Rotate(handler)))` when `t1 != t2`. `Rotate` is
   one new `Arrow.Transform` kind, an ordinary chain element (Offset packing and
   `optimize` untouched); applying it re-enters the region: it re-extends the
   handlers parameter (the context, for bindings), drives the remainder, and
   rotates again on the next foreign suspension. `ContextEffect.handle` and
   `Effect.catching` install the same way, one mechanism for all three. Scope
   is the step's position in the chain: outside-installed and inside-installed
   now produce DIFFERENT chains, dissolving the byte-identical-chains defect at
   the root.
3. **Dispatch happens at the suspension site against the threaded handlers.**
   `evalLoop`'s `Continue`/`Suspend` arms (Pending.scala:415-425) stop calling
   `evalOperation`; they resolve the tag in the handlers parameter:
   - Resume: the clause applies locally (section 5.3), the suspension's `cont`
     untouched as the remainder;
   - Stop: unwind to the owning rotate step, discarding without capture;
   - Cont/First/Loop: capture: the delimited continuation is the chain segment
     up to the owning rotate step, sliced from Arrows that already exist rather
     than re-folded through `prefixArrow`; crossed rotate steps re-enter their
     regions on resumption;
   - miss: the boundary park to `handlePartial`, poll loop unchanged.
4. **`prepend` and `Arrow.Interceptor` are deleted everywhere** (Suspend,
   Continue, Bracket, Defer, ContextBinding). Re-establishment on resume is the
   rotate steps already sitting in the chain: paid once at installation instead
   of per park.

Untouched, by construction: the `<` union and implicit lift, inline
`map`/`flatMap`, Arrow/Transform/Offset/AndThen and `optimize`, `Suspend` as a
bare leaf, `Continue`'s fused cont, `Bracket`/`Defer` semantics, Safepoint and
the preempt slices, the eager settled path. A pure chain crosses no rotate step
and consults no parameter: eagerMap5 and the plain dispatch rows keep their
exact code path.

The cost ledger, honestly: Resume operations drop from park-and-search to one
lookup plus the clause (measured, E1/E2b); Cont-family capture remains
O(prefix), as capture inherently is, but reuses existing chain segments;
region entry pays one appended step and one parameter push (the new
region-entry benchmark row); parks stop paying per-park prepend re-installs.

# 7. Needs your ruling

1. This incorporation plan (sections 5 and 6). Approving it opens the
   implementation round on the real kernel, with the 11 reference programs
   (section 4a, old-kernel-validated) as the acceptance suite and the benchmark
   board plus a region-entry row as the performance gate.
2. Two small open choices inside it: the rotate step's name, and whether
   `handle*` applies Resume/Stop clauses eagerly at installation when the
   computation is already suspended on the matching tag (the old kernel's
   handleLoop eagerness) or defers uniformly to the drive.

`EvidenceProbeMain` (E1/E2b harness, real kernel2 machinery) remains the one
temporary probe; it is deleted when the implementation round lands and the
reference programs become suite tests.
