verdict: BLOCKED

Round 3. Judged against the range I was given, `31a7b4bde9..7a7cd22ad8` (four commits, tip
`7a7cd22ad8`), and the two derivations:

- `reviews/proto-region-stack/derivation.md`
- `reviews/proto-eval-budget/derivation.md`

State I judged, recorded because both moved during the review:

- `derivation.md` at commit `1b60e37aac`, md5 `1ec67efbfee7478b353aa55efc25be50`. The version
  dispatched to me was one commit older; the difference is the "Declared: the context an answered
  operation resumes with" section, treated below.
- the implementation worktree advanced to `d197133298` mid-review, one commit past my range tip.
  That commit adds the test the current derivation names as the context pin. I read it and ran it
  rather than assume it; see "The declared context change".

Round 2 had four findings. One is closed and verified closed. One is closed in the code and left
open in the derivation. Two are unchanged. A fifth issue, which no earlier round of this lens
looked for, is C1 below: the region stack's concession names a pin that does not pass.

Ids are fresh for this round.

## C1 the region stack's concession is pinned by a test that fails

site: `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:586-593`

derivation says (`proto-region-stack/derivation.md:128-135`, "Concession: mutability"):

> **The region stack.** Justified because the region chain is the only thing whose depth was the
> Java stack's. Scoped to one instance per `Eval.apply`, reachable from nothing that leaves the
> eval. Protected because entries are written only by the eval's own arms and hold complete values,
> and a nested eval builds its own. Pinned by `ArrowEffectTest` "handles nested per recursion step
> in bounded stack" (the defect), `EvalTest` "a nested eval shares the thread's stack and sees none
> of the outer regions" (per-eval scoping), and `EvalTest` "the captured continuation is multi-shot"
> with "each shot of a multi-shot capture resumes from capture-time state" (a resumed shot
> re-installs from the node, not from a stack an earlier shot mutated).

code does: the test named for per-eval scoping is red.
`sbt 'kyo-kernelJVM/testOnly kyo.proto.kernel.internal.EvalTest'` at the tip:

```
[info] - a nested eval shares the thread's stack and sees none of the outer regions *** FAILED *** (1 millisecond)
[info]   Expected exception java.lang.Throwable to be thrown, but no exception was thrown (EvalTest.scala:590)
[info] Tests: succeeded 53, failed 3, canceled 0, ignored 0, pending 0
```

The three failures are `EvalTest.scala:97`, `:583` and `:590`. All three assert on a message the
proto's main sources do not produce: `git grep "unhandled suspension" 31a7b4bde9` returns test files
and prose only, no main source, and `Nested.unnest` (unchanged by this diff) ends in
`v.asInstanceOf[A]`, which is where the `:583` `ClassCastException` comes from.

This is not caused by the change, and I verified that with a run rather than by reading. A detached
worktree at `31a7b4bde9`, same command:

```
[info] - an eval inside a map evaluates its argument rather than nesting it *** FAILED *** (4 milliseconds)
[info] - an unhandled operation is a bug *** FAILED *** (3 milliseconds)
[info] - a nested eval shares the thread's stack and sees none of the outer regions *** FAILED *** (0 milliseconds)
[info] Tests: succeeded 51, failed 3, canceled 0, ignored 0, pending 0
```

Identical three, at the identical lines. The change adds two tests and both pass; the count moves
51 to 53 and the failures do not move.

why it matters: "Pinned by" is the fourth part of a concession in this kernel, and it is the part
that says the mutable structure's stated scope is checked by something rather than asserted. Of the
four pins the region-stack concession names, three do pass at the tip and one does not:
`ArrowEffectTest` "handles nested per recursion step in bounded stack", "the captured continuation
is multi-shot" and "each shot of a multi-shot capture resumes from capture-time state" are green
(`ArrowEffectTest`: 88 succeeded, 0 failed, 0 aborted), and the per-eval-scoping pin is red. The
red one is the only pin for the specific property that a nested eval builds its own stack and sees
none of the enclosing one's regions, which is exactly the property that makes a mutable `Stack`
safe to hold across a nested `Eval.apply`. Its first assertion does run and pass
(`eval(outer) == 6`, `:589`), so the property is partly exercised, but the test aborts at `:590`
and `:591-592` never execute, so nothing after that line establishes anything. A derivation that
cites a red test as a pin tells a reviewer the protection is checked when it is not. The fix is
either to make the named test pass, or to name a pin that does, and in either case to say what
happened to the two other red proto tests rather than let the suite ship with them.

