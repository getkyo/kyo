verdict: BLOCKED

Round 2. Scope: two changes, two derivations, judged against `31a7b4bde9..1f47d6f590`
(`a10624dfa4` region stack, `ffc1819ecc` eval budget, `1f47d6f590` guard loop / budget reset /
`@tailrec` / doc corrections / new test).

- `reviews/proto-region-stack/derivation.md`
- `reviews/proto-eval-budget/derivation.md`

Round 1's C2 and C3 are closed and verified closed (see "Clean" below). C1 is closed at `recover`
and reopens one method over, as C2 here. The new finding C1 is on the budget change, which was
clean in round 1 and is not clean now: the third commit deleted a piece the budget derivation
names and the file's own comment still claims.

Ids are fresh for this round; they are not continuations of round 1's.

## C1 the `finally Safepoint.restore(slot, saved)` the budget derivation names is gone, and the code still says it is there

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:41-42`, and the
absence across `:285-348`

derivation says (`proto-eval-budget/derivation.md`, "Surface"):

> `kyo/proto/kernel/internal/Eval.scala`, `apply` only:
>
> - resolve the slot, save at entry, **restore in a `finally`**;

and, as the closing sentence of the same section:

> The `finally` is what makes a nested eval leave the enclosing one's budget as it found it, and
> what keeps a throw from leaking the eval's own.

and its mapping table names the value:

> | the caller's budget put back | `Safepoint.restore(slot, saved)` |

code does: `saved` is bound and never read again. `ffc1819ecc` had the restore
(`ffc1819ecc:Eval.scala:320`, `finally Safepoint.restore(slot, saved)`); `1f47d6f590` removed it
along with `run`, and did not put it back. The whole of `Safepoint` usage in the file is now:

```
41:        val slot  = Safepoint.get()
42:        val saved = Safepoint.save(slot)
313:                    Safepoint.reset(slot)
```

(`grep -nE "Safepoint\.(save|restore|reset|drain)"` over `kyo-kernel/` confirms the proto eval is
the only main-source site and that it saves without restoring; the reference kernel still does
both, at `kyo/kernel/internal/Eval.scala:722` and `:780`.)

The file asserts the deleted behaviour verbatim, at `Eval.scala:36-42`:

```scala
        // the depth guard bounds strict recursion within one eval, so the budget is this eval's and
        // not whatever the thread had left. Inheriting a spent one is a fixed point rather than a
        // slow path: every application defers, the settled arm applies the deferral, and its
        // continuation is the application that just deferred. Restored below, so a nested eval leaves
        // the enclosing one's budget as it found it and a throw does not leak this one's
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
```

"Restored below" is false: nothing below restores, and `finally` does not appear in the file.

why it matters: this is the fourth check, a derived piece the code does not implement, with no
recorded ruling anywhere in either derivation. It is not cosmetic. `save` installs `State.init` and
hands back the caller's counter (jvm-native `Safepoint.scala:196-200`); without the restore, a
nested eval returns leaving the enclosing eval's counter replaced by its own leftover, so the
enclosing eval's account of the strict applications still live on the Java stack is lost, and its
`exit`s then increment a counter that no longer corresponds to them. `Safepoint.reset(slot)` at the
guard does not stand in for it: `reset` installs `Initial` (`(self & Armed) | Initial`), which is
not `saved`, and it runs only in the catch. The budget derivation's own "Why this is its own
change" section argues the two changes must be judgeable separately; the third commit made the
region-stack work delete a piece of the budget work, which is the substitution this lens exists to
catch.

## C2 the new `recover` scaladoc asserts a property of `release` that `release`'s own scaladoc denies, and the declared surface forbids fixing it

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:22-23` against
`:27-28`

derivation says (`proto-region-stack/derivation.md`, "Ruled, not open"):

> `release` already reads the current state, and does so structurally: a region only becomes
> releasable by being reified into a node, and the reification writes the live state into it. So
> this change makes `recover` match `release` rather than introducing a new rule.

and (`proto-region-stack/derivation.md`, "Surface"):

> - `kyo/proto/kernel/internal/Handler.scala`, **`recover`'s scaladoc only**. It states the
>   install-time state as the contract, and after this change no path honours it, so the sentence
>   changes with the behaviour.

code does: the diff adds, in `recover`'s scaladoc, a cross-reference that states the premise as
shipped source,

