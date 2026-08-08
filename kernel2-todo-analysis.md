# kernel2 review-TODO analysis

Source: the 31 review notes added to `kyo-kernel2` sources (30 `TODO`s plus the
`// Is this ArrowHandler?` margin note in Handler.scala). Per instruction, each note is
treated as a category, swept across the whole module, not just the line it sits on.
Every proposed change below is pending user validation; nothing has been changed yet.

## Key facts established during analysis

1. **`Kyo.lift` is a real bug, and `liftSlow` is not an artifact.**
   `Kyo.lift` is a bare cast (`v.asInstanceOf`): lifting a value that is itself a
   suspension produces a `A < S < S2` whose inner suspension sits at the outer level,
   so `evalNow` reports `Absent` and `eval` of the outer runs the inner. Correct
   dynamic nesting is exactly what `liftSlow` does (box `Kyo`/`Nested` values in
   `Kyo.Nested`). The implicit lift macro already routes its `Nested`/`DefaultLift`
   modes through `liftSlow`. So: `Kyo.lift` is deleted, `liftSlow`'s logic becomes the
   one canonical explicit lift, owned by the `<` companion. Only tests call `Kyo.lift`
   today.
2. **`stride`/`period` ARE used today, but die with the Safepoint drive integration.**
   `driveLoop` counts `n` down per `Defer` bounce and polls `preempt()` at 0, resetting
   to `stride - 1`; `period` feeds `stride = max(1, period / Arrow.Period)`. Since
   `optimize` already splices a segment boundary (a `Defer`) every `Arrow.Period = 512`
   frames, each bounce is already the right cadence, and the whole
   `preempt: () => Boolean` + `period` + `stride` + `neverPreempt` plumbing is a
   stand-in for the built-and-tested Safepoint machinery (`Safepoint.preempted` /
   `clearPreempt`), the recorded next integration step. Fixing the "unused?" TODOs
   correctly means doing that integration, not deleting parameters piecemeal.
3. **`finalize` is not a usable name.** An extension method named `finalize` is
   permanently shadowed by `java.lang.Object#finalize` (member selection wins), so
   `self.finalize` would never resolve to it. Proposed alternative: `abandon`, which
   the method's own doc comment already uses ("the abandon trigger").
4. **`unsafeGet` is a proto1 leftover.** It exists in `kyo/proto/pending.scala`, not in
   the current kernel's surface; kernel2's only caller is one test.
5. **The truncated TODO** (`Kyo.scala:76`, "// TODO how about a single ") sits on
   `ContextSnapshot`, right after the TODO saying context reads should be handled with
   a threaded context param + `Defer`. Read as "how about a single mechanism/suspension
   for context reads and snapshots"; needs user confirmation.
6. **kyo-data `KyoException` vs kernel2 internal `KyoException`**: same simple name,
   different things. kyo-data's is the public user-facing exception; kernel2's is the
   suppressed-exception trace carrier attached for stack enrichment. They must not be
   conflated; the internal one gets renamed (proposal: `EffectTrace`), which also
   subsumes deleting the `Trace` stub, since this machinery IS the new trace mechanism.
7. **TypeMap fitness**: kyo-data `TypeMap` (TreeSeqMap-backed, typed get/add/union)
   covers most of `Context`, but `inherit` (filterNot by tag subtype) and
   `set`-with-NoninheritableFlag need `private[kyo]` additions to TypeMap. Feasible;
   note TreeSeqMap preserves insertion order where the current `Map` does not
   (harmless, arguably better).
8. **Old-kernel Loop drivers** are `@tailrec` on the immediate-outcome path and wrap a
   suspension exactly once in a re-entering continuation node. kernel2's drivers
   allocate an `Arrow.Transform` per iteration through `map`, even for immediate
   outcomes.
9. **`Arrow.of` is a pure upcast** (`of(t: Transform) : Arrow = t`); every one of its
   12 call sites compiles without it since `Transform extends Arrow`.
