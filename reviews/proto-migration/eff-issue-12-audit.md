# The proto kernel against hasura/eff issue 12

A reading audit of `kyo-kernel/shared/src/main/scala/kyo/proto` (packages `kyo.proto`,
`kyo.proto.kernel`, `kyo.proto.kernel.internal`, plus the jvm-native and js-wasm halves) at
commit `66294c5fca`, against the semantics argued over in
https://github.com/hasura/eff/issues/12 and the discussion it links. Nothing was run,
compiled, or edited; every prediction below is derived from the source as it stands, with
file and line references into that tree. The confirmed findings of `soundness-audit.md`
(S1 to S6) are fixed on this tip and are not re-reported; the rulings recorded in the brief
and in `backlog.md` (fork and join copies silent to `done` and `release`, a read after a
restored crossing sees the scope it restores in, `Eval.partial` never nested, a bracket
inside a `handleFirst` region released at that region's end with the remainder refused,
a park resuming with its captured bindings) bind throughout and are cited as RULED where
they decide a scenario.

## What the issue and its discussion say

The opening post (KingoftheHomeless, 2020-07-30): in `freer-simple`, and as later confirmed
in `eff`, a handler installed with `interpret` whose clause raises `throwError` is not caught
by a `catchError` placed around the operation inside the handled computation:

```haskell
bad = run $ runError @String $ interpret (\SomeAction -> throwError "not caught") $
  someAction `catchError` \(_ :: String) -> return "caught"
-- Left "not caught"; polysemy gives Right "caught"
```

The first comment extends it to `local` (a `Reader` modification around `someAction` is not
seen by an interpreter clause that does `ask`) and to `listen` and `censor`. The reddit
thread it links for `censor` (a MonadWriter-under-ContT discussion) could not be fetched
from this environment and is not used.

lexi-lambda's reply (2020-08-11) frames it as delimited control, after Sitaram's "Handling
Control": a handler clause runs at the point of the `interpret` call; during the dynamic
extent of the clause the more-local handlers are not in scope; when the clause returns, the
local evaluation context is restored. She argues this "handler encapsulation" is the desired
default (a handler that installs a private `Error` region for short-circuiting must not have
its throws caught by a client's nested `catch`), notes `handle` plus `locally` runs a handler
action in the local context instead, and then shows why `locally` cannot promise the outer
handlers are still there: with a coroutine effect, a `yield` inside a `locally` action
suspends the computation *inside the handler*, the caller can stash the suspended coroutine,
leave the handler's scope, install a different handler of the same effect, and resume; the
handlers in scope are then different from the ones the suspended code was written against.
She also names the observable edge case she considers an actual bug: after such a swap "the
behavior of any suspended `catch` operations will be determined by the old `Error` handler,
not the new one". Her proposed way out is an interposition semantics: `catch` should not be
a nested handler but a second chance for the *current* handler, so the handler in place at
the time of the `throw` answers, wherever the continuation is resumed.

KingoftheHomeless's second wall (2020-09-09) argues the polysemy semantics (a clause's
effects are handled at the use site) is what programmers expect, shows the workaround
encodings (`Exceptional eff exc`, `Telling eff w`) and their cost, and doubts `sendLocally`
can be made safe once coroutines exist. Issue 13 (linked, fetched) makes that concrete:
`locally m >> abort (Left b)` with `m` yielding to a `Coroutine` handler lets the caller
resume the coroutine outside `runSomeAction`, at which point `abort` runs against a handler
that is gone; `unsafeCoerce` follows. lexi-lambda's diagnosis there: `locally` is only sound
in tail position, because "the computation has run off and done other things, and those
things may have thrown all the local evaluation context away"; the only safe reading of a
second `locally` is to restore the captured local context again, which needs an eager
continuation capture. polysemy issue 264 (linked, fetched) is the other side of the trade:
polysemy has no `Coroutine` effect, which is what makes the swap impossible there.

The semantic questions this leaves, in the order the entries below take them:

1. Does a clause see the handlers installed between it and the operation (the OP)? Which
   context bindings does a clause read (the `local` variant)?
2. Is there a `locally`, a way to run a handler's action inside the local scope so a local
   `catch` sees it?
3. The coroutine swap: when a continuation captured inside a handler escapes and is resumed
   under a different handler of the same effect, which handler answers the remainder, and
   where does the handler's own post-resume code run (issue 13's `locally m >> abort`)?
4. "The handler in scope at capture" versus "at resume" for context bindings, on both sides
   of the answering handler, and for parks.
5. Exceptions thrown through a resumed continuation: which `recover` clause sees them, in
   the eval that captured and in a fresh one.
6. The same question for a handler's own clause after it suspends outward.
7. Multi-shot resumption over regions with completion hooks and over brackets: how many
   times, in what order relative to the clause's remainder, and what a bracket does.
8. `handleFirst` handing out a remainder whose regions the ending region already drained.
9. A scoped construct (bracket, binding, isolate) *below* the handler when a continuation
   escapes and is resumed after the construct ended.
10. Isolates crossed by the child's own operations, parked mid-child, resumed twice, and
    restored under a different region of the same tag: where the join lands.
11. A park taken inside a clause before it resumes a crossing, abandoned or resumed.
12. Nested handlers of the same effect: delegation from a clause and the absence of
    interposition.
13. Release ordering when a continuation is never resumed, and when a bracket meets a
    multi-shot clause.

Entries are numbered, red first. Each states the law, a public-surface sketch in the suite
style, the value the current code is predicted to produce, the path through the source that
produces it, and a verdict: PREDICTED-RED (a defect), PREDICTED-GREEN-WORTH-PINNING (a law
with no pin today), or RULED (decided by a ruling above or an existing pin, cited).

## How the kernel maps onto the issue's vocabulary

- `interpret` / `liftH`: every handler clause. A `SuspendArrow` answered by a handler below
  other regions is a foreign crossing: `Eval.dumped` moves the regions above the handler off
  the stack into a snapshot owed to the handler's lane (Eval.scala:73, 82, 131, 136, 147;
  Stack.scala:187-207), `rebound` removes their context keys (Eval.scala:411-424), and the
  clause runs with the handler on top (Eval.scala:78, 92, 122). The dumped regions come back
  only when the clause applies the continuation: `SuspendArrow.crossing` wraps the answer in
  a `Kyo.Park` carrying the snapshot (KyoInternal.scala:67-83) and `installed` re-pushes
  each region with its captured state (Eval.scala:237-279). This is Sitaram's semantics as
  lexi-lambda describes it, made explicit as data.
- `locally`: absent as an operation. The answer a clause hands to `cont` is evaluated at the
  clause's level before the crossing re-installs anything (KyoInternal.scala:73). The one
  way to run something inside the local scope is to answer with a computation held as a
  value and let the body run it (entry 3).
- `Coroutine` and the swap: any handler whose clause suspends on an outer effect is itself a
  region between that outer handler and the suspension, so it is dumped into the crossing
  and travels with the continuation (entry 4).
