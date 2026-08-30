verdict: BLOCKED

Scope: two changes, two derivations.

- `reviews/proto-region-stack/derivation.md` against `31a7b4bde9..a10624dfa4`
- `reviews/proto-eval-budget/derivation.md` against `a10624dfa4..ffc1819ecc`

The budget change conforms on all four checks and carries no findings (see "Clean" below).
Every finding here is on the region-stack change.

## C1 the ruled recover-state change contradicts `Handler.recover`'s own stated contract, and the surface excludes the file that states it

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:294`, against
`kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:17-21`

derivation says (`proto-region-stack/derivation.md`, "Ruled, not open"):

> **Recover reads the working state.** Today `recover` is consulted with the state the extent was
> installed with, and `done` with the state the loop has threaded. **That split is not a contract**:
> the guard sits outside `region`, so the working state lives in a frame the throw has already
> destroyed and `st0` is the only state in scope.

and (`proto-region-stack/derivation.md`, "Surface"):

> Does **not** change: `KyoInternal` node classes, **the `Handler` protocol**, `ArrowEffect`,
> `ContextEffect`, `Pending`, `Loop`, `Arrow`, `Effect`, `Eval.release`.

code does: `Handler.scala:17-21`, unchanged by the diff, states the split as a contract, verbatim:

```scala
    /** Consulted when a NonFatal throw unwinds the region's extent, with the state the region was installed with. A Present computation
      * replaces the region's outcome, and what follows the extent still follows; Absent declines, and the failure keeps unwinding through
      * the enclosing regions.
      */
    def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
```

while the eval that consults it now reads the live state off the stack entry (`Eval.scala:289-294`):

```scala
                val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                val state   = stack.state.asInstanceOf[VX]
                ...
                handler.recover(state, ex) match
```

why it matters: the derivation's second check is that each piece maps to an existing value that
"means what the derivation says". `Handler.recover` exists, but it does not mean what the
derivation says it means: the derivation's premise for the ruling is that the installed-state
reading is not a contract, and the kernel source states it as one, in the scaladoc of the very
method the equation names for "failing a region". Before the change the primary guard honored that
sentence (`kyo.handler.recover(st0, ex)` at the `Handle` arm, with `st0` the installed state); after
it, no path does. Because the surface declares the `Handler` protocol unchanged, the shipped tree
documents a contract its only evaluator violates. Either the sentence is amended with the ruling, or
the ruling is not fully realized. Note the ruling's supporting claim about `release` is sound at the
code level: every consultation of `Handler.release` does receive the region's current state, because
a region only becomes releasable through the reification, which writes the live state in. It is only
`recover` whose stated contract is now false.

## C2 a recovery costs Java frames, in the exact currency the change exists to remove, with no ruling

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:286-309`

derivation says (`proto-region-stack/derivation.md`, "The equation"):

> The chain is a value, so it can live on the heap. **The stack is that chain, unrolled.** That is
> the whole change: nothing about what a region means changes, only where the chain is kept.

and (`proto-region-stack/derivation.md`, "Surface"):

> `run`: carries the extent guard, because the per-region `try` dies with `region`.

code does:

```scala
        @tailrec def recovered(ex: Throwable): A < S =
            ...
                handler.recover(state, ex) match
                    case Present(r) =>
                        ...
                        run(r.chain(cont), outer)
                    case Absent =>
                        recovered(ex)
        def run(v: A < S, ctx: Context): A < S =
            val res =
                try loop(v, Arrow.id, Arrow.id, ctx)
                catch case ex if NonFatal(ex) => recovered(ex)
```

why it matters: `run` and `recovered` are mutually recursive, and Scala eliminates only direct self
tail calls, so every recovered region pushes a `recovered` frame and a `run` frame that stay live
until the eval finishes. N regions that fail and recover in sequence cost O(N) Java stack. The
baseline reached the same continuation by a self tail call out of the `Handle` arm, after the
`try`, and was flat for that shape:

```scala
                    val res =
                        try region(st0, kyo.value, Arrow.id, bound)
                        catch
                            case ex if NonFatal(ex) =>
                                val r = kyo.handler.recover(st0, ex).getOrElse(throw ex)
                                Debugger.onRecover(kyo.handler, ex)
                                r
                    Debugger.onRegionExit(kyo.handler, res)
                    loop(res, kyo.cont, contA.chain(contB), ctx)
```

The reference kernel names this exact hazard and engineers around it at the same site
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:742-744`):

> And it cannot recurse, which would read better, because the recursive call would sit inside the
> catch and cost a frame per recovery: ten thousand scopes that fail and recover in sequence would
> overflow. A loop out here costs neither.

The derivation records no ruling on this, and none of its listed pinning tests exercises sequential
failure-and-recovery, so a change whose stated purpose is "cost heap, not stack" ships a new
stack-depth dependency inside its own declared surface. The code's comment at `Eval.scala:284-285`
overstates what it achieves: "without the eval holding a frame per region to consult them from" is
true only of the declining regions the `@tailrec` walk skips, not of the recovering one.

## C3 the diff adds a method and changes a signature that the declared surface does not name

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:286` and `:304`