## C2 `Handler.release`'s scaladoc is rewritten, and the derivation excludes it in two places

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:27-31`

derivation says (`proto-region-stack/derivation.md:107-110`, "Surface"):

> - `kyo/proto/kernel/internal/Handler.scala`, `recover`'s scaladoc only. It states the install-time
>   state as the contract, and after this change no path honours it, so the sentence changes with
>   the behaviour. Declared here because a contract in a doc comment is still a contract, and the
>   first version of this derivation wrongly listed `Handler` as untouched.

and again (`proto-region-stack/derivation.md:64`, the mapping table):

> | abandoning a region | `Handler.release`, on the node, untouched by this change |

code does: the diff rewrites `release`'s scaladoc, replacing its first sentence and adding a new
claim about reification.

```scala
-    /** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region was installed
-      * with. It runs where nothing is installed to answer for it, so it takes no effects; the default owes nothing.
+    /** The abandonment notification: consulted when a holder gives up on an extent's continuation, with the state the region has reached. A
+      * region only becomes abandonable by being reified into a node, and the reification writes the live state into it, so the node's state
+      * is that state rather than a stale copy. It runs where nothing is installed to answer for it, so it takes no effects; the default owes
+      * nothing.
       */
     def release(state: State, ex: Throwable): Any < Any = ()
```

why it matters: this is round 2's C2, closed on the side the derivation does not govern and left
open on the side it does. Round 2 offered two exits, widen the surface or drop the cross-reference;
the code took the first and the derivation was not updated, so the declared surface now excludes
the very edit that landed, and the mapping table still calls `release` untouched. The new sentence
itself is correct and I checked it: `loop` returns a bare suspension only when the stack is empty
(`Eval.scala:128`) and returns a settled value only when the stack is empty (`Eval.scala:266`), so
every region is completed or reified before the eval returns, and the reifying sites write the live
state (`Eval.scala:142`, `:162`, `:179`, `:197`, all reading the entry's `state`). The finding is
the declaration, not the sentence. By the derivation's own standard in the same bullet, a contract
in a doc comment is a contract, so a second contract sentence changed in the same file is a second
declaration owed.

## C3 the guard's concession still counts five mutable locals where the code has six

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:295-298` and `:321-322`

derivation says (`proto-region-stack/derivation.md:137-140`, "Concession: mutability"):

> **The guard's loop.** Justified because the alternative costs a frame per recovered region. Scoped
> to five locals in `apply`, none escaping. Protected because `out` is initialised from `v` so there
> is no sentinel and no `Null`, and `settled` is the only exit.

code does: six. `grep -n "var " Eval.scala` at the tip returns, outside `loop`,

```scala
        var curr    = v
        var ctx     = Context.empty
        var out     = v
        var settled = false
```

and, inside the catch,

```scala
                        var ex        = failure
                        var unwinding = true
```

why it matters: unchanged from round 2's C3, which is why it is worth restating rather than
dropping. The substantive halves of the sentence hold and I re-verified them at the tip: none of
the six escapes `apply`, `out` is initialised from `v` so there is no sentinel and no `Null`, and
`settled` is the only exit from the outer `while`. The count is the one part a reader checks by
counting, and it is the one part that is wrong. A concession whose stated scope does not match its
actual scope cannot be checked by the reader it is written for.

## C4 the declared surface still does not name the test file the diff changes

site: `kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala`

derivation says (`proto-region-stack/derivation.md:89-113`, "Surface"):

