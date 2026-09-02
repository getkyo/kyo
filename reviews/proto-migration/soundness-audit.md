# Soundness audit of the proto kernel

Static read of `kyo-kernel/shared/src/main/scala/kyo/proto` (packages `kyo.proto`,
`kyo.proto.kernel`, `kyo.proto.kernel.internal`), the platform `Safepoint` and `Report`
files, and every test under `kyo-kernel/*/src/test/scala/kyo/proto`, against the ten axes
in the brief. Nothing was run, compiled, or edited. Every prediction below is derived from
the code as it stands at commit `07cdd9d9bc`; line numbers refer to that tree.

Findings are numbered, red ones first. Each states the law it pins, the test, the exact
assertion, the value the current code is predicted to produce, and the path through the code
that produces it. Ranks:

- CONFIRMED-BY-READING: the path was traced end to end; the test is predicted red.
- LIKELY: a plausible path with one step not fully traceable by reading.
- COVERAGE: the law holds as far as the read goes, nothing pins it; the test is predicted green.

One correction to the reading model, recorded because it changes several traces: the base
`Arrow.apply(v: A)` (Arrow.scala:16-18) and `Id.apply(v: A)` (Arrow.scala:39) hand a raw `A`
to a `A < S` position from outside the `<` companion, so the implicit lift fires there and
`Nested.nest`s a raw pending payload. That is the one representation-preserving step at the
`k(...)` boundary in `Handler.answersLoopState` (Handler.scala:162) and in every `cont(x)`
a clause makes. It is load bearing and silent; it is also why a generic `def box[A](v: A):
A < Any = v` nests while a primitive lift does not.

## Test prelude

All cases below assume the fixtures the existing suites already use:

```scala
import kyo.Closed
import kyo.Const
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Kyo
import kyo.proto.Loop
import kyo.proto.kernel.*
import kyo.proto.kernel.internal.EffectTrace
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Safepoint

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

sealed trait Cfg    extends ContextEffect[Int]
sealed trait CfgSub extends Cfg
def read: Int < Cfg = ContextEffect.suspend(Tag[Cfg])

def answerAsk[A, S](value: Int)(v: A < (Ask & S)): A < S =
    ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue((), value: Int < Any), a => a)

def recordSay[A, S](log: collection.mutable.ListBuffer[String])(v: A < (Say & S)): A < S =
    ArrowEffect.handleLoop(Tag[Say], v)([C] => s => { log += s; Loop.continue((), (): Unit < Any) }, a => a)

def hooked[A, S](log: collection.mutable.ListBuffer[String], label: String, value: Int)(v: A < (Cfg & S)): A < S =
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

## Findings

### 1. `Loop.repeat` drops a pending body that is not an `Arrow` node (CONFIRMED-BY-READING)

Axis 9 and axis 1. Law: `Loop.repeat(n)(body)` evaluates `body` exactly `n` times for every
`body: Any < S`, whatever node the value is.

```scala
"repeat evaluates a bracket body n times" in {
    var acquired = 0
    var released = 0
    val body: Unit < Any =
        Effect.bracket(Effect.defer { acquired += 1; acquired })((_, _) => released += 1)(_ => Effect.defer(()))
    Loop.repeat(3)(body).eval
    assert(acquired == 3)
    assert(released == 3)
}

"repeat evaluates a bare suspension n times" in {
    val log = collection.mutable.ListBuffer[String]()
    recordSay(log)(Loop.repeat(3)(say("x"))).eval
    assert(log.toList == List("x", "x", "x"))
}

