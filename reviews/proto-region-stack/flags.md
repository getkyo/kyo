# Adjudication

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto kyo-kernel/shared/src/test/scala/kyo/proto`
against the shipped tip emits **99 rows**. Every row appears below exactly once, with the line its
id actually carries. The scan covers the test tree as well as main; an earlier version scanned only
main, so a construct the tests added produced no row at all.

The table is generated from the script's output in one pass. Three earlier versions were blocked:
two for filing verdicts against lines their ids did not name, and one for a **fallback that filed any
`new ...` line as `moved` without comparing it**, which is the hand-written escape hatch this
preamble had claimed to have removed. There is no fallback now: a row either matches the baseline or
is classified by an explicit rule, and anything matching neither is reported rather than absorbed.

#

## false positive

The flagged word appears in prose, not in code.

| id | site | line | class |
|----|------|------|-------|
| F1 | kernel/internal/Eval.scala:44 | `// armed bit is unobservable while nothing in the proto arms. It is kept because ...` | mutability |
| F20 | kernel/internal/Eval.scala:140 | `// instead of re-entering it. Nothing inside the try is evaluated, so a` | carrier |
| F70 | kernel/internal/Handler.scala:22 | `* The state is the live one for the same reason [[release]]'s is: a region's stat...` | mutability |

## moved

Present at 31a7b4bde9, compared line by line with the renames applied and whitespace stripped.

