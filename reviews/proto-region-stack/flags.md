# Adjudication

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` against the shipped tip emits
**89 rows**. Every row appears below exactly once, with the line the id actually carries.

This table is **generated from the script's own output**, not written from memory. Two earlier
versions were blocked for filing verdicts against lines their ids did not name, twice, so the id,
the line and the verdict now come from one pass over `flags.sh` and cannot drift. A `moved` verdict
is decided by comparing the line, with the declared renames applied and whitespace stripped, against
the baseline file; it is a test rather than a recollection.

Verdict vocabulary is the skill's: a category from the cast ladder's closed set, a measurement, a
`moved` provenance, or `REMOVE`.

## justified: concession, the guard's loop

Contract in full below.

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

## moved

Present verbatim at 31a7b4bde9 under the declared renames.

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

## justified: representation assertion

The machine finished with no region left, so what it holds is the eval's answer.

| id | site | line | class |
|----|------|------|-------|
| F15 | kernel/internal/Eval.scala:128 | `if stack.isEmpty then susp.asInstanceOf[A < S]` | cast |
| F47 | kernel/internal/Eval.scala:266 | `if stack.isEmpty then res.asInstanceOf[A < S]` | cast |

## justified: erasure-forced

Array element re-typing at the storage boundary (Stack), which the ladder names with this carrier. The row is asserted at Any, the subtype end, so it is widened out of the way rather than claimed.

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
| F67 | kernel/internal/Eval.scala:327 | `val cont    = stack.cont.asInstanceOf[Arrow[Y, A, S]]` | cast |

## justified: erasure-forced

Tag storage outside its opaque scope, an approved category. The test is the baseline's own, restructured from a pattern guard to an if because the arm now binds the rebuilt node before popping.

| id | site | line | class |
|----|------|------|-------|
| F19 | kernel/internal/Eval.scala:132 | `if !(susp.tag.erased <:< handler.tag.erased) then` | cast |

## false positive

The flagged word appears in a comment, not in code. The script is recall-tuned and tolerates these
by design; they are rows that need a verdict, not defects.

| id | site | line | class |
|----|------|------|-------|
| F1 | kernel/internal/Eval.scala:44 | `// armed bit is unobservable while nothing in the proto arms. It is kept because ...` | mutability |
| F20 | kernel/internal/Eval.scala:140 | `// instead of re-entering it. Nothing inside the try is evaluated, so a` | carrier |

## justified: erasure-forced

The row half of the storage-boundary read: the handler is asserted at Any, so what it produces is typed at Any too. Not a value carrier.

| id | site | line | class |
|----|------|------|-------|
| F21 | kernel/internal/Eval.scala:151 | `val rebuilt: Y < Any =` | carrier |
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
| F37 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | cast |
| F38 | kernel/internal/Eval.scala:218 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | carrier |
| F39 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | cast |
| F40 | kernel/internal/Eval.scala:223 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | carrier |
| F41 | kernel/internal/Eval.scala:227 | `case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>` | cast |

## moved

One of the baseline's own allocations, retyped by the declared renames.

| id | site | line | class |
|----|------|------|-------|
| F23 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | carrier |
| F24 | kernel/internal/Eval.scala:156 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | allocation |
| F27 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F28 | kernel/internal/Eval.scala:173 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |
| F31 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier |
| F32 | kernel/internal/Eval.scala:190 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation |

## false positive

The word appears in a scaladoc sentence, not in code.

| id | site | line | class |
|----|------|------|-------|
| F70 | kernel/internal/Handler.scala:22 | `* The state is the live one for the same reason [[release]]'s is: a region's stat...` | mutability |

## justified

No existing type holds a growable heterogeneous sequence of open regions, and the entries must be reachable by the eval while a region is open, which a value in the pending union is not.

| id | site | line | class |
|----|------|------|-------|
| F71 | kernel/internal/Stack.scala:18 | `final private[kyo] class Stack:` | new-type |

## justified: concession

Interpreter mutability, contract in full below.

| id | site | line | class |
|----|------|------|-------|
| F72 | kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | mutability |
| F75 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | mutability |
| F77 | kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | mutability |
| F79 | kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | mutability |
| F81 | kernel/internal/Stack.scala:24 | `private var size     = 0` | mutability |

## justified: measurement

Four arrays per eval and a doubling copy; the full class shows no confirmed regression, evidence.md.

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

## justified: erasure-forced

The state column's type genuinely cannot be written; this is the storage boundary the ladder names with this carrier.

