# Adjudication

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto kyo-kernel/shared/src/test/scala/kyo/proto`
against the tip `b58e2fbc4e` emits **90 rows**. Every row appears below exactly once, with the line
its id actually carries, across both the main and test trees.

Generated from the script's output in one pass, with no fallback: a row either matches its baseline
counterpart or is classified by an explicit rule, and anything matching neither is reported rather
than absorbed. Earlier versions were blocked three times, twice for filing verdicts against lines
their ids did not name and once for a rule that filed any `new ...` line as `moved` without
comparing it.

### The renames a `moved` verdict applies

The loop's type parameters were `A, B, C, S` and are now `T, B, C, S2`, because the loop returns the
eval's answer `A < S` and those names shadowed it; inner scopes shift `S2` to `S3`. A line is `moved`
when it equals its baseline counterpart after applying `S3`,`S2`, then `S2`,`S`, then `T`,`A`, and
stripping whitespace. A row change is not a rename: a line whose row moved from `S` to `Any` is not
`moved`.

Verdict vocabulary is the skill's: a category from the cast ladder's closed set, a measurement, a
`moved` provenance, or `REMOVE`.

## false positive

The flagged word appears in prose, not in code.

| id | site | line | class |
|----|------|------|-------|
| F1 | kernel/Effect.scala:52 | `// TODO is there a reason for this? it'll create a pointer in deferInline` | placeholder |
| F2 | kernel/Effect.scala:67 | `// TODO let's update code like this to be structured line <.map with a single branch` | placeholder |
| F3 | kernel/internal/Eval.scala:44 | `// armed bit is unobservable while nothing in the proto arms. It is kept because ...` | mutability |
| F22 | kernel/internal/Eval.scala:140 | `// instead of re-entering it. Nothing inside the try is evaluated, so a` | carrier |
| F61 | kernel/internal/Handler.scala:22 | `* The state is the live one for the same reason [[release]]'s is: a region's stat...` | mutability |

## moved

Present at 31a7b4bde9, compared line by line with the declared renames applied and whitespace stripped.

