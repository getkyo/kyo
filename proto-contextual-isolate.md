# Contextual isolate: fork/join wired end to end

Implemented overnight in the probe worktree, per the ruling: context effects are scoped,
join takes State directly (no Result), fork/join are the boundary protocol. Everything is
committed on `probe/contextual-isolate` (commit `54f686f98e`, based on `c65bb5c34b`), in
the shared object store, nothing pushed. Both suites green, full bench class at parity.
Nothing in your tree was touched.

## The shape that landed

Your TODOs in Isolate.scala said: Transform should carry the snapshot of the stack, and
capture wraps the computation with Park. That is exactly the wiring, with one new node to
make capture expressible as a value:

- **`Kyo.Snapshot` / `SnapshotWith`** (KyoInternal): reads the contextual regions in scope
  as Park currency, the same `[handler, state, continuation]` triples a Park installs. The
  eval answers it from the live stack without consuming it: one arm after the Park arm that
  collects the `HandlerContext` entries bottom-first with `Arrow.id` continuations and
  delivers the array fused (`kyo.cont(entries, contA.chain(contB))`).
- **`Isolate.internal.Contextual`** is now a real implementation:
  - `type State = Array[AnyRef]`, `type Transform[A] = (Array[AnyRef], A)`.
  - `capture` is a `SnapshotWith` (pending-guard apply mirroring `suspendWith`).
  - `isolate` folds `fork` over the entries (each `hc.fork(state)` may suspend), wraps the
    computation as `Kyo.Park(v, forked)`, and pairs: `parked.map(a => (forked, a))`.
  - `restore` destructures the pair and captures again at the merge point:
    `capture(current => join(forked, current, 0).andThen(a))`.
- **Join semantics** (each is pinned by a test):
  - Joins run at the merge point, in entry order, observing the origin state current at
    that moment.
  - `join(current, forked, result)` receives `result == forked`: without an update lane a
    branch cannot move its binding, so the branch's final state is its forked state.
  - The joined value runs for its effects and is not re-installed: re-binding the origin
    region would be an update, and there is no update lane.
  - A region the origin has already exited by merge time is skipped (tag scan against the
    current snapshot).
  - Defaults are identity: fork copies the binding, join keeps the origin's.

## Two kernel bugs the pins surfaced (both fixed)

**1. Context region exit died on an erased bridge checkcast.** The glue's
`def done(state, v0) = v0` compiles, in the anonymous handler class, to a bridge method
with a checkcast to the handle site's `B`. A Park-installed context region exits with the
*parked body's* result, a foreign type for that bridge: `Integer` meets `checkcast Tuple2`
and throws ClassCastException. Five pins failed on exactly this (any test whose handle-site
`B` was a tuple); the ones with `B = Int` passed by luck. The fix states the semantics
instead of patching the cast: a context region binds, it does not transform, so the settled
arm now passes the result through in its union representation without consulting `done`
(this also skips the unnest/re-lift round trip). `done` is now `final` on `HandlerContext`
and lands on `bug(...)` so no future path can call it silently. The glue no longer defines
it.

**2. The Park arm dropped continuations when `entries` was empty.** The pending
`contA`/`contB` chain is attached to entry 0 at install; with zero entries the loop never
runs and the chain was silently lost. Reachable via Contextual with no region in scope
(reproduced first: the identity-cycle pin failed 42 != 43 with the fix disabled). An empty
park now passes the continuations through directly.

## Verification

- `kyo-kernelJVM/test`: 1504/1504 (1497 baseline + 7 new pins in IsolateTest under
  "the contextual isolate": child reads forked binding, defaults identity round trip, join
  argument order `(current, forked, forked)`, joins in entry order across two regions,
  exited-region skip, replay independence with a single fork, empty-scope identity).
- `kyo-kernelJS/test`: 1458/1458. Native/Wasm not run.
- Full ProtoBench class, back to back against `c65bb5c34b`, `-f 1` screen: every row at
  parity or better. The one suspect, suspensionBaseline +3.7%, dissolved at `-f 3`:
  73.7 +-1.9 vs 74.6 +-2.0, overlapping bands. Two rows improved beyond noise at `-f 3`:
  continuationBodiesFuse 10.6 -> 8.6, trailingMapsStayLinear 297.7 -> 291.8. The settled
  arm restructure (region exit) and the Park guard cost nothing measurable anywhere else;
  handleLoop rows hold at ~40.5, emitting at 79.1.

## What the port to your tree touches

Six files, all shared, ready to apply with the Edit tool on your go (diff:
`git diff c65bb5c34b probe/contextual-isolate` from the main repo):

1. `Handler.scala`: `join` drops Result (`(current, forked, result) => State < S`);
   `done` final on HandlerContext; `import kyo.bug`.
2. `ContextEffect.scala`: `onJoin` default `(current, _, _) => current`; glue `join`
   passthrough; glue `done` removed.
3. `KyoInternal.scala`: the `Snapshot`/`SnapshotWith` node after `SuspendContextWith`.
4. `Eval.scala`: Snapshot arm; settled-arm restructure (context exit passes through);
   empty-entries Park guard arm.
5. `EffectTrace.scala`: Snapshot trace arm (exhaustivity).
6. `Isolate.scala`: the Contextual implementation replacing the TODOs.

New casts for sign-off (both erasure-forced at the eval boundary, same category as the
neighboring casts in their arms): `res.asInstanceOf[Y < Any]` in the settled arm's context
branch (replaces the identical-strength cast previously hidden inside the erased `done`
call), and `kyo.value.asInstanceOf[T < S2]` in the empty-Park guard.

Probe-only, not part of the port: `ScratchPolyBench.scala`.

## Open threads (unchanged from last night)

HandlerCont lane burst treatment (poly ~400); emitting 79.1 vs the 67.7 pre-fix best;
crossings still on the fallback lane; Native/Wasm validation; `Debugger.enabled` back to
true when you want it.