- `catch` as a nested handler: a region with a `recover` clause, or a nested handler of the
  same arrow effect. Whether it is "the old one" after a swap depends on whether it stood
  between the outer handler and the suspension (captured) or at or below the outer handler
  (the resume site's).
- Handler encapsulation the other way round (skip an inner handler from the body) exists as
  `ArrowEffect.Mask` (ArrowEffect.scala:480-500), which is the issue's "effect
  encapsulation" as a first-class operation; it is not what the OP wants and is covered by
  `ArrowEffectMaskTest`.
- Interposition (a handler getting several chances): absent. A handler answers once; a
  clause that raises its own effect re-enters itself (`handleCont`) or reaches the successor
  (`handleLoop` before its outcome), entry 15.

## Test prelude

The sketches assume the fixtures the suites already define, in the style of
`ArrowEffectTest`, `ContextEffectTest`, `EffectBracketTest`, `IsolateTest` and `EvalTest`:

```scala
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Kyo
import kyo.proto.Loop
import kyo.proto.kernel.*
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Safepoint
import scala.collection.mutable.ListBuffer

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

sealed trait Wrap extends ArrowEffect[Const[Unit], Const[Unit]]
def recovering[A, S](v: A < (Wrap & S))(f: Throwable => A): A < S =
    ArrowEffect.handleCont(Tag[Wrap], v)([C] => (_, cont) => cont(()), a => a, ex => Maybe(f(ex)))

sealed trait Cfg  extends ContextEffect[Int]
sealed trait Cfg2 extends ContextEffect[Int]
def read: Int < Cfg   = ContextEffect.suspend(Tag[Cfg])
def read2: Int < Cfg2 = ContextEffect.suspend(Tag[Cfg2])

def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
    ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

def hooked[A, S](log: ListBuffer[String], label: String, value: Int)(v: A < (Cfg & S)): A < S =
    ContextEffect.handle(Tag[Cfg])(
        (_: Maybe[Int]) => value,
        fork = (p: Int) => p,
        join = (p: Int, _: Int, _: Int) => p,
        done = (s: Int) => discard(log += s"done $label $s"),
        release = (s: Int, _: Throwable) => discard(log += s"release $label $s")
    )(v)

private def requestStop(): Unit =
    discard(Safepoint.get())
    discard(Safepoint.stop(Thread.currentThread()))
    Safepoint.deadline(java.lang.System.currentTimeMillis() - 1)

private object Boom extends RuntimeException("boom", null, false, false)
```

`hooked` and `answerAsk` are the shapes `EffectBracketTest`, `EvalTest` and the previous
audit already use; nothing forwards a kernel API under another name.

## Entries

### 1. A crossing resumed in a nested eval inside the clause is released again at the owner's exit (PREDICTED-RED)

Question 5 and 7 combined with the escape: the continuation is resumed in a different eval
while the region that owns its debt is still open. Law (the one S4 fixed for a single
stack): a region dumped by a crossing and re-installed by a resume that completes normally
fires `done` once and `release` never.

```scala
"a crossing resumed in a nested eval inside the clause completes its region without a release at the owner's exit" in {
    val log          = ListBuffer[String]()
    val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
    val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
        [C] => (_, cont) => answerAsk(0)(cont(41)).eval + 1,
        a => a
    )
    assert(r.eval == 43)
    assert(log.toList == List("done cfg 1"))
}
```

Predicted: `r.eval == 43` and `log == List("done cfg 1", "release cfg 1")`.

Reasoning. The outer eval borrows stack A (Eval.scala:40). The `ask` inside the `Cfg`
region is answered by the `Ask` region below it, so `dumped(A, 0, kyo)` moves `Cfg` into a
snapshot `SC` and appends it to lane 0 (Eval.scala:73, Stack.scala:187-207, the `owed(from -
1).append` at :205). The clause applies `cont(41)`: `crossing.apply` sees a settled answer and
builds `Kyo.Park(Effect.defer(41, kc, resume), SC)` (KyoInternal.scala:74-81). `.eval`
(Pending.scala:292-298) starts a nested `Eval.apply`, which borrows a *different* stack B:
the pool hands back a fresh instance when A is checked out (Stack.scala:272-278). On B the
`Park` arm runs `installed` (Eval.scala:185-186, 237-279): `B.settle(SC)` walks B's lanes and
its `evalOwed` and finds nothing, because the debt sits in A's lane 0 (Stack.scala:66-76);
`Cfg` is pushed onto B, the remainder settles to 42, `contextExit` fires `done cfg 1`
(Eval.scala:281-289), B is released. Back on A the clause returns 43, the `Ask` region's
settled arm calls `done` and then `arrowExit` (Eval.scala:202-207, 291-295), which drains
lane 0 through `drainDiscarded` with the "remainder discarded" signal (Eval.scala:405-409):
`SC` is still there, so `expandOwed` reaches `Cfg`'s handler and `released` fires
`release cfg 1` after the region already completed. The bracket twin is masked: `Cell.drain`
after `Cell.complete` is a failed CAS (Effect.scala:22-25), which is why `EvalTest` "a shot
evaluated inside the clause leaves the region intact for the next" (:525) sees nothing with a
hook-free region.

This is the cross-eval instance of the S4 law. `Stack.settle` matches debts by snapshot
identity, but only within one stack; a snapshot consumed by another eval has no way to say
so. The fix shape that fits the code: the snapshot itself records consumption (a settled
mark on the `Snapshot`, or the `Cell` pattern lifted to the snapshot) so that both `settle`
and `expandOwed` skip a consumed snapshot; `installed` marks at :253 and the drains check.
If the ruling is instead that the owner drains whatever it still holds and a raw hook is
at-least-once (the direction of Q4 for `handleFirst` remainders), the pin should assert
`List("done cfg 1", "release cfg 1")` and say so; either way the value is undecided today.
The same path is reached, with a race added, by handing `cont(41)` to another thread and
evaluating it there while the clause is still running.

### 2. A local handler between the answering handler and the operation is not in scope for the clause (PREDICTED-GREEN-WORTH-PINNING)

Question 1, the opening post's shape. Law (the eff/Sitaram reading this kernel commits to):
when a clause raises an effect, the handler that answers it is found on the stack with the
regions above the answering handler removed, so a handler of that effect installed inside
the handled body, around the operation, never sees the clause's raise; the handler below
does.

```scala
"a local handler around the operation does not see the effect its interpreter's clause raises" in {
    var localAnswered = 0
    val local: Int < Ask =
        ArrowEffect.handleCont(Tag[Say], ask: Int < (Ask & Say))(
            [C] =>
                (_, _) =>
                    localAnswered += 1
                    -1
            ,
            a => a
        )
    val interpreted: Int < Say =
        ArrowEffect.handleCont(Tag[Ask], local)([C] => (_, cont) => say("not caught").map(_ => cont(0)), a => a)
    val r: Int < Any = ArrowEffect.handleCont(Tag[Say], interpreted)([C] => (_, _) => -99, a => a)
    assert(r.eval == -99)
    assert(localAnswered == 0)
}
```

Predicted: `-99`, `localAnswered == 0`. (The polysemy answer would be `-1`.)

Reasoning. Stack at the `ask`: `[Say_outer, Ask, Say_local]`. `find(Ask)` returns 1, not the
top, so `dumped` takes `Say_local` off the stack into a snapshot owed to lane 1
(Eval.scala:73). The clause's `say("not caught")` is then dispatched with the stack
`[Say_outer, Ask]`: `find(Say)` walks from the top and reaches `Say_outer` at 0
(Stack.scala:171-177); the local region is not there to be found. `Say_outer`'s clause
discards, its region completes through `done` and `arrowExit` drains lane 0, whose snapshot
holds `Ask` with `Say_local` nested inside it; neither is a context handler so nothing
fires (Eval.scala:476-495). The `local` variant with a context binding (the first comment's
`local (\_ -> "localed")`) is already pinned: `EvalTest` "a clause reads the outer binding,
not a dumped one" (:1099) asserts 100, because `rebound` recomputes each dumped context key
from the exact-tag region still on the stack (Eval.scala:411-424). The arrow-effect shape
above has no pin with a same-effect local handler *and* an outer handler that answers; the
nearest, `EvalTest` "a clause does not see handlers inside its own scope" (:1314), has no
outer handler and asserts an unhandled bug.

### 3. Answering with a computation held as a value runs it inside the local scope, so a local handler catches it (PREDICTED-GREEN-WORTH-PINNING)

Question 2. There is no `locally`, but the kernel has the one thing lexi-lambda says a
correct `locally` needs and eff cannot afford: the continuation is captured eagerly with its
local regions, and a value fed into it is evaluated after those regions are re-installed.
Law: an answer that is a computation held as a value is delivered unopened
(`ArrowEffectTest` "a boxed answer crosses the answers loop unopened", :2051, `EvalTest` "an
answer that is a computation held as a value stays a value", :400); when the body runs it,
its effects are answered by the handlers in scope at the use site, the local one included.

```scala
sealed trait AskBoxed extends ArrowEffect[Const[Unit], [X] =>> Int < Say]
def askBoxed: (Int < Say) < AskBoxed = ArrowEffect.suspend[Any](Tag[AskBoxed], ())

"a computation answered as a value runs under the local handler the clause could not see" in {
    var localAnswered = 0
    val local: Int < AskBoxed =
        ArrowEffect.handleCont(Tag[Say], askBoxed.map(v => v): Int < (AskBoxed & Say))(
            [C] =>
                (_, _) =>
                    localAnswered += 1
                    -1
            ,
            a => a
        )
    val payload: Int < Say = say("not caught").map(_ => 7)
    val interpreted: Int < Say =
        ArrowEffect.handleCont(Tag[AskBoxed], local)([C] => (_, cont) => cont(payload), a => a)
    val r: Int < Any = ArrowEffect.handleCont(Tag[Say], interpreted)([C] => (_, _) => -99, a => a)
    assert(r.eval == -1)
    assert(localAnswered == 1)
}
```

Predicted: `-1`, `localAnswered == 1`, and `-99` is never produced.

Reasoning. `cont(payload)` is `Arrow.apply(v: A)` (Arrow.scala:16-18); inside it the
generic `A` is handed to an `A < S` position and the implicit lift nests the pending payload
(Implicits.scala:8-13, Nested.scala:16-20; the representation note at the top of
`soundness-audit.md`). The crossing therefore sees a settled value and builds the `Park`
that re-installs `Say_local` (KyoInternal.scala:74-81, Eval.scala:256-278). The park's
value is `Effect.defer(nested, kc, resume)`; `map(v => v)` receives the payload unnested
(Pending.scala:32) and returns it as a pending computation, so `say("not caught")` is
dispatched with `Say_local` on top of the stack and answered there. Contrast entry 2, where
the same `say` was raised by the clause. This is the polysemy semantics on demand: the
effect is declared with `O[X] = Int < Say`, the row of the payload is what the local handler
must cover, and the type system enforces it. Worth pinning because it is the kernel's
answer to the issue's central complaint and no pin states it as such.

### 4. A handler whose clause suspends outward travels with the continuation, and its post-resume code runs at the resume site (PREDICTED-GREEN-WORTH-PINNING)

Question 3, the coroutine swap, and issue 13's `locally m >> abort`. Two laws.

4a. A handler standing between the outer handler and the suspension is part of the captured
continuation: resumed under a *different* handler of the same effect, the captured one is
re-installed above it and answers the remainder's operations first.

```scala
"a handler whose clause suspends outward travels with the continuation and answers ahead of the handler at the resume site" in {
    var stash        = Maybe.empty[Arrow[Unit, Int, Say]]
    val yields       = ListBuffer[String]()
    var atResumeSite = 0
    val body: Int < Ask = ask.map(a => ask.map(b => a * 10 + b))
    val captured: Int < Say =
        ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => say("yield").map(_ => cont(1)), a => a)
    val first: Int < Any = ArrowEffect.handleCont(Tag[Say], captured)(
        [C] =>
            (_, cont) =>
                stash = Maybe(cont)
                -1
        ,
        a => a
    )
    assert(first.eval == -1)
    val swapped: Int < Say =
        ArrowEffect.handleCont(Tag[Ask], stash.get(()): Int < (Ask & Say))(
            [C] =>
                (_, cont) =>
                    atResumeSite += 1
                    cont(5)
            ,
            a => a
        )
    val second: Int < Any = ArrowEffect.handleCont(Tag[Say], swapped)(
        [C] =>
            (s, cont) =>
                yields += s
                cont(())
        ,
        a => a
    )
    assert(second.eval == 11)
    assert(atResumeSite == 0)
    assert(yields.toList == List("yield"))
}
```

Predicted: `-1`, then `11`, `atResumeSite == 0`, `yields == List("yield")`.

Reasoning. First eval, stack `[Runner, H1]`. The body's `ask` is answered by `H1` at the top;
its clause's `say("yield")` finds `Runner` at 0 below `H1`, so `dumped(stack, 0, kyo)` takes
`H1` (state `()`, its stored continuation) into the crossing's snapshot (Eval.scala:73) and
`Runner` stashes the crossing step. Second eval, stack `[Runner2, H2]`: `stash.get(())`
builds the `Park`, `installed` pushes `H1` on top of `H2` (Eval.scala:256-278) and the
deferred `cont(1)` runs the body remainder, whose second `ask` is found by `find(Ask)` at
the innermost matching region, `H1` (Stack.scala:171-177). `H1`'s clause yields again, this
time to `Runner2`, which resumes; `b = 1`, the result is `11`, and `H2` never answers. This
is exactly the "old handler" behaviour lexi-lambda calls arguably a bug in eff and the
kernel's deliberate reading of a delimited continuation: everything between the outer
handler and the suspension is the continuation, handler regions included. The other side
is pinned: a continuation handed out of a clause *without* the clause suspending leaves its
handler behind, and the remainder is answered by whatever stands at the resume site
(`EvalTest` "resumes under a later region of the same tag, which answers the remainder",
:508). Pinning 4a records which of the two the shape of the escape selects.

4b. The clause's own code after `cont(x)` (issue 13's `liftH (throw b)` after `locally m`)
is captured in the same crossing and runs at the resume site, reading the resume site's
bindings.

```scala
"a clause's code after the resume runs where the continuation is resumed, under the bindings there" in {
    var stash = Maybe.empty[Arrow[Unit, Int, Say & Cfg]]
    val captured: Int < (Say & Cfg) =
        ArrowEffect.handleCont(Tag[Ask], ask.map(a => a))(
            [C] => (_, cont) => say("yield").map(_ => cont(1)).map(x => read.map(c => x + c)),
            a => a
        )
    val first: Int < Any =
        ContextEffect.handleInheritable(Tag[Cfg], 10)(
            ArrowEffect.handleCont(Tag[Say], captured)(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(cont)
                        -1
                ,
                a => a
            )
        )
    assert(first.eval == -1)
    val second: Int < Any =
        ContextEffect.handleInheritable(Tag[Cfg], 100)(
            ArrowEffect.handleCont(Tag[Say], stash.get(()))([C] => (_, cont) => cont(()), a => a)
        )
    assert(second.eval == 101)
}
```

Predicted: `101`, not `11`.

Reasoning. At the `say`, the registers hold the clause's `.map(_ => cont(1))` and
`.map(x => read.map(...))`; `crossing` takes `contA.chain(contB)` as `resume`
(KyoInternal.scala:67, Eval.scala:77) and the park's value is `Effect.defer((), kc, resume)`.
On resume the deferred chain runs after `H1` is re-installed: `cont(1)` returns 1, then the
read. `Cfg(10)` stood *below* `Runner` in the first eval, so it was never dumped and is not
in the snapshot; the read is a `SuspendContext` resolved against the threaded `ctx`
(Eval.scala:57-60), which at that point holds the resume site's `Cfg(100)` (the `Handle`
arm at :169-174). Types keep this sound where issue 13 was not: the clause's row `Say & Cfg`
travels with the continuation, so the resume site must provide both. What the pin records
is the scoping: a handler's post-resume code is not "at the handler".