| id | site | line | class |
|----|------|------|-------|
| F4 | kernel/internal/Eval.scala:56 | `case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>` | cast |
| F5 | kernel/internal/Eval.scala:58 | `case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>` | cast |
| F6 | kernel/internal/Eval.scala:63 | `case kyo: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked if ctx.contains(kyo...` | cast |
| F7 | kernel/internal/Eval.scala:68 | `case kyo: Kyo.Suspend[EX, T, S2] @unchecked =>` | cast |
| F8 | kernel/internal/Eval.scala:84 | `case sa: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] @unchecked =>` | cast |
| F9 | kernel/internal/Eval.scala:89 | `new Kyo.SuspendArrow[IX, OX, EX, VX, C, S2] with Arrow.Transform[OX[VX], C, S2]:` | allocation |
| F10 | kernel/internal/Eval.scala:95 | `case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F11 | kernel/internal/Eval.scala:98 | `case sc: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>` | cast |
| F12 | kernel/internal/Eval.scala:103 | `new Kyo.SuspendContext[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation |
| F13 | kernel/internal/Eval.scala:109 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F14 | kernel/internal/Eval.scala:112 | `case sd: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked =>` | cast |
| F15 | kernel/internal/Eval.scala:117 | `new Kyo.SuspendContextDefault[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation |
| F16 | kernel/internal/Eval.scala:124 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast |
| F47 | kernel/internal/Eval.scala:248 | `case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>` | cast |
| F48 | kernel/internal/Eval.scala:254 | `case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>` | cast |
| F55 | kernel/internal/Eval.scala:279 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | cast |
| F56 | kernel/internal/Eval.scala:279 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | carrier |

## justified: erasure-forced, the eval's answer

The loop's type parameters cannot express that the step's type is the eval's when the stack is empty, which is the only condition under which this runs.

| id | site | line | class |
|----|------|------|-------|
| F17 | kernel/internal/Eval.scala:128 | `if stack.isEmpty then susp.asInstanceOf[A < S]` | cast |
| F49 | kernel/internal/Eval.scala:266 | `if stack.isEmpty then res.asInstanceOf[A < S]` | cast |

## justified: erasure-forced, the storage boundary

Array element re-typing at the storage boundary (Stack), the category the ladder names with this carrier. The row is asserted at Any, the subtype end, so it is widened out of the way rather than claimed.

| id | site | line | class |
|----|------|------|-------|
| F18 | kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F19 | kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F20 | kernel/internal/Eval.scala:131 | `val state   = stack.state.asInstanceOf[VX]` | cast |
| F36 | kernel/internal/Eval.scala:208 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F37 | kernel/internal/Eval.scala:208 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F45 | kernel/internal/Eval.scala:235 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F46 | kernel/internal/Eval.scala:235 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F50 | kernel/internal/Eval.scala:270 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F51 | kernel/internal/Eval.scala:270 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F52 | kernel/internal/Eval.scala:271 | `val r       = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))` | cast |
| F53 | kernel/internal/Eval.scala:273 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast |
| F54 | kernel/internal/Eval.scala:273 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier |
| F57 | kernel/internal/Eval.scala:298 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast |
| F58 | kernel/internal/Eval.scala:298 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier |
| F59 | kernel/internal/Eval.scala:299 | `val state   = stack.state.asInstanceOf[VX]` | cast |

## justified: erasure-forced, Tag storage

Tag storage outside its opaque scope, an approved category.

| id | site | line | class |
|----|------|------|-------|
| F21 | kernel/internal/Eval.scala:132 | `if !(susp.tag.erased <:< handler.tag.erased) then` | cast |

## justified: erasure-forced, the row of a read handler

The handler is asserted at Any, so what it builds and produces is typed at Any too.

| id | site | line | class |
|----|------|------|-------|
| F23 | kernel/internal/Eval.scala:151 | `val rebuilt: Y < Any =` | carrier |
| F25 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | carrier |
| F26 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | allocation |
| F29 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F30 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |
| F33 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F34 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |
| F39 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | cast |
| F40 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | carrier |
| F44 | kernel/internal/Eval.scala:233 | `val r = Nested.unnest[Y < Any](o)` | carrier |

## justified: typed pattern

Ladder step 2: bound at the needed type rather than rebound and cast, so the runtime test is identical and the claim is visible.

| id | site | line | class |
|----|------|------|-------|
| F24 | kernel/internal/Eval.scala:153 | `case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] @unchecked =>` | cast |
| F27 | kernel/internal/Eval.scala:165 | `case p: Pending[OY[VY], S3] @unchecked =>` | cast |
| F28 | kernel/internal/Eval.scala:170 | `case sc: Kyo.SuspendContext[VX, CX, AX, EX] @unchecked =>` | cast |
| F31 | kernel/internal/Eval.scala:182 | `case p: Pending[VX, S3] @unchecked =>` | cast |
| F32 | kernel/internal/Eval.scala:187 | `case sd: Kyo.SuspendContextDefault[VX, CX, AX, EX] @unchecked =>` | cast |
| F35 | kernel/internal/Eval.scala:200 | `case p: Pending[VX, S3] @unchecked =>` | cast |
| F38 | kernel/internal/Eval.scala:214 | `case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX] @unchecked =>` | cast |
| F41 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | cast |
| F42 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | carrier |
| F43 | kernel/internal/Eval.scala:227 | `case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>` | cast |

## justified: erasure-forced, the storage boundary at the eval's answer

Array element re-typing, asserted at the eval's answer type because a recovered region's result flows to the eval's result.

| id | site | line | class |
|----|------|------|-------|
| F60 | kernel/internal/Eval.scala:300 | `val cont    = stack.cont.asInstanceOf[Arrow[Y, A, S]]` | cast |

## justified

No existing type holds a growable heterogeneous sequence of open regions, and the entries must be reachable by the eval while a region is open, which a value in the pending union is not.

| id | site | line | class |
|----|------|------|-------|
| F62 | kernel/internal/Stack.scala:18 | `final private[kyo] class Stack:` | new-type |

## justified: concession, the region stack

Interpreter mutability; contract in full below.

| id | site | line | class |
|----|------|------|-------|
| F63 | kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | mutability |
| F66 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | mutability |
| F68 | kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | mutability |
| F70 | kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | mutability |
| F72 | kernel/internal/Stack.scala:24 | `private var size     = 0` | mutability |

## justified: measurement

Four arrays per eval and a doubling copy; no row of the class regressed beyond its own error.

| id | site | line | class |
|----|------|------|-------|
| F64 | kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | allocation |
| F67 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | allocation |
| F69 | kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | allocation |
| F71 | kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | allocation |
| F76 | kernel/internal/Stack.scala:55 | `val hs = new Array[Handler[?, ?, ?, ?, ?]](n)` | allocation |
| F78 | kernel/internal/Stack.scala:56 | `val ss = new Array[Any](n)` | allocation |
| F79 | kernel/internal/Stack.scala:57 | `val xs = new Array[Context](n)` | allocation |
| F80 | kernel/internal/Stack.scala:58 | `val cs = new Array[Arrow[?, ?, ?]](n)` | allocation |

## justified: erasure-forced, the state column

The column's type cannot be written, which is the storage boundary the ladder names with this carrier.

| id | site | line | class |
|----|------|------|-------|
| F65 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | carrier |
| F73 | kernel/internal/Stack.scala:28 | `def push(handler: Handler[?, ?, ?, ?, ?], state: Any, ctx: Context, cont: Arrow[?...` | carrier |
| F74 | kernel/internal/Stack.scala:47 | `def state: Any                      = states(size - 1)` | carrier |
| F75 | kernel/internal/Stack.scala:48 | `def state_=(v: Any): Unit           = states(size - 1) = v` | carrier |
| F77 | kernel/internal/Stack.scala:56 | `val ss = new Array[Any](n)` | carrier |

## justified: test construct

A stackless shared exception, whose four-argument constructor takes the cause as null, as the demo's Discarded does; and a SuspendContext with a non-identity update, which the public surface cannot build and which the context pin needs.

| id | site | line | class |
|----|------|------|-------|
| F81 | kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | carrier |
| F82 | kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | new-type |
| F83 | kernel/internal/EvalTest.scala:608 | `"a context update outlives an operation answered after it" in {` | terminology |
| F84 | kernel/internal/EvalTest.scala:609 | `sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]` | new-type |
| F85 | kernel/internal/EvalTest.scala:611 | `new Kyo.SuspendContext[Int, Count, Int, Count]:` | allocation |
| F86 | kernel/internal/EvalTest.scala:627 | `def recovering(to: Int): Int < Any =` | carrier |
| F87 | kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | carrier |
| F88 | kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | allocation |
| F89 | kernel/internal/EvalTest.scala:635 | `Kyo.handle[Ask, Int, Int, Any, Unit](body, h, ())` | carrier |
| F90 | kernel/internal/EvalTest.scala:637 | `def go(i: Int): Int < Any =` | carrier |

## The concession contracts in full

**The region stack.** Justified: the region chain is the only thing whose depth was the Java stack's.
Scope: one instance per `Eval.apply`, reachable from nothing that leaves the eval. Protection: entries
are written only by the eval's own arms and hold complete values, and a nested eval builds its own.
Pinned by `ArrowEffectTest` "handles nested per recursion step in bounded stack", and by `EvalTest`'s
two multi-shot cases.

Note honestly: the third pin the derivation names, `EvalTest` "a nested eval shares the thread's stack
and sees none of the outer regions", is **red**, because it asserts the eval boundary rejection that is
still open. That protection is currently unchecked, and it is called out rather than counted.

**The guard.** No longer a concession: it was four locals and two `while` loops and is now two
tail-recursive methods, after a probe showed that a self-recursive call from inside a `catch` is
eliminated, which is what the loop existed to avoid. The one allocation is the pair `recovered`
returns, on the failure path only. Pinned by `EvalTest` "regions that fail and recover in sequence
cost no stack", 10000 cycles, which livelocks without the budget reset.

## One thing with no pinning test, stated rather than implied

`finally Safepoint.restore(slot, saved)` has no test. A first attempt passed with the fix reverted, so
it pinned nothing and was deleted rather than kept for the look of it: a nested eval's own `exit`
calls return most of what it spent, so the enclosing eval survives losing the depth, and the armed bit
is unobservable while nothing in the proto arms. It stays because discarding a caller's state is wrong
whether or not this tree can see it, and because the reference kernel does the same at the same place.

## Nothing removed

No row's verdict is `REMOVE`. The one `null` is a stackless exception's cause in a test; the `Any`s are
rows inside types, except the state column, whose type cannot be written.