10. **`LastResort | Null = null`** also violates the standing "always Maybe, not null"
    rule; the fix folds it into the typed-handler redesign as a proper boundary-handler
    node passed as `Maybe`.

## Change items

Each item is one iteration unit. "Covers" cites the notes by file:line as they stand in
the working tree.

### C1. Lifting consolidation: `Lifting` superclass, delete `Kyo.lift`, canonical `nest`/`unnest`
Covers: Pending.scala:29, Kyo.scala:24, Pending.scala:91, Pending.scala:160.
- New `private[kyo] abstract class Lifting` (internal/Lifting.scala) holding: implicit
  macro `lift`, `liftAnyVal`, `liftUnit`, `abortCastUnit` + macro, `liftPureFunction1-6`,
  the `Render` given; `object <` extends it. Implicits inherited into the companion
  remain in implicit scope.
- Delete `Kyo.lift` (bug, fact 1). The canonical dynamic-nesting lift is the current
  `liftSlow` logic; proposed name `nest` (on the `<` companion / `Lifting`), paired with
  renaming `Kyo.unwrap` to `Kyo.unnest` (stays `@static` for the bytecode reasons in its
  comment). All 20 `liftSlow` call sites and the LiftMacro `defaultLift` route through it.
- Tests using `Kyo.lift` switch to the new explicit route.
- DECISION: name for the explicit lift (`nest` proposed).

### C2. Forwarding elimination sweep
Covers: Pending.scala:322 (unsafeGet), :394 (neverPreempt), :492 (driveInstalled),
Effect.scala deferInline, Arrow.of (Pending.scala:797 and 11 more sites), Handler.scala:30
(aliases; folded into C3).
- Delete `unsafeGet` (fact 4); the one test asserts via `eval`/`evalNow`.
- `neverPreempt`: single `private[kyo] val` (no `def` forwarding a private `val`).
  (Disappears entirely in C15.)
- Delete `driveInstalled`; `install` in ArrowEffect calls the drive directly.
  `drivePartial` likewise collapses into its single caller.
- Delete `Effect.deferInline`; only `defer` remains. Swap-round note: old-kernel call
  sites of `deferInline` migrate to `defer` (source-compatible by-name).
- Delete `Arrow.of`; construct the `Transform` where the `Arrow` is expected.

### C3. Typed Handler hierarchy
Covers: Handler.scala:30, :37.
- `Cont`, `Resume`, `Stop`, `First`, `Loop`, `Context` become generically typed classes
  (e.g. `Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2]` storing the user clause at
  its public type). The `Clause`/`InputClause`/`LoopClause` aliases are deleted.
- ArrowEffect handle methods stop minting erased adapter closures per call; they store
  the user clause directly. The unavoidable erased boundary concentrates at the
  dispatch tag-match in Pending.scala, where each cast is justified by the matched tag
  (as the existing doc already argues).
- Side effect worth measuring: removes the per-handle adapter allocation; re-run JMH.

### C4. Handler kind naming
Covers: Handler.scala:27 margin note ("Is this ArrowHandler?").
- Rename `Handler.Operation` to reflect the effect kind it interprets; proposal:
  `ArrowHandler`, and `Handler.Context` to `ContextBinding` (also fixes the confusing
  `Handler.Context` vs `internal.Context` name overlap).
- DECISION: final names.

### C5. Type-parameter naming + variance sweep
Covers: Kyo.scala:44, Arrow.scala:209.
- Convention: values `A, B, C...`, effects `S, S2, S3...` module-wide.
- `Suspension[X, -E]` becomes `Suspension[+A, -S]` (covariance verified feasible: `A`
  only occurs in contra-of-contra positions).
- `Continue[X, +B, -S]` becomes `Continue[A, +B, -S]` (pivot stays invariant: occurs in
  both `Suspension[A, ?]` and `Arrow[A, B, S]`).