"repeat evaluates a handled region n times" in {
    var answered = 0
    val body: Int < Any =
        ArrowEffect.handleLoop(Tag[Ask], ask)([C] => _ => { answered += 1; Loop.continue((), 1: Int < Any) }, a => a)
    Loop.repeat(2)(body).eval
    assert(answered == 2)
}
```

Predicted: `acquired == 0`, `released == 0`; `log.isEmpty`; `answered == 0`.

Reasoning. Loop.scala:404-421 matches the body with `case _: Arrow[?, ?, ?] =>
suspended(i + 1)(v)` and otherwise `loop(i + 1)`, silently treating any non-`Arrow` value as
settled. The pending nodes that are not `Arrow`s are the plain `Kyo.Defer` built by
`Effect.defer(v, cont)` (Effect.scala:70-82), the plain `Kyo.SuspendArrow` built by
`ArrowEffect.suspend` (ArrowEffect.scala:35), the plain `Kyo.SuspendContext` built by
`ContextEffect.suspend` (ContextEffect.scala:19, 46), every `Kyo.Handle` built by
`handleCont`, `handleLoop`, `handleLoopState`, `handleFirst`, `Kyo.handle`, and
`ContextEffect.handle` (ArrowEffect.scala:79, 111, 152, 293, 345; KyoInternal.scala:92;
ContextEffect.scala:136), `Kyo.Park`, and any `Kyo.Snapshot` that is not a `SnapshotWith`.
`Effect.bracket` returns `defer(acquire).chain(ensure)` (Effect.scala:67); `Arrow.Ensure.apply`
on a pending input returns `Effect.defer(v, this, cont)` (Arrow.scala:105-106), and with
`cont` equal to `Id` the three argument `defer` collapses to the two argument one
(Effect.scala:86-87), which allocates a plain `Defer` (Effect.scala:78-81). `Loop.apply`,
`Loop.foreach`, `Loop.forever`, `Loop.whileTrue`, and `flatten` also hand back a plain
`Defer` when they suspend (Loop.scala:175, 209, 396, 445, 478; Pending.scala:276). Only
values that end in a `map`, `flatMap`, `andThen`, `unit`, `suspendWith`,
`Effect.defer(thunk)`, or a `*With` handler are `Arrow`s. The pinned cases
(LoopTest "repeat", "repeat with a settled body") use `Effect.defer(thunk)` and a settled
value, both on the green side of the `Arrow` test. `Kyo.scala` is not exposed because every
combinator wraps its step in `map` or `andThen`.

### 2. `Loop.indexed` returns a non-`Arrow` pending outcome as the loop's value (CONFIRMED-BY-READING)

Axis 9, axis 1, axis 8. Law: `Loop.indexed(run)` iterates until `run` produces a done outcome
and returns that payload, for every `run` result of type `Outcome[A, O] < S`.

```scala
"indexed iterates a bracket-shaped step" in {
    var acquired = 0
    val r: Int < Any = Loop.indexed { i =>
        Effect.bracket(Effect.defer { acquired += 1; i })((_, _) => ()) { j =>
            if j < 2 then Loop.continue else Loop.done(j)
        }
    }
    assert(r.eval == 2)
    assert(acquired == 3)
}