```scala
      * The state is the live one for the same reason [[release]]'s is: a region's state is single-sourced, held by the eval while the region
      * runs and by the node once it is a value, and every consumer reads whichever copy is live when it runs.
```

while the next scaladoc in the same file, untouched because the surface says "recover's scaladoc
only", says the opposite in its first sentence:

```scala
    /** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region was installed
      * with. It runs where nothing is installed to answer for it, so it takes no effects; the default owes nothing.
      */
    def release(state: State, ex: Throwable): Any < Any = ()
```

why it matters: this is round 1's C1 one method over. The second check asks whether a named value
means what the derivation says; `Handler.release` is named in the mapping table ("abandoning a
region") and is the whole support for the recover-state ruling. At the code level the ruling is
sound, and I re-verified it: both consultations pass the live state, the node's
(`KyoInternal.scala:157-162`, `handler.release(state, ex)` off `Handle.state`, which the
foreign-crossing rebuild writes live at `Eval.scala:135`) and the rebuilt suspension's
(`Eval.scala:156-157`, `:172-173`, `:190-191`, reading the stack entry). At the documented level the
value states the install-time contract, which is exactly the defect round 1 raised against
`recover`. The change did not create `release`'s staleness, but it did add the sentence that makes
the two docs contradict each other on the same page, and the declared surface excludes the sentence
that would resolve it. Either the surface widens to cover `release`'s first sentence, or the new
cross-reference cites a contract the tree does not state.

## C3 the mutability concession's scope claim undercounts the guard's mutable locals

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:289-292` and `:314-315`

derivation says (`proto-region-stack/derivation.md`, "Concession: mutability"):

> **The guard's loop.** Justified because the alternative costs a frame per recovered region.
> **Scoped to five locals in `apply`**, none escaping. Protected because `out` is initialised from
> `v` so there is no sentinel and no `Null`, and `settled` is the only exit.

code does: six.

```scala
        var curr    = v
        var ctx     = Context.empty
        var out     = v
        var settled = false
```

and inside the catch,

```scala
                    var ex        = failure
                    var unwinding = true
```

why it matters: "scoped to" is the second of the four parts a concession in this kernel has to
carry, and it is the part a reader checks by counting. The substantive claims hold, verified: none
of the six escapes `apply`, `out` is initialised from `v` so there is no sentinel and no `Null`, and
`settled` is the only exit from the outer loop. The count is the one part of the sentence that is
wrong, and a concession whose stated scope does not match its actual scope is not a concession a
reviewer can check.

## C4 the surface's change list does not name the test file the same derivation requires

site: `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:601-624`

derivation says (`proto-region-stack/derivation.md`, "Surface"):

> Changes:
>
> - `kyo/proto/kernel/internal/Stack.scala`, new. Four arrays and a size.
> - `kyo/proto/kernel/internal/Eval.scala`, inside `apply`: [five bullets]
> - `kyo/proto/kernel/internal/Handler.scala`, `recover`'s scaladoc only.

code does: `git diff --name-status 31a7b4bde9..1f47d6f590` reports a fourth path,

```
M	kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala
```

adding `"regions that fail and recover in sequence cost no stack"` plus a private `Boom` object.

why it matters: the third check is that every file the diff touches appears in the declared
surface, and `EvalTest.scala` appears in neither the "Changes" list nor the "Does not change" list.
This is the mildest finding of the four because the derivation does authorize the artifact, by exact
name and size, in its concession section ("Pinned by `EvalTest` "regions that fail and recover in
sequence cost no stack", 10000 cycles, which fails on the recursive shape"), and the test that
landed is that test. It is a bookkeeping gap in the Surface section, not a substitution: the fix is
to list the file where the other three are listed.

## Clean

Checked this round and conforming, recorded so a re-reader does not redo them.

**The guard costs constant stack for both declining and recovering regions.** This was round 1's C2
and it is closed. `run` and `recovered` are gone; the guard is two nested `while` loops inside
`apply` (`Eval.scala:293-346`) with no call that can grow with the number of regions. A declining
region iterates the inner `while unwinding` loop after `stack.pop()`; a recovering region sets
`curr = r.chain(cont)`, `ctx = outer`, `unwinding = false`, falls out of the catch back into the
outer `while !settled`, and re-enters `loop` from the same frame, not from inside the catch. The
only call in either loop that can recurse is `loop`, which now carries `@tailrec`
(`Eval.scala:44`), so the property is compiler-checked rather than claimed; `sbt
kyo-kernelJVM/Test/compile` is green on the tip, which is that check passing. The shape matches the
one the reference kernel names and engineers around at the same site
(`kyo/kernel/internal/Eval.scala:743-756`), including the reason it cannot be a `try` around
`loop`'s body. The new `EvalTest` case pins it at 10000 sequential recoveries.

**Round 1's C1 is closed at `recover`.** The scaladoc now states the reached state
(`Handler.scala:17-18`), and both consulting paths read it: the guard reads `stack.state`
(`Eval.scala:309`), which the `Continue2` arm advances in place (`Eval.scala:222`), and `reenter`
reads the entry's state captured at reification (`Eval.scala:129-131`). No path reads the install
state any more, so the sentence and the behaviour agree. The residue is C2 above, at `release`.

**Round 1's C3 is closed.** `recovered` no longer exists and `run` no longer exists, so neither the
unnamed method nor the undeclared signature change survives. The derivation's guard bullet was
rewritten to declare what replaced them, including the loop-not-recursion argument and the folding
of the context-default arm ("This is a change to `run` and is declared as one"), and the three
file-level imports are now declared and match the diff exactly (`Maybe.Absent`, `Maybe.Present`,
`scala.annotation.tailrec`).

**The equation holds.** Re-verified arm by arm against the baseline rather than carried over. The
`Handle` arm pushes `kyo.cont.chain(contA.chain(contB))` and tail-calls the interior with identity
registers, which is the same composition the baseline applied as `loop(res, kyo.cont,
contA.chain(contB), ctx)`. The settled arm fires only with both registers `Id` and completes one
entry per pass through `Handler.done`. The `Suspend` arm absorbs the registers first, then tests the
innermost entry's tag, rebuilding and popping on a miss. The context-default arm resumes with
`Context.empty`, as the baseline's `run` did; a recovery resumes with `outer`, which is the
enclosing context the baseline's `loop(res, kyo.cont, ..., ctx)` carried. The `Debugger` sequence is
preserved one-for-one, including `onRecover` before `onRegionExit` on the recovery path and neither
on the declining path.

**One behaviour restored rather than changed.** The inner `try handler.recover(state, ex) catch {
case ex2 => ex = ex2; Absent }` (`Eval.scala:322-328`) is not new behaviour against the baseline:
there, region N's catch was dynamically nested inside region N-1's, so a recover that threw landed
in the enclosing region's guard (`31a7b4bde9:Eval.scala:219-225`). The new sentence in `recover`'s
scaladoc ("A recover that fails itself is the failure those enclosing regions then see") documents
that unchanged behaviour, and both sit inside the declared surface. The intermediate `a10624dfa4`
had lost it; the tip has it back.

**Every piece maps to an existing value.** `Kyo.Handle.handler`, `.state`, `.cont`
(`KyoInternal.scala:164-167`) are three of the four columns pushed at `Eval.scala:257`;
`Loop.Continue2` advances the state at `:222`; `Handler.done` at `:264`; the `Loop` done outcome
bypasses `done` at `:226`; `Handler.release` is untouched and still reached through the node. The
`ctxs` column is the one the derivation resolves rather than reads off, and it does what the
derivation says: pre-binding `ctx` stored at push, restored at every exit including the recovery
exit. `Stack` is authorized explicitly by the derivation's own "New type" section.
`Safepoint.get/save/restore/reset` all exist with the claimed meanings (jvm-native
`Safepoint.scala:83, 191, 196, 202, 205`), which is what makes C1 a drop rather than a missing
value: the value is there and unused.

**Nothing else was quietly dropped.** Every row of the region-stack mapping table is realized. The
baseline's `case res: Pending[AX, S] => bug` is covered by construction rather than relocated:
`Pending`'s sealed subtypes are `Defer`, `Suspend` (three shapes) and `Handle`
(`KyoInternal.scala:42-154`), all matched before the settled arm, and the matched-tag
`bug(s"unhandled: $susp")` catches the non-`SuspendArrow` case the baseline's `Pending` guard
caught. `Eval.release` and `answerLoop` are untouched (the diff's last hunk ends at `end apply`).
Both pre-existing pinning tests the derivation names still exist unmodified
(`proto/kernel/ArrowEffectTest.scala:285`, `proto/kernel/internal/EvalTest.scala:586`), as do the
two multi-shot pins (`ArrowEffectTest.scala:329`, `:778`). `Stack.pop`'s revised scaladoc is inside
a file the surface declares as new, so it needs no separate declaration.
