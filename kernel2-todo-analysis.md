# kernel2 review issues: tracker

State as of this morning: the authorized queue is COMPLETE. Every issue below is
either done (committed with the full kernel2 JVM suite green), closed by your ruling,
or waiting on you. Nothing outside kyo-kernel2 was touched.

Working rules in force: "fix" authorizes without re-asking, one issue per green-suite
commit; the tracker carries only what needs your attention; every issue is its own
item; all changes confined to kyo-kernel2; every command logged and watched.

# Needs your attention

## 18. Context threading design: ready for your review
- File: `kernel2-context-threading-design.md` (opened for you)
- The design replaces `ContextRead`/`ContextSnapshot` and the chain-walking reads
  with a context summary maintained on `Arrow` at composition time, read in one field
  load, handed to a single `Defer`-shaped read node as a parameter, the current
  kernel's shape at exactly one node kind. Quality-passed against everything that
  landed overnight (pure installation, typed handlers, deleted Trace, dropped items
  16 and 19, `runDetached` on `ContextEffect`).
- Its open questions for you:
  1. Naming: `Defer.Step` / `Defer.Read` for the two defer forms, `Detached` /
     `detach` / `attach` for the fork boundary.
  2. Exact-tag binding matches (dropping today's subtype-tolerant read resolution,
     which makes pre-fork and post-fork reads resolve differently).
  3. The pre-existing bracket defect it found (context reads inside acquire, release,
     and discard-path finalizers resolve against the wrong chain): land the fix
     inside this item (my recommendation) or as its own issue.
- On your approval, implementation proceeds benchmarks-first per the doc's section 6.

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
