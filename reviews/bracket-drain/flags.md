# Flags: bracket-drain (3eb1c6991d..HEAD, kyo-kernel/shared/src/main)

Every construct of concern on the added lines, with its verdict. Verdict vocabulary per
the skill: a cast-ladder category, a measurement, a `moved` provenance naming where the
code came from, or REMOVE.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Arrow.scala:96 | `abstract private[kyo] class Bind[-A, B, -S] extends Step[A, B, S]:` | new-type | justified: the gate-skip is the class's whole contract and no existing shape carries it: every arrow application that runs a user function consults the budget gate (the `Arrow.apply` step, the Loop steps, the map and defer gates), and `Id`, the one gate-free `Step`, applies nothing; a flag on Step would be look-safe indirection; main's `BindingStep` is the precedent, and where main pairs it with an eval-side park guard the proto reaches the same end by the gate-skip alone, pinned by the settle-strand reproduction |
| F2 | Arrow.scala:101 | `case v: Pending[A, S2] @unchecked =>` | cast | typed pattern, erasure-forced: the house idiom of every arrow apply (same file, line 67) |
| F3 | Effect.scala:20 | `sealed private[kyo] trait Finalize extends ContextEffect[Cell]` | new-type | justified: a hidden region effect must be a type to carry a `Tag`; moved unchanged from the deleted Sync prototype |
| F4 | Effect.scala:23 | scaladoc text | mutability | false positive: a comment line |
| F5 | Effect.scala:26 | `final private[kyo] class Cell(...) extends AtomicBoolean:` | new-type | justified: the claim is the state side of the division of labor (the eval guarantees reachability, the state exactly-once); CAS because a drain and a completion can race across threads; moved unchanged from Sync |
| F6 | Effect.scala:31 | `private[kyo] object Cell:` | new-type | companion of F5; carries only `inert` |
| F7 | Effect.scala:35 | `val cell = new Cell(_ => ())` | allocation | one per classload: `inert` is a val |
| F8 | Effect.scala:50 | `val open = new Arrow.Bind[A, B, S1 & S2]:` | allocation | one per bracket call. Structural count per bracket, unmeasured (gate row named in review.md): the old shape built two DeferWith at the call plus the exit map's DeferWith in the body; the new shape builds one DeferWith, the Bind, and the chain's Defer, with no exit map. Equal counts by structure; the bench rows are the evidence when they land |
| F9 | Effect.scala:53 | `val cell = new Cell(...)` | allocation | one per bracket application: per-run mutable state is minted per shot by the complete-value law, so a replayed acquire tail gets a fresh obligation |
| F10 | Eval.scala:27 | scaladoc text | terminology | false positive: the release scaladoc's pre-existing sentence |
| F11 | Eval.scala:74 | `var j = 0` | mutability | engine-room local in the cold expandOwed helper; never escapes |
| F12 | Eval.scala:75 | `while ...` | mutability | same |
| F13 | Eval.scala:77 | `var i = 0` | mutability | same |
| F14 | Eval.scala:78 | `while ...` | mutability | same |
| F15 | Eval.scala:82 | `snapshot.state(i).asInstanceOf[AnyRef]` | cast | erasure-forced: array element re-typing at the storage boundary, the ladder's named Stack example; identical to the pre-existing collect arms |
| F16 | Eval.scala:87 | `end while` | mutability | same as F14 |
| F17 | Eval.scala:89 | `end while` | mutability | same as F12 |
| F18 | Eval.scala:112 | `var i = entries.regions - 1` | mutability | moved: the pre-existing Debugger dump walk, hoisted into the `dumped` helper |
| F19 | Eval.scala:113 | `while ...` | mutability | moved with F18 |
| F20 | Eval.scala:130 | `var c = ctx` | mutability | engine-room local in the rebound helper, which runs once per non-top crossing (the crossing delta row gates it); never escapes |
| F21 | Eval.scala:131 | `var i = 0` | mutability | same |
| F22 | Eval.scala:132 | `while ...` | mutability | same |
| F23 | Eval.scala:134 | `case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>` | cast | typed pattern, erasure-forced: the eval's abstract-member idiom, identical to the settled arm's binder |
| F24 | Eval.scala:136 | `c.update(hc.tag, stack.state(j).asInstanceOf[VX])` | cast | erasure-forced storage boundary; mirrors the settled pop's rebind line verbatim |
| F25 | Eval.scala:140 | `end while` | mutability | same as F22 |
| F26 | Eval.scala:181 | `parked.asInstanceOf[A < S]` | cast | moved: the pre-existing empty-stack park return, reindented by the owed guard |
| F27 | Eval.scala:228 | `case handler: Handler.LoopHandler[...] @unchecked =>` | cast | moved: the pre-existing top-tier arm; the top/non-top split makes the tier explicit, so the arm no longer needs a guard |
| F28 | Eval.scala:263 | `stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]` | cast | moved: the pre-existing done-arm continuation read |
| F29 | Eval.scala:263 | same line | carrier | moved with F28; `Any` is the eval's erased currency position, pre-existing |
| F30 | Eval.scala:285 | `new Arrow.Step[OX[VX], C, EX & S2]:` | allocation | moved: the pre-existing crossing step, one per forced continuation; still behind the lazy def so a loop-done discard builds none |
| F31 | Eval.scala:289 | `case p: Pending[OX[VX], S3] @unchecked =>` | cast | moved: pre-existing step arm |
| F32 | Eval.scala:293 | `Effect.defer(v, kc, ca, cb).asInstanceOf[Any < Any]` | cast | moved: pre-existing park packing |
| F33 | Eval.scala:293 | same line | carrier | moved with F32 |
| F34 | Eval.scala:301 | `case handler: Handler.ContHandler[...] @unchecked =>` | cast | moved: the non-top tier's copy of the pre-existing arm; the top/non-top split duplicates the two Cont arms, measured below as bytecode-neutral against the unsplit variant |
| F35 | Eval.scala:305 | `case handler: Handler.ContOpHandler[...] @unchecked =>` | cast | moved with F34 |
| F36 | Eval.scala:307 | `new Kyo.SuspendArrow[...]` | allocation | moved: the pre-existing operation reification, duplicated by the split |
| F37 | Eval.scala:347 | continuation read | cast | moved: the non-top done arm's pre-existing read |
| F38 | Eval.scala:347 | same line | carrier | moved with F37 |
| F39 | Eval.scala:421 | `hc.done(stack.state(top).asInstanceOf[VX])` | cast | erasure-forced: handler and state travel in parallel slots and the type system cannot carry their pairing; ruled 2026-08-29 ("re-typing an array element at the storage boundary is a sanctioned erasure-forced cast and is not a reason to invent a carrier") and re-confirmed this session against the Obligation carrier |
| F40 | Stack.scala:17 | comment text | carrier | false positive: a comment line |
| F41 | Stack.scala:18 | comment text | carrier | false positive: a comment line |
| F42 | Stack.scala:19 | `private var owed = new Array[Chunk[Stack.Snapshot]](0)` | mutability | the fourth parallel array, same concession shape as handlers/states/continuations, protected by the reset-at-exit protocol (`pop` returns and clears, truncate and clear reset, grow fills) |
| F43 | Stack.scala:19 | same line | allocation | once per pooled Stack instance (empty; grow allocates the real one) |
| F44 | Stack.scala:24 | `private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty` | mutability | lives on the pooled Stack instead of an eval local precisely so the nested eval functions do not lift it into a per-eval box; reset in clear() and at every take |
| F45 | Stack.scala:93 | `new Array[AnyRef](size * 4)` | allocation | moved: the pre-existing snapshot pack, stride 3 to 4 |
| F46 | Stack.scala:97 | `states(i).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F47 | Stack.scala:118 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing contextual pack, stride change |
| F48 | Stack.scala:165 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing dump pack, stride change |
| F49 | Stack.scala:170 | `states(j).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F50 | Stack.scala:195 | `new Array[Chunk[Stack.Snapshot]](capacity)` | allocation | grow-time, same shape as the three sibling arrays beside it |
| F51 | Stack.scala:216 | `Snapshot.empty` | allocation | once per classload: a val |
| F52 | Stack.scala:222 | `new Array[AnyRef](regions * 4)` | allocation | moved: pre-existing Builder, stride change |
| F53 | Stack.scala:242 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F54 | Stack.scala:243 | `def state(i: Int): Any` | carrier | moved: pre-existing accessor |
| F55 | Stack.scala:244 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F56 | Stack.scala:245 | `self(i * 4 + 3).asInstanceOf[Chunk[Snapshot]]` | cast | erasure-forced: the fourth slot of the same storage-boundary layout as F53/F55; the accessor is the one home for it |

## Measurement backing the split verdicts (F27, F34-F36)

`loop$1` bytecode: base 2197, the unsplit eager variant (9a9a12ff71) 2508, the final
split-plus-extraction tree 2504. The split's duplicated arms are offset by the extracted
cold bodies, and the hot top trace runs the pre-change lazy code with zero non-top
overhead. The +307 residual over base is the semantic addition itself (the settled arms'
drain sites, the done call, the crossing's vacate lines, the Park re-home). `recovered$1`
235 to 309; the Defer and Handle arms are untouched.
