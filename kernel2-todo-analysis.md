# kernel2 review issues: tracker

State as of this morning: the authorized queue is COMPLETE. Every issue below is
either done (committed with the full kernel2 JVM suite green), closed by your ruling,
or waiting on you. Nothing outside kyo-kernel2 was touched.

Working rules in force: "fix" authorizes without re-asking, one issue per green-suite
commit; the tracker carries only what needs your attention; every issue is its own
item; all changes confined to kyo-kernel2; every command logged and watched.

# Needs your attention

## 18b. Binding-scope encoding: one ruling closes the round
- Full context: `kernel2-threading-round-report.md` section 4 (and the design doc's
  section 2.3a). The context threading you approved is implemented, benchmarked,
  and committed; the suite is 617 of 618 green. The one red test is structural: a
  binding installed OUTSIDE an operation handler must be visible to the handler's
  CLAUSE (old-kernel scoping by wrapper nesting), but pure installation makes
  outside-installed and inside-installed bindings produce byte-identical chains,
  so no dispatch rule can scope clauses correctly for both.
- Your ruling picks the encoding:
  1. A nesting-preserving `Bound(inner, binding, cont)` node, the old kernel's
     wrapper made explicit: exact old-kernel scoping; touches drive and dispatch.
     My recommendation.
  2. Entry and exit markers in the chain with a scope-depth walk at dispatch:
     keeps the flat chain, fragile under dispatch's chain surgery.
  3. Prefix-fold at dispatch: smallest change, fixes the failing case, leaks
     inside-installed bindings into clause scope (documented divergence from the
     old kernel in exactly the indistinguishable case).

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