| id | site | line | class |
|----|------|------|-------|
| F74 | kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | carrier |
| F82 | kernel/internal/Stack.scala:28 | `def push(handler: Handler[?, ?, ?, ?, ?], state: Any, ctx: Context, cont: Arrow[?...` | carrier |
| F83 | kernel/internal/Stack.scala:47 | `def state: Any                      = states(size - 1)` | carrier |
| F84 | kernel/internal/Stack.scala:48 | `def state_=(v: Any): Unit           = states(size - 1) = v` | carrier |
| F86 | kernel/internal/Stack.scala:56 | `val ss = new Array[Any](n)` | carrier |

## The concession contracts in full

**The region stack** (`Stack`'s five `var`s). Justified: the region chain is the only thing whose
depth was the Java stack's, and a heap chain is what removes that. Scope: one instance per
`Eval.apply`, reachable from nothing that leaves the eval. Protection: entries are written only by
the eval's own arms and hold complete values, and a nested eval builds its own. Pinned by
`ArrowEffectTest` "handles nested per recursion step in bounded stack" (the defect itself),
`EvalTest` "a nested eval shares the thread's stack and sees none of the outer regions",
`EvalTest` "the captured continuation is multi-shot" and "each shot of a multi-shot capture resumes
from capture-time state".

**The guard's loop** (`curr`, `ctx`, `out`, `settled`, `ex`, `unwinding`, and the two `while`s; six
locals, not the five an earlier version claimed). Justified: the alternative resumed a recovery by
calling back into the guard from inside its own catch, costing a frame per recovered region, which is
the dependency this change exists to remove. Scope: six locals in `apply`, none escaping. Protection:
`out` is initialised from `v`, so there is no sentinel and no `Null`; `settled` is the only exit; `ex`
only ever moves forward to the failure a recover itself raised. Pinned by `EvalTest` "regions that
fail and recover in sequence cost no stack", 10000 cycles, which livelocks without the budget reset.

## One thing with no pinning test, stated rather than implied

`finally Safepoint.restore(slot, saved)` has no test. A first attempt passed with the fix reverted,
so it pinned nothing and was removed rather than kept for the look of it. The reason it is hard to
pin: a nested eval's own `exit` calls return most of what it spent, so the enclosing eval survives
losing the depth, and the armed bit the restore also returns is unobservable while nothing in the
proto arms. It is kept because discarding a caller's state is wrong whether or not this tree can
currently see it, and because the reference kernel does the same at the same place.

## One semantic change the classes above do not name

The own-tag answer paths resume with the loop's **current** context where the baseline resumed with
the context the region was installed with (baseline `region(st, r, Arrow.id, ctx)`, whose `ctx` never
advanced, against `loop(r, Arrow.id, Arrow.id, ctx)` here). The proto's own documentation says a
context read rebinds "the updated value for the rest of that region's extent", which the baseline
dropped at every answered operation and this does not, so the change is toward the stated contract.

Pinned by `EvalTest` "a context update outlives an operation answered after it", which fails at
`31a7b4bde9` and passes here. The public surface cannot show it, since every `ContextEffect.suspend`
carries `update(v) = v`, but `SuspendContext` is constructible from the internal package where the
tests live. An earlier draft called it unobservable and left it unpinned, which was true of the
surface and false of the tree.

## Nothing removed

No row's verdict is `REMOVE`. The constructs `rulings.md` names, an `Any` or `Null` **value** carrier,
a `var` outside the engine room, a new type without an argument, a placeholder body, banned
vocabulary, are absent from the diff rather than justified in it. The `Any` that does appear is
always a row inside a type, never the type of a value, except the state column, whose type cannot be
written and which is adjudicated as the storage boundary.

## What the two earlier versions got wrong

Recorded because the escape matters more than the correction, and because both escapes were the same
one:

- version one claimed every row had a verdict while one had none; carried eight rows at
  `measurement pending`, which is not an accepted verdict; verdicted two rows `moved` against code
  absent from the control; and justified `Stack` by who asked for it.
- versions one and two both **filed verdicts against lines their ids did not name**. Version two's
  own footer confessed the defect and then repeated it at larger scale, and it inverted the central
  category: `erasure-forced` was spent on relocated typed patterns while the four real
  storage-boundary casts carried only `moved`.
- both asserted a completeness for the `moved` group that neither had established.

This version is generated from `flags.sh`'s output in one pass, and `moved` is decided by comparing
each line against the baseline file under the declared renames. The failure mode was writing the
table from memory; the fix is not writing it by hand.