| id | site | line | class |
|----|------|------|-------|
| F2 | kernel/internal/Eval.scala:56 | `case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>` | cast |
| F3 | kernel/internal/Eval.scala:58 | `case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>` | cast |
| F4 | kernel/internal/Eval.scala:63 | `case kyo: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked if ctx.contains(kyo...` | cast |
| F5 | kernel/internal/Eval.scala:68 | `case kyo: Kyo.Suspend[EX, T, S2] @unchecked =>` | cast |
| F6 | kernel/internal/Eval.scala:84 | `case sa: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] @unchecked =>` | cast |
| F7 | kernel/internal/Eval.scala:89 | `new Kyo.SuspendArrow[IX, OX, EX, VX, C, S2] with Arrow.Transform[OX[VX], C, S2]:` | allocation |
| F8 | kernel/internal/Eval.scala:95 | `case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F9 | kernel/internal/Eval.scala:98 | `case sc: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>` | cast |
| F10 | kernel/internal/Eval.scala:103 | `new Kyo.SuspendContext[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation |
| F11 | kernel/internal/Eval.scala:109 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F12 | kernel/internal/Eval.scala:112 | `case sd: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked =>` | cast |
| F13 | kernel/internal/Eval.scala:117 | `new Kyo.SuspendContextDefault[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation |
| F14 | kernel/internal/Eval.scala:124 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F45 | kernel/internal/Eval.scala:248 | `case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>` | cast |
| F46 | kernel/internal/Eval.scala:254 | `case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>` | cast |
| F53 | kernel/internal/Eval.scala:279 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | cast |
| F54 | kernel/internal/Eval.scala:279 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | carrier |
| F60 | kernel/internal/Eval.scala:303 | `case suspend: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked =>` | cast |

## justified: erasure-forced, the eval's answer

The loop's type parameters cannot express that the step's type is the eval's when the stack is empty, which is the only condition under which this runs.

| id | site | line | class |
|----|------|------|-------|
| F15 | kernel/internal/Eval.scala:128 | `if stack.isEmpty then susp.asInstanceOf[A < S]` | cast |
| F47 | kernel/internal/Eval.scala:266 | `if stack.isEmpty then res.asInstanceOf[A < S]` | cast |

## justified: erasure-forced, the storage boundary

Array element re-typing at the storage boundary (Stack), the category the ladder names with this carrier. The row is asserted at Any, the subtype end, so it is widened out of the way rather than claimed.

| id | site | line | class |
|----|------|------|-------|
| F16 | kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F17 | kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F18 | kernel/internal/Eval.scala:131 | `val state   = stack.state.asInstanceOf[VX]` | cast |
| F34 | kernel/internal/Eval.scala:208 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F35 | kernel/internal/Eval.scala:208 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F43 | kernel/internal/Eval.scala:235 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F44 | kernel/internal/Eval.scala:235 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F48 | kernel/internal/Eval.scala:270 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F49 | kernel/internal/Eval.scala:270 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F50 | kernel/internal/Eval.scala:271 | `val r       = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))` | cast |
| F51 | kernel/internal/Eval.scala:273 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F52 | kernel/internal/Eval.scala:273 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F64 | kernel/internal/Eval.scala:325 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F65 | kernel/internal/Eval.scala:325 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F66 | kernel/internal/Eval.scala:326 | `val state   = stack.state.asInstanceOf[VX]` | cast |

## justified: erasure-forced, Tag storage

Tag storage outside its opaque scope, an approved category; the test is the baseline's own, restructured because the arm now binds the rebuilt node before popping.

| id | site | line | class |
|----|------|------|-------|
| F19 | kernel/internal/Eval.scala:132 | `if !(susp.tag.erased <:< handler.tag.erased) then` | cast |

## justified: erasure-forced, the row of a read handler

The handler is asserted at Any, so what it builds and produces is typed at Any too. New in this change, not relocated: the baseline carried the region's own row here because it read the handler from a closure.

| id | site | line | class |
|----|------|------|-------|
| F21 | kernel/internal/Eval.scala:151 | `val rebuilt: Y < Any =` | carrier |
| F23 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | carrier |
| F24 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | allocation |
| F27 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F28 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |
| F31 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F32 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |
| F37 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | cast |
| F38 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | carrier |
| F42 | kernel/internal/Eval.scala:233 | `val r = Nested.unnest[Y < Any](o)` | carrier |

## justified: typed pattern

Ladder step 2: bound at the needed type rather than rebound and cast, so the runtime test is identical and the claim is visible.

| id | site | line | class |
|----|------|------|-------|
| F22 | kernel/internal/Eval.scala:153 | `case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] @unchecked =>` | cast |
| F25 | kernel/internal/Eval.scala:165 | `case p: Pending[OY[VY], S3] @unchecked =>` | cast |
| F26 | kernel/internal/Eval.scala:170 | `case sc: Kyo.SuspendContext[VX, CX, AX, EX] @unchecked =>` | cast |
| F29 | kernel/internal/Eval.scala:182 | `case p: Pending[VX, S3] @unchecked =>` | cast |
| F30 | kernel/internal/Eval.scala:187 | `case sd: Kyo.SuspendContextDefault[VX, CX, AX, EX] @unchecked =>` | cast |
| F33 | kernel/internal/Eval.scala:200 | `case p: Pending[VX, S3] @unchecked =>` | cast |
| F36 | kernel/internal/Eval.scala:214 | `case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX] @unchecked =>` | cast |
| F39 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | cast |
| F40 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | carrier |
| F41 | kernel/internal/Eval.scala:227 | `case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>` | cast |

## justified: concession, the guard

The guard's loop; contract in full below.

| id | site | line | class |
|----|------|------|-------|
| F55 | kernel/internal/Eval.scala:295 | `var curr    = v` | mutability |
| F56 | kernel/internal/Eval.scala:296 | `var ctx     = Context.empty` | mutability |
| F57 | kernel/internal/Eval.scala:297 | `var out     = v` | mutability |
| F58 | kernel/internal/Eval.scala:298 | `var settled = false` | mutability |
| F59 | kernel/internal/Eval.scala:300 | `while !settled do` | mutability |
| F61 | kernel/internal/Eval.scala:321 | `var ex        = failure` | mutability |
| F62 | kernel/internal/Eval.scala:322 | `var unwinding = true` | mutability |
| F63 | kernel/internal/Eval.scala:323 | `while unwinding do` | mutability |
| F68 | kernel/internal/Eval.scala:351 | `end while` | mutability |
| F69 | kernel/internal/Eval.scala:353 | `end while` | mutability |

## justified: erasure-forced, the storage boundary at the eval's answer

Array element re-typing at the storage boundary, asserted at the eval's answer type rather than at Any because a recovered region's result flows to the eval's result. It is the one read in the set that is not the variance widening the group above describes.

| id | site | line | class |
|----|------|------|-------|
| F67 | kernel/internal/Eval.scala:327 | `val cont    = stack.cont.asInstanceOf[Arrow[Y, A, S]]` | cast |

## justified

No existing type holds a growable heterogeneous sequence of open regions, and the entries must be reachable by the eval while a region is open, which a value in the pending union is not.

| id | site | line | class |
|----|------|------|-------|
| F71 | kernel/internal/Stack.scala:18 | `final private[kyo] class Stack:` | new-type |

## justified: concession, the region stack

Interpreter mutability; contract in full below.

| id | site | line | class |
|----|------|------|-------|
| F72 | kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | mutability |
| F75 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | mutability |
| F77 | kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | mutability |
| F79 | kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | mutability |
| F81 | kernel/internal/Stack.scala:24 | `private var size     = 0` | mutability |

## justified: measurement

Four arrays per eval and a doubling copy; the full class on both legs shows no row regressed beyond its own error.

| id | site | line | class |
|----|------|------|-------|
| F73 | kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | allocation |
| F76 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | allocation |
| F78 | kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | allocation |
| F80 | kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | allocation |
| F85 | kernel/internal/Stack.scala:55 | `val hs = new Array[Handler[?, ?, ?, ?, ?]](n)` | allocation |
| F87 | kernel/internal/Stack.scala:56 | `val ss = new Array[Any](n)` | allocation |
| F88 | kernel/internal/Stack.scala:57 | `val xs = new Array[Context](n)` | allocation |
| F89 | kernel/internal/Stack.scala:58 | `val cs = new Array[Arrow[?, ?, ?]](n)` | allocation |

## justified: erasure-forced, the state column

The column's type genuinely cannot be written, which is the storage boundary the ladder names with this carrier.

| id | site | line | class |
|----|------|------|-------|
| F74 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | carrier |
| F82 | kernel/internal/Stack.scala:28 | `def push(handler: Handler[?, ?, ?, ?, ?], state: Any, ctx: Context, cont: Arrow[?...` | carrier |
| F83 | kernel/internal/Stack.scala:47 | `def state: Any                      = states(size - 1)` | carrier |
| F84 | kernel/internal/Stack.scala:48 | `def state_=(v: Any): Unit           = states(size - 1) = v` | carrier |
| F86 | kernel/internal/Stack.scala:56 | `val ss = new Array[Any](n)` | carrier |

## justified: test construct

A stackless shared exception, whose four-argument constructor takes the cause as null; the demo's Discarded is built the same way. Filling ten thousand stack traces would measure the JVM instead of the eval.

| id | site | line | class |
|----|------|------|-------|
| F90 | kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | carrier |
| F91 | kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | new-type |
| F92 | kernel/internal/EvalTest.scala:608 | `"a context update outlives an operation answered after it" in {` | terminology |
| F93 | kernel/internal/EvalTest.scala:609 | `sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]` | new-type |
| F94 | kernel/internal/EvalTest.scala:611 | `new Kyo.SuspendContext[Int, Count, Int, Count]:` | allocation |
| F95 | kernel/internal/EvalTest.scala:627 | `def recovering(to: Int): Int < Any =` | carrier |
| F96 | kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | carrier |
| F97 | kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | allocation |
| F98 | kernel/internal/EvalTest.scala:635 | `Kyo.handle[Ask, Int, Int, Any, Unit](body, h, ())` | carrier |
| F99 | kernel/internal/EvalTest.scala:637 | `def go(i: Int): Int < Any =` | carrier |

## The concession contracts in full

**The region stack.** Justified: the region chain is the only thing whose depth was the Java stack's.
Scope: one instance per `Eval.apply`, reachable from nothing that leaves the eval. Protection:
entries are written only by the eval's own arms and hold complete values, and a nested eval builds
its own. Pinned by `ArrowEffectTest` "handles nested per recursion step in bounded stack",
`EvalTest` "a nested eval shares the thread's stack and sees none of the outer regions", and the two
multi-shot cases.

**The guard's loop**, six locals: `curr`, `ctx`, `out`, `settled`, `ex`, `unwinding`. Justified: the
alternative resumed a recovery by calling back into the guard from inside its own catch, costing a
frame per recovered region. Scope: six locals in `apply`, none escaping. Protection: `out` is
initialised from `v`, so no sentinel and no `Null`; `settled` is the only exit; `ex` only moves
forward to the failure a recover itself raised. Pinned by `EvalTest` "regions that fail and recover
in sequence cost no stack", 10000 cycles, which livelocks without the budget reset.

## One thing with no pinning test, stated rather than implied

`finally Safepoint.restore(slot, saved)` has no test. A first attempt passed with the fix reverted, so
it pinned nothing and was deleted rather than kept for the look of it: a nested eval's own `exit`
calls return most of what it spent, so the enclosing eval survives losing the depth, and the armed bit
is unobservable while nothing in the proto arms. It stays because discarding a caller's state is wrong
whether or not this tree can see it, and because the reference kernel does the same at the same place.

## Nothing removed

No row's verdict is `REMOVE`. The constructs `rulings.md` names, an `Any` or `Null` **value** carrier
in the evaluator, a `var` outside the engine room, a new type without an argument, a placeholder body,
banned vocabulary, are absent. The one `null` in the diff is a stackless exception's cause argument in
a test, adjudicated above; the `Any`s are rows inside types, except the state column, whose type
cannot be written.

## What the three earlier versions got wrong

- **v1** claimed every row had a verdict while one had none; carried eight rows at `measurement
  pending`, not an accepted verdict; verdicted rows `moved` against code absent from the control; and
  justified `Stack` by who asked for it.
- **v1 and v2** filed verdicts against lines their ids did not name. v2's own footer confessed it and
  repeated it at larger scale, and inverted the central category, spending `erasure-forced` on
  relocated typed patterns while the real storage-boundary casts carried only `moved`.
- **v3** was generated, which fixed the id drift, but kept a **fallback that filed any `new ...` line
  as `moved` without comparing it**. Six rebuilt-node lines went through it whose rows had changed
  from `S` to `Any`, so a change new to this commit was filed as relocated and its `Any` never
  adjudicated. It also scanned only `main`, so a construct the tests added produced no row.

This version has no fallback, declares the renames its comparison applies, and scans both trees. The
recurring failure was a rule that answers when the comparison cannot; the fix is that nothing answers
in its place.
