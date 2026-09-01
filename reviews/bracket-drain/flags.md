# Flags: bracket-drain (3eb1c6991d..HEAD, kyo-kernel {shared,jvm-native,js-wasm}/src/main)

Every construct of concern on the added lines, with its verdict. Verdict vocabulary per
the skill: a cast-ladder category, a measurement, a `moved` provenance naming where the
code came from, or REMOVE.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | js-wasm .../Report.scala:5 | `private[kyo] object Report:` | new-type | justified: a platform edge must be one name with per-platform bodies, the Safepoint split's exact shape; js-wasm prints the trace because no thread handler links there |
| F2 | jvm-native .../Report.scala:5 | `private[kyo] object Report:` | new-type | the jvm-native body of F1: the thread's uncaught-exception handler, the scheduler Worker's pattern (Worker.scala:386), per the 2026-09-01 ruling |
| F3 | kyo/Closed.scala:5 | `final class Closed(...)` | new-type | moved: git mv from kyo-core/shared/src/main/scala/kyo/Closed.scala, package and body unchanged, kyo-core callers untouched; ruled 2026-09-01 ("We have a Closed exception, let's move if to the kernel") |
| F4 | Arrow.scala:96 | `abstract private[kyo] class Ensure[-A, B, -S] extends Step[A, B, S]:` | new-type | justified: the gate-skip is the class's whole contract and no existing shape carries it: every arrow application that runs a user function consults the budget gate, and `Id`, the one gate-free `Step`, applies nothing; a flag on Step would be look-safe indirection; main's `BindingStep` is the precedent; named `Ensure` by the 2026-09-01 ruling (uninterruptible application, not binding) |
| F5 | Arrow.scala:101 | `case v: Pending[A, S2] @unchecked =>` | cast | typed pattern, erasure-forced: the house idiom of every arrow apply (same file, line 67) |
| F6 | Effect.scala:20 | `sealed private[kyo] trait Finalize extends ContextEffect[Cell]` | new-type | justified: a hidden region effect must be a type to carry a `Tag`; moved unchanged from the deleted Sync prototype |
| F7 | Effect.scala:23 | scaladoc text | mutability | false positive: a comment line |
| F8 | Effect.scala:26 | `final private[kyo] class Cell(...) extends AtomicBoolean:` | new-type | justified: the claim is the state side of the division of labor (the eval guarantees reachability, the state exactly-once); CAS because a drain and a completion can race across threads; moved unchanged from Sync |
| F9 | Effect.scala:31 | `private[kyo] object Cell:` | new-type | companion of F8; carries only `inert` |
| F10 | Effect.scala:35 | `val cell = new Cell(_ => ())` | allocation | one per classload: `inert` is a val |
| F11 | Effect.scala:54 | `val ensure = new Arrow.Ensure[A, B, S1 & S2]:` | allocation | one per bracket call. Structural count per bracket, backed by the flat bracket bench rows: the old shape built two DeferWith at the call plus the exit map's DeferWith in the body; the new shape builds one DeferWith, the Ensure, and the chain's Defer, with no exit map |
| F12 | Effect.scala:57 | `val cell = new Cell(...)` | allocation | one per bracket application: per-run mutable state is minted per shot by the complete-value law, so a replayed acquire tail gets a fresh obligation |
| F13 | Effect.scala:66 | `val h = new Handler.ContextHandler[...]` | allocation | one per application, structural: `derive`'s only input is the outer state, so the handler that mints this application's cell must itself be per-application; it is the region's identity |
| F14 | Effect.scala:79 | `new Kyo.Handle[...]` | allocation | one per application: the region node itself, the same node the equation's `Kyo.handle` would build; direct construction skips only the settled-body branch |
| F15 | Eval.scala:27 | scaladoc text | terminology | false positive: the release scaladoc's pre-existing sentence |
| F16-F22 | Eval.scala:74-89 | expandOwed locals and while loops | mutability | engine-room locals in the cold expandOwed helper; never escape |
| F20 | Eval.scala:82 | `snapshot.state(i).asInstanceOf[AnyRef]` | cast | erasure-forced: array element re-typing at the storage boundary, the ladder's named Stack example; identical to the pre-existing collect arms |
| F23 | Eval.scala:110 | `private def unhandled(kyo: Any): Nothing` | carrier | justified: cold failure construction; the parameter exists only to be interpolated into the bug message, and typing it would re-import the arm's binders into a helper that never inspects it |
| F24 | Eval.scala:112 | `private def unanswerable(handler: Handler[?, ?, ?]): Nothing` | carrier | same as F23; wildcarded because only `toString` is used |
| F25-F26 | Eval.scala:118-119 | dumped helper walk | mutability | moved: the pre-existing Debugger dump walk, hoisted into the `dumped` helper |
| F27-F32 | Eval.scala:140-150 | rebound helper locals | mutability | engine-room locals in the rebound helper, once per non-top crossing (the crossing delta row gates it); never escape |
| F30 | Eval.scala:144 | `case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>` | cast | typed pattern, erasure-forced: the eval's abstract-member idiom, identical to the settled arm's binder |
| F31 | Eval.scala:146 | `c.update(hc.tag, stack.state(j).asInstanceOf[VX])` | cast | erasure-forced storage boundary; mirrors the settled pop's rebind line verbatim |
| F33 | Eval.scala:191 | `parked.asInstanceOf[A < S]` | cast | moved: the pre-existing empty-stack park return, reindented by the owed guard |
| F34 | Eval.scala:210 | `installed(kyo, resume: Arrow[Any, Any, Any], ...)` | carrier | the park's erased cargo position, pre-existing: a park's value and resume travel at the erased currency type by the node's own contract |
| F35-F39 | Eval.scala:212-224 | installed reenter pre-pass walk | mutability | engine-room locals, once per park install (cold next to the loop) |
| F38 | Eval.scala:216 | `hc.reenter(entries.state(ri).asInstanceOf[VX])` | cast | erasure-forced: handler and state travel in parallel slots, the F48 pairing at the reenter edge |
| F40-F47 | Eval.scala:229-242 | install loop reads and binders | cast/carrier | moved: the pre-existing park install loop (continuation read, ContextHandler binder, state cast, handler re-typing), relocated intact into `installed` |
| F48 | Eval.scala:256 | `hc.done(stack.state(top).asInstanceOf[VX])` | cast | erasure-forced: handler and state travel in parallel slots and the type system cannot carry their pairing; ruled 2026-08-29 ("re-typing an array element at the storage boundary is a sanctioned erasure-forced cast and is not a reason to invent a carrier") and re-confirmed this session against the Obligation carrier |
| F49 | Eval.scala:261 | `ctx.update(hc.tag, stack.state(j).asInstanceOf[VX])` | cast | moved: the settled pop's pre-existing rebind, hoisted into `contextExit` |
| F50 | Eval.scala:313 | `case handler: Handler.LoopHandler[...] @unchecked if atTop =>` | cast | moved: the pre-existing top-tier arm; the top/non-top split makes the tier explicit, so the arm no longer needs a guard |
| F51-F52 | Eval.scala:323 | `stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]` | cast/carrier | moved: the pre-existing pending-arm continuation read; `Any` is the eval's erased currency position |
| F53 | Eval.scala:329 | `type OutT = Outcome2[...]` | new-type | not a type: a local alias restating the pattern's own type so the loop instantiation is explicit; inference collapses the existential row without it (measured: the inferred call fails to compile) |
| F54 | Eval.scala:330 | `loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(reentry), next, ctx)` | carrier | the loop's third argument is the erased currency position of its pre-existing signature |
| F55 | Eval.scala:352 | `type OutT = ...` | new-type | same as F53, non-top arm |
| F56 | Eval.scala:353 | `val reentry2 = continuation.asInstanceOf[Arrow[OX[VX], C, EX & S2]]` | cast | erasure-forced: the same re-typing the top arm's `reentry = k.asInstanceOf[...]` performs on its continuation; the crossing def's inferred value type cannot name the handler's `C` |
| F57 | Eval.scala:354 | `loop[OutT, Y, Any, S2](...)` | carrier | same as F54 |
| F58-F59 | Eval.scala:388 | `installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]], ctx)` | cast/carrier | moved: the pre-existing park-arm resume packing at the erased cargo position |
| F60-F61 | Eval.scala:404 | `loop(res.asInstanceOf[Y < Any], next, Arrow.id, contextExit(hc, top, ctx))` | cast/carrier | moved: the settled arm's pre-existing result re-typing; the exit work moved into `contextExit`, the cast did not change |
| F62 | Handler.scala:53 | `type OutT = ...` | new-type | same as F53: the staged method's local alias for its own signature's outcome type |
| F63 | Handler.scala:54 | `new Arrow.Step[OutT, B, S]:` | allocation | moved: the pre-existing effectful-clause dispatch step, relocated from the eval arm into the staged `clauseDispatch`; one per pending exit, count unchanged; measured: emitting 78.6 vs base 79.1 (-0.7%) after the Defer re-entry was removed |
| F64-F65 | Handler.scala:58-60 | typed patterns in the dispatch | cast | typed patterns, erasure-forced: the house step idiom, moved with F63 |
| F66 | KyoInternal.scala:64 | `new Arrow.Step[O[A], C, S]:` | allocation | moved: the pre-existing crossing resume step, relocated from the eval arm into the staged `crossing`; still reached only through the lazy `def continuation`, so a loop-done discard builds none |
| F67 | KyoInternal.scala:68 | `case p: Pending[O[A], S3] @unchecked =>` | cast | typed pattern, erasure-forced; moved with F66 |
| F68-F69 | KyoInternal.scala:72 | `Effect.defer(v, kc, resume).asInstanceOf[Any < Any]` | cast/carrier | moved: the pre-existing park packing at the node's erased cargo position |
| F70-F71 | Stack.scala:17-18 | comment text | carrier | false positive: comment lines |
| F72 | Stack.scala:19 | `private var owed = new Array[Chunk[Stack.Snapshot]](0)` | mutability | the fourth parallel array, same concession shape as handlers/states/continuations, protected by the reset-at-exit protocol (`pop` clears, truncate and clear reset, grow fills) |
| F73 | Stack.scala:19 | same line | allocation | once per pooled Stack instance (empty; grow allocates the real one) |
| F74 | Stack.scala:24 | `private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty` | mutability | lives on the pooled Stack instead of an eval local precisely so the nested eval functions do not lift it into a per-eval box; reset in clear() and at every take |
| F75 | Stack.scala:49 | comment text | terminology | false positive: a comment line |
| F76 | Stack.scala:53 | `private var owes = false` | mutability | the sticky owed flag: set by dump/owe/oweBelow, cleared only in clear(); can false-positive after a drain, never misses, so exit sites stay one branch |
| F77 | Stack.scala:57 | comment text | terminology | false positive: a comment line |
| F78 | Stack.scala:103 | `new Array[AnyRef](size * 4)` | allocation | moved: the pre-existing snapshot pack, stride 3 to 4 |
| F79 | Stack.scala:107 | `states(i).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F80 | Stack.scala:128 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing contextual pack, stride change |
| F81 | Stack.scala:175 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing dump pack, stride change |
| F82 | Stack.scala:180 | `states(j).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F83 | Stack.scala:206 | `new Array[Chunk[Stack.Snapshot]](capacity)` | allocation | grow-time, same shape as the three sibling arrays beside it |
| F84 | Stack.scala:227 | `Snapshot.empty` | allocation | once per classload: a val |
| F85 | Stack.scala:233 | `new Array[AnyRef](regions * 4)` | allocation | moved: pre-existing Builder, stride change |
| F86 | Stack.scala:253 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F87 | Stack.scala:254 | `def state(i: Int): Any` | carrier | moved: pre-existing accessor |
| F88 | Stack.scala:255 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F89 | Stack.scala:256 | `self(i * 4 + 3).asInstanceOf[Chunk[Snapshot]]` | cast | erasure-forced: the fourth slot of the same storage-boundary layout as F86/F88; the accessor is the one home for it |

## Measurement backing the split and staging verdicts (F50, F53, F63, F66)

`loop$1` bytecode: base 2197, the unsplit eager variant (9a9a12ff71) 2508, the
split-plus-extraction tree 2504. The split's duplicated arms are offset by the extracted
cold bodies, and the hot top trace runs the pre-change lazy code with zero non-top
overhead. `recovered$1` 235 to 309; the Defer and Handle arms are untouched. The staged
relocations (F63, F66) and the exit extractions were then measured as the full-class -f 1
board in review.md: emitting 78.6 (-0.7%), idle 17.9 (flat), fuse 11.4 (inside band),
trailing maps -15.7%.
