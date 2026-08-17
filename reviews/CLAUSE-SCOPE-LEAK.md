# The clause-scope leak: report for review

Status: **reproduced, localised, not fixed.** Two fix attempts, both wrong, both reverted. This
document is the handoff to a held-out reviewer, who is asked to design the path forward. It does not
prescribe a fix; it lays out the evidence, the attempts, the history, and several directions.

The goal that should govern the design: **make the kernel safer about when an `Arrow` is a value and
when it is a computation**, so that this class of bug is hard to write, without a major
rearchitecture.

## 1. The symptom

A handler's *clause* is the handler's own code. Its effects should be answered by the handlers
**outside** the region it manages, never by handlers the region's **body** installed inside it. In this
kernel, for one specific shape, they are answered inside.

Minimal reproduction (`EvalTest.scala`, all live and red):

```scala
sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]      // "give me an Int"
sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]   // "log this"
def ask: Int < Ask             = ArrowEffect.suspend[Any](Tag[Ask], ())
def say(s: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], s)

def recordSay[A, S](name: String, log: ListBuffer[String])(v: A < (Say & S)): A < S =
    ArrowEffect.handleLoop(Tag[Say], v)([C] => _ => { log += name; Loop.continue(()) }, a => a)

val log      = ListBuffer[String]()
val program: Int < (Ask & Say) = say("m").map(_ => ask).map(_ + 1)
val sayInner = recordSay("inner", log)(program)
val askScope = ArrowEffect.handleLoop(Tag[Ask], sayInner)(
    [C] => _ => Loop.continue(say("c").map(_ => 41)),   // the Ask handler's clause itself says "c"
    a => a)
val sayOuter = recordSay("outer", log)(askScope)

Eval(sayOuter) == 42
log == List("inner", "outer")     // expected: the clause's say("c") goes to sayOuter
// actual:  List("inner", "inner")   the clause's say("c") is captured by sayInner
```