> Changes:
>
> - `kyo/proto/kernel/internal/Stack.scala`, new. Four arrays and a size.
> - `kyo/proto/kernel/internal/Eval.scala`, inside `apply`: [six bullets]
> - `kyo/proto/kernel/internal/Handler.scala`, `recover`'s scaladoc only. [...]
>
> Does **not** change: `KyoInternal` node classes, the `Handler` protocol's signatures,
> `ArrowEffect`, `ContextEffect`, `Pending`, `Loop`, `Arrow`, `Effect`, `Eval.release`,
> `answerLoop`.

code does: `git diff --name-status 31a7b4bde9..7a7cd22ad8` reports a fourth path,

```
M	kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala
```

adding `"regions that fail and recover in sequence cost no stack"` and a private `Boom` object, and
the same file is changed again at `d197133298` for the context pin.

why it matters: unchanged from round 2's C4, whose stated fix was "to list the file where the other
three are listed", and the file is still in neither list. `grep -n "test" derivation.md` shows
`EvalTest` named only in the concession and declared sections, as pins. That is authorization for
the artifact, and it is why this stays the mildest of the four, but the third check reads the
Surface section, and a reader who reads only the Surface section is told this change touches three
files when it touches four, now five hunks across two commits.

## Verified closed

**Round 2's C1 is closed.** The budget derivation's fourth piece is back and I read all four sites
at the tip:

```
47:        val slot  = Safepoint.get()
48:        val saved = Safepoint.save(slot)
320:                        Safepoint.reset(slot)
355:        finally Safepoint.restore(slot, saved)
```

which is the mapping table's four rows realized one for one. The `try` at `:299` encloses the whole
`while !settled` loop and the `out` that follows it, so the restore runs on the settled path, on the
`throw ex` path out of the unwinding loop (`:324`), and on a fatal throw. The file comment that
round 2 caught asserting a deleted behaviour now says what the code does: "Restored in the finally
below so a nested eval hands the enclosing one back what it had" (`:41-42`). All four `Safepoint`
members exist with the claimed meanings on both platform sources
(`jvm-native/.../Safepoint.scala:83, 197, 203, 206`; `js-wasm/.../Safepoint.scala:59, 101, 107, 110`).

## The declared context change

Accurately described, and its unobservability claim did not hold. Both halves verified.

The description is exact. The baseline's `region` took `ctx` as a parameter and re-entered itself
with that same parameter at both own-tag answer paths (`31a7b4bde9:Eval.scala:181` `region(st, r,
Arrow.id, ctx)` and `:187` `region(o._1, o._2, next, ctx)`), while the context updates happened in
`loop`'s own `ctx` (`:38`, `:43`) and were lost when `loop` returned. The tip passes `loop`'s
current `ctx` at both (`Eval.scala:222`, `:230`). The region-exit paths are unchanged: they use
`stack.ctx`, which is the enclosing `ctx` captured at push (`:262`), which is what the baseline's
`loop(res, kyo.cont, contA.chain(contB), ctx)` at `:226` carried. So the change is confined to the
two own-tag answer paths, which is what the section says.

The claim in the version dispatched to me was:

> It is unobservable in this tree and therefore unpinned: every `SuspendContext` the public surface
> can build carries `update(v) = v`, so no update is ever non-identity, and a test would have to
> construct a node the surface cannot produce.

The premise is true and the conclusion is not. All four public constructors do carry the identity
update (`ContextEffect.scala`, `suspend` and `suspendWith` in both arities, each `def update(v: A) =
v`), but `Kyo.SuspendContext` is an `abstract class` in `kyo.proto.kernel.internal`, which is the
package `EvalTest` is in, so the tree can build a non-identity update even though the surface
cannot. "Unobservable in this tree" was the overreach; "unobservable through the public surface"
would have held.