"indexed iterates a step that is itself a suspended loop" in {
    var rounds = 0
    val r: Int < Any = Loop.indexed { i =>
        Loop.foreach {
            Effect.defer {
                rounds += 1
                Loop.done(if i < 2 then Loop.continue else Loop.done(i))
            }
        }
    }
    assert(r.eval == 2)
    assert(rounds == 3)
}
```

Predicted: the first `eval` throws `ClassCastException` (a `Loop.Continue` delivered where
an `Int` is unboxed) with `acquired == 1`; the second likewise with `rounds == 1`.

Reasoning. Loop.scala:289-298 (and the four arities that follow, 302-370) dispatch the step's
result with `case _: Arrow[?, ?, ?] => suspended(idx)(v)`, `case res: Done[?]`, and
`case res => res.asInstanceOf[O < S]`. A plain `Defer` from `Effect.bracket` or from the inner
`Loop.foreach` (Loop.scala:396) falls to the last arm and is returned as the whole loop's
value. The evaluator then runs it once; the `Loop.continue` it produces (the shared
`_continueUnit`, Loop.scala:62-68) is delivered as the `Int` result and the `.eval` unbox
fails. The pinned `indexed` cases all suspend through `Effect.defer(thunk)`, an `Arrow`.

### 3. A context binding leaks past its region's exit when tags are related by subtyping (CONFIRMED-BY-READING)

Axis 7 and axis 2. Law: after a context region exits, no binding it introduced or rebound is
visible; a defaulted read after both regions exit takes the default.

```scala
"a region's exit under a subtype binding does not leave a supertype key behind" in {
    val inner: Int < Any = ContextEffect.handleInheritable(Tag[Cfg], 2)(read)
    val outer: (Int, Int) < Any =
        ContextEffect.handleInheritable(Tag[CfgSub], 1)(
            inner.map(a => ContextEffect.suspend(Tag[Cfg], -1).map(b => (a, b)))
        )
    val v = outer.map((a, b) => ContextEffect.suspend(Tag[Cfg], -1).map(c => (a, b, c)))
    assert(v.eval == (2, 1, -1))
}
```

Predicted: `(2, 1, 1)`.

Reasoning. Entering the outer region binds `CfgSub -> 1` (Eval.scala:361-362, keyed by the
region's own tag). Entering the inner binds `Cfg -> 2`; the read takes 2. At the inner exit
`contextExit` (Eval.scala:229-236) calls `stack.find(hc.tag)`, and `find` matches by
`handlers(i).tag.erased <:< tag.erased` (Stack.scala:146-152), so the outer `CfgSub` region is
found for `Cfg` and the context is updated with `ctx.update(Tag[Cfg], 1)`: a second key,
`Cfg -> 1`, now sits beside `CfgSub -> 1` (TypeMap.addErased removes only the exact key,
TypeMap.scala:179-180). The read of `Cfg` with default inside the outer region correctly
yields 1. At the outer exit `find(Tag[CfgSub])` returns -1 and `ctx.remove(Tag[CfgSub])`
runs `TypeMap.removeExact` (Context.scala:32, TypeMap.scala:170-177), which drops only the
`CfgSub` node. `Cfg -> 1` survives, and the last read finds it through
`Context.get`'s subtype search (Context.scala:17, TypeMap.scala:142-148) instead of taking
the default. `rebound` (Eval.scala:125-138) has the same add-by-supertype-key shape for
dumped regions, and the only path that rebuilds the context from the stack is the throw path
(`guarded`, Eval.scala:467-474), which is why no existing case sees it: ContextEffectTest
"extent" and EvalTest "the exit law" use one tag.

### 4. A contextual isolate's join is scoped to the innermost region at restore time, not to the binding it joins (CONFIRMED-BY-READING)

Axis 7. Law (IsolateTest "the origin continues at the joined state", extended across a region
boundary): after `restore`, the origin's reads of the joined binding see the joined state for
the rest of the binding's own extent, whatever arrow regions open and close in between.

```scala
"the joined state outlives an arrow region that closes between the restore and the read" in {
    val contextual = Isolate.internal.Contextual
    sealed trait Bind extends ContextEffect[Int]
    def readBind: Int < Bind = ContextEffect.suspend(Tag[Bind])
    val prog: (Int, Int) < Bind =
        contextual.capture { st =>
            answerAsk(0)(
                contextual.restore(contextual.isolate(st, readBind)).map(child => ask.map(_ => child))
            ).map(child => readBind.map(after => (child, after)))
        }
    val r = ContextEffect.handle(Tag[Bind])(
        (_: Maybe[Int]).getOrElse(10),
        fork = (p: Int) => p * 2,
        join = (p: Int, _: Int, c: Int) => p + c
    )(prog)
    assert(r.eval == (20, 30))
}
```

Predicted: `(20, 10)`.

Reasoning. `restore` (Isolate.scala:87-103) realizes the join as `Kyo.Park(cont2(av, Id),
joined)`, where `cont2` is the continuation the evaluator holds at the `Snapshot` node
(Eval.scala:381: `contA.chain(contB)`), which is only the remainder up to the innermost open
region's boundary, since region continuations live on the stack. The park installs a fresh
`Bind -> 30` region above the `Ask` region (Eval.scala:377-378, 187-227); nothing updates the
owning `Bind -> 10` region's state slot. When the installed region exits at the end of `cont2`,
`contextExit` (Eval.scala:229-236) rebinds the context from `stack.find(Bind)`, the origin at
state 10. The `Ask` region then completes and the read after it sees 10. The pinned case
passes only because no region boundary intervenes between the restore and the read. Whether
the law should be "for the rest of the owning binding's extent" (the kernel `Var` isolate
semantics, where restore sets the enclosing `Var`) or "until the innermost region exits" is a
ruling; the current shape is neither documented nor pinned.

### 5. A debt re-homed below the answering region is never settled by the resume, so a raw release hook fires after done (CONFIRMED-BY-READING)

Axis 4 and axis 3. Law (backlog Q7, "the double release gone (its pin flipped to one
release)"): a region dumped by a crossing and re-installed by the resume that completes
normally fires `done` once and `release` never.

```scala
"a foreign loop clause that suspends before its outcome leaves the inner region's debt unsettled" in {
    val log = collection.mutable.ListBuffer[String]()
    val body: Int < (Ask & Say) = hooked(log, "cfg", 1)(ask.map(x => x + 1))
    val handled: Int < Say = ArrowEffect.handleLoop(Tag[Ask], body)(
        [C] => _ => say("pre").map(_ => Loop.continue((), 41: Int < Any)),
        a => a
    )
    assert(recordSay(log)(handled).eval == 42)
    assert(log.toList == List("pre", "done cfg 1"))
}

