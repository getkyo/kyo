# kernel2 review issues: tracker

State as of this morning: the authorized queue is COMPLETE. Every issue below is
either done (committed with the full kernel2 JVM suite green), closed by your ruling,
or waiting on you. Nothing outside kyo-kernel2 was touched.

Working rules in force: "fix" authorizes without re-asking, one issue per green-suite
commit; the tracker carries only what needs your attention; every issue is its own
item; all changes confined to kyo-kernel2; every command logged and watched.

# Needs your attention

## 18c. Rotation implemented: review the landed round
- On your "proceed", rotation landed in the real kernel end to end: handle
  loops with Rotate steps replace the chain search; the Handlers parameter (a
  Chunk of fun-format handlers, maintained like the context) answers their
  operations locally; bindings, catching, and observation share the one
  mechanism; prepend, Interceptor, ContextBinding, evalOperation, prefixArrow,
  and hasHandler are deleted.
- Suite 623 of 623, including the former context-scope red and five new pins
  carrying the old-kernel-validated values. Board: pure-path rows identical;
  dispatch rows 2.8x to 4.4x faster with allocation halved or better; one noted
  regression, contextRead100 at +11% time and +8 B per read (rotate node per
  read bounce), routed to the optimization round.
- Semantics resolution to be aware of: handling is eager again (old-kernel
  construction-time acting); two tests that pinned lazy installation were
  re-pinned accordingly.

## 27. `object Kyo`'s utility surface belongs at `kyo.Kyo`
- Blocked by design until the swap round: the kyo-test runner classpath carries the
  old kernel, and `kyo.Kyo` would collide (the same collision that kept the package
  `kyo.kernel2`). The move is mechanical when the old kernel leaves the classpath.

# Done: the overnight ledger (each with the full suite green)

| # | Issue | Commit |
|---|-------|--------|
| 2 | Forwarding methods removed | `564dc4cc9f` |
| 5 | Type parameter naming, Suspension covariance | `9b47ace3af` |
| 6 | drive/dispatch verbs consolidated to eval | `04adb36022` |
| 7 | Informative node renderings, depth-bounded (kyo-test hang diagnostics render computations; unbounded toString wedged the runner; AndThen carried the same latent hazard) | `8ca699fac0` |
| 8 | `(x: Any) match` widenings removed | `eba87074f4` |
| 10 | `discard` renamed `finalizeBracket` | `a9d5624818` |
| 25 | `Chunk` for `List` in the finalizer family (your instruction) | `7ef7fca9de` |
| 1 | `Kyo.lift` explicit nesting body, `Implicits` base class in `Implicits.scala`, `unwrap` renamed `unnest` | `40b715c811` |
| 20 | `Step`/`step`/`handlePartial` restricted to `private[kyo]` | `3fa2cad56f` |
| 11 | `Observe.apply` in its own file with an effectful observer | `32f78f8e3b` |
| 12 | Trace carrier renamed `EffectTrace`, `Trace` stub deleted | `43d5c021a7` |
| 9, 28 | Typed `prepend` via `Arrow.Interceptor` (one documented cast); `runDetached` moved to `ContextEffect` | `8440e2e013` |
| 14 | `LastResort` removed: handlePartial loops around the plain drive | `25bb4f1f07` |
| 3, 4, 29 | Typed handlers storing clauses at their public types, `ArrowHandler`/`ContextBinding` naming, PURE INSTALLATION (no evals at handle sites), clause throws unwind to the enclosing Catching | `daf66e9585` |
| cast sweep | Subtyping-proven casts removed, `fromKyo` conversions instead of node casts, nesting-safe lift in Observe; the rest sit under justification comments | `c0229dfe1c` |
| 26 | Node hierarchy moved to `kyo.kernel2.internal` (old-kernel layout) | `9118d5a4b4` |
| 15, 21, 23 | Preemption integrated: drives poll the Safepoint in three modes (Preemptible/Masked/Cascade), `Parked` renamed `Preempted` with total fields, Overflow deleted for a detached cell-backed fallback that keeps delivery, slice deadline for single-threaded runtimes, masked bracket releases; all preempt/period parameters gone | `863a0b87f0` |
| 17 | Tailrec loop drivers, allocation only on suspension (benchmarked first) | `ef7d29c956` |
| 18 | Context threaded as an execution parameter, old-kernel faithful: reads as Defers, bindings as typed re-arming interceptors, drives supply the context, public application defers, ContextRead/ContextSnapshot and the chain walks deleted, bracket release under region interceptors; measured better-or-equal everywhere except JIT-profile shifts on dispatch rows proven allocation-identical by probe (report section 2) | `c1c1a6f09a`, `94b020c89b`, `0496b6b1c7` |
| multi-shot combinators | All collection combinators replay-safe (indexed Chunk snapshots, immutable loop state, your ruling); reproduction-first, plus the finding that nested-resume multi-shot diverges identically on BOTH kernels (spliced continuations, conformant), guards use the terminating captured-continuation shape | `c68ac9ce1c`, `208134314f` |