The current derivation retracts exactly that and pins it (`derivation.md:151-156`), and the pin is
real, not a decoration. `d197133298` adds `EvalTest` "a context update outlives an operation
answered after it", which builds a `Kyo.SuspendContext` with `def update(v: Int) = v + 1`, binds 10,
and asserts 12 across an `Ask` operation answered in between. It passes at the tip. Its handler is
`answerAsk`, which is `ArrowEffect.handleLoop`, so it runs the `Loop.Continue2` arm at
`Eval.scala:227-230`, one of the two paths the section names. The baseline would give 11 by the
trace above, so the assertion does separate the two behaviours; I did not run it at `31a7b4bde9`
myself, and the derivation's "fails at `31a7b4bde9`" is the author's evidence, not mine.

That commit is outside the range I was given, so the missing pin is not filed as a finding.

## Clean

Checked this round against the sources, not carried over from round 2.

**The equation holds.** `loop`'s signature is character for character the one the derivation writes
(`Eval.scala:53` against `derivation.md:82`), including `@tailrec`, and
`sbt 'kyo-kernelJVM/Test/compile'` is green at the tip, which is that annotation being checked. The
`Handle` arm pushes the four columns and tail-calls the interior with identity registers (`:262-263`);
the settled arm completes one entry through `Handler.done` with both registers `Id` (`:265-276`); the
`Suspend` arm absorbs the registers into one node first and then asks the innermost entry, answering
on a tag match and reifying and popping on a miss (`:128-247`); the guard replaces `run` as two
nested `while` loops with no call that grows with the number of regions (`:299-356`). Composition is
preserved across the change of shape: the baseline's `loop(res, kyo.cont, contA.chain(contB), ctx)`
and the tip's push of `kyo.cont.chain(contA.chain(contB))` with a later `loop(r, cont, Arrow.id,
outer)` are the same composition by associativity of `chain`.

**Every piece maps to an existing value.** `Kyo.Handle.handler`, `.state`, `.cont` exist as declared
(`KyoInternal.scala:165-167`) and are three of the four pushed columns; `Loop.Continue2` advances the
state in place (`:229`); `Handler.done` at `:271`; the `Loop` done outcome bypasses `done` at
`:231-238`; `Handler.recover` at `:145` and `:334`; `Handler.release` is reached through the node and
through the rebuilt suspension's override, both with the live state. `Stack` is the only new type and
the derivation's "New type" section authorizes it explicitly; it is four arrays and a size, as
declared. No new node kind, carrier, or type beyond it.

**The mutable `Stack` does not escape.** Every `stack.*` read in the file is in `loop`'s own arms or
the guard; the anonymous suspensions capture the `handler`, `state` and `cont` locals read before
they are constructed (`:130-131`, `:208-209`, `:235-236`, `:273-274`), never the `Stack`. The
concession's "reachable from nothing that leaves the eval" holds; it is the pin for it that is red
(C1).

**Nothing outside the surface in the main sources.** `Eval.release` and `answerLoop` and the trailing
type aliases are byte-identical to the baseline (diffed directly). `Handler`'s method signatures are
unchanged; only the two scaladoc blocks differ, one declared and one not (C2). No file in the "Does
not change" list appears in the diff.

**The removed `bug` arm is covered by construction.** The baseline's `case res: Pending[AX, S] =>
bug(...)` inside `region` has no successor, and it needs none: `Pending` is `sealed`
(`KyoInternal.scala:42`) with subtypes `Defer`, `Suspend` in its three shapes, and `Handle`
(`:58, 75, 86, 107, 121, 154`), all matched ahead of the settled arm, and the matched-tag
`bug(s"unhandled: $susp")` at `:244` catches the non-`SuspendArrow` case.

**Failure semantics are the baseline's.** A throw in `done` or in a clause reaches the same recover it
would have, because the entry is still on the stack when they run (`:271` before the pop at `:275`).
A recover that throws becomes the failure the enclosing regions see, by `ex = ex2; Absent` (`:333-338`),
which is what the baseline's dynamically nested `try`s did. A throw with no region left rethrows the
current exception (`:324`), which is the baseline's escape out of the outermost `try`.
