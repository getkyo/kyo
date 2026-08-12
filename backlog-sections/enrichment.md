## Exception enrichment

Failures carry effect-level frames again, reconstructed from the live chain at the catch rather
than recorded as they run. No `Safepoint` field, no ring, no pool, no per-platform file.

### Mechanism

The walk, entered from a catch with the loop's own `v` and `hs`, emitting innermost first.

```scala
// role-tagged entry: Suspend.map's product IS an Arrow.AndThen (KyoInternal.scala:70),
// handleWith's IS a Transform + Handler.Cont + Handled (ArrowEffect.scala:89).
private def reconstruct(v: Any < Nothing, hs: Handlers, out: Builder): Unit =
    v match                                    // 1. the failing node's own frame
        case s: Kyo.Suspend[?,?,?,?,?,?] => emit(s.frame); walkArrow(s.cont, out)
        case d: Kyo.Defer[?,?,?]         => walkArrow(d.cont, out)   // carries none
        case _                           => ()
    cells(hs)  // 2. @tailrec outward: emit handler.tag.show, walkArrow(exit), prev

// AndThen -> push b, push a; Step -> emit head.frame, push tail; Transform -> emit frame.
// Worklist, never `a.step`: that clears the shared scratch (Arrow.scala:42-44, 113-114) and
// mints a Step per node. Frame.internal skipped by identity (Frame.scala:126-135).
private def walkArrow(a: Arrow[?,?,?], out: Builder): Unit
```

Frames out: `Arrow.Transform.frame` (`Arrow.scala:70`) per step, `Kyo.Suspend.frame`
(`KyoInternal.scala:48`, via `root` at `:51, 65`, so the operation's site not an intermediate
`map`'s), `Handler.tag` (`Handler.scala:10`) per region. The op label is `Frame.calleeName`
(`Frame.scala:67-80`), so its `(String, Frame)` pair goes. The cap is the walk's carrier.

The carrier keeps the deleted type and constructor form (`5d5d3e613f^:.../EffectTrace.scala:11`):

```scala
final private[kyo] class EffectTrace extends Exception(null, null, false, false):
    var elements: Chunk[StackTraceElement] = Chunk.empty   // already synthesized
    var dropped:  Int                      = 0
```

Three roles: idempotence marker (presence in `getSuppressed`), accumulation across nested
drives, programmatic read (`getMessage`, plus `fiberTrace`). No `installed` cursor: a
reconstruction is written once, never revised. Not per-step accumulation: `evalLoop` starts every
drive at `Empty` (`Eval.scala:118`), so an inner drive's `hs` excludes the outer's regions and
each crossed boundary appends outward, bounded by drive nesting plus guard count.

The splice, once, at the outermost kernel exit.

```scala
ex.setStackTrace(carrier.elements.toArray ++ ex.getStackTrace.filterNot(kernelPlumbing))
```

Order matters: the old kernel found its splice point by matching file name and line against the
first synthesized element (`origin/main:.../Trace.scala:194-196`), which is what fails on JS and
why three `TraceTest` cases are `pendingUntilFixed`. Leading with the synthesized frames leaves
no position to find, so the JS cases become real assertions. `kernelPlumbing` covers
`kyo.kernel.` and `kyo.Arrow` (the prototype's `:55` predicate is short now), never user anons.

Attach at every guarded arm, splice at the two exits: the drive boundary (`Eval.scala:15-33`) and
`catching`'s arms before `f(ex)` (`Effect.scala:20, 44`), where the old kernel put
`Safepoint.enrich` (old `Effect.scala:50, 59`). Skip fatal (tested inside `attach`, not as a catch
guard, so propagation is unchanged) and `NoStackTrace` (`KyoException.scala:26`); `Abort` failures
are values and never reach a catch site.

The `Eval` arms: per-arm `try` excluding the tail call, so `@tailrec` holds, no pair return.

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    val next =
        try walk(kyo.cont, kyo.value)
        catch case ex: Throwable => EffectTrace.attach(ex, kyo, hs); throw ex
    loop(next, hs)
```

Six regions cover the handler clauses (`Eval.scala:51, 73, 92`), the dispatches
(`:65, 83, 98, 116-117`), and the unhandled-suspension throw (`:48`). Nothing on the non-throwing
path; the cost is `kyo` and `hs` live into the handler. Allocation comes back byte-identical.

### Findings

**Accumulate-at-catch is dominated here.** Two `try/catch` sites exist module-wide
(`Effect.scala:19-20, 43-44`); the prototype's fidelity came from the per-transform catches in
`Arrow.Step.run` and `guardedRun` (`5d5d3e613f^:.../Arrow.scala:73, 123`), so restoring it puts a
`try/catch` in every inlined per-site expansion. At the sites that exist it yields one frame per
boundary, a strict subset of the walk there.

**The physical stack is complementary, not redundant.** `javap` on the tree's compiled tests
confirms the per-site Transform is an anon class in the user's own compilation unit
(`kyo.kernel.EffectTest$$anon$1`, `LineNumberTable line 54`), step body lifted onto the user's
class at the same line. Fused segments already read as user source; the walk covers only what
crosses a suspension, a `Defer` bounce, or region nesting.

**Honestly lost: pre-suspension history.** An applied continuation arrow is unreachable, so no
try/catch architecture recovers steps from before a suspension; the old ring did, at 16.

**`fiberTrace` unifies.** The same walker over `curr` replaces `curr.toString`
(`kyo-core/.../IOTask.scala:53-63`) and unignores `IOTaskTest.scala:12, 38, 57, 81, 106`. Keep the
throw containment: `curr` is mutable even though everything it points at is not.

### Rulings

1. **Name.** Reuse `EffectTrace` (ruled at `43d5c021a7`), or split a second name since the walker
   is also `fiberTrace`. Recommend reuse.
2. **Pre-suspension history.** Accept the loss, or the ring returns in some form. Recommend
   accept: a stack trace never shows returned calls, and the walk gives the active chain.
3. **`NoStackTrace`.** Skip splice and carrier both, or skip only the splice so the frames stay
   readable as data on a `KyoException`. Recommend skip only the splice.
4. **Cap.** One total cap, drop-newest (the walk just stops), or a reserve so region cells always
   emit when a deep chain fills the budget. Recommend drop-newest at 64.
5. **`addSuppressed` on JS and Wasm.** Proven on JVM and Native only; fallback is splice-only,
   costing idempotence detection and the programmatic read. Ruling needed only if the link fails.
6. **`Debug.trace`.** No drive hook needed, but `catching`'s one-layer rewrite
   (`Effect.scala:28-76`) must be exposed taking a per-step function, of which `catching` becomes
   an instance. Approve that one name, or drop `Debug.trace`; it survives suspensions, not forks.
7. **Arm scope.** Six regions, or three (`:51, 92, 48`) if the board moves. Confirm the set before
   measuring so the A/B answers the right question.
8. **Pre-existing, unrelated.** `Eval.apply` and `Eval.partial` (`Eval.scala:15-33`) call
   `Safepoint.save`/`restore` with no `try/finally` and `partial` arms the slot at `:28`, so an
   escape leaves the thread's budget and armed bit as the aborted drive left them. Own fix.

**Status:** design only, nothing implemented. Full argument, attach-point inventory, and test
plan in `exception-enrichment-design.md`.
