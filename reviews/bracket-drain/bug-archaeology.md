# Bug archaeology: cats-effect and ZIO resource-safety defects, ported to `Effect.bracket`

Historical, verified bug reports from `typelevel/cats-effect`, `typelevel/fs2` and `zio/zio`
about bracket, Resource, finalizer and interruption defects, mapped onto the proto kernel's
model and ranked by what they would add over the 28 pins already in
`EffectBracketTest.scala`.

## Provenance of the code reads

Every claim about kernel behavior below is read from **HEAD (`6d5c316c8c`)**, not from the
working tree. During this session another agent reverted the worktree to a pre-bracket
baseline (`Effect.scala` currently has no `bracket`, `EffectBracketTest.scala` is deleted on
disk, `proto/Sync.scala` is restored). HEAD still carries the implementation: `def bracket`
in `Effect.scala`, 28 pins in `EffectBracketTest.scala`, `Arrow.Bind` at `Arrow.scala:96`.
Line numbers cite HEAD.

## The model map

Upstream bugs are written in a vocabulary this kernel does not have. The translation used
throughout:

| upstream concept | proto kernel counterpart |
|---|---|
| `fiber.cancel` / interruption of a suspended effect | a holder abandoning a parked remainder: `Eval.release(parked, signal)` |
| interruption that discards a continuation | a handler clause dropping its captured continuation, drained at the answering region's exit through the owed slot |
| `ExitCase` / `Exit` / `Outcome` payload | the `Maybe[Throwable]` passed to `release`: `Absent` on completion, `Present` on failure, abandonment, or discard |
| `Resource.use` error masking | the bind step's settle slice: `Arrow.Bind` applies strictly on settled input with no safepoint gate, so no stop can land between the acquire settling and the region installing |
| `uncancelable` / `poll` | not a user surface here; the only masked window is that one settle-to-install slice, which is masked by construction |
| `fork` / `start` / `foreachPar` | the contextual isolate, `Isolate.internal.Contextual`, whose `fork` installs `Cell.inert` so a child cannot drain a parent's obligation |
| finalizer that itself fails | `released` (`Eval.scala:144`) suppresses onto the signal and continues the walk |
| multi-shot resumption | genuinely novel: no upstream system has it, and it is where the sharpest open question lives (candidate 5) |

### Families that do not port, and why

Stated so the shortlist is not silently narrow:

