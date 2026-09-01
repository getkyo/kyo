# Flags: bracket-drain (3eb1c6991d..HEAD, kyo-kernel/shared/src/main)

Every construct of concern on the added lines, with its verdict. Verdict vocabulary per
the skill: a cast-ladder category, a measurement, a `moved` provenance, or REMOVE.

| id | site | added line | class | verdict |
|----|------|------------|-------|---------|
| F1 | Arrow.scala:96 | `abstract private[kyo] class Bind[-A, B, -S] extends Step[A, B, S]:` | new-type | justified: the gate-skip is the class's whole contract and no existing arrow shape can carry it, since every Step and Transform application consults the budget gate; a flag on Step would be look-safe indirection; main's `BindingStep` is the precedent |
| F2 | Arrow.scala:101 | `case v: Pending[A, S2] @unchecked =>` | cast | typed pattern, erasure-forced: the house idiom of every arrow apply (same file, line 67) |
| F3 | Effect.scala:20 | `sealed private[kyo] trait Finalize extends ContextEffect[Cell]` | new-type | justified: a hidden region effect must be a type to carry a `Tag`; moved unchanged from the deleted Sync prototype |
| F4 | Effect.scala:23 | comment text | mutability | false positive: a scaladoc line |
| F5 | Effect.scala:26 | `final private[kyo] class Cell(...) extends AtomicBoolean:` | new-type | justified: the claim is the state-side of the division of labor (the eval guarantees reachability, the state exactly-once); CAS because a drain and a completion can race across threads; moved unchanged from Sync |
| F6 | Effect.scala:31 | `private[kyo] object Cell:` | new-type | companion of F5; carries only `inert` |
| F7 | Effect.scala:35 | `val cell = new Cell(_ => ())` | allocation | one per classload: `inert` is a val |
| F8 | Effect.scala:50 | `val open = new Arrow.Bind[A, B, S1 & S2]:` | allocation | one per bracket call, replacing the map arrow the previous shape allocated at the same site: net zero |
| F9 | Effect.scala:53 | `val cell = new Cell(...)` | allocation | one per bracket application: per-run mutable state is minted per shot by the complete-value law, so a replayed acquire tail gets a fresh obligation |
| F10 | Eval.scala:27 | scaladoc text | terminology | false positive: the release scaladoc's pre-existing sentence |
| F11 | Eval.scala:74 | `var j = 0` | mutability | engine-room local in a cold drain helper; never escapes |
| F12 | Eval.scala:75 | `while j < snapshots.length do` | mutability | same |
| F13 | Eval.scala:77 | `var i = 0` | mutability | same |
| F14 | Eval.scala:78 | `while i < snapshot.regions do` | mutability | same |
| F15 | Eval.scala:82 | `snapshot.state(i).asInstanceOf[AnyRef]` | cast | erasure-forced: array element re-typing at the storage boundary, the ladder's named Stack example; identical to the pre-existing collect arms |
| F16 | Eval.scala:87 | `end while` | mutability | same as F14 |
| F17 | Eval.scala:89 | `end while` | mutability | same as F12 |
| F18 | Eval.scala:112 | `var c = ctx` | mutability | engine-room local in the downdate walk (per non-top crossing); never escapes |
| F19 | Eval.scala:113 | `var i = 0` | mutability | same |
| F20 | Eval.scala:114 | `while i < entries.regions do` | mutability | same |
| F21 | Eval.scala:116 | `case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>` | cast | typed pattern, erasure-forced: the eval's abstract-member idiom, identical to the settled arm's binder |
| F22 | Eval.scala:118 | `c.update(hc.tag, stack.state(j).asInstanceOf[VX])` | cast | erasure-forced storage boundary; mirrors the settled pop's downdate line verbatim |
| F23 | Eval.scala:122 | `end while` | mutability | same as F20 |
| F24 | Eval.scala:160 | `var rootOwed: Chunk[Stack.Snapshot] = Chunk.empty` | mutability | eval-local; leaves only by transfer into an immutable `Kyo.Park`, cleared at the transfer |
| F25 | Eval.scala:173 | `parked.asInstanceOf[A < S]` | cast | moved: the pre-existing empty-stack park return, untouched but reindented by the owed guard |
| F26 | Eval.scala:218 | `var i = dumped.regions - 1` | mutability | moved: the pre-existing Debugger.whenEnabled walk, renamed binder |
| F27 | Eval.scala:260 | `case handler: Handler.LoopHandler[...] @unchecked if atTop =>` | cast | moved: pre-existing arm; only the guard changed from a recomputed comparison to the pre-dump val, which the eager dump makes load-bearing |
| F28 | Eval.scala:299 | `stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]` | cast | moved: the pre-existing done-arm continuation read |
| F29 | Eval.scala:299 | same line | carrier | moved with F28; `Any` here is the eval's erased currency position, pre-existing |
| F30 | Eval.scala:341 | same idiom | cast | moved: the non-top done arm's pre-existing read |
| F31 | Eval.scala:341 | same line | carrier | moved with F30 |
| F32 | Eval.scala:413 | `hc.done(stack.state(top).asInstanceOf[VX])` | cast | erasure-forced: handler and state travel in parallel slots and the type system cannot carry their pairing; ruled 2026-08-29 ("re-typing an array element at the storage boundary is a sanctioned erasure-forced cast and is not a reason to invent a carrier") and re-confirmed this session against the Obligation carrier |
| F33 | Stack.scala:17 | comment text | carrier | false positive: a comment line |
| F34 | Stack.scala:18 | comment text | carrier | false positive: a comment line |
| F35 | Stack.scala:19 | `private var owed = new Array[Chunk[Stack.Snapshot]](0)` | mutability | the fourth parallel array, same concession shape as handlers/states/continuations, protected by the reset-at-exit protocol (`pop` returns and clears, truncate and clear reset, grow fills) |
| F36 | Stack.scala:19 | same line | allocation | once per pooled Stack instance (empty; grow allocates the real one) |
| F37 | Stack.scala:73 | `new Array[AnyRef](size * 4)` | allocation | moved: the pre-existing snapshot pack, stride 3 to 4 |
| F38 | Stack.scala:77 | `states(i).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F39 | Stack.scala:98 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing contextual pack, stride change |
| F40 | Stack.scala:145 | `new Array[AnyRef](count * 4)` | allocation | moved: pre-existing dump pack, stride change |
| F41 | Stack.scala:150 | `states(j).asInstanceOf[AnyRef]` | cast | moved: pre-existing pack cast |
| F42 | Stack.scala:175 | `new Array[Chunk[Stack.Snapshot]](capacity)` | allocation | grow-time, same shape as the three sibling arrays beside it |
| F43 | Stack.scala:196 | `Snapshot.empty` | allocation | once per classload: a val |
| F44 | Stack.scala:202 | `new Array[AnyRef](regions * 4)` | allocation | moved: pre-existing Builder, stride change |
| F45 | Stack.scala:222 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F46 | Stack.scala:223 | `def state(i: Int): Any` | carrier | moved: pre-existing accessor |
| F47 | Stack.scala:224 | accessor cast | cast | moved: pre-existing accessor, stride change only |
| F48 | Stack.scala:225 | `self(i * 4 + 3).asInstanceOf[Chunk[Snapshot]]` | cast | erasure-forced: the fourth slot of the same storage-boundary layout as F45/F47; the accessor is the one home for it |