### 5. A binding above the answering handler is captured, one below it is the resume site's, and a park captures everything (PREDICTED-GREEN-WORTH-PINNING, parks RULED)

Question 4, "the handler in scope at capture" versus "at resume" for context effects. The
issue treats this as one question; in this kernel it has three answers, decided by where
the binding stands relative to the answering handler and by whether the suspension is a
crossing or a park.

Law. (a) A binding installed between the answering handler and the operation is dumped into
the crossing and re-installed on resume with its captured state, wherever the continuation
is resumed: pinned by `IsolateTest` "a crossed binding resumes at its captured value" (:256,
the enclosing `handleInheritable(..., 5)` at the resume site does not win) and
`ContextEffectTest` "a captured binding resolves against the scope it resumes in" (:156,
whose binding is in fact inside the handler and resolves to its captured 11). (b) A binding
installed *below* the answering handler is not part of the continuation; a resumed remainder
reads whatever the resume site binds, or takes the default, or is an unhandled bug. Not
pinned. (c) A park (`Eval.partial`) snapshots the whole stack, so every binding of the parked
eval resumes with its captured state regardless of the resume site: RULED (Q3, "a park
resumes with its captured bindings wherever it resumes"; `ContextEffectThreadingTest` :27
and :50).

```scala
"a binding below the answering handler is the resume site's, one above it is the captured one" in {
    var stash = Maybe.empty[Arrow[Int, (Int, Int), Cfg & Cfg2]]
    val body: (Int, Int) < (Ask & Cfg & Cfg2) = ask.map(a => read.map(c => read2.map(c2 => (c + a, c2))))
    val inside: (Int, Int) < (Ask & Cfg)      = ContextEffect.handleInheritable(Tag[Cfg2], 2)(body)
    val handled: (Int, Int) < Cfg = ArrowEffect.handleCont(Tag[Ask], inside)(
        [C] =>
            (_, cont) =>
                stash = Maybe(cont)
                (-1, -1)
        ,
        a => a
    )
    assert(ContextEffect.handleInheritable(Tag[Cfg], 1)(handled).eval == ((-1, -1)))
    val resumed: (Int, Int) < Any =
        ContextEffect.handleInheritable(Tag[Cfg], 100)(
            ContextEffect.handleInheritable(Tag[Cfg2], 200)(answerAsk(0)(stash.get(0)))
        )
    assert(resumed.eval == ((100, 2)))
}
```

