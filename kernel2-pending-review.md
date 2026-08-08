# kernel2: pending items for review

Everything else from the threading round is done, green, and committed (617 of 618
tests; the one red below IS the pending decision). This doc carries only what
needs you.

# 1. THE RULING: how bindings encode their scope

## The problem, self-contained

The context now threads through execution as a parameter, and bindings install
purely as interceptors prepended at the region's entry. That combination loses one
piece of information the old kernel keeps: WHERE the binding was installed relative
to an operation handler. The failing test:

```scala
ContextEffect.handle(Tag[Env], 3) {
    ArrowEffect.handleResume(Tag[CtxOp], program)([C] => _ => env.map(_ * 2))
}
// old kernel: the clause's env read sees 3 (the binding wraps the whole handler)
// kernel2 today: the read dies as a missing-value defect
```

The clause runs at dispatch time under the drive's context; the binding interceptor
sits mid-chain, never executed (the operation parks at the region head before any
value flows through it). And no dispatch-side rule can repair this, because pure
installation makes the two scoping cases produce byte-identical chains:

```scala
handle(Env, 3)(handleResume(...)(program))   // binding OUTSIDE: clause must see 3
handleResume(...)(handle(Env, 3)(program))   // binding INSIDE: clause must not
// both encode as: Continue(op, [binding, steps..., delimiter])
```

The old kernel distinguishes them by wrapper NESTING. Value-flow semantics are
correct in both cases today; only clause scope needs the distinction, so the
ENCODING must change. Four candidates:

## Candidate 1: a nesting-preserving Bound node

```scala
final private[kyo] class Bound[V, E <: ContextEffect[V], A, +B, -S](
    val inner: A < (E & S),                  // the delimited region
    val binding: ContextBinding[V, E],       // derives this scope's value per entry
    val cont: Arrow[A, B, S]                 // the continuation OUTSIDE the binding
) extends Kyo[B, S]
```

The old kernel's wrapper made explicit: installation wraps the node (scope
encoded), the drive enters `inner` under the derived context, parks re-wrap the
node, dispatch passes through it so a clause runs under the context OUTSIDE the
node. Exact old-kernel scoping; touches drive and dispatch; reads keep their O(1)
threaded lookup.

## Candidate 2: entry and exit markers in the chain

Two chain elements per binding with a scope-depth walk at dispatch. Keeps the flat
chain, but the pairing must survive every piece of chain surgery dispatch performs
(prefix capture, reinstall, optimize). Fragile; listed for completeness.

## Candidate 3: prefix-fold at dispatch

Fold `ContextBinding`s found in the captured prefix into the clause context.
Smallest change and fixes the failing test, but since the chains are identical it
NECESSARILY leaks inside-installed bindings into clause scope, a documented
divergence from the old kernel.

## Candidate 4, your proposal: rotation as an arrow step

Your mini-kernel gist (8c8b2b02) states the mechanism as a law:

```
Handler Rotation
handle(t1, suspend(t2, in, cont), f) == suspend(t2, in, x => handle(t1, cont(x), f))   where t1 != t2
```

An unmatched suspension crosses the handler outward and the handler rotates into
its continuation, staying wrapped around the region's remainder: the old kernel's
re-wrap as algebra. This dissolves the core contradiction: a binding can be
RECORDED at its scope boundary (appended, so installation order encodes scope,
which fixes the identical-chains problem outright) while ACTING at every entry
(rotation re-wraps it to the front on each crossing). Two readings:

1. **R1: rotation for scope, threading kept at the boundaries.** Bindings return
   to appended, dispatch-visible delimiters. A context read dispatches: it
   resolves against the innermost matching binding delimiter in its continuation,
   and FALLS BACK to the threaded context when no delimiter matches, which is how
   a fiber's inherited bindings reach reads across the boundary
   (`handlePartial(context)` stays exactly for this). The clause context is the
   fold of binding delimiters OUTSIDE the matched handler, now correct because
   position encodes scope.
2. **R2: the gist's full position.** No ContextEffect kind in the kernel at all:
   `Env`-style effects are plain ArrowEffects handled with `cont(value)` (your
   gist's Env is literally `ArrowEffect[Const[Unit], Const[V]]`), scope and
   multi-shot correctness fall out of dispatch structure plus the rotation law,
   and `Context` survives only as the materialized carrier at fork boundaries.
   Subsumes candidate 1 entirely and deletes the ContextEffect/ArrowEffect split.

**The cost question attached to candidate 4**: a dispatching read costs its
dispatch distance (the walk to the innermost matching delimiter) instead of the
threaded O(1) map lookup, and O(1) was the original motivation for threading the
parameter. Under R1 the common fiber-context read (no local binding) is one failed
chain search plus the map lookup. The new contextRead100 benchmark row exists
precisely to measure this before and after.

**A sub-decision that follows the candidate**: matching semantics. The threaded
implementation resolves reads by exact tag key in the context map; the old
chain-walk was subtype-tolerant (`h.effectTag <:< readTag`). Dispatch-based reads
(candidate 4) reopen that choice; the suite currently pins neither.

## My recommendation

R1. It is your rotation mechanism, restores the old kernel's scoping exactly,
keeps the ContextEffect surface and the fork-boundary carrier unchanged for the
swap round, and leaves R2 open as a later unification once the kernel is proven in
place. Candidate 1 is the fallback if you want scope fixed without touching read
resolution at all.

# 2. Blocked, no action possible: `object Kyo` to package `kyo`

Issue 27 from the review round stays blocked by design until the swap round: the
kyo-test runner classpath carries the old kernel, and `kyo.Kyo` would collide. The
move is mechanical when the old kernel leaves the classpath.

# 3. What happens on your ruling (no further input needed)

1. Implement the chosen encoding; the red clause-scope test is the acceptance
   test, plus new tests pinning BOTH installation orders (outside-installed
   visible to clauses, inside-installed not).
2. Benchmark the read profile (contextRead100 and the dispatch rows) against the
   current board before the suite adaptation completes.
3. Full suite green, commit, tracker updated.
