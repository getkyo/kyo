# Adjudication

Generated from `flags.sh 31a7b4bde9..HEAD -- kyo-kernel/shared/src/main/scala/kyo/proto
kyo-kernel/shared/src/test/scala/kyo/proto`, against the tip `6a40f9de4b`, in one pass over the working
tree, which is clean against that tip. Every row below carries the line its id actually names, and
every row carries a verdict.

Scoped to the kernel surface on purpose. The range also adds `ProtoBench.scala`, whose 39 further
rows are all `Int < Any` in benchmark bodies; that file is presented on its own rather than
adjudicated here, because a benchmark's carrier types say nothing about the kernel.

## The verdicts

| verdict | what it means | rows |
|---|---|---|
| `typed-pattern` | `case x: T @unchecked`, the ladder's second rung. The runtime test is the same one a cast would perform and the claim is visible at the binding, which is why it is preferred to a cast rather than merely tolerated | 26 |
| `storage-boundary` | reading a `Stack` slot back at the type its `push` established. Erasure-forced: the four arrays hold every region's values, so their element types are the erasure. The check lives at the push, which is typed in all five parameters, so a wrongly built entry cannot reach these reads | 16 |
| `eval-answer` | the loop's result where the stack is empty. The loop's row is the eval's, and with no region left the value in hand is the eval's answer | 2 |
| `representation` | asserting a value is already union-represented so the strip does not fire. Load-bearing: this is the arm that stops a done payload being handed to the loop as work | 2 |
| `allocation` | a node allocation, each carrying the comment that says what it fulfils | 19 |
| `carrier` | the pending type as a carrier in a test or a signature; not a construct of concern, emitted because the pattern cannot tell them apart | 17 |
| `mutability` | the stack's four arrays and its size, plus `Maybe` locals in tests. The concession the derivation records | 6 |
| `new-type` | `Stack`, plus three test-local fixtures | 4 |
| `prose` | a comment or scaladoc line the pattern matched on a keyword | 4 |
| `not-a-cast` | `<:<` on two erased tags, matched by the cast pattern; it is a subtype test, not a cast | 1 |

**The cast count went down.** The absorb block's `kyo.asInstanceOf[C < S]` is gone: the block now
produces a `Kyo.Suspend[?, ?, ?]` for the regions to be asked about, rather than the arm's answer.
That is the only cast the change removes, and no cast in the file was left unmoved without a reason
below.