Predicted: `(-1, -1)` then `(100, 2)`.

Reasoning. First eval, stack `[Cfg(1), Ask, Cfg2(2)]`: `find(Ask)` returns 1, `dumped` takes
`Cfg2(2)` into the snapshot and `rebound` drops its key (Eval.scala:73-74, 411-424); the
clause stashes the crossing step. Second eval, stack `[Cfg(100), Cfg2(200), Ask']`: the park
re-installs `Cfg2` with state 2 on top and updates the context with it (Eval.scala:264-269),
so `read2` takes 2, while `read` is resolved against the threaded context (Eval.scala:57-60)
where only the resume site's `Cfg(100)` was ever bound. The two reads in one program are the
point: which side of the handler a binding stands on is what decides its scoping, not the
resume site. The pin also fixes the arrow-effect analogue of (b): the answering handler is
itself below the operation and never travels (entry 4's contrast case).

### 6. A recovery captured with the continuation answers a throw in the resumed remainder in a fresh eval, ahead of the resume site's (PREDICTED-GREEN-WORTH-PINNING)

Question 5. Law: a region with a `recover` clause that stood between the answering handler
and the suspension is re-installed on resume, so a throw in the resumed remainder is
recovered by it, before any recovery at the resume site and without the capture site's
regions being consulted at all. `ArrowEffectMaskTest` "a recovering region inside the mask
catches a failure raised after the tunneled answer returns" (:122) pins this for a resume
inside the clause; `ArrowEffectTest` "a throw after a stateful region reached through a
resumed continuation reaches the recovery" (:2142) pins a recovery *outside*. The
fresh-eval, escaped-continuation shape with both is not pinned.

```scala
"a recovery captured with the continuation answers a throw in the remainder before the resume site's recovery" in {
    var stash = Maybe.empty[Arrow[Int, Int, Ask & Wrap]]
    val body: Int < (Ask & Wrap) = recovering(ask.map(a => if a < 0 then (throw Boom): Int else a))(_ => -1)
    val first: Int < Any = recovering(
        ArrowEffect.handleCont(Tag[Ask], body)(
            [C] =>
                (_, cont) =>
                    stash = Maybe(cont)
                    0
            ,
            a => a
        )
    )(_ => -3)
    assert(first.eval == 0)
    val resumed: Int < Any = recovering(answerAsk(0)(stash.get(-5)))(_ => -2)
    assert(resumed.eval == -1)
}
```

Predicted: `0` then `-1`; `-2` and `-3` are never produced.

Reasoning. `recovering` is a `handleCont` with a `recover` clause on its `ContHandler`
(ArrowEffect.scala:90-124, the `override def recover` at :109). In the first eval the stack is
`[Wrap(-3), Ask, Wrap(-1)]`; the crossing dumps `Wrap(-1)` with its handler, whose `recover`
is a method on the handler object, so it travels in the snapshot (Stack.scala:187-207). In the
second eval, stack `[Wrap(-2), Ask']`, `installed` pushes `Wrap(-1)` on top (Eval.scala:270-
276). The throw in the remainder propagates to `guarded`'s catch (Eval.scala:352-366),
`recovered` walks from the top and consults the first `ArrowHandler` it meets, the
re-installed `Wrap(-1)`, whose `recover` is `Present` (Eval.scala:325-342); its result is
chained onto that region's stored continuation and `guarded` pops it, so nothing below is
asked. The capture site's `Wrap(-3)` region completed long ago. Worth pinning as the
kernel's answer to "which `catch` is in scope after a swap": the one that stood between
the handler and the operation, and it is the *same* region, not a re-derived one.

### 7. A loop region's recovery does not guard its clause after the clause suspends; a cont region's does (PREDICTED-GREEN-WORTH-PINNING, ruling question)

Question 6. The two handler flavours scope a clause's own post-suspension code differently,
and their `recover` clauses follow. Law as the code has it: for `handleLoop` and
`handleLoopState`, a clause that suspends before producing its outcome runs outside its
region (pinned: `EvalTest` "a clause's suspension before done is answered outside its
scope", :1290, "a clause's own-tag suspension before its outcome is answered by the
successor", :1300), so a throw after that suspension is not seen by the region's own
`recover`; for `handleCont`, the clause's result runs inside the region, an outward
suspension dumps the region into the crossing and the resume re-installs it, so the same
throw is recovered by the region itself.

```scala
"a loop region's recovery does not guard its clause after the clause suspends" in {
    val viaLoop: Int < Say = ArrowEffect.handleLoop(Tag[Ask], ask.map(_ + 1))(
        [C] => _ => say("c").map(_ => (throw Boom): Loop.Outcome2[Unit, Int < (Ask & Say), Int < Say]),
        a => a,
        _ => Maybe(-1)
    )
    val r = ArrowEffect.handleCont(Tag[Say], viaLoop)([C] => (_, cont) => cont(()), a => a)
    assert(intercept[RuntimeException](r.eval) eq Boom)
}

"a cont region's recovery guards its clause after the clause suspends" in {
    val viaCont: Int < Say = ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
        [C] => (_, _) => say("c").map(_ => (throw Boom): Int),
        a => a,
        _ => Maybe(-1)
    )
    val r = ArrowEffect.handleCont(Tag[Say], viaCont)([C] => (_, cont) => cont(()), a => a)
    assert(r.eval == -1)
}
```

Predicted: the first throws `Boom`; the second evaluates to `-1`.

Reasoning, loop side. Stack `[Say, Ask]`, the `ask` is at the top of `Ask`, so `answers` runs
(Eval.scala:96-98, Handler.scala:140-216); the clause returns the pending `say("c").map(...)`,
which `answersLoopState` hands back as the outcome (Handler.scala:204-206). The `pending`
arm pops the `Ask` region before dispatching the outcome (Eval.scala:104-111, the `pop` at
:108). The `say` is then answered by `Say` at the top; its clause applies `cont(())`, which
runs the clause's `map` and throws inside `Arrow.apply` (Arrow.scala:16-18); `answering`
attaches the trace and rethrows (Handler.scala:36-41), `guarded` catches, and `recovered`
finds only `Say`, with no `recover`, on the stack (Eval.scala:308-350). The `Ask` region's
`recover` is never consulted because its region is not standing; it would have been
re-installed by `clauseDispatch` only around a `Continue2` outcome (Handler.scala:84-89).
Cont side: the clause result runs with `Ask` on the stack (Eval.scala:78-80); `say` finds
`Say` at 0 below `Ask`, dumps `Ask` into the crossing (Eval.scala:73), `Say` resumes, the
park re-installs `Ask` (Eval.scala:256-278) and the throw meets `Ask`'s `recover` first
(Eval.scala:325-342).

Both are consequences of pinned scoping laws, so the verdict is green for each. What no pin
or ruling decides is whether the divergence is wanted: a `handleLoop` clause's post-
suspension failure escapes the region whose clause it is, while the same code under
`handleCont` is caught there. It matters for the issue's "failure at the use site or at the
handler" question because the loop flavour is the one the fused fast path prefers. Raised
in the closing section.

### 8. Each shot of a multi-shot resume re-establishes a hooked region and completes it before the clause continues; the handler completes once (PREDICTED-GREEN-WORTH-PINNING; the bracket half RULED)

Question 7. Law: a context region captured in a crossing is re-installed per shot with its
captured state, fires `done` per shot, and the region's exit precedes the clause's remainder
for that shot; the answering handler's `done` runs once after the last shot; `release` never
fires. For a bracket the second shot is refused with `kyo.Closed` before its body runs:
RULED (`EffectBracketTest` "a multi-shot capture over a bracket refuses the second shot",
:358, "no branch of a multi-shot clause reads a resource that was already released", :1074).

```scala
"each shot re-establishes a hooked region and completes it before the clause continues" in {
    val log = ListBuffer[String]()
    val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
    val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
        [C] =>
            (_, cont) =>
                cont(1).map { a =>
                    log += s"shot $a"
                    cont(2).map { b =>
                        log += s"shot $b"
                        a + b
                    }
                }
        ,
        a =>
            log += "handler done"
            a
    )
    assert(r.eval == 5)
    assert(log.toList == List("done cfg 1", "shot 2", "done cfg 1", "shot 3", "handler done"))
}
```

Predicted: `5` and exactly that log; no `release cfg 1`.

Reasoning. The crossing dumps `Cfg(1)` into snapshot `SC` owed to the `Ask` lane
(Eval.scala:73). `cont(1)` builds a park over `SC`; `installed` settles `SC` out of the lane
(Eval.scala:253, Stack.scala:66-76), pushes `Cfg` with its stored continuation chained to the
clause's `.map { a => ... }` (the `i == 0` chain at Eval.scala:259-262), and the body remainder
settles to 2 with empty registers; the settled arm's `contextExit` fires `done` and pops
(Eval.scala:199-201, 281-289) before `next` runs the clause remainder ("shot 2"). `cont(2)`
builds a second park over the *same* `SC`: `settle` finds nothing (already settled, a no-op
by design), `Cfg` is pushed again with the captured state 1 (Eval.scala:264-269, no
re-derivation), completes, and the second remainder runs. The `Ask` region's `done` runs at
its settled arm once the clause's value settles (Eval.scala:202-207). The previous audit's
finding 13 predicted the debt side of this green; the order of `done` against the clause
remainder and the single handler completion are what this pin adds.

### 9. A `handleFirst` remainder re-enters a raw context region that the ending region already released (PREDICTED-GREEN-WORTH-PINNING; the bracket half RULED Q4)

Question 8. RULED for brackets: the region a `handleFirst` installs ends with its token,
its exit drains what it owes, and the remainder finds its brackets released and is refused
(`EffectBracketTest` "a handleFirst clause runs before the release its remainder runs
after", :1155). For a raw `ContextEffect.handle` region there is no state to refuse on, so
the same sequence *revives* the region: `release` at the region's end, then `done` at the
remainder's end, in that order, with the clause running between them.

```scala
"a handleFirst remainder re-enters a raw region the region's end already released" in {
    val log = ListBuffer[String]()
    val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
    val first: Int < Ask = ArrowEffect.handleFirst(Tag[Ask], body)(
        handle = [C] =>
            (_, cont) =>
                log += "clause"
                cont(41)
        ,
        done = a => a
    )
    assert(answerAsk(0)(first).eval == 42)
    assert(log.toList == List("clause", "release cfg 1", "done cfg 1"))
}
```

Predicted: `42` and `List("clause", "release cfg 1", "done cfg 1")`.

Reasoning. `handleFirst` is a `handleCont` whose clause returns the `FirstSuspended` token
and whose `done` lane unwraps it (ArrowEffect.scala:177-201). The `ask` is a crossing
(`Cfg` above), the token is the clause's value, and the region's settled arm calls
`handler.done` *synchronously* (Eval.scala:204): that is the `done` lane, which runs the
user's `handle` ("clause") and builds `cont(41)`, a park over the `Cfg` snapshot, as the
region's result. Only then does `arrowExit` pop and drain the lane (Eval.scala:206, 291-295),
firing `release cfg 1` with the "remainder discarded" signal. The park is then evaluated:
`installed` calls `reenter`, a no-op for a `ContextEffect.handle` region (Handler.scala:134,
ContextEffect.scala:125-132 overrides `done` and `release` only), pushes `Cfg(1)`, and the
remainder completes it: `done cfg 1`. The bracket's `reenter` is what turns the same path
into `Closed` (Effect.scala:56-58). Worth pinning because it is the raw-hook face of Q4 and
because a fix for entry 1 that marks consumed snapshots would also be the place to decide
whether a raw region may be revived after its release fired; see the closing section.

### 10. A bracket below the answering handler does not travel with an escaped continuation, and nothing refuses the remainder (PREDICTED-GREEN-WORTH-PINNING)

Question 9, a scoped construct *below* the handler. Law: a bracket, binding, or isolate
installed around the handler is not part of any continuation the handler captures; it ends
at its own extent (pinned for the in-eval case by `EffectBracketTest` "a bracket outside two
regions releases at its own extent, not when an inner region discards", :1017). An escaped
continuation resumed later runs its remainder with no region to refuse it, even though the
remainder closed over the released resource. The contrast, a bracket *inside* the handler,
is captured and refused: pinned (`EffectBracketTest` "a leaked capture resumed after its
region completed is refused as closed", :260).

```scala
"a bracket outside the answering handler is not carried by an escaped continuation, so the remainder runs after the release" in {
    var stash = Maybe.empty[Arrow[Int, Int, Ask]]
    val log   = ListBuffer[String]()
    val v: Int < Any =
        Effect.bracket(Effect.defer(1))((_, _) => discard(log += "release")) { r =>
            ArrowEffect.handleCont(
                Tag[Ask],
                ask.map { a =>
                    log += s"use $r"
                    a + r
                }
            )(
                [C] =>
                    (_, cont) =>
                        stash = Maybe(cont)
                        -1
                ,
                a => a
            )
        }
    assert(v.eval == -1)
    assert(log.toList == List("release"))
    assert(answerAsk(0)(stash.get(41)).eval == 42)
    assert(log.toList == List("release", "use 1"))
}
```

Predicted: `-1`, `List("release")`, then `42` and `List("release", "use 1")`.

Reasoning. Stack `[Finalize(cell), Ask]`; the `ask` is at the top of `Ask`, so the
continuation handed to the clause is `kyo.cont.chain(contA.chain(contB))` (Eval.scala:76),
plain arrows with no snapshot: the `Ensure`-built `Finalize` region (Effect.scala:38-66)
is below and untouched. The clause returns `-1`, `Ask` completes, the bracket's use value
settles, `contextExit` completes the cell (Effect.scala:54). `stash.get(41)` later is a
`Defer` chain with no `Park`, so no `reenter` is ever called (Eval.scala:240-251 is reached
only through `installed`); the remainder runs with `r = 1` and returns 42. That is the
correct reading of a delimited continuation and the thing the issue's `unsafeCoerce`
example warns about: the kernel refuses what it captured and cannot refuse what it did not.
The pin records that the boundary of refusal is the answering handler, and that a user who
closes over a resource across a stashed continuation gets no help from the bracket.

### 11. A crossing inside an isolated child resumed twice joins each shot into the live parent (PREDICTED-GREEN-WORTH-PINNING)

Question 10, isolates crossed by the child's own operations under multi-shot. Law: the
forked copies stand between the answering handler and the operation, so they are dumped and
re-installed per shot with the forked state; each shot's trailing capture and restore joins
into the origin found by identity on the live stack, against the parent's *current* state;
the origin's `done` fires once with the last joined state (RULED for the single-shot cycle:
copies are silent, `IsolateTest` "an isolate cycle fires done once, for the region the user
installed, with the joined state", :566).

```scala
"a crossing inside an isolated child resumed twice joins each shot into the live parent" in {
    val log = ListBuffer[String]()
    val child: Int < (Cfg & Ask) = Isolate.internal.Contextual.run(read.map(c => ask.map(a => c + a)))
    val handled: Int < Cfg = ArrowEffect.handleCont(Tag[Ask], child)(
        [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 100 + y)),
        a => a
    )
    val r: (Int, Int) < Any = ContextEffect.handle(Tag[Cfg])(
        (_: Maybe[Int]).getOrElse(10),
        fork = (p: Int) => p * 2,
        join = (p: Int, _: Int, c: Int) => p + c,
        done = (s: Int) => discard(log += s"done $s")
    )(handled.map(v => read.map(after => (v, after))))
    assert(r.eval == ((2122, 50)))
    assert(log.toList == List("done 50"))
}
```

Predicted: `(2122, 50)` and `List("done 50")`.

Reasoning. `capture` reads the stack's context regions (`Stack.contextual`,
Stack.scala:131-151) under the `Snapshot` arm (Eval.scala:188-189) and `isolate` installs one
`Forked` copy per region through a `Kyo.Park` (Isolate.scala:81-86, 110-121); stack
`[Cfg(10), Ask, Forked(20)]`. The child's `ask` is a crossing: `Forked(20)` is dumped
(Eval.scala:73) and `rebound` puts the origin's 10 back in the context (Eval.scala:411-424).
Shot 1 re-installs the copy (state 20), the body yields 21, the trailing capture reads
`[Cfg(10), Forked(20)]`, the copy exits silently (Handler.scala:132, the `Forked` class at
Isolate.scala:102-108 overrides nothing), and `restore`'s join finds the origin by identity at
index 0, computes `10 + 20`, and writes 30 into the origin's slot (Isolate.scala:123-148,
the `setState` at :142); the `Snapshot` arm rebuilds the context from the stack afterwards
(Eval.scala:189, 297-306). The clause remainder then applies `cont(2)`: the *same* snapshot
re-installs the copy at its captured 20, the body yields 22, and the second join sees the
parent at 30, giving 50. The read after the `Ask` region takes 50 and the origin's `done`
reports 50. No pin today combines a crossing inside the child with a second shot; the
value fixes both that each shot is a full fork-and-join and that the join is against the
live parent, not the parent at capture.

### 12. An isolate parked inside its child joins into the origin the park re-establishes (PREDICTED-GREEN-WORTH-PINNING)

Question 10 with a park. Law: a park inside the child snapshots the whole stack, origin
regions included; resumed in a fresh eval, the origin is re-installed as the same handler
object, the child's restore finds it by identity, the join lands on it, and its `done`
fires in the new eval with the joined state.

```scala
"an isolate parked inside its child joins into the origin re-established by the park" in {
    val log = ListBuffer[String]()
    val child: Int < Cfg = Isolate.internal.Contextual.run(read.map { c =>
        requestStop()
        Effect.defer(c + 1)
    })
    val prog: (Int, Int) < Any = ContextEffect.handle(Tag[Cfg])(
        (_: Maybe[Int]).getOrElse(10),
        fork = (p: Int) => p * 2,
        join = (p: Int, _: Int, c: Int) => p + c,
        done = (s: Int) => discard(log += s"done $s")
    )(child.map(v => read.map(after => (v, after))))
    val parked = Eval.partial(prog)
    assert(parked.evalNow.isEmpty)
    assert(log.isEmpty)
    assert(parked.eval == ((21, 30)))
    assert(log.toList == List("done 30"))
}
```

Predicted: parked, `log` empty, then `(21, 30)` and `List("done 30")`.

Reasoning. The `Defer` arm's poll parks (Eval.scala:49-51) with `stack.snapshot()` moving
`[Cfg(10), Forked(20)]` and their continuations out (Stack.scala:114-129); no hook fires.
`parked.eval` re-installs both through `installed` with their captured states and the same
handler objects (Eval.scala:256-278). The child's remainder completes, the trailing capture
reads the re-installed pair, the copy exits silently, and `restore`'s identity search
(Isolate.scala:130) finds the re-installed origin; `setState` writes 30, the read after takes
it, and the origin's exit in the new eval fires `done 30`. `EffectBracketTest` "abandoning an
isolated child releases its bracket" (:545) pins the abandonment side; this pins the resume
side and that a park does not detach a child from its origin.

### 13. An isolate resumed under a different region of its tag joins nothing (PREDICTED-GREEN-WORTH-PINNING, ruling question)

Question 10, the swap applied to a context effect's join. Law as the code has it: the
join looks for the origin *handler object* on the live stack; a continuation carrying the
forked copies, escaped and resumed under a different region of the same tag, reads its
captured forked state and joins into nothing, silently. RULED for "no region at all at
restore" (`IsolateTest` "a region exited before the merge is not joined", :481); the
same-tag-different-region case is the one the issue asks about and is not pinned.

```scala
"an isolate resumed under a different region of its tag joins nothing" in {
    var stash = Maybe.empty[Arrow[Int, Int, Ask & Cfg]]
    val log   = ListBuffer[String]()
    def region[A](label: String)(v: A < Cfg): A < Any =
        ContextEffect.handle(Tag[Cfg])(
            (_: Maybe[Int]).getOrElse(10),
            fork = (p: Int) => p * 2,
            join = (p: Int, _: Int, c: Int) =>
                log += s"join $label"
                p + c
            ,
            done = (s: Int) => discard(log += s"done $label $s")
        )(v)
    val child: Int < (Cfg & Ask) = Isolate.internal.Contextual.run(read.map(c => ask.map(a => c + a)))
    val first: Int < Any = region("first")(
        ArrowEffect.handleCont(Tag[Ask], child)(
            [C] =>
                (_, cont) =>
                    stash = Maybe(cont)
                    -1
            ,
            a => a
        )
    )
    assert(first.eval == -1)
    assert(log.toList == List("done first 10"))
    val second: Int < Any = region("second")(answerAsk(0)(stash.get(1)))
    assert(second.eval == 21)
    assert(log.toList == List("done first 10", "done second 10"))
}
```

Predicted: `-1`, `List("done first 10")`, then `21` and `List("done first 10", "done
second 10")`; `join second` never fires and `second`'s region completes at 10.

Reasoning. In the first eval the copy is dumped, the clause stashes, `Ask` completes and
drains the copy silently, the origin exits unjoined. In the second eval the park
re-installs the copy at 20 (`installed`, Eval.scala:264-269), the remainder yields 21, and
`restore`'s search compares `stack.handler(j) eq origin` against the first region's handler
object (Isolate.scala:130); `region("second")` built a different one, so `j < 0` and the
loop skips (:131-143). The "read after a restored crossing sees the scope it restores in"
ruling makes the *reads* resolve at the resume site; the join, by identity, does not follow
them. Whether a join should fall back to the innermost live region of the same tag when
its origin is gone is a ruling; the sketch pins the identity law until then.

### 14. A park taken inside a clause before it resumes a crossing carries the crossed bracket: abandoned it releases, resumed it completes (PREDICTED-GREEN-WORTH-PINNING)

Question 11. Law: a debt sitting in a handler's lane when a slice parks travels inside the
park's snapshot; `Eval.release` on the park reaches it and releases with the abandonment
signal; a resume re-owes it and the later crossing resume settles it, so the bracket
completes with `Absent`. `EffectBracketTest` "a park after a crossing resume still owes the
bracket" (:202) pins the park *after* the resume; the park *before* it, with the debt still
in the lane, exercises `Stack.snapshot`'s lane packing and `expandOwed` through the public
surface and is not pinned.

```scala
"a park taken in a clause before it resumes a crossing carries the crossed bracket" in {
    def program(seen: ListBuffer[Maybe[Throwable]]): Int < Any =
        val body: Int < Ask =
            Effect.bracket(Effect.defer(7))((_, outcome) => discard(seen += outcome))(a => ask.map(_ + a))
        ArrowEffect.handleCont(Tag[Ask], body)(
            [C] =>
                (_, cont) =>
                    Effect.defer {
                        requestStop()
                        ()
                    }.map(_ => Effect.defer(cont(1)))
            ,
            a => a
        )
    val abandoned = ListBuffer[Maybe[Throwable]]()
    val p1        = Eval.partial(program(abandoned))
    assert(p1.evalNow.isEmpty)
    Eval.release(p1, Boom)
    assert(abandoned.toList == List(Maybe(Boom)))
    val resumed = ListBuffer[Maybe[Throwable]]()
    val p2      = Eval.partial(program(resumed))
    assert(p2.evalNow.isEmpty)
    assert(p2.eval == 8)
    assert(resumed.toList == List(Maybe.empty))
}
```

Predicted: `List(Maybe(Boom))`, then `8` and `List(Maybe.empty)`.

Reasoning. The `ask` crosses the bracket's `Finalize` region into snapshot `SB` owed to the
`Ask` lane. The clause's first `Effect.defer` runs strictly (the stop is requested inside
it), its `map` returns `Effect.defer(cont(1))`, and that node's `Defer` arm sees the armed
stop (Eval.scala:49-51) before `cont(1)` is applied. `park` packs the `Ask` entry with
`takeOwed(0) = [SB]` (Stack.scala:114-129, the fourth slot). `Eval.release` walks the park:
the `Ask` handler is not a context handler, `expandOwed(entries.owed(0))` reaches the cell
and drains it with `Boom` (Eval.scala:452-465, 476-495). On resume, `installed` re-owes
`[SB]` to the fresh `Ask` lane (Eval.scala:275), the deferred `cont(1)` builds the crossing
park, and `settle(SB)` removes it before the cell is pushed (Eval.scala:253, Stack.scala:66-
76); `reenter` passes, the use completes, `Cell.complete` reports `Absent`.

### 15. Nested handlers of the same effect: delegation from a clause, and no interposition (RULED by existing pins)

Question 12. The issue's interposition proposal gives one handler several chances at one
operation. This kernel has none of that; what it has is fully pinned, and recorded here so
the map is complete:

- The innermost region of a tag answers, for every flavour: `ArrowEffectTest` "the
  innermost done wins under nested same-tag handlers" (:406), "the innermost handle wins
  under nested same-tag handlers" (:1222), "the innermost handleFirst wins under nested
  same-tag handlers" (:1584); `Stack.find` walks from the top (Stack.scala:171-177).
- A `handleCont` clause that raises its own effect re-enters its own region, since the
  clause result runs with the region on the stack (Eval.scala:78-80): `ArrowEffectTest` "an
  operation the clause raises re-enters the same region" (:1380). There is no way for such a
  clause to delegate to the outer handler of the same effect short of the mask.
- A `handleLoop` clause that raises its own effect before its outcome reaches the
  successor, because the region is popped first (Eval.scala:104-111, 135-145): `EvalTest`
  "a clause's own-tag suspension before its outcome is answered by the successor" (:1300).
  The same effect raised inside a pending *answer* is answered by the region itself, which
  is still standing (Eval.scala:130-134): `EvalTest` "an effectful answer's own-tag re-raise
  is answered by this handler" (:276).
- A handler's own `recover` sees its own clause's throw on every dispatch path where the
  region stands (`ArrowEffectTest` "recovers a throw in the handler", :1656), and a recovery
  standing *inside* the region is passed over by a clause throw (:2226).
- The one operation that lets a body skip an inner handler is `ArrowEffect.Mask`
  (ArrowEffect.scala:480-500), covered by `ArrowEffectMaskTest`; it is the issue's
  "effect encapsulation" as a value and works in the direction the OP does not need.

No new pin; the consequence for the issue is that "the current handler gets a second
chance at the `catch`" is not expressible, and the use-site semantics is obtained by
answering with a computation (entry 3).

### 16. Release ordering when a continuation is never resumed, and when a bracket meets a multi-shot clause (RULED by existing pins and rulings)

Question 13. All decided:

- A dropped continuation's regions release when the answering region exits, before that
  region's own continuation runs: `EvalTest` "a dropped capture's regions release when the
  answering region exits" (:1008), `EffectBracketTest` "a discarded continuation releases
  when its region completes, before the handler's continuation" (:981); the drain is
  `arrowExit` (Eval.scala:291-295).
- Sibling dumps drain newest first at the owner's exit: `EvalTest` :1027; a `Loop.done`
  releases every outstanding bracket innermost first: `EffectBracketTest` :623, :150.
- A multi-shot clause over a bracket: the second shot is refused with `kyo.Closed` and the
  first shot's release is the only one (:358, :1051, :1094, :1114); a bracket acquired *per
  branch* releases where each branch ends (:1035); a bracket that encloses the multi-shot
  region releases after every branch (:1232); a bracket the clause opens around the
  continuation releases when the body result settles (:998).
- A continuation held past the end of the eval refuses every time it is applied (:1135),
  and a branch built inside a clause and evaluated later is refused (:1206).
- A discarded capture's bracket is told the discard signal, a `KyoException` (:562).

Entry 8 adds the raw-hook order these pins do not state; entry 10 adds the bracket that
stands outside the handler.

## What reading could not decide, and the one experiment for each

1. Whether the S4 law ("a region crossed to a foreign clause that resumes ... completes
   without a release") holds per eval or per stack (entry 1). Experiment: run entry 1's
   sketch on JVM. Red confirms the cross-eval gap; the fix is then a consumed mark on the
   `Stack.Snapshot` set by `installed` and honoured by `settle` and `expandOwed`, unless the
   ruling is that the owner drains what it still holds and the pin asserts the double firing.
2. Whether a `handleLoop` region's `recover` is meant to guard the clause's code after the
   clause suspends outward, as `handleCont`'s does (entry 7). Experiment: run both sketches.
   Making the loop side match the cont side means re-installing the region around the
   pending outcome instead of popping it (Eval.scala:104-111, 135-145), which also flips
   `EvalTest` "a clause's own-tag suspension before its outcome is answered by the
   successor" (:1300) to "answered by itself"; the ruling has to pick one of the two laws.
3. Whether a raw `ContextEffect.handle` region may be revived after its `release` fired
   (entry 9), or should carry a released mark and refuse on `reenter` the way the bracket's
   cell does. Experiment: run entry 9's sketch next to its bracket twin
   (`EffectBracketTest` :1155); the same mark answers item 1 if it lives on the snapshot.
4. Whether a join whose origin is gone should fall back to the innermost live region of
   its tag (entry 13). Experiment: run entry 13's sketch; a ruling for tag fallback turns
   the `eq origin` search at Isolate.scala:130 into a `findExact` and the pin's expected log
   into `List("done first 10", "join second", "done second 30")`.
5. Whether the row carried by a stashed continuation is the intended guard against issue
   13's swap (entry 4b), so that a resume site missing one of the clause's effects is a
   compile error and never a runtime hole. Experiment: a `typeCheckFailure` pin resuming
   `stash.get(())` under a site that handles `Say` but not `Cfg`.
6. Which key a context read at a supertype tag returns when both the exact key and a
   subtype key are bound, which `rebound` and `installed` rely on for tags related by
   subtyping (Context.scala:17-24, `TypeMap` not read for this audit). Experiment:
   `handleInheritable(Tag[Cfg], 1)(handleInheritable(Tag[CfgSub], 2)(read))` and the
   reverse nesting, asserting the innermost value in both.