- **Async finalizers and awaiting them.** cats-effect [#721](https://github.com/typelevel/cats-effect/issues/721) (`cancel` does not wait for finalizers already in flight), [#267](https://github.com/typelevel/cats-effect/issues/267) (finalizers not sequenced because cancel tokens were fire-and-forget, fixed by [#305](https://github.com/typelevel/cats-effect/pull/305)), fs2 [#2966](https://github.com/typelevel/fs2/issues/2966) (the bracket finalizer was itself cancelable, fixed by [#2968](https://github.com/typelevel/fs2/pull/2968) "Bracket finalizer should be uncancelable"). Here `release` is a pure `(A, Maybe[Throwable]) => Unit` thunk run to completion inside the eval slice. It cannot suspend, cannot be interrupted mid-flight, and cannot interleave with a sibling. **fs2 #2966 is structurally impossible in this kernel**, which is worth recording as a design property rather than a pin.
- **Runtime and scheduler machinery.** cats-effect [#3981](https://github.com/typelevel/cats-effect/issues/3981) and [#2926](https://github.com/typelevel/cats-effect/issues/2926) (Dispatcher), [#3205](https://github.com/typelevel/cats-effect/pull/3205) (`async_` made uncancelable because a cancel between callback registration and invocation orphaned it), ZIO [#950](https://github.com/zio/zio/issues/950) and [#6016](https://github.com/zio/zio/issues/6016). No fibers, no runtime, no callback registration at the kernel level.
- **Strict-evaluation hazards.** cats-effect [#532](https://github.com/typelevel/cats-effect/issues/532) (`Resource.allocated` typed `release: F[Unit]` instead of by-name, so under an eager `F` the release side effect fired at construction). Impossible here: `release` is a function value, never an evaluated computation.

---

## Ranked candidates

### 1. A release failure on the discard path is swallowed with no carrier

**Upstream.** ZIO [#3897](https://github.com/zio/zio/issues/3897), "Interruption exit lost":
a `ZIO.die` raised *inside* an `onInterrupt` finalizer was completely lost from the resulting
`Cause`; the exit showed only "An interrupt was produced by #1". Fixed by
[#3883](https://github.com/zio/zio/pull/3883), restoring "the trace of errors in finalizers".
Same family: ZIO [#940](https://github.com/zio/zio/issues/940) (finalizer defects caused
`ZManaged#flatMap`/`foldM`/`zipWithPar` to abandon the remaining finalizers) and
[#6125](https://github.com/zio/zio/issues/6125), whose fix PR
[#6133](https://github.com/zio/zio/pull/6133) explains that "an interrupt may have occurred
after the suppressed cause was added", so both causes must be combined rather than one
overwriting the other.

**Expressible?** Yes, and this is the one candidate where I believe the current
implementation is **wrong by comparison to both references**.

`Eval.drainDiscarded` (`Eval.scala:123-124`):

```scala
private def drainDiscarded(owed: Chunk[Stack.Snapshot]): Unit =
    if !owed.isEmpty then drainOwed(owed, new kyo.KyoException("remainder discarded")(using Frame.internal))
```

The signal is minted locally. `drainOwed` reaches `released` (`Eval.scala:144-154`), which on
a throwing release does `ex.addSuppressed(t)` where `ex` is that local `KyoException`.
`drainOwed` returns `Unit`. The synthetic exception is never thrown, never logged, never
handed to `Debugger`. **A `release` that throws while draining a discarded remainder vanishes
completely**: no suppression anybody can observe, no report, nothing.

Contrast the two references. ZIO folds a finalizer defect into the `Cause` (that is what
#3897 restored). cats-effect routes a finalizer error with nowhere to go to
`IORuntime.reportFailure`, the unhandled-error reporter. Neither drops it.

Pin `:264` ("a throwing release on the discard drain does not starve the ones after it")
proves non-starvation, which is the #940 half. It asserts nothing about observability, and it
*cannot*, because there is no carrier to assert on.

Note the asymmetry: the abandonment path is fine, because the caller owns the signal.
`EvalTest:782` asserts `cause.getSuppressed.exists(_ eq Bad)` and `EffectBracketTest:302`
asserts the same for a bracket. Only the discard path, which mints its own signal, loses it.

**Ported pin.** Not writable today. The smallest affordance that makes it writable is a
`Debugger` hook inside `released`'s catch, alongside the existing `Debugger.onRelease` at the
top of the same method:

```scala
catch
    case t if NonFatal(t) && (t ne ex) =>
        Debugger.onReleaseFailure(handler, t)   // new
        ex.addSuppressed(t)
```

With it, the pin is the direct ZIO #3897 analogue:

```scala
"a release that throws while draining a discarded remainder is reported" in {
    object Bad extends RuntimeException("bad", null, false, false)
    val body: Int < Ask =
        Effect.bracket(Effect.defer(1))((_, _) => throw Bad)(_ => ask.map(x => x))
    val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
    // observe through the debugger that Bad reached a reporter rather than a dropped local
    assert(eval(dropped) == -1)
}
```

**Prediction.** The observability pin **fails today** (there is nothing to observe). Whether
that is a defect or an accepted kernel-tier limitation is a ruling for the user; both
references decided it is a defect. Ranked first because it is invisible, it diverges from
both references, and the fix is one line plus a hook.

---

### 2. A bracket under a handler that recovers the failure

**Upstream.** cats-effect [#3757](https://github.com/typelevel/cats-effect/issues/3757),
"`Resource#attempt` holds onto acquired resources even in case of error": with acquire
incrementing a counter and release decrementing it, composing two resources and forcing an
error after the first acquires left the counter at 1 under `.attempt`, meaning the release
never ran. Closed with a milestone. Root cause: `attempt`'s recovery path short-circuited to
`Left(error)` without re-threading the resource's finalizer chain. Companion:
cats-effect [#2472](https://github.com/typelevel/cats-effect/issues/2472), "Unexpected
finalization of uncancelable resource", where `Resource.makeCase` reported
`ExitCase.Succeeded` at release time even when the body errored or was canceled; fixed by
[#2617](https://github.com/typelevel/cats-effect/pull/2617). That is the "finalizer runs, but
is told the wrong thing" half of the same family.

**Expressible?** Yes, directly. `Handler.ArrowHandler.recover` (`Handler.scala:33`) is the
kernel's error-absorbing edge, exposed publicly through the `ArrowEffect.handleCont` overload
that takes a `recover: Throwable => Maybe[B < (S & S2)]` (`ArrowEffect.scala:97`). A bracket
whose `use` fails, nested inside a region that recovers, is exactly `resource.use(...).attempt`.

**Coverage today: none.** The word `recover` does not appear anywhere in
`EffectBracketTest.scala`. `EvalTest:762` pins the *inverse* nesting ("a binding is not
released when an inner region recovers the failure": the binding is outside the recovering
region, its extent continues, so it correctly does not release). That is a different fact.

**Ported pin.**

```scala
"a bracket under a recovering handler releases with the failure, before the recovery" in {
    var seen  = Maybe.empty[Maybe[Throwable]]
    val order = collection.mutable.ListBuffer[String]()
    val body: Int < Ask =
        Effect.bracket(Effect.defer(7)) { (_, outcome) =>
            seen = Maybe(outcome)
            discard(order += "release")
        }(_ => Effect.defer((throw Boom): Int))
    val recovered: Int < Any =
        ArrowEffect.handleCont[kyo.Const[Unit], kyo.Const[Int], Ask, Int, Int, Any, Any](Tag[Ask], body)(
            [C] => (_, cont) => cont(0),
            a => a,
            _ =>
                discard(order += "recover")
                Maybe(9)
        )
    assert(eval(recovered) == 9)
    assert(seen.exists(_.exists(_ eq Boom)))
    assert(order.toList == List("release", "recover"))
}
```

Second pin, same shape but with the failure raised *after* a crossing
(`use = a => ask.map(_ => (throw Boom): Int)`), which exercises the dump, the owed slot, the
park re-install and the unwind together.

**Prediction: passes.** The Finalize region is pushed above the recovering entry, so
`Eval.recovered` (`Eval.scala:444`) walks top-down, hits the `ContextHandler` case first,
pops it, drains what it owes, and calls `released(hc, cell, Boom)`, which is `cell.drain(Boom)`.
Only then does it reach the `ArrowHandler` and call `recover`. So the release fires with
`Present(Boom)` and strictly before the recovery value exists, which is the cats-effect law
that #3757 violated.

Ranked second: the most frequently reported upstream shape, zero current coverage, cheap,
and expected green, so it is a contract lock rather than a bug hunt.

---

### 3. Two stacked brackets abandoned together

**Upstream.** cats-effect [#461](https://github.com/typelevel/cats-effect/issues/461),
"finalizer of `Bracket.guarantee` is not executed upon `fiber.cancel`": two chained
`.guarantee()` calls on a fiber, cancel it, and only the *last* finalizer ran. Reporter:
"Looks like only last guarantee is taken into account during cancellation." The reproduction
is two `Ref[Boolean]`s over `Async[F].never[Unit]`; after cancel only the second was `true`.

**Expressible?** Yes. Cancel maps to `Eval.release` on a park; two chained guarantees map to
two nested brackets with the park inside the innermost body.

**Coverage today: partial, and only as two separate mechanisms.** `EffectBracketTest:75`
pins abandonment for a *single* bracket. `EffectBracketTest:104` pins innermost-first for two
brackets on the *unwind* path, not the abandonment path. `EvalTest:655` pins innermost-first
on the abandonment path for two *bindings*, not for brackets with distinct `Cell`s. The
composition of all three is unpinned.

**Ported pin.**

```scala
"two stacked brackets abandoned together release innermost first" in {
    val log = collection.mutable.ListBuffer[String]()
    val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
        Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner")) { b =>
            Effect.defer {
                requestStop()
                Effect.defer(b + 1)
            }
        }
    }
    val parked = Eval.partial(v)
    assert(parked.isInstanceOf[Kyo.Park[?, ?]])
    Eval.release(parked, Boom)
    assert(log.toList == List("inner", "outer"))
}
```

**Prediction: passes.** `Eval.release` walks the park's `entries` in index order, appending
outermost first (`Eval.scala:45-58`), and `releaseCollected` (`Eval.scala:92`) walks the
buffer backwards, so the drain runs innermost first. Ranked third on fame and cheapness: this
is the canonical "N finalizers, only one ran on cancel" bug and it is currently only pinned
one mechanism at a time.

---

### 4. A failing acquire must never release

**Upstream.** cats-effect [#487](https://github.com/typelevel/cats-effect/issues/487),
"IOApp hangs when Resource contains exception": an exception thrown during acquisition printed
a stack trace and then hung forever. Reproduction is `Resource.liftF(IO.delay(throw new
RuntimeException("Test"))).use(...)`. Beyond that specific hang, "if acquire fails, release is
never called" is the foundational bracket law in both libraries.

**Expressible?** Yes, trivially.

**Coverage today: none.** Not one of the 28 pins has a failing acquire. The nearest,
`:142` ("the acquire is not guarded before it settles"), covers a *stop* landing mid-acquire,
not a *failure*.

**Ported pins.**

```scala
"a failing acquire never releases" in {
    var count = 0
    val v     = Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => count += 1)(a => Effect.defer(a))
    val ex    = intercept[RuntimeException](eval(v))
    assert(ex eq Boom)
    assert(count == 0)
}

"a failing inner acquire releases the outer bracket only" in {
    val log = collection.mutable.ListBuffer[String]()
    val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
        Effect.bracket(Effect.defer((throw Boom): Int))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
    }
    val ex = intercept[RuntimeException](eval(v))
    assert(ex eq Boom)
    assert(log.toList == List("outer"))
}
```

**Prediction: passes.** `bracket` is `defer(acquire).chain(open)`. The throw happens inside
`deferInline`'s `apply`, within `loop`, inside `guarded`'s `try`. `open.apply(a)` never runs,
so no `Cell` is ever constructed. Ranked fourth: it is a foundational law with zero coverage
and a two-line pin.

---

### 5. Multi-shot over the acquire: one resource, two obligations

**Upstream.** ZIO [#7](https://github.com/zio/zio/issues/7) / [#16](https://github.com/zio/zio/issues/16),
where the reporter observed both "inner finalizer never gets executed" and, on a second run,
"first specified finalizer gets executed twice somehow". Fixed by
[#62](https://github.com/zio/zio/pull/62) with the regression test `testBracketRegression1`
in `RTSSpec.scala`, asserting the ordered sequence under interruption.

**Expressible?** Yes, and this is the case **no upstream system can even pose**, because
their continuations are one-shot. It is therefore the highest-value probe for this kernel
specifically.

`Effect.bracket` is `defer(acquire).chain(open)`, and `open` is an `Arrow.Bind` whose apply
(HEAD `Arrow.scala:96-105`) is strict on settled input:

```scala
final def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2) =
    v match
        case v: Pending[A, S2] @unchecked => Effect.defer(v, this, cont)
        case _                            => cont.head(apply(Nested.unnest(v)), cont.tail)
```

So a capture taken *inside the acquire* and resumed twice applies `open` twice, minting two
`Cell`s. If the resource was created *before* the suspension, both `Cell`s wrap the same
physical resource and `release` runs twice for one acquisition.

**Coverage today: none.** `:336` ("a multi-shot capture over a bracket releases at the first
completion") puts the suspension in the `use`, so there is one `Cell` and one release. The
acquire side is untested.

**Ported pin, posing the question sharply.**

```scala
"a multi-shot capture taken inside the acquire opens one obligation per shot" in {
    val acquired = collection.mutable.ListBuffer[Int]()
    val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
    var n        = 0
    val acquire: Int < Ask =
        Effect.defer {
            n += 1
            discard(acquired += n)
            n
        }.map(r => ask.map(_ => r))
    val body: Int < Ask =
        Effect.bracket(acquire)((_, outcome) => discard(outcomes += outcome))(a => Effect.defer(a))
    val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
        [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x * 10 + y)),
        b => b
    )
    assert(eval(r) == 11)
    assert(acquired.toList == List(1))          // the resource was created once
    assert(outcomes.toList == List(Maybe.empty, Maybe.empty))  // released twice
}
```

**Prediction: mechanically passes as written (two releases for one acquisition); the
semantics is an open ruling.** Under the kernel's own value semantics ("a shot re-runs the
remainder", and the remainder includes `open`), two obligations is the consistent answer, and
the derivation's line that "uniqueness stays in the `Cell`" is satisfied per application.
Under the classical bracket law it is a double-release of a single resource.

Ranked fifth and flagged for escalation: whichever way the user rules, the pin should record
it, because today it is an accident rather than a decision. This is the one candidate I would
put in front of the user as a question rather than as a test to add.

---

### 6. A release that throws still runs exactly once

**Upstream.** The ZIO #7 "executed twice" half above, plus ZIO
[#940](https://github.com/zio/zio/issues/940) (a finalizer defect abandoning the rest of the
chain, fixed via [#937](https://github.com/zio/zio/pull/937)).

**Expressible?** Yes, and it probes a genuinely delicate spot: `hc.done(state)`
(`Eval.scala:421`) is **not** wrapped in a `try`, and it fires *before*
`drainDiscarded(stack.pop())` on the next line. So a throwing completion leaves the entry on
the stack, and the ensuing unwind reaches that same entry again.

**Coverage today: partial.** `:280` pins that a throwing completion fails the computation and
that the *outer* bracket sees it. Nothing asserts that the *inner* release ran exactly once
rather than twice. And the settled fast path is a different code path entirely
(`ContextEffect.scala:156`, `done(derive(Absent))` fired at the call site, outside the `try
use(a)` guard) with no coverage at all for a throwing release.

**Ported pins.**

```scala
"a release that throws on completion runs exactly once" in {
    object Bad extends RuntimeException("bad", null, false, false)
    var count = 0
    val v = Effect.bracket(Effect.defer(1)) { (_, _) =>
        count += 1
        throw Bad
    }(a => Effect.defer(a))
    val ex = intercept[RuntimeException](eval(v))
    assert(ex eq Bad)
    assert(count == 1)
}

"a release that throws on the settled fast path runs exactly once" in {
    object Bad extends RuntimeException("bad", null, false, false)
    var count = 0
    val v = Effect.bracket(Effect.defer(1)) { (_, _) =>
        count += 1
        throw Bad
    }(a => a + 1)
    val ex = intercept[RuntimeException](eval(v))
    assert(ex eq Bad)
    assert(count == 1)
}
```

**Prediction: both pass.** `Cell.complete` does `compareAndSet(false, true)` *before* calling
`fin`, so when `fin` throws the cell is already claimed and the unwind's `cell.drain(ex)`
CAS-fails into a no-op. The exactly-once guarantee survives a failing finalizer by
construction, which is precisely what ZIO #7 got wrong. Worth pinning because the ordering
inside `Cell.complete` is load-bearing and unremarked.

---

### 7. Completion ordering and release promptness

**Upstream.** fs2 [#1535](https://github.com/typelevel/fs2/issues/1535), where bracketed
resources released only in reverse order at the very end of the whole stream instead of
promptly after use (appending `.onComplete(Stream.empty)` changed the behavior). And fs2
[#967](https://github.com/typelevel/fs2/issues/967), the opposite direction: `joinUnbounded`
printed `acquired / released / using` instead of `acquired / using / released`. Both are
"the finalizer ran, at the wrong time".

**Expressible?** Yes.

**Coverage today: partial.** `:104` pins nested ordering on the *failure* path only. Nothing
pins nested ordering on the *success* path, and nothing pins that two sequential brackets do
not both stay open until the end.

**Ported pins.**

```scala
"nested brackets complete innermost first" in {
    val log = collection.mutable.ListBuffer[String]()
    val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
        Effect.bracket(Effect.defer(2))((_, _) => discard(log += "inner"))(b => Effect.defer(b))
    }
    assert(eval(v) == 2)
    assert(log.toList == List("inner", "outer"))
}

"a bracket releases before the next one acquires" in {
    val log = collection.mutable.ListBuffer[String]()
    def one(name: String): Int < Any =
        Effect.bracket(Effect.defer {
            discard(log += s"acquire $name")
            name
        })((_, _) => discard(log += s"release $name"))(_ => Effect.defer(1))
    val v = one("a").map(_ => one("b"))
    assert(eval(v) == 1)
    assert(log.toList == List("acquire a", "release a", "acquire b", "release b"))
}
```

**Prediction: both pass.** The settled pop fires `done` per entry as it unwinds the stack
top-down (`Eval.scala:421`), and the first bracket's region exits at its own settled pop,
before the `map` continuation reaches the second acquire. Cheap, and it locks the property
fs2 got wrong in both directions.

---

### 8. A bracket owned by an isolated child

**Upstream.** The single largest ZIO family. [#9203](https://github.com/zio/zio/issues/9203),
"`ZIO.raceFirst` sometimes prevents scope finalisers from running", fixed by
[#9353](https://github.com/zio/zio/pull/9353) with tests in `ZIOSpec.scala` and
`ZStreamSpec.scala`; root cause was race arms spawned with `fork` instead of `forkIn`, so a
child's scope finalizer was never grafted onto the parent's chain.
[#3271](https://github.com/zio/zio/issues/3271), a forked `useForever` whose "resource
release" never printed after interruption. [#4103](https://github.com/zio/zio/issues/4103),
where on interrupt the outer resource closed *before* the inner `foreachPar` children.
[#3986](https://github.com/zio/zio/issues/3986), parallel branches interleaving nested
finalizers, fixed by [#4040](https://github.com/zio/zio/pull/4040) with the tests "preserves
ordering of nested finalizers" and "Maintains finalizer ordering in inner ZManaged values".

**Expressible?** Partially. There is no scheduler, so "the race arm that lost" has no
analogue. What does port is the ownership question underneath all four: **who drains a
bracket opened inside an isolated child**.

**Coverage today: one direction only.** `:374` pins a bracket *around* an isolate (fork
installs `Cell.inert`, so the child's death cannot drain the parent's obligation). The
reverse, a bracket *inside* the child, is unpinned on all three exits.

**Ported pins.**

```scala
"a bracket owned by an isolated child releases at the child's completion" in {
    val outcomes = collection.mutable.ListBuffer[Maybe[Throwable]]()
    val v = Isolate.internal.Contextual.run(
        Effect.bracket(Effect.defer(7))((_, outcome) => discard(outcomes += outcome))(a => Effect.defer(a + 1))
    ).map(_ + 1)
    assert(eval(v) == 9)
    assert(outcomes.toList == List(Maybe.empty))
}

"a failure inside an isolated child releases the child's bracket" in { /* use throws Boom */ }

"abandoning an isolated child releases its bracket" in {
    // requestStop inside the child's use, Eval.partial, then Eval.release
}
```

**Prediction: the first two pass; the third is the one I would actually run rather than
reason about.** `Isolate.Contextual.isolate` wraps the child in
`Kyo.Park(inner, forked)` where `forked` maps every Finalize state through
`fork = (_: Cell) => Cell.inert`. On abandonment, `Eval.release` collects the park's entries
(inert cells, harmless no-ops) and then descends into `kyo.value`, where the child's own
bracket is a `Kyo.Handle` node carrying its real `Cell`, so it should be collected and
drained. That reasoning is sound but this is the least-exercised composition in the kernel,
and it is exactly the shape ZIO got wrong four separate times. Ranked eighth on confidence,
not on importance.

---

### 9. The outcome payload is right, not merely present

**Upstream.** ZIO [#6911](https://github.com/zio/zio/issues/6911), where `.onInterrupt` fired
even though the effect died with a defect rather than being interrupted, because the guard
only checked whether the resulting `Cause` contained *any* `Interrupt` node (always true after
`collectAllPar` interrupts the surviving siblings). The finalizer ran; it was told the wrong
story.

**Expressible?** Yes. The kernel has three distinct signals: the real failure on the unwind,
the holder's signal on abandonment, and a freshly minted
`KyoException("remainder discarded")` on the discard path.

**Coverage today: weak.** `:130` and `:213` assert only `outcome.isDefined` on the discard
path. No pin asserts *which* throwable any path delivers.

**Ported pin (a strengthening of existing pins rather than a new case).**

```scala
"a discarded capture's bracket is told the discard signal, not a caller's failure" in {
    var seen = Maybe.empty[Maybe[Throwable]]
    val body: Int < Ask =
        Effect.bracket(Effect.defer(7))((_, outcome) => seen = Maybe(outcome))(a => ask.map(x => a + x))
    val dropped: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)([C] => (_, _) => -1, b => b)
    assert(eval(dropped) == -1)
    assert(seen.exists(_.exists(_.isInstanceOf[kyo.KyoException])))
    assert(seen.exists(_.forall(_ ne Boom)))
}
```

Plus an identity assertion on the abandonment path (`seen` holds the exact signal object
passed to `Eval.release`), which `:75` already nearly has.

**Prediction: passes.** Ranked ninth because the behavior is right and only the assertions
are loose, but the ZIO fix history says loose assertions here are how the bug hides.

---

### 10. A release that requests a stop

**Upstream.** ZIO [#2005](https://github.com/zio/zio/issues/2005), "Interruptible regions in
finalizers cause finalizers to not run after fiber is interrupted": placing
`ZIO.unit.interruptible` inside an `ensuring` block caused the finalizer's remaining code
never to run, because the fiber's already-set interrupted status re-fired mid-finalizer and
truncated it. Removing the marker made the rest of the finalizer run.

**Expressible?** Only by analogy, since `release` cannot suspend. The nearest probe is a
release that calls `requestStop()`, setting the safepoint flag from inside the drain.

**Ported pin.**

```scala
"a release that requests a stop does not starve the releases after it" in {
    val log = collection.mutable.ListBuffer[String]()
    val v = Effect.bracket(Effect.defer(1))((_, _) => discard(log += "outer")) { _ =>
        Effect.bracket(Effect.defer(2)) { (_, _) =>
            requestStop()
            discard(log += "inner")
        }(_ => Effect.defer((throw Boom): Int))
    }
    val ex = intercept[RuntimeException](eval(v))
    assert(ex eq Boom)
    assert(log.toList == List("inner", "outer"))
}
```

**Prediction: likely passes, but genuinely uncertain.** `releaseCollected` (`Eval.scala:92`)
loops to completion with no safepoint check, so the drains themselves cannot be truncated,
which is the ZIO #2005 property. What is less clear is what a stop set mid-drain does to the
*resumption* afterwards, since `guarded` may re-enter `loop` with the flag now set. Ranked
tenth: low expected yield, but it is a "bullet proof" probe with an uncertain prediction,
which is the profile worth running.

---

## Already covered, one line each

- cats-effect [#579](https://github.com/typelevel/cats-effect/issues/579)
  (`.allocated.continual` canceled between the acquire completing and the release being
  paired, losing the release) is the settle-to-install leak: covered by `:116` ("a stop
  landing as the acquire settles still installs the region") and `:142` ("the acquire is not
  guarded before it settles").
- cats-effect [#532](https://github.com/typelevel/cats-effect/issues/532) (`Resource.allocated`
  strict in `release`): structurally impossible; `release` is a function value here, never an
  evaluated computation.
- cats-effect [#2472](https://github.com/typelevel/cats-effect/issues/2472) (wrong `ExitCase`
  on an uncancelable body): covered by `:30` and `:41`, which assert `Absent` on both the
  eval-owned completion and the settled fast path.
- cats-effect [#267](https://github.com/typelevel/cats-effect/issues/267) (finalizers not
  sequenced on cancelation): ordering is covered on the unwind by `:104`, on the discard by
  `:319`, and on abandonment by `EvalTest:655`.
- ZIO [#940](https://github.com/zio/zio/issues/940) (a finalizer defect abandoning the rest of
  the chain): covered by `:264` and `EvalTest:782`.
- fs2 [#967](https://github.com/typelevel/fs2/issues/967) (released before used): the
  no-premature-drain property is covered by `:199` ("an effectful loop clause resuming after
  the pop completes the bracket").
- cats-effect [#3757](https://github.com/typelevel/cats-effect/issues/3757)'s *sibling* case
  (a leaked handle resumed after its region is gone): covered by `:240`.
- ZIO [#3632](https://github.com/zio/zio/issues/3632) (finalizers registered in the wrong
  order): the sibling-dump ordering mechanism is covered by the derivation's "sibling dumps
  draining newest first" pin in `EvalTest`; only the bracket-level composition is missing, and
  it is subsumed by candidate 3.

---

## Shortlist

In the order I would add them.

1. **A bracket under a recovering handler** (candidate 2). Highest-frequency upstream shape,
   zero coverage, expected green. Two pins: without a crossing, and with one.
2. **A failing acquire never releases** (candidate 4). Foundational law, zero coverage, two
   lines. Plus the nested variant.
3. **Two stacked brackets abandoned together** (candidate 3). The canonical cats-effect #461
   bug; each mechanism is pinned separately today, the composition is not.
4. **A throwing release runs exactly once** (candidate 6), on both the eval pop path and the
   settled fast path. Pins the load-bearing CAS-before-`fin` ordering in `Cell.complete`.
5. **Multi-shot over the acquire** (candidate 5). Escalate to the user first: this is a
   ruling, not a bug. Pin whichever way it is ruled.
6. **Completion ordering and promptness** (candidate 7). Two cheap pins covering the two
   directions fs2 got wrong.
7. **A bracket owned by an isolated child** (candidate 8), all three exits. Lowest confidence
   in my own prediction, highest upstream recurrence; run it rather than reason about it.
8. **Strengthen the outcome-payload assertions** (candidate 9). Not a new case, but the ZIO
   history says `isDefined` is where this bug hides.
9. **The swallowed release failure on the discard path** (candidate 1). Needs the one-line
   `Debugger` affordance before a pin can exist; this is a defect by comparison to both
   references and should be raised as such.
10. **A release that requests a stop** (candidate 10). Low expected yield, uncertain
    prediction, cheap to run.

## Sources

- [typelevel/cats-effect#461](https://github.com/typelevel/cats-effect/issues/461)
- [typelevel/cats-effect#579](https://github.com/typelevel/cats-effect/issues/579)
- [typelevel/cats-effect#3757](https://github.com/typelevel/cats-effect/issues/3757)
- [typelevel/cats-effect#532](https://github.com/typelevel/cats-effect/issues/532)
- [typelevel/cats-effect#267](https://github.com/typelevel/cats-effect/issues/267) and [#305](https://github.com/typelevel/cats-effect/pull/305)
- [typelevel/cats-effect#375](https://github.com/typelevel/cats-effect/issues/375)
- [typelevel/cats-effect#487](https://github.com/typelevel/cats-effect/issues/487)
- [typelevel/cats-effect#721](https://github.com/typelevel/cats-effect/issues/721)
- [typelevel/cats-effect#2472](https://github.com/typelevel/cats-effect/issues/2472) and [#2617](https://github.com/typelevel/cats-effect/pull/2617)
- [typelevel/cats-effect#3205](https://github.com/typelevel/cats-effect/pull/3205)
- [typelevel/cats-effect#3981](https://github.com/typelevel/cats-effect/issues/3981), [#2926](https://github.com/typelevel/cats-effect/issues/2926)
- [typelevel/fs2#2966](https://github.com/typelevel/fs2/issues/2966) and [#2968](https://github.com/typelevel/fs2/pull/2968)
- [typelevel/fs2#967](https://github.com/typelevel/fs2/issues/967), [#1535](https://github.com/typelevel/fs2/issues/1535), [#1022](https://github.com/typelevel/fs2/issues/1022)
- [zio/zio#7](https://github.com/zio/zio/issues/7), [#16](https://github.com/zio/zio/issues/16), [#62](https://github.com/zio/zio/pull/62)
- [zio/zio#940](https://github.com/zio/zio/issues/940) and [#937](https://github.com/zio/zio/pull/937)
- [zio/zio#2005](https://github.com/zio/zio/issues/2005)
- [zio/zio#3271](https://github.com/zio/zio/issues/3271)
- [zio/zio#3632](https://github.com/zio/zio/issues/3632) and [#3633](https://github.com/zio/zio/pull/3633)
- [zio/zio#3897](https://github.com/zio/zio/issues/3897) and [#3883](https://github.com/zio/zio/pull/3883)
- [zio/zio#3986](https://github.com/zio/zio/issues/3986) and [#4040](https://github.com/zio/zio/pull/4040)
- [zio/zio#4103](https://github.com/zio/zio/issues/4103)
- [zio/zio#6125](https://github.com/zio/zio/issues/6125) and [#6133](https://github.com/zio/zio/pull/6133)
- [zio/zio#6911](https://github.com/zio/zio/issues/6911)
- [zio/zio#9203](https://github.com/zio/zio/issues/9203) and [#9353](https://github.com/zio/zio/pull/9353)
- [zio/zio#351](https://github.com/zio/zio/issues/351), [#950](https://github.com/zio/zio/issues/950), [#3308](https://github.com/zio/zio/issues/3308), [#6016](https://github.com/zio/zio/issues/6016)