Same setup with **no** `sayOuter`: expected *unhandled suspension* (nothing outside handles the
clause's `say`); actual: `sayInner` silently answers it.

**Concrete harm.** A library `Ask` handler that logs through `Say` has its logging captured by whatever
`Say` handler a *user* installs inside the region. Handler internals become observable to, and
interceptable by, the code being handled.

**Livelock variant.** When the leaked effect is the handler's *own tag*, the re-raise is dispatched
inside the region, finds this very handler, whose clause re-raises again, forever. Two test runs hung on
this before the case was bounded with a counter; it now fails with `"clause answered its own re-raise"`.

## 2. Exactly which shape leaks

Seven cases were added under `"clause scope"` in `EvalTest` to find the boundary. Result:

| clause shape | example | scoped? |
|---|---|---|
| suspends, *then* produces its outcome | `say("c").map(_ => Loop.continue(41))` | ✅ correct |
| stateful, suspends then outcome | `say("c").map(_ => Loop.continue(n+1, 41))` | ✅ correct |
| `handleCont`, suspends then `cont` | `say("c").map(_ => cont(41))` | ✅ correct |
| suspends then `done` | `say("c").map(_ => Loop.done(-1))` | ✅ correct |
| **outcome carries** the suspension | `Loop.continue(say("c").map(_ => 41))` | ❌ **leaks** |
| outcome carries a re-raise of own tag | `Loop.continue(ask.map(_ + 100))` | ❌ **livelock** |
| a `VarE` variant of the above | reads region state if leaked | ✅ passes (goes through suspend-first path) |

So it is precisely **`HandleLoop`/`HandleLoopState` returning `Continue(x)` where `x` is itself a
computation.** Every other clause shape is fine.

## 3. Where in the drive

`Eval.dispatchInline`, `HandleLoop` arm (identical structure in `HandleLoopState`):

```scala
hl.run(s.input) match
    case out: Arrow[Any, Any, Any] @unchecked =>
        Chain(out, outcome(whole, h, i))              // clause SUSPENDED: outcome() parks the region
    case c: Loop.Continue[?] =>
        c._1 match
            case p: Arrow[Any, Any, Any] @unchecked => Chain(p, whole)        // <-- THE LEAK
            case o                                  => whole(Nested.unnest[Any](o))
    case done =>
        stack.truncate(i)
        done
```

- **Suspend-first path** (`out: Arrow`): `outcome(whole, h, i)` runs *now*, copies the stack segment
  above `i` into an `Arrow.Eval` node, **truncates the stack to `i`**, and returns a `Transform`. The
  clause's own computation `out` therefore runs with the region physically absent from the stack. When
  it settles to `Continue(answer)`, `region(...)` rebuilds the region and delivers the answer into it.
  Correct by construction: nothing inside can capture the clause's effects because nothing inside is on
  the stack.
- **Synchronous `Continue(p: Arrow)`**: `Chain(p, whole)` with the stack **untouched**. `p` becomes the
  drive's next step and runs with the region's inner handlers still above `i`. That is the leak.
- The `case o` branch unnests, meaning the payload *can* be a `Nested` value. The `case p: Arrow` branch
  is a fast path for the bare-arrow payload, and it treats "bare arrow" as "run me now."

## 4. This is a regression, and the tests that guard it were dark

- The test *"a clause runs outside its own scope"* dates from **2026-08-10**, commit `6b68934741`
  ("step 1: Loop/LoopState/Cont handlers evaluated as regions").
- The `case p: Arrow => Chain(p, whole)` fast path first appears **2026-08-16**, commit `fe9860a17e`
  ("WIP: SuspendWith carries the continuation as separate data").
- The old-impl test files were commented out around then because the old `Eval.run` was stubbed to
  `???` mid-migration; the merge in this campaign un-darkened them and they went red.

The pre-regression drive at `fe9860a17e~1`:

```scala
case c: Loop.Continue[?] =>
    val st = whole.step
    st.head(c._1.asInstanceOf[Any < Any], st.tail)
```

It hands `c._1` to `whole.step.head` as a `<`-typed value, whatever its runtime shape. Note this is
**not** the same as the current `case o => whole(Nested.unnest(o))`: `st.head(v, tail)` is the
`Transform.apply(v: A < S2, next)` protocol, which the receiving step is free to *evaluate*, whereas
`whole(v)` is `Suspend.apply(v: Any) = cont(v)`, a raw hand-off. Attempt 2 below foundered on exactly
this difference. Whether `st.head` at that commit evaluated a computation-valued `v` before delivering
it, or whether the region machinery of that era did, has **not** been established; the reviewer may
want to.

## 5. Two attempts, both wrong, both reverted

**Attempt 1: route the synchronous case through `outcome`.** Replaced `Chain(p, whole)` with
`outcome(whole, h, i)(c, Arrow[Any])`, on the theory that `outcome` "runs the answer outside the region."
**Wrong reading of `outcome`.** Its `region(handler, payload)` builds
`Arrow.Eval(entries, tags, states, Identity(payload, body))`, i.e. it delivers the payload **into** the
rebuilt region. So the answer still ran inside, plus an extra park. 8 red instead of 5, with
`Tuple2 cannot be cast to Integer` (the `Continue` object reaching the body raw).

**Attempt 2: deliver `_1` as a value regardless of shape.** Replaced the whole `c._1 match` with
`whole(Nested.unnest[Any](c._1))`, on the theory that the pre-regression drive delivered by value.
**Wrong reading of `whole(...)`.** `Suspend.apply(v) = cont(v)` is a raw hand-off; a bare `Arrow`
reaches the body's `map(_ + 1)` as an object. 8 red, `Effect$$anon$1 cannot be cast to Integer`.

Both were reasoned from partial reads of the drive rather than verified against it. The reviewer should
weight the code over these readings.

## 6. What the representation says, and where it is contradicted

```scala
opaque type <[+A, -S] = A | Arrow[Any, A, S] | Nested[A]
```

Three arms: a plain value, a **computation** (an `Arrow` with input `Any`), and a **boxed value** that is
itself `Boxed`, marked `Nested` so the drive does not run it. `Nested` is the representation's own
word for "this arrow is data."

The `Arrow` hierarchy splits on the same line:

| | `head` | input | meaning |
|---|---|---|---|
| `Transform[A, B, S]` | `this` | `A` | a function; needs input; `Identity`, every `map` body |
| `Defer[A, B, S]` | `Arrow[A]` | `Any` | a computation; carries what it needs |

`Chain`, `Bind`, `Eval` (park node), `Suspend`, `SuspendWith`, `Handle` all extend `Defer[Any, …]`.
So **the only arrows that belong in the computation arm are `Defer`s**, and the opaque type's
`Arrow[Any, A, S]` was always saying so.

Two mechanisms let a bare `Arrow` reach a value slot anyway:

**a. `fromArrow`.**
```scala
implicit private[kyo] def fromArrow[A, S](v: Arrow[Any, A, S]): A < S = v
```
An identity conversion asserting "this arrow is a computation." Compiled call sites (javap over the
kernel): `ArrowEffect` 56, `Effect` 5, `Eval` 3, `Arrow` 3, `<` 1. Since `Arrow` is contravariant in its
input, `Arrow[Any, A, S]` does exclude `Transform[Int, …]`; but it admits every `Defer`, including
`SuspendWith`, which is what `say("c").map(_ => 41)` is. So `fromArrow` is typed correctly for the
`Defer`/`Transform` line and is not by itself what lets *this* leak through. It is the mechanism by
which any computation, legitimate or not, can be placed into a `<` slot without a `Nested`, and it is
where the "value or step?" ambiguity is created.

**b. `Loop.continue` stores its payload raw.**
```scala
inline def continue[A, O, S](inline v: A): Outcome[A, O] < Any =
    val outcome = new Continue[A]: val _1 = v      // no lift, no Nested
    outcome.asInstanceOf[Outcome[A, O] < Any]
```
The clause type is `Outcome[O[C] < (E & S), B]`: the answer slot is declared as a *computation type*.
So `Loop.continue(say("c").map(_ => 41))` stores a bare `SuspendWith` in `_1`, and the drive's
`case p: Arrow` fires. Every `continue` overload stores raw, and `Continue2._1` is *state*, not an
answer, so nesting inside `Loop.continue` blindly would be wrong in the other direction.

## 7. Directions, not a prescription

Listed with what each buys and what it costs. They are not exclusive.

**D1. Nest the answer at the clause boundary.** In `handleLoop`/`handleLoopState`'s wrapper, where the
user's `Outcome[O[C] < (E & S), B]` becomes the drive's payload, apply `Nested.nest` to `_1` (and
`Continue2._2`). Then `Continue` never carries a bare `Defer`, the drive's `case p: Arrow` branch is
unreachable and can be deleted, and the answer takes the existing `case o => whole(unnest(o))` path.
*Buys:* the drive stops guessing; the invariant is enforced at the one place "user answer" becomes
"drive payload." *Costs:* one allocation per computation-valued answer; and it must be verified that
`whole(unnest(nested))` then actually delivers a `Defer` in a way the body evaluates, since attempt 2
suggests raw hand-off does not (see §4's open question).

**D2. Bound `fromArrow` to `Defer`.** Change its parameter from `Arrow[Any, A, S]` to `Defer[?, A, S]`
(or make `Transform` non-convertible another way). *Buys:* a `Transform` can never enter a `<` slot,
which the opaque type already implies; the `Defer`-only rule becomes a compile-time fact. *Costs:* does
**not** close this leak on its own, since `SuspendWith` is a `Defer`; it closes an adjacent class. Worth
doing regardless.

**D3. Make `fromArrow` explicit rather than implicit.** ~68 kernel sites would name it. *Buys:* every
"this arrow is the drive's next step" assertion is visible and greppable; nothing converts silently.
*Costs:* the largest diff of the directions, and it does not by itself change semantics.

**D4. Fix the drive alone.** On synchronous `Continue(p: Defer)`, park the region as the suspend-first
path does, run `p` with the stack cut to `i`, and on settle deliver the *value* into the rebuilt region.
This is a new shape ("park, run answer outside, resume with value") that neither existing branch
implements: `outcome`'s park runs the *clause* outside and delivers the *answer* inside. *Buys:* no
representation change. *Costs:* another special case in `dispatchInline`, and it leaves `Continue` able
to carry a bare `Defer`, so the next fast path someone writes has the same trap.

**D5. Establish what the pre-regression drive actually did** before choosing. `st.head(c._1, st.tail)`
at `fe9860a17e~1` may have evaluated a computation-valued payload; if so, that is the semantics to
restore and it tells whether D1's delivery is sufficient. Cheap to check by building that commit and
probing.

A design that combines D1 (or D4) with D2 would address both the reproduced leak and the adjacent
`Transform` class, and would leave the drive with fewer places that inspect an `Arrow`'s runtime shape
to decide whether to run it. That is the direction the "safer lift/nest/unnest" goal points at; which
combination, and whether the nest belongs at the clause boundary or somewhere the reviewer sees more
clearly, is the reviewer's call.

## 8. Constraints for the reviewer

- **No major rearchitecture.** The `handle*` methods' shape is by design and stays.
- **The tests stay as written.** They pin the old kernel's contract, which is the correct one.
- **Every fix must be verified against a clean build**, not incremental; two earlier bugs in this
  campaign hid behind stale classes.
- The reviewer is asked for a design and its verification plan, not to implement.

## 9. Files

- `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala` — `dispatchInline`, `outcome`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala` — `handleLoop`, `handleLoopState`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Loop.scala` — `continue` overloads
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala` — `<`, `fromArrow`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Nested.scala` — `nest`, `unnest`
- `kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala` — the hierarchy
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala` — `"clause scope"` block and
  the two original cases
- History: `git show fe9860a17e~1:kyo-kernel2/shared/src/main/scala/kyo/kernel/proto/Eval.scala`