Closed by your rulings: #13 (track C boundary doc rejected; the boundary rides #18's
context threading), #16 (single-allocation defer dropped; unit-fixed constructor
nicety noted), #19 (Context/TypeMap dropped entirely), #22 (Maybe slots dropped),
#24 (clearPreempt analysis accepted; its nested-boundary rule is enforced by the
mode split: only handlePartial consumes).

# Performance record

After the preemption integration (vs the interim conformance ledger):

| row | ledger | now |
|-----|--------|-----|
| eagerMap5 | 5.67 ns/op | 4.62 ns/op (Overflow deletion made `thread` total, removing a hot-path type test) |
| suspension | 480 ns / 2,136 B | 441 ns (typed handlers removed the +16 B dispatch closure) |
| state10 | 826 ns / 4,576 B | 828 ns / 4,560 B |
| narrowIter | 2,539 ns / 12,040 B | 2,517 ns / 12,024 B |
| stateMap10k | 2.12 ms | 2.06 ms |
| deepBind10k | 68.5 us | 66.6 us |
| resumeFused | 18.5 ns | 18.4 ns |

eagerMap5 and resumeFused remain allocation-free; the +0.9 ns on eagerMap5 versus
the 3.71 no-preemption floor is the preemption capability's measured price.

Loop drivers (issue 17, before vs after the tailrec port):

| row | before | after |
|-----|--------|-------|
| loopPure10k | 76,883 ns / 320,344 B | 18,912 ns / 160,008 B (4.1x; the remaining 16 B per iteration is the outcome carrier) |
| loopSuspend1k | 63,922 ns / 379,934 B | 71,439 ns / 379,938 B (about 7.5 ns per resumed iteration from re-entry type tests, on a dispatch-dominated path; recorded honestly) |

# Simplification round: typed inputs, Kyo reorganization, central lift

State as of this round (all changes in kyo-kernel2):

- Transform.run takes its typed input again: `run[C, S2](v: A, ...)`. The erased
  `v: Any` parameter was an allocation-avoidance trick and is removed per ruling;
  minted transforms consume `f(v)` directly, ContextEffect reads take `v: Unit`,
  bracket use takes `v: R`, Loop step transforms take their outcome types.
- The user-facing combinator surface moved out of the kernel-internal node file:
  `kyo/kernel2/Kyo.scala` is the public object (combinators only), the node
  hierarchy lives in `internal/KyoInternal.scala` (object `internal.Kyo`).
- Lifting is done only by the pending type's central lift methods. The implicit
  route stays in Implicits.scala backed by LiftMacro; the runtime nesting wrap is
  `LiftMacro.defaultLift` (the macro emits it, kernel raw re-entry sites call it
  directly on their erased currency). Public `Kyo.lift` is the old kernel's
  `= v`: zero cost, and the explicit route for intentional nesting since the
  macro settles pending types at expansion. `Nested` no longer escapes
  kyo.kernel2.internal; `fromKyo` (Kyo => <, free retyping) already exists in
  Pending.scala.
- All `@static` annotations removed (Safepoint had 11, internal.Kyo.unnest had
  one). Root cause of two separate "value X is not a member of object" compile
  mysteries (unnest, then Safepoint.pollPreempt) that survived clean rebuilds;
  empirically tied to @static on object members under sbt/zinc with Scala 3.8.4.

Removed-for-now optimizations to revisit at the end of the work, each only with
a measured win and a compile-stability check:

1. Erased `v: Any` Transform.run parameter (megamorphic call-site erasure).
2. `@static` on hot object members (unnest, Safepoint accessors).

Next round: unsafe-code cleanup. Type the rotate machinery (`Rotate` with real
type parameters instead of `Arrow.Transform[Any, Any, Any]`), the handle loops'
currencies (old-kernel-style existential captures at the tag-matched arm), and
rewrap. Casts remain only at documented tag-keyed and trampoline boundaries. The
Bracket arm's loop-wrapped acquire is a known typing pressure point to resolve
during that round (an aborting ctl handler inside acquire currently completes
the acquire with the handler's value; the typed rewrite must settle the intended
semantics with a pin).