## Rows

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:44 | `// armed bit is unobservable while nothing in the proto arms. It is kept because discarding a` | mutability | `prose` |
| F2 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:56 | `case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>` | cast | `typed-pattern` |
| F3 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:58 | `case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>` | cast | `typed-pattern` |
| F4 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:63 | `case kyo: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>` | cast | `typed-pattern` |
| F5 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:68 | `case kyo: Kyo.Suspend[EX, T, S2] @unchecked =>` | cast | `typed-pattern` |
| F6 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:84 | `case sa: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] @unchecked =>` | cast | `typed-pattern` |
| F7 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:89 | `new Kyo.SuspendArrow[IX, OX, EX, VX, C, S2] with Arrow.Transform[OX[VX], C, S2]:` | allocation | `allocation` |
| F8 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:95 | `case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, c2)` | cast | `typed-pattern` |
| F9 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:98 | `case sc: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>` | cast | `typed-pattern` |
| F10 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:103 | `new Kyo.SuspendContext[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation | `allocation` |
| F11 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:109 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast | `typed-pattern` |
| F12 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:112 | `case sd: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked =>` | cast | `typed-pattern` |
| F13 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:117 | `new Kyo.SuspendContextDefault[VX, CX, C, S2] with Arrow.Transform[VX, C, S2]:` | allocation | `allocation` |
| F14 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:124 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` | cast | `typed-pattern` |
| F15 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:128 | `if stack.isEmpty then susp.asInstanceOf[A < S]` | cast | `eval-answer` |
| F16 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast | `storage-boundary` |
| F17 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:130 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier | `storage-boundary` |
| F18 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:131 | `val state   = stack.state.asInstanceOf[VX]` | cast | `storage-boundary` |
| F19 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:132 | `if !(susp.tag.erased <:< handler.tag.erased) then` | cast | `not-a-cast` |
| F20 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:147 | `val rebuilt: Y < Any =` | carrier | `carrier` |
| F21 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:149 | `case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] @unchecked =>` | cast | `typed-pattern` |
| F22 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:152 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | carrier | `carrier` |
| F23 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:152 | `new Kyo.SuspendArrow[IY, OY, EY, VY, Y, Any] with Arrow.Transform[OY[VY], Y, Any]:` | allocation | `allocation` |
| F24 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:161 | `case p: Pending[OY[VY], S3] @unchecked =>` | cast | `typed-pattern` |
| F25 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:166 | `case sc: Kyo.SuspendContext[VX, CX, AX, EX] @unchecked =>` | cast | `typed-pattern` |
| F26 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:169 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier | `carrier` |
| F27 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:169 | `new Kyo.SuspendContext[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation | `allocation` |
| F28 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:178 | `case p: Pending[VX, S3] @unchecked =>` | cast | `typed-pattern` |
| F29 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:183 | `case sd: Kyo.SuspendContextDefault[VX, CX, AX, EX] @unchecked =>` | cast | `typed-pattern` |
| F30 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:186 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | carrier | `carrier` |
| F31 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:186 | `new Kyo.SuspendContextDefault[VX, CX, Y, Any] with Arrow.Transform[VX, Y, Any]:` | allocation | `allocation` |
| F32 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:196 | `case p: Pending[VX, S3] @unchecked =>` | cast | `typed-pattern` |
| F33 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:204 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast | `storage-boundary` |
| F34 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:204 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier | `storage-boundary` |
| F35 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:210 | `case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX] @unchecked =>` | cast | `typed-pattern` |
| F36 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:214 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | cast | `typed-pattern` |
| F37 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:214 | `case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>` | carrier | `typed-pattern` |
| F38 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:219 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | cast | `typed-pattern` |
| F39 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:219 | `case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>` | carrier | `typed-pattern` |
| F40 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:223 | `case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>` | cast | `typed-pattern` |
| F41 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:235 | `val r = o.asInstanceOf[Y < Any]` | cast | `representation` |
| F42 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:235 | `val r = o.asInstanceOf[Y < Any]` | carrier | `representation` |
| F43 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:237 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast | `storage-boundary` |
| F44 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:237 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier | `storage-boundary` |
| F45 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:250 | `case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>` | cast | `typed-pattern` |
| F46 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:256 | `case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>` | cast | `typed-pattern` |
| F47 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:268 | `if stack.isEmpty then res.asInstanceOf[A < S]` | cast | `eval-answer` |
| F48 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:272 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast | `storage-boundary` |
| F49 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:272 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier | `storage-boundary` |
| F50 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:273 | `val r       = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))` | cast | `storage-boundary` |
| F51 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:275 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | cast | `storage-boundary` |
| F52 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:275 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` | carrier | `storage-boundary` |
| F53 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:281 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | cast | `typed-pattern` |
| F54 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:281 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | carrier | `typed-pattern` |
| F55 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:304 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | cast | `storage-boundary` |
| F56 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:304 | `val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]` | carrier | `storage-boundary` |
| F57 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:305 | `val state   = stack.state.asInstanceOf[VX]` | cast | `storage-boundary` |
| F58 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:316 | `r.chain(stack.cont.asInstanceOf[Arrow[Y, A, S]])` | cast | `storage-boundary` |
| F59 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Handler.scala:22 | `* The state is the live one for the same reason [[release]]'s is: a region's state is single-sourced, ...` | mutability | `prose` |
| F60 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:18 | `final private[kyo] class Stack:` | new-type | `new-type` |
| F61 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | mutability | `mutability` |
| F62 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:20 | `private var handlers = new Array[Handler[?, ?, ?, ?, ?]](8)` | allocation | `allocation` |
| F63 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | carrier | `carrier` |
| F64 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | mutability | `mutability` |
| F65 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:21 | `private var states   = new Array[Any](8)` | allocation | `allocation` |
| F66 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | mutability | `mutability` |
| F67 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:22 | `private var ctxs     = new Array[Context](8)` | allocation | `allocation` |
| F68 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | mutability | `mutability` |
| F69 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:23 | `private var conts    = new Array[Arrow[?, ?, ?]](8)` | allocation | `allocation` |
| F70 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:24 | `private var size     = 0` | mutability | `mutability` |
| F71 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:36 | `cont: Arrow[B, Any, S]` | carrier | `carrier` |
| F72 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:56 | `def state: Any                      = states(size - 1)` | carrier | `carrier` |
| F73 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:57 | `def state_=(v: Any): Unit           = states(size - 1) = v` | carrier | `carrier` |
| F74 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:64 | `val hs = new Array[Handler[?, ?, ?, ?, ?]](n)` | allocation | `allocation` |
| F75 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:65 | `val ss = new Array[Any](n)` | carrier | `carrier` |
| F76 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:65 | `val ss = new Array[Any](n)` | allocation | `allocation` |
| F77 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:66 | `val xs = new Array[Context](n)` | allocation | `allocation` |
| F78 | kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:67 | `val cs = new Array[Arrow[?, ?, ?]](n)` | allocation | `allocation` |
| F79 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | carrier | `carrier` |
| F80 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:603 | `private object Boom extends RuntimeException("boom", null, false, false)` | new-type | `new-type` |
| F81 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:608 | `"a context update outlives an operation answered after it" in {` | terminology | `terminology` |
| F82 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:609 | `sealed trait Count extends kyo.proto.kernel.ContextEffect[Int]` | new-type | `new-type` |
| F83 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:611 | `new Kyo.SuspendContext[Int, Count, Int, Count]:` | allocation | `allocation` |
| F84 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:627 | `def recovering(to: Int): Int < Any =` | carrier | `carrier` |
| F85 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | carrier | `carrier` |
| F86 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:628 | `val h = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | allocation | `allocation` |
| F87 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:635 | `Kyo.handle[Ask, Int, Int, Any, Unit](body, h, ())` | carrier | `carrier` |
| F88 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:637 | `def go(i: Int): Int < Any =` | carrier | `carrier` |
| F89 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:647 | `// these livelock; the count is sampled directly here, which fails while the scenario still ends.` | mutability | `prose` |
| F90 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:661 | `val h = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Ask]:` | allocation | `allocation` |
| F91 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:692 | `object Inner extends RuntimeException("inner", null, false, false)` | carrier | `carrier` |
| F92 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:692 | `object Inner extends RuntimeException("inner", null, false, false)` | new-type | `new-type` |
| F93 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:693 | `var seen = Maybe.empty[Throwable]` | mutability | `mutability` |
| F94 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:695 | `val innerHandler = new Handler.HandlerCont[Const[String], Const[Unit], Say, Int, Int, Ask]:` | allocation | `allocation` |
| F95 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:702 | `val outerHandler = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | carrier | `carrier` |
| F96 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:702 | `val outerHandler = new Handler.HandlerCont[Const[Unit], Const[Int], Ask, Int, Int, Any]:` | allocation | `allocation` |
| F97 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:711 | `// deferred, so the throw lands while the loop is inside the region rather than while the` | mutability | `prose` |
| F98 | kyo-kernel/shared/src/test/scala/kyo/proto/kernel/internal/EvalTest.scala:715 | `val outer: Int < Any        = Kyo.handle[Ask, Int, Int, Any, Unit](inner, outerHandler, ())` | carrier | `carrier` |

## The one row that is a judgement rather than a class

`F81`, the test name "a context update outlives an operation answered after it", is flagged as
terminology because of "after". The ruling it is checked against is about identifiers: the loop's
registers are `contA` and `contB`, never `afterA` and `afterB`. This is a sentence about time
order, where "after" is the English word and "cont" would not parse. Kept, and recorded here so it
is a decision rather than an omission.

`F92` and `F80` are `RuntimeException` singletons in tests, stackless and shared so that filling a
trace ten thousand times does not become what the test measures. `F82` is a test-local
`ContextEffect`, needed because the public surface cannot build the node the test is about.
