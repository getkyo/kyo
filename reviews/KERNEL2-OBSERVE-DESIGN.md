# Observe for kernel2: exploration

The ask: a `kyo.kernel.Observe` that Debug.trace (already locally adapted to presume it) can use:

```scala
Observe { (frame, value) => ... }(v)   // every step inside v's extent reports (frame, value)
```

replacing the old kernel's `Safepoint.Interceptor`, which kernel2 deliberately dropped. Two
constraints set by the user: the observation must propagate to forked fibers (IOTask integration
details undecided), and the expected residency is the Stack / `Kyo.Park` machinery, so slices and
resumes carry it the way they carry everything else.

## What the old kernel did, and why it cannot port

`Safepoint.Interceptor.enter(frame, value)` fired on every `Safepoint.handle`, i.e. on every node
application, because the old kernel evaluated every `.map` through a node. It was installed in the
Safepoint (thread state), and `Isolate.internal.restoring` re-installed it on the far side of a
fork. Two reasons it cannot port as-is:

- kernel2 has no per-application Safepoint hook, and adding one puts a callback check on the
  hottest path in the kernel for every program, observed or not. That is the exact cost the
  interceptor's removal bought back.
- thread-state residency is the wrong home: kernel2's unit of continuation is the value (parks,
  dumped continuations), so anything thread-resident fails the fork-propagation requirement
  unless every fork boundary manually re-installs it, which is the old design's wart.

## The residency question: three options

### (a) Thread/Safepoint slot (old shape). Rejected

Hot-path cost for everyone plus manual re-installation at every fork boundary. Both disqualify it.

### (b) A bare `var observer` on `Stack`, snapshot in `Park`

The eval consults `stack.observer` at its reporting points; `park` copies it into a new `Park`
field; `restore` writes it back; `Eval.apply` starts it null.

Propagation works for parks (fibers built on `Eval.partial` slices inherit it), but a continuation
captured by `handleCont`'s dump does NOT carry it: `dump` folds entries, and a bare var is not an
entry. A clause that ships the continuation to another fiber loses the observation. Fixable only
by giving `dump` a parallel channel for it, which is (c) with extra steps.

### (c) A region entry, with the var as its resolved cache. Recommended

The observer is a stack ENTRY, sibling to `Binding`: a `Region` node holding the callback. Being
an entry buys, through machinery that already exists and is already tested:

- **parks carry it**: `Park` snapshots entries; `restore` re-installs them. A fiber resuming a
  slice observes exactly what the parked extent observed. No new `Park` field.
- **captured continuations carry it**: `dump` reifies regions into the continuation value, `push`
  re-installs them. A clause that ships `k` to another fiber ships the observation with it. This
  is the fork-propagation requirement solved by construction, before IOTask's details are even
  decided.
- **multi-shot is already correct**: replaying a captured continuation re-installs the entry per
  replay, the same discipline bindings follow.
- **nesting is scoping**: an inner `Observe` sits above an outer one; innermost wins (or both
  fire, walking outward: decision below).

The cost question is where the eval reads it. A scan per report would be O(depth), so the entry
keeps a resolved cache: `Stack` holds `var observer` maintained exactly where bindings maintain
their resolution (entry push, pop, `restore`'s re-resolution walk). The var is a cache of the
innermost observer entry, never the truth; the entries are the truth, which is what keeps every
snapshot/replay path correct without new code.

## Where the eval reports

Node cadence: the points the eval already controls, each gated on `observer ne null`:

- the Defer arm, after the payload read: `(contA.head.frame, payload)` describes the step about
  to run.
- the Suspend arm: `(kyo.frame, input)` describes the operation being performed.
- the settled-delivery pop loop: `(entry.frame, value)` describes a value flowing back into an
  entry.
- the Binding arm: install and read events if wanted (Debug's dedup-by-frame suggests noise is
  tolerable; probably skip).

Two consequences to be explicit about, both acceptable for a debug facility and both candidates
for later tightening:

1. **Fused strict steps are invisible.** Kernel2 runs settled maps at construction with no node,
   so an observer at node cadence cannot see them (the old kernel had no such fusion; its trace
   saw every map). Full fidelity is reachable later without new machinery: an observed extent can
   hold the slot's budget drained (the depth guard's own lever), forcing every step to defer into
   the Defer arm where the observer sees it. That de-fuses only the observed extent; nothing
   outside pays. Not part of the first cut.
2. **The fast answer paths bypass the eval's arms.** The answers loops deliver inline, so
   handler answers inside an observed extent would go unreported. The dispatch gates already
   choose fast-vs-general per suspension; they gain `(stack.observer eq null)` as one more
   condition, exactly as the pos gates work. Per-suspension, not per-answer, so the cost sits on
   the same cold-ish edge the gates already occupy.

## Cost when no observer exists (the number that matters)

- one reference load + null test per Defer/Suspend/settled-pop iteration in the eval's own arms;
- one reference load + null test per dispatch-gate decision;
- `Stack.push`/`pop` maintenance of the cache: a type test per entry push/pop on the paths that
  already type-test entries (the pop loop matches on entry kind today).

Expectation is sub-noise, but per the perf discipline that is a hypothesis until the full board
runs on both variants; the change lands with that run or it does not land.

## The API surface

```scala
object Observe:
    /** Observes every step the eval takes inside `v`'s extent. */
    def apply[A, S](f: (Frame, Any) => Unit)(v: A < S)(using Frame): A < S
```

- the callback is `(Frame, Any) => Unit`: effects-in-effects (an observer that suspends) is
  explicitly out of scope, matching the old interceptor and Debug's use.
- a callback that throws is a step that throws: the failure unwinds normally (releases run,
  recoveries answer). No special lane.
- nesting: first cut, innermost observer only (the cache IS the innermost; the walk-outward
  variant needs a chain and nobody needs it yet).
- `private[kyo]`? No: `Observe` is the public face (Debug imports `kyo.kernel.Observe`), the node
  and cache are internal.

## What this deliberately does not decide

- IOTask integration: nothing here depends on it. Slices inherit via `Park`, shipped
  continuations via `dump`; whatever IOTask becomes, it is built from those two.
- Whether ContextEffect-style fork hooks (`fork`/`join`) should ALSO see observers: no; observers
  are not context values, they are regions, and regions already cross.
- The budget-drain full-fidelity mode: parked as a follow-up with its own measurement.

## Open questions for the user

1. Report cadence for the first cut: Defer + Suspend + settled-pop (as above), or a narrower
   Suspend-only start (cheapest possible, still enough for Debug.trace's frame stream)?
2. Innermost-only nesting acceptable for the first cut?
3. Any need to observe values crossing INTO the extent (arguments), or only produced values, as
   sketched?