"a resume wrapped in a region inside the clause leaves the debt unsettled" in {
    val log = collection.mutable.ListBuffer[String]()
    sealed trait Other extends ContextEffect[Int]
    val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(x => x + 1))
    val handled: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
        [C] => (_, cont) => ContextEffect.handleInheritable(Tag[Other], 5)(cont(41)),
        a => a
    )
    assert(handled.eval == 42)
    assert(log.toList == List("done cfg 1"))
}
```

Predicted: `List("pre", "done cfg 1", "release cfg 1")` and `List("done cfg 1",
"release cfg 1")`.

Reasoning, first case. The `ask` inside the `Cfg` region is answered by the `Ask` region
below it (stack `[Say, Ask, Cfg]`, `idx = 1`), the foreign loop arm (Eval.scala:313-352).
`running` returns the pending `say("pre")...`, so `dumped(stack, 1, kyo)` moves `Cfg` into a
snapshot owed to lane 1 (Stack.scala:154-174, `owed(from - 1)`), the `Ask` entry is popped,
and `oweBelow(1, takePopped())` moves that debt to lane 0, the `Say` entry (Eval.scala:332-333,
Stack.scala:55-59). The clause outcome settles to `Continue2`, `clauseDispatch` rebuilds the
`Ask` region as a fresh `Handle` (Handler.scala:81-86) and applies the crossing, whose park
re-installs `Cfg` (KyoInternal.scala:65-81, Eval.scala:187-227). The install's only settle is
`stack.settle(stack.depth - 1, entries)` (Eval.scala:202), which inspects the lane of the
fresh `Ask` entry, empty; the debt sits in lane 0 and is never matched. `Cfg` completes and
`contextExit` fires `done`. When `Say` exits, `arrowExit` drains lane 0 through
`drainDiscarded` (Eval.scala:238-240, 119-123) and `released` fires the hook with the
"remainder discarded" signal. The bracket twin (EffectBracketTest "an effectful loop clause
resuming after the pop completes the bracket") is green only because `Cell.drain` after
`complete` is a no-op CAS (Effect.scala:22-25); the kernel path is the same.

Second case: the debt lands in the `Ask` entry's own lane, but the crossing's park is installed
while the clause's `Other` region is the top entry, so the settle checks `Other`'s empty lane
(Eval.scala:202) and the `Ask` exit drains the debt after `Cfg` already completed.

### 6. The effect trace skips a later eval's regions on the same thread because the carrier remembers the pooled stack instance (CONFIRMED-BY-READING)

Axis 10. Law (EffectTraceTest "regions splice innermost first", "nested evals accumulate their
regions"): every region a failure crosses on its way out contributes its label to the carrier.

```scala
"a failure rethrown through a later eval on the same thread names the later eval's region" in {
    val shared = new RuntimeException("shared")
    def carrier(ex: Throwable) = ex.getSuppressed.collectFirst { case c: EffectTrace => c }
    intercept[RuntimeException](answerAsk(1)(ask.map(_ => (throw shared): Int)).eval)
    val log = collection.mutable.ListBuffer[String]()
    intercept[RuntimeException](recordSay(log)(say("x").map(_ => (throw shared): Int)).eval)
    val regions = carrier(shared).toList.flatMap(_.elements.filter(_.getMethodName == "handle").map(_.getClassName))
    assert(regions.exists(_.endsWith("Ask")))
    assert(regions.exists(_.endsWith("Say")))
}
```

Predicted: the `Say` assertion fails; only `Ask` is present.

Reasoning. `reconstruct` (EffectTrace.scala:56-69) skips the walk when
`carrier.seen.exists(_ eq stack)` and records `carrier.seen = stack` otherwise. The stack is
the pooled `Stack` object: `Stack.release` pushes it back and the next `Stack.borrow` on the
same thread pops the same instance (Stack.scala:239-261, StackThreadingTest "borrow is thread
local" pins the `eq`). The second eval's `guarded` catch (Eval.scala:461) therefore sees a
carrier that "already walked this stack" and attaches nothing, although the stack's contents
are a different eval's regions. The dedup meant to stop the unwind from re-adding the regions
`answering` already attached (Q5) uses object identity where it needs eval identity. The
threading pin uses two threads, hence two stacks, and does not reach this.

### 7. `ContextEffect.handle` on a settled body hands `done` a state derived from nothing, at construction time (CONFIRMED-BY-READING)

Axis 7 and axis 1. Law: the state a region reports to `done` is `derive(outer)` for the
enclosing binding, and a settled body and a deferred settled body are observationally the same.

```scala
"done receives the same derived state for a settled body and a deferred one" in {
    val seen = collection.mutable.ListBuffer[Int]()
    def region(v: Int < Cfg): Int < Cfg =
        ContextEffect.handle(Tag[Cfg])(
            (o: Maybe[Int]) => o.getOrElse(0) + 1,
            fork = (p: Int) => p,
            join = (p: Int, _: Int, _: Int) => p,
            done = (s: Int) => discard(seen += s)
        )(v)
    val outer: Int < Any =
        ContextEffect.handleInheritable(Tag[Cfg], 10)(
            Effect.defer(region(42)).map(a => region(Effect.defer(a)))
        )
    assert(outer.eval == 42)
    assert(seen.toList == List(11, 11))
}
```

Predicted: `List(1, 11)`.

Reasoning. ContextEffect.scala:143-145: the settled arm runs `done(derive(Maybe.empty))`
eagerly, wherever the expression is built, with no access to the enclosing binding (10 here)
and before the outer region has necessarily been entered. The pending arm derives from
`ctx.get(handler.tag)` at entry (Eval.scala:361). The derivation record calls the settled
arm out as deliberate for brackets ("so a pure `use` cannot strand the obligation"); for a
user handler whose `derive` reads the outer value it is a different program. Nothing pins the
state `done` receives on the settled path.

### 8. A `ContextEffect.handle` node re-derives its state on every read (CONFIRMED-BY-READING)

Axis 4 and axis 1. Law: `derive` runs once per region entry; a node held as a value has one
state.

```scala
"abandoning an unentered region derives once and releases that state" in {
    var derives = 0
    val log = collection.mutable.ListBuffer[String]()
    val region: Int < Any =
        ContextEffect.handle(Tag[Cfg])(
            (_: Maybe[Int]) => { derives += 1; derives },
            fork = (p: Int) => p,
            join = (p: Int, _: Int, _: Int) => p,
            release = (s: Int, _: Throwable) => discard(log += s"release $s")
        )(read)
    Eval.release(region, Boom)
    discard(region.toString)
    Eval.release(region, Boom)
    assert(derives == 1)
    assert(log.toList == List("release 1", "release 1"))
}
```

Predicted: `derives == 3` and `List("release 1", "release 3")`.

Reasoning. ContextEffect.scala:140 defines the handle node's state as `def state =
h.derive(Maybe.empty)`, a fresh derivation per access. `Eval.release` reads `kyo.state` for
every `Handle` it collects (Eval.scala:38), and `Handle.toString` reads it again
(KyoInternal.scala:110-111). EvalTest "a discarded region value releases its interior before
its own extent" pins that an unentered region's release fires, so the observable defect is the
count and the value, not the firing: each abandonment sees a different state and `derive`'s
side effects run at release time. The bracket is immune because its node captures `cell` in a
`val` (Effect.scala:59-65).

### 9. A nested `Eval.partial` consumes the enclosing slice's stop (LIKELY)

Axis 6. Law: a stop requested for a thread is honored by the slice that is running, and a
slice does not lose a stop to a nested partial it started.

```scala
"a stop arriving inside a nested partial still parks the enclosing slice" in {
    var innerParked = false
    val inner: Int < Any = Effect.defer { requestStop(); Effect.defer(1) }
    val outer: Int < Any =
        Effect.defer {
            innerParked = Eval.partial(inner).evalNow.isEmpty
            ()
        }.map(_ => Effect.defer(41)).map(_ + 1)
    val p = Eval.partial(outer)
    assert(innerParked)
    assert(p.evalNow.isEmpty)
    assert(p.eval == 42)
}
```

Predicted: `innerParked` true, `p.evalNow == Maybe(42)`: the outer slice runs to completion.

Reasoning. `Eval.partial` (Eval.scala:151-157) consumes the slot's stop in its `finally`
regardless of which slice the stop was aimed at; the inner partial parks, consumes, and returns
the park to the outer thunk, which discards it here. The outer slice's later `Defer` nodes
poll `Safepoint.stopped` (Eval.scala:246) and see nothing. The step not traceable by reading
is whether the scheduler contract forbids a nested `partial` in a slice; if it does, this is a
documentation item, otherwise a sharing rule for the stop is missing.

### 10. The physical frames cached on the carrier belong to the first thread that spliced (LIKELY)

Axis 10. Law: a spliced stack trace shows the effect frames followed by the throwing thread's
own physical frames.

```scala
"a shared exception spliced on two threads shows each thread's own physical frames" in {
    val shared = new RuntimeException("shared")
    def run(marker: Int): Unit =
        try discard(answerAsk(1)(ask.map(_ => (throw shared): Int)).eval)
        catch case _: RuntimeException => ()
    run(1)
    val seenFirst = shared.getStackTrace.map(_.getMethodName).toList
    val t = new Thread(() => run(2), "second-splicer")
    t.start(); t.join()
    val seenSecond = shared.getStackTrace.map(_.getMethodName).toList
    assert(seenSecond.exists(_ == "run"))
    assert(seenSecond != seenFirst || true)
}
```

The assertion that matters needs a physical frame that only exists on the second thread (a
distinct method name on the second thread's call path); the shape above is a placeholder for
that. Predicted: after the second splice `shared.getStackTrace` is
`carrier.elements ++ physical` with `physical` cached from the first thread
(EffectTrace.scala:76-83, `carrier.physical` set once), so the second thread's frames never
appear. Not fully traceable by reading because `Throwable.getStackTrace` on a shared instance
was already overwritten by the first splice; the physical frames captured on the second thread
are those of the first splice, so the cache is consistent with itself and only the intent
("frames of the throwing thread") is violated.

### 11. Synthetic fork and join regions fire the user's completion hooks (LIKELY)

Axis 7. Law: `done` fires once per region the user installed; `fork` and `join` produce
states, not regions with their own completion edges.

```scala
"an isolate cycle fires done once, for the origin" in {
    val contextual = Isolate.internal.Contextual
    val log = collection.mutable.ListBuffer[String]()
    val prog: Int < Cfg = contextual.capture(st => contextual.restore(contextual.isolate(st, read)))
    val r = ContextEffect.handle(Tag[Cfg])(
        (_: Maybe[Int]).getOrElse(10),
        fork = (p: Int) => p * 2,
        join = (p: Int, _: Int, c: Int) => p + c,
        done = (s: Int) => discard(log += s"done $s")
    )(prog)
    assert(r.eval == 20)
    assert(log.toList == List("done 10"))
}
```

Predicted: `List("done 20", "done 30", "done 10")`.

Reasoning. `isolate` installs the forked entries as regions through a `Kyo.Park`
(Isolate.scala:83) and `restore` installs the joined entries the same way (Isolate.scala:99);
both exit through `contextExit`, which fires `done` (Eval.scala:230). Whether the fork and join
copies are regions in their own right (each owing a completion) or bookkeeping is a ruling;
`Effect.bracket` sidesteps it with an inert fork cell, and no case with a `done` hook covers the
cycle.

### 12. A raw `Continue2` cannot reach the outcome channel without `Loop.done` (COVERAGE)

Axis 8, backlog Q2. Law: the only way a `Continue2` instance enters a clause outcome as a
payload is through `Loop.done`, which wraps it (Loop.scala:130-134), so the class dispatch in
`answers` and the eval arms (Handler.scala:153-154, Eval.scala:293, 318) cannot confuse it.

```scala
"an Any-typed Continue2 does not conform to the clause's outcome without Loop.done" in {
    assert(scala.compiletime.testing.typeCheckErrors(
        """
        val hostile: Any = Loop.continue((), 0: Int < Any).eval
        ArrowEffect.handleLoop(Tag[Ask], ask: Any < Ask)([C] => _ => hostile, (a: Any) => a)
        """
    ).nonEmpty)
}
```

Predicted green: `Outcome2` and `<` are opaque outside their companions (Loop.scala:49,
Pending.scala:13) and `CanLift[Any]` does not resolve (`Any <:< Any < Nothing` holds,
CanLift.scala:32), so a bare `Any` neither conforms nor lifts. This is the compile-time
closure the backlog asks for; it is worth pinning because a future widening of `Outcome2`
(for example a lower bound) would open the arm silently.

### 13. Multi-shot re-owes the same dumped snapshot per shot (COVERAGE)

Axis 3 and axis 4. Law: each shot of a crossing continuation re-installs the captured entries
with their captured debts, and each shot drains what it re-owes at its own exit.

```scala
"each shot of a crossing drains the debts it re-installs" in {
    val log = collection.mutable.ListBuffer[String]()
    val body: Int < (Ask & Say) =
        hooked(log, "outer", 1)(hooked(log, "inner", 2)(say("s").map(_ => 0)).map(a => ask.map(_ + a)))
    val handledSay: Int < Ask = ArrowEffect.handleCont(Tag[Say], body)([C] => (_, cont) => cont(()), a => a)
    val twice: Int < Any = ArrowEffect.handleCont(Tag[Ask], handledSay)(
        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        a => a
    )
    assert(twice.eval == 30)
    assert(log.count(_ == "done outer 1") == 2)
    assert(log.count(_ == "release outer 1") == 0)
}
```

Predicted green: `installed` copies `entries.owed(i)` onto each re-installed entry
(Eval.scala:216, 223); the inner region's debt, if any, is settled by the resume of the same
shot. Worth pinning because the `owe` on install is the only place a debt is duplicated by
design.

### 14. A context region whose `done` throws is released with the failure, once (COVERAGE)

Axis 5. Law: a throw inside `done` unwinds as a failure of the region, `release` sees it, and
neither hook runs twice.

```scala
"a throwing done is followed by one release carrying the failure" in {
    val log = collection.mutable.ListBuffer[String]()
    val r: Int < Any = ContextEffect.handle(Tag[Cfg])(
        (_: Maybe[Int]) => 7,
        fork = (p: Int) => p,
        join = (p: Int, _: Int, _: Int) => p,
        done = (_: Int) => throw Boom,
        release = (s: Int, ex: Throwable) => discard(log += s"release $s ${ex eq Boom}")
    )(read)
    assert(intercept[RuntimeException](r.eval) eq Boom)
    assert(log.toList == List("release 7 true"))
}
```

Predicted green: `contextExit` calls `done` before `pop` (Eval.scala:230-231), so the throw
finds the region on the stack and `recovered` runs `released(hc, state, ex)` once
(Eval.scala:419-425).

### 15. `Eval.release` twice on the same park fires a raw hook twice and a bracket once (COVERAGE)

Axis 4. Law: the kernel guarantees reachability at least once per edge; exactly-once belongs
to the state (the bracket's `Cell`).

```scala
"a double abandonment reaches a raw hook twice and a bracket once" in {
    val log = collection.mutable.ListBuffer[String]()
    var bracket = 0
    val v: Int < Any =
        Effect.bracket(Effect.defer(1))((_, _) => bracket += 1) { a =>
            hooked(log, "cfg", a)(Effect.defer { requestStop(); Effect.defer(a) })
        }
    val p = Eval.partial(v)
    assert(p.evalNow.isEmpty)
    Eval.release(p, Boom)
    Eval.release(p, Boom)
    assert(bracket == 1)
    assert(log.toList == List("release cfg 1", "release cfg 1"))
}
```

Predicted green (Eval.scala:26-62, Effect.scala:22-25). Pinning it records the at-least-once
ruling as behavior rather than as a sentence in a review.

### 16. `dispatchFirst` does not see through an isolate capture (COVERAGE)

Axis 7. Law as documented by the existing pins: `dispatchFirst` reports the first operation
through regions, parks, and deferrals. A `SnapshotWith` from `Isolate.run` hides it.

```scala
"dispatchFirst reports nothing under an isolate capture" in {
    var seen = 0
    ArrowEffect.dispatchFirst(Tag[Ask], Isolate.internal.Contextual.run(ask))([C] => _ => seen += 1)
    assert(seen == 0)
}
```

Predicted green (ArrowEffect.scala:209-218 has no `Snapshot` arm). If a scheduler relies on
`dispatchFirst` to route a fiber's first operation, an `Isolate.run` at the top of the fiber is
invisible to it; either add the arm or pin the exclusion.

### 17. A suspension at a subtype tag inside a fused loop takes the general path and is unhandled by the supertype region (COVERAGE)

Axis 2. Law (ArrowEffectTest "a supertype handler leaves a subtype effect in the row" for
`handleCont`): the fast path in `answersLoopState` and the general path agree on tag
resolution.

```scala
"the fused loop leaves a subtype operation to the general path" in {
    sealed trait AskSub extends Ask
    val askSub: Int < AskSub = ArrowEffect.suspend[Any](Tag[AskSub], ())
    val body: Int < AskSub = ask.map(a => askSub.map(b => a * 10 + b))
    val r = ArrowEffect.handleLoop(Tag[Ask], body)([C] => _ => Loop.continue((), 1: Int < Any), a => a)
    val out: Int < Any = ArrowEffect.handleLoop(Tag[AskSub], r)([C] => _ => Loop.continue((), 2: Int < Any), a => a)
    assert(out.eval == 12)
}
```

Predicted green: the fast path compares `sN.tag.erased =:= effectTag.erased`
(Handler.scala:169, 177), so `AskSub` falls to `Loop.continue(st, next)` and the evaluator's
`find` resolves it to the `AskSub` region. Worth pinning because the fast path is the one
place tag equality replaces tag subtyping.

### 18. A stop arriving in the fused loop parks a boxed answer intact (COVERAGE)

Axis 6 and axis 1. Law (ArrowEffectTest "a boxed answer crosses the answers loop unopened",
extended across a park): a `Nested` answer parked by the armed check in `answersLoopState`
(Handler.scala:163-165) resumes as data.

```scala
"a boxed answer parked mid loop is still data after the resume" in {
    sealed trait Give extends ArrowEffect[Const[Unit], Const[Int < Say]]
    val give: (Int < Say) < Give = ArrowEffect.suspend[Any](Tag[Give], ())
    val payload: Int < Say = say("p").map(_ => 7)
    val region: (Int < Say) < Any =
        ArrowEffect.handleLoop(Tag[Give], give.map(v => Kyo.lift[Int < Say, Any](v)))(
            [C] => _ => { requestStop(); Loop.continue((), Kyo.lift[Int < Say, Any](payload)) },
            a => Kyo.lift[Int < Say, Any](a)
        )
    val parked = Eval.partial(region)
    val got: Int < Say = parked.eval
    assert(got.asInstanceOf[AnyRef] eq payload.asInstanceOf[AnyRef])
}
```

Predicted green: the park wraps `next` (already the applied continuation) in
`Effect.defer(next, Arrow.id)`, and `next` for an `Id` continuation is the `Nested` returned by
`Id.apply` through the lift, so the box is preserved.

### 19. A foreign in-place answer updates the state a recovery sees (COVERAGE)

Axis 5 and axis 2. Law (ArrowEffectTest "a stateful region's recovery clause receives the live
state", extended to the foreign arm): `stack.setState` at Eval.scala:320 is what `recovered`
reads.

```scala
"a recovery after a foreign in-place answer sees the advanced state" in {
    val body: Int < (Ask & Say) = say("x").map(_ => ask.map(_ => (throw Boom): Int))
    val inner: Int < Say = ArrowEffect.handleLoopState(Tag[Ask], 0, body)(
        [C] => (s, _) => Loop.continue(s + 1, 1: Int < Any),
        (_, a) => a,
        (s, _) => Maybe(-100 - s)
    )
    val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
    assert(r.eval == -101)
}
```

Predicted green. The `say` is answered above the `Ask` region, so the `ask` that follows is
foreign to nothing (it is at the top by then); to force the foreign arm, wrap the `ask` in a
`hooked` region. The value stays -101 either way; the pin is the arm, not the number.

### 20. A leaked resume's `Closed` is an ordinary failure a recovering enclosure can catch (COVERAGE)

Axis 10 and axis 5. Law: `kyo.Closed` is thrown from `reenter` at install (Effect.scala:56-58,
Eval.scala:193-197) after the park has been released, and it is `NonFatal`, so an enclosing
`recover` may answer it.

```scala
"a leaked capture's refusal is recoverable by the region that resumes it" in {
    var leaked = Maybe.empty[Arrow[Int, Int, Ask]]
    val body: Int < Ask = Effect.bracket(Effect.defer(7))((_, _) => ())(a => ask.map(_ + a))
    val first: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, cont) => { leaked = Maybe(cont); -1 }, a => a)
    assert(first.eval == -1)
    val again: Int < Any =
        ArrowEffect.handleCont[Const[Unit], Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], leaked.get(1))(
            [C] => (_, cont) => cont(0),
            a => a,
            ex => Maybe(if ex.isInstanceOf[Closed] then -2 else -3)
        )
    assert(again.eval == -2)
}
```

Predicted green. Pinning it decides whether refusal is meant to be catchable; if not, the
throw site needs to bypass `recovered`.

### 21. The foreign clause outcome is retained on the pooled stack until the eval ends (COVERAGE, informational)

Axis 2. Eval.scala:315 writes `stack.scratch = outcome0` and nothing reads `scratch`; it is
cleared only by `Stack.clear` at release (Stack.scala:73-87). It is a dead write that retains
the last foreign outcome for the eval's lifetime. No test can observe it through the public
surface; it belongs in the backlog's "leaking fields" ledger.

## What reading could not decide, per axis, and the one experiment that would

1. Representation contract. Whether the implicit lift inside `Arrow.apply(v: A)` is relied on
   knowingly (the SKILL lists "one summon each" for Arrow.scala and Eval.scala). Experiment:
   replace `this.head(v, this.tail)` with `this.head(v.asInstanceOf[A < Any], this.tail)` and
   run PendingTest; "an answer can be a computation value" and "a loop answer payload delivers
   unwrapped through a bare suspension" turn red if the lift is load bearing.
2. Handler answers and regions. Whether any public shape can push a second debt into one lane
   between a dump and its resume in a single shot (which would defeat `settle`'s
   last-only check even on the paths it does reach). Experiment: finding 5's first case with
   the inner body raising a second foreign operation after the resume, logging release order.
3. Foreign crossings. Whether a crossing resumed from a different eval (a fiber handed the
   remainder) is meant to drain at the original region's exit under the region-exit ruling, or
   whether the resume site should adopt the debt. Experiment: EvalTest "a captured continuation
   is a value" with a `hooked` region inside the crossed body, resumed on another thread after
   the first eval completed, counting `release` per resume.
4. Brackets and abandonment. Whether the fired hooks in findings 5, 8, 11, and 15 fall under
   the at-least-once ruling or the Q7 exactly-once direction. Experiment: run findings 5 and 11
   as written; a green run means the ruling is at-least-once and the pins should assert that.
5. Recovery. Whether a recovery that returns a throwing computation should be re-consulted
   (it is not: `guarded` pops the region before evaluating the recovered value,
   Eval.scala:464). Experiment: `recover = _ => Maybe(Effect.defer(throw Second))` under a
   second recovering region, asserting the second sees `Second` and the first ran once.
6. Parks and the safepoint. Whether nested `Eval.partial` inside a slice is allowed by the
   scheduler contract (finding 9), and whether the `Overflowed` slot's shared budget counter
   (Safepoint.scala:23-24, 115-123 on jvm-native) can be driven to a wrong sign by
   concurrent `enter`/`exit` from overflowed threads. Experiment: SafepointConcurrencyTest
   "the overflowed slot" with 64 overflowed threads hammering `enter`/`exit` and one asserting
   `enter` still returns true after the storm.
7. Isolate and ContextEffect. Which scope owns a join (finding 4) and whether fork and join
   copies are regions (finding 11). Experiment: run findings 4 and 11; the values they produce
   are the ruling's raw material.
8. Loop.Outcome nesting. Nothing open; finding 12 is the compile-time closure.
9. Stack safety and the fusion fast paths. Whether `Loop.whileTrue`'s recursion through
   `condition.map` (Loop.scala:457-484, `loop` calls itself inside the map's function) relies
   on the budget rescue alone. Experiment: `Loop.whileTrue(counter < n)(counter += 1)` with
   `n = 1_000_000` on a 256 KB thread, as ArrowEffectThreadingTest does for the clause.
10. Effect trace and Closed. Finding 6 is decided by reading; finding 10 needs a thread with a
    distinct physical frame to assert against.
