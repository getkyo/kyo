# kernel2 design: rotation, real handlers, and the evidence environment

The single design covering the rotation ruling and proper support for the handler
formats, per your direction: "reflect on the new Handler impls... they'd allow
cheaper handling not even producing a continuation in some cases... I think we
might add a Handlers param like Context. Or Context can contain Handlers."

Status: exploration + experiments in progress. Nothing implemented until you
approve this design.

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

# 5. Open questions going into the experiments

1. Where `Stop`'s unwind meets `Catching` nodes in between (the throw-unwind and
   the stop-unwind should be the same mechanism).
2. Whether `Offset` fused chains stay as-is inside regions (expected yes: they
   carry only plain transforms once handlers are nodes).
3. What remains of `hasHandler` (expected: deleted with the search).
4. Boundary semantics: what `handlePartial` looks like when the runtime's
   handlers are evidence entries rather than a tag parameter.