- `Offset[-A, X0, +B, -S]` becomes `Offset[-A, B, +C, -S]`; `Step`'s abstract type
  member `X` renamed (proposal: `Mid`); `isEmpty[X, Y, Z]` becomes `[A, B, S]`.

### C6. Verb consolidation: drive/dispatch to eval
Covers: Pending.scala:486.
- `driveLoop` to `evalLoop`; `dispatch` to `evalSuspension`; `dispatchControl` to
  `evalOperation`; `dispatchLast` to `evalBoundary`. `drivePartial`/`driveInstalled`
  are already gone via C2. `resolveContext`/`snapshotContext` keep the resolve prefix
  (they compute values, they do not evaluate computations) unless the user wants those
  under eval too.
- DECISION: final names.

### C7. toString sweep
Covers: Kyo.scala:37.
- `Nested(value)` with the value rendered; `Defer`, `Offset`, `Continue` and any other
  bare-constant `toString` get shape + content (frame positions where available),
  matching the informative style of `Suspend`/`ContextRead`/`Transform`.

### C8. Remove `(x: Any) match` widenings
Covers: Pending.scala:120 (and the same pattern at flatMap/andThen/unit, Observe.run,
eval, Effect.catching, Loop drivers, Arrow run sites).
- Match the scrutinee directly with `@unchecked` type patterns. Note: outside
  Pending.scala the `<` opaque is not transparent (Effect.catching, Loop); direct
  matching against class patterns still compiles there since the opaque bound erases,
  with the `@unchecked` annotation carrying the justification. Verified per site during
  implementation; no widening remains.

### C9. Principled `prepend`
Covers: Kyo.scala:50.
- `prepend` is only ever called with pass-through interceptors (`Catching`, `Observe`),
  which is why `Suspension.prepend`'s cast is sound but looks wrong. Introduce
  `Arrow.Interceptor` (a `Transform[Any, Any, Any]` subtype for value-pass-through
  wrappers) as `prepend`'s parameter type; `Suspension.prepend` then composes it
  without the unexplained cast.

### C10. `discard` rename
Covers: Pending.scala:104.
- `finalize` impossible (fact 3). Proposal: `abandon`.
- DECISION: `abandon` vs keep `discard`.

### C11. Observe extraction
Covers: Pending.scala:333.
- New `internal/Observe.scala`: `private[kyo] object Observe` with `apply` (current
  `observe`) and the `Observe` transform class. Tests covering it move to
  `ObserveTest.scala` per the test-file naming rule.

### C12. Trace-carrier rename + Trace stub deletion
Covers: internal/KyoException.scala:12, internal/Trace.scala:9.
- Rename internal `KyoException` to `EffectTrace` (fact 6). Delete `internal/Trace.scala`;
  `runDetached` loses the `Trace` parameter (its real trace enrichment is the
  `EffectTrace` machinery, which already travels with exceptions).

### C13. Boundary snapshot as a value
Covers: Isolate.scala:106.
- Replace the callback shape `runDetached(f: (Trace, Context) => A < S)` with a direct
  value: `private[kyo] def snapshot(using Frame): Context < Any` (the `ContextSnapshot`
  suspension with `.inherit` applied), so kyo-core/IOTask write
  `Isolate.internal.snapshot.map(ctx => ...)`. Shape to be validated against the actual
  IOTask adaptation at swap.
- DECISION: confirm the value-shape API.

### C14. LastResort becomes a typed boundary handler
Covers: Pending.scala:481.
- The boundary clause carrier cannot vanish (handlePartial's clause must reach the
  drive) but stops being a nameless one-off: it becomes a typed node in the Handler
  hierarchy (with C3), threaded as `Maybe[Handler.Boundary]` instead of
  `LastResort | Null = null` (fact 10).