derivation says (`proto-region-stack/derivation.md`, "Surface"):

> - `kyo/proto/kernel/internal/Eval.scala`, inside `apply` only:
>   - `loop`'s signature, per the section above;
>   - the `Handle` arm: install and continue, replacing `region`;
>   - the settled arm: complete the innermost region and continue;
>   - the `Suspend` arm: after the registers absorb, ask the regions;
>   - `run`: carries the extent guard ... `run`'s existing job, answering a context read that no
>     region answered, is unchanged.

code does: a sixth site inside `apply`, a new method the list does not name,

```scala
        @tailrec def recovered(ex: Throwable): A < S =
```

and a signature change to `run` that the derivation spelled out for `loop` and not for `run`:

```scala
-        def run(v: A < S): A < S =
+        def run(v: A < S, ctx: Context): A < S =
```

why it matters: `loop`'s signature change got its own derivation section precisely because a
signature is a claim about what a method does; `run`'s did not, and it changed anyway. The recorded
ruling under Scope is specifically about this method during this change ("on rewriting `run` while
changing region handling: > you should only update the regions handling?"), with the standing
consequence that an improvement outside the declared surface is still a finding. Both edits are
plausible consequences of moving the guard, which is why this is C3 and not C1, but neither was
derived and the guard bullet as written does not cover them.

## Clean

Checked and conforming, recorded so a re-reader does not redo them.

**The equation holds.** Each clause of "evaluate the innermost thing; when it settles, complete the
enclosing regions from the inside out; when it suspends, let the innermost region whose tag matches
answer it, reifying each region it crosses on the way out" has an operational reading in the code:
the `Handle` arm pushes and tail-calls into the interior with identity registers; the settled arm
fires only with both registers `Id` and completes one entry per pass; the `Suspend` arm absorbs the
registers first, then tests the innermost entry's tag, rebuilds and pops on a miss so the next pass
asks the next region out. The `Debugger` event sequence (`onRegionEnter`, `onForeign`, `onHandle`,
`onResult`, `onRecover`, `onRegionExit`, `onRelease`) is preserved one-for-one against the baseline
at every arm.

**Every piece maps to an existing value.** Verified against the sources: `Kyo.Handle.handler`,
`.state`, `.cont` (`KyoInternal.scala:164-167`) are the three columns pushed at `Eval.scala:256`;
`Loop.Continue2` advances the state in place at `:223`; `Handler.done` at `:265`; the `Loop` done
outcome bypasses `done` at `:227`; `Handler.release` is untouched and still reached through the
node. The `ctxs` column is the fourth, and it is the one the derivation resolves rather than reads
off; it stores the pre-binding `ctx` at push and restores it at every exit, which is what the
baseline's enclosing frame held. `Stack` is a new type and is authorized explicitly, both by the
derivation's own "New type" section and by the recorded ruling under New types, which names `Stack`
as the cast ladder's example. `Safepoint.get`, `.save`, `.restore` exist with the claimed meanings
(`jvm-native/.../proto/kernel/internal/Safepoint.scala:83,197,203`, mirrored in `js-wasm`), and the
reference kernel does perform the same save/restore at its eval boundary
(`kyo/kernel/internal/Eval.scala:722,780`), as the budget derivation claims.

**The surface holds** at file level: `git diff --name-status 31a7b4bde9..ffc1819ecc` reports exactly
`Eval.scala` modified and `Stack.scala` added. Every file the region-stack derivation lists under
"Does not change" is untouched, including the `Handler` protocol (which is C1's problem, not a
surface breach). The foreign-crossing rebuild did move out of `region` substantively verbatim,
differing only in reading `handler`/`state` from the entry instead of the closure and in the type
parameters that erasure forces. `Eval.release` and `answerLoop` are byte-identical. No test file was
touched, and both pinning tests the derivation names still exist unmodified
(`proto/kernel/ArrowEffectTest.scala:285`, `proto/kernel/internal/EvalTest.scala:586`). Within
`apply`, the sites outside the five named bullets are C3.

**Nothing was quietly dropped.** Every row of the mapping table is realized. The `bug` guards
survive relocation: the baseline's `case res: Pending[AX, S] => bug` becomes
`case _ => bug(s"unhandled: $susp")` in the matched-tag branch, and `bug(s"unhandled: ${kyo.handler}")`
becomes `bug(s"unhandled: $handler")`. `Stack` is "four arrays and a size", entries stay columnar,
and `pop` does not clear, all as derived.

**The budget change is clean on all four checks.** Its diff is the whole of its surface: `val slot`,
`val saved` at `apply` entry and `try run(v, Context.empty) finally Safepoint.restore(slot, saved)`.
Three pieces, three existing values, no other file, no fork, nothing dropped.