### C15. Preemption plumbing: Safepoint integration replaces preempt/period/stride
Covers: Pending.scala:385, :505, :394, Arrow.scala:34.
- `evalLoop` polls `Safepoint.preempted` at each `Defer` bounce (cadence is already one
  bounce per 512 frames by construction, fact 2) with the consume-then-check protocol
  at boundary drives and cascade for installed-handler drives.
- Deletes: `preempt`/`period`/`stride` parameters, `eval(preempt, period)` overload,
  `never`/`neverPreempt`, and `handlePartial`'s `preempt`/`period` parameters (the
  scheduler requests preemption via `Safepoint.preempt()`).
- `Period` moves to `Safepoint` (it is the preemption-granularity knob); Arrow's
  segment cadence references it there.
- This is the already-recorded drive-integration round; doing it here closes these
  TODOs at the root instead of cosmetically.

### C16. Single-allocation defer
Covers: Effect.scala:80.
- Reshape `Kyo.Defer` so `Effect.defer` mints one anonymous class (old-kernel
  `KyoDefer` style): `Defer` gains an abstract `run(): A < S`; the (value, cont) form
  becomes a concrete subclass used by rescue/segmentBoundary; `map`/`prepend` compose
  via a `Chained` subclass. `evalLoop`'s Defer arm calls `run()`.
- JMH after: deepBind and suspension rows are sensitive to Defer allocation.

### C17. Loop driver optimization
Covers: Loop.scala:76.
- Port the old kernel's driver shape (fact 8) to every kernel2 driver (apply 1-4,
  indexed 1-4, and the rest of the surface): `@tailrec` on immediate outcomes, a single
  re-entering continuation node on suspension. JMH plus the LoopTest oracle validate.

### C18. Context machinery redesign: threaded context param + Defer-based reads
Covers: Kyo.scala:64, Kyo.scala:76 (truncated, fact 5), Pending.scala:747, Arrow.scala:16.
- Benchmarks first (the TODO asks for them): add JMH rows for context read resolution
  (shallow/deep chains, repeated reads) to quantify the chain-walk cost.
- Design: thread `Context` as an `evalLoop` parameter, old-kernel style; `ContextRead`
  and `ContextSnapshot` stop being distinct chain-walking suspensions and resolve
  against the threaded context at `Defer` cadence; `Handler.Context` delimiters update
  the threaded value when installed/crossed; park/resume must preserve bindings (the
  delimiters stay in the chain as the durable carrier).
- `hasHandler` (Arrow.scala:16) is re-evaluated inside this design: its remaining
  consumer after the redesign is operation dispatch; measure the per-node field cost
  against the walk savings and decide keep/drop/retype.
- This is the largest item: design writeup + benchmarks land for approval before code.

### C19. Context to TypeMap
Covers: internal/Context.scala:16.
- Migrate `Context` to kyo-data `TypeMap` with `private[kyo]` TypeMap additions for
  `inherit`-style filtering and flag-maintaining `set` (fact 7). Sequenced after C18's
  design settles what Context must support.

### C20. Visibility audit
Covers: Arrow.scala:27.
- `Step` becomes `private[kyo]`; same audit over the rest of the Arrow/Kyo surface
  (`step`, `stepSlow`, `optimize`, `isEmpty` already private[kyo] or become so).
  `Arrow` itself stays public: it is the continuation type in the public handler model
  (`handleFirst`/`handlePartial` signatures).

## Suggested iteration order

Mechanical and low-risk first, then structural, then the design rounds:
C2, C5, C6, C7, C8, C10, C20 (mechanical/naming) -> C1, C9, C11, C12 (structural) ->
C3+C4+C14 (typed handlers cluster) -> C13 (boundary API) -> C16, C17 (allocation/perf)
-> C15 (Safepoint integration) -> C18 -> C19.
Full kernel2 suite green plus JMH comparison after each perf-relevant item
(C3, C15, C16, C17, C18).
