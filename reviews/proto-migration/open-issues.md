# Open soundness issues in the proto kernel, for a fix design

Tip: `99605870e8`. Standing instruction: tests only, no fixes yet; this document is the brief
for the design of the fixes. Three issues are open. Each has a red or as-is pin in the suites,
a traced mechanism, the rulings that bind the design, and the candidate shapes considered so
far. Sections 5 and 6 carry the constraints and the context of what was already changed.

## 1. S9. A crossing resumed in a nested eval is released again at the owner's exit

Pin, red: ContextEffectTest "a crossing resumed in a nested eval inside the clause completes
its region without a release at the owner's exit" (ContextEffectTest.scala:325). Program:

```scala
val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))        // a context region with done/release hooks
val r: Int < Any = ArrowEffect.handleCont(Tag[Ask], body)(
    [C] => (_, cont) => answerAsk(0)(cont(41)).eval + 1,          // the clause resumes the crossing in a nested eval
    a => a
)
r.eval == 43
log == List("done cfg 1")                                          // today: List("done cfg 1", "release cfg 1")
```

Mechanism. The outer eval borrows stack A. `ask` inside the `Cfg` region is answered by the
`Ask` region below it, so `Eval.dumped` (Eval.scala:394) moves `Cfg` into a snapshot through
`Stack.dump` (Stack.scala:187), which appends the snapshot to the lane below the dump point
(`owed(from - 1)`, the debt: if the clause never resumes, the region is released at the owner's
exit). The clause's `cont(41)` builds a `Kyo.Park` over that snapshot (`crossing`,
KyoInternal.scala:67); `.eval` starts a nested `Eval.apply`, which borrows a different pooled
stack B. On B, the `Park` arm (Eval.scala:185) runs `installed` (Eval.scala:237), whose
`stack.settle(entries)` (Eval.scala:253, Stack.scala:66) searches B's lanes and B's eval lane
for the snapshot by identity and finds nothing: the debt lives in A's lane. `Cfg` is pushed on
B, completes, `contextExit` fires `done`. Back on A the clause returns, the `Ask` region's
settled arm runs `done` and `arrowExit`, which drains A's lane through `drainDiscarded`
(Eval.scala:405) with the "remainder discarded" signal: the snapshot is still there, so
`expandOwed` (Eval.scala:476) reaches `Cfg` and `release` fires after the region already
completed. The same path with a race is a resume handed to another thread.

This is the cross-eval twin of S4 (fixed in `ccafba44c9`): `Stack.settle` removes a debt by
snapshot identity from whichever lane of *one stack* holds it. A snapshot consumed by another
eval has no way to say so.

Law in force: a region dumped by a crossing and re-installed by a resume that completes
normally fires `done` once and `release` never (S4's law, ContextEffectTest "a region crossed to
a foreign loop answered with a pending outcome completes without a release", and its nested
region twin). Brackets are masked by the Cell's CAS (`Cell.drain` after `Cell.complete` is a
failed compare-and-set, Effect.scala), which is why only raw hooks show it.

Candidate shapes considered:

1. The debt is discharged on the snapshot itself: `installed` marks the snapshot consumed
   wherever it re-installs it, and every drain (`expandOwed`, `drainDiscarded` through
   `drainOwed`, `Eval.release`) skips a consumed snapshot. Subsumes S4's lane scan (`settle`
   becomes the mark), covers nested evals and other threads. Open questions inside this shape:
   where the mark lives (`Stack.Snapshot` is `opaque type Snapshot = Span[AnyRef]`,
   Stack.scala:233, four slots per region, built by `Stack.wrap` over an array; a trailing slot,
   a header slot, or a different carrier), the shared `Stack.Snapshot.empty` instance
   (Stack.scala:238) which must never be marked, the visibility of the mark to a draining
   thread (a plain write, at worst the duplicate release the code has today, or a volatile
   read on every drain), and the multi-shot semantics below.
2. A stack remembers the stack that was active on its thread when it was borrowed, and
   `settle` walks that chain. Fixes the nested-eval case only; a cross-thread resume stays
   at-least-once.
3. Rule raw hooks at-least-once across evals, the direction Q4 took for `handleFirst`
   remainders, and flip the pin to assert `List("done cfg 1", "release cfg 1")`.

Multi-shot interaction, which any shape must decide. `installed` re-owes `entries.owed(i)` on
every re-install (Eval.scala:264-276 region), so on a second shot the same inner snapshot
object is owed again. Today `settle` removes one occurrence per resume, so a second shot that
abandons before re-reaching an inner resume drains that inner snapshot again and fires its raw
hook a second time although that region instance completed in shot one (the pin ContextEffectTest
"each shot of a crossing drains the debts it re-installs" asserts only the outer region's `done`
twice and no release). Under shape 1 a consumed snapshot is never drained again, which matches
"one of `done` or `release` per region instance"; the design has to say which of the two is
the law and pin it.

## 2. Entry 9. A raw context region is revived after its release fired

Pin, green as the code behaves today: ContextEffectTest "a handleFirst remainder re-enters a
raw region the region's end already released" (ContextEffectTest.scala:378). Program:

```scala
val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
val first: Int < Ask = ArrowEffect.handleFirst[Const[Unit], Const[Int], Ask, Int, Int, Any, Ask](Tag[Ask], body)(
    handle = [C] => (_, cont) => { log += "clause"; cont(41) },
    done = a => a
)
answerAsk(0)(first).eval == 42
log == List("clause", "release cfg 1", "done cfg 1")               // release, then the region completes again
```

Mechanism. `handleFirst` ends its region with the `FirstSuspended` token; the region's exit
drains what it owes (the dumped `Cfg`, released with the discard signal), and the remainder the
clause built is a `Park` over the same snapshot, evaluated afterwards: `installed` calls
`reenter`, a no-op for a `ContextEffect.handle` region (Handler.scala, the default), pushes
`Cfg` again and the remainder completes it: `done` after `release`. For a bracket the same path
is refused with `kyo.Closed` because its `reenter` checks the cell (Effect.scala:48-50), the Q4
ruling. So raw regions and brackets disagree on whether a released region can be revived.

Rulings in force: Q4 as revised on 2026-09-03 (a `handleFirst` region passes what it owes to
the scope below at its exit, so the remainder re-installs its regions unreleased; the pin above
now expects `("clause", "done cfg 1")`, one of `done` or `release`, and the release-then-revive
this entry described no longer happens in the same-eval case); S3 (fork copies are silent to
hooks; `Forked` forwards `reenter` to its origin since S8, Isolate.scala:108).

What remains of this entry is the cross-eval face: a raw region stashed out of a `handleFirst`
and resumed in a later eval is released at the first eval's end and revived by each resume
(IsolateTest `continuationOf`), where a bracket is refused. Candidate: the same mark as S9
carries a released state, and `installed` refuses a released snapshot the way the bracket's
cell refuses, so raw regions and brackets agree there too. The alternative is to keep the
revive across evals and record it as the raw-hook face of the cross-eval lane.

## 3. S10. A context read at a supertype tag ignores an inner subtype binding

Pin, red: ContextEffectTest "a read takes the innermost binding whether its tag is exact or a
subtype" (ContextEffectTest.scala:270). Program:

```scala
handleInheritable(Tag[CfgSub], 1)(handleInheritable(Tag[Cfg], 2)(read at Cfg))  == 2   // green
handleInheritable(Tag[Cfg], 1)(handleInheritable(Tag[CfgSub], 2)(read at Cfg))  == 2   // red, answers 1
```

Mechanism. `Context.get` (Context.scala:17) is `TypeMap.get` (kyo-data TypeMap.scala:40),
which resolves an exact key anywhere in the map first (`exact`), and otherwise searches by
subtyping oldest entry first (`search`, "so resolution is deterministic"). Under an outer exact
`Cfg` and an inner `CfgSub`, the exact search finds the outer; with no exact key the oldest
related binding wins, the outermost. Dispatch (`Stack.find`, Stack.scala:171) walks from the
top, innermost first, so reads and dispatch disagree on which related region is in scope. S2
(fixed in `ccafba44c9`) made the exit side exact (`Stack.findExact` in `contextExit` and
`rebound`); this is the read side.

Candidate: a read resolves innermost first among related keys. `TypeMap` nodes are newest
first (`addErased` prepends a `Node`, TypeMap.scala:179), so a walk from the head taking the
first `<:<` match is innermost first; `Context.get` can do that walk itself (`Node` is
`private[kyo]`) or kyo-data can gain the lookup. Entry (`derive(ctx.get(tag))`,
Eval.scala:169) inherits whichever rule reads use, so the same walk decides what a nested
region derives from.

## 4. Rulings that bind the design

- S3 (2026-09-02): fork and join copies are silent to `done` and `release`; the join is written
  into the owning region in place; a read placed after a restored crossing sees the scope it
  restores in.
- S7: `Eval.partial` is never nested; only the scheduler's task loop calls it. Nested `.eval`
  inside a clause (S9's shape) is ordinary and allowed.
- Q3: a park resumes with its captured bindings wherever it resumes. Q4 (revised 2026-09-03):
  a `handleFirst` region hands its debt to the scope below; the remainder carries its brackets,
  releases them once when it completes, is refused on a second resumption, and a dropped
  remainder releases at the enclosing exit.
- Entry 7 of the eff audit stands: a `handleLoop` clause's post-suspension throw escapes its
  region's own `recover`; a `handleCont` clause's is caught. Entry 13 stands: an isolate
  resumed under a different region of its tag joins nothing.
- Report finding 21: the `stack.scratch = outcome0` store is the measured C2 escape from
  `a8cff0a3f8` and stays.

## 5. Constraints on the fix

- `kyo-kernel/.claude/skills/kernel/SKILL.md` is the standard: composition first, no new
  node kinds unless a combinator is missing, no new terminology, avoid new types, correct by
  construction over correct by inspection, method size on the hot path is a design property
  (`loop` and dispatch do not inline; every byte moved out of line matters), one variable per
  measurement, no regression accepted without a number.
- No leaking fields on the pooled stack ("DO NOT GO BACK TO THESE LEAKING FIELDS"): anything
  stored on `Stack` is cleared by `Stack.clear` at release, and nothing on it may retain user
  values past the eval.
- `Maybe`, never `null`; imports rather than qualified names; no comments or prose in the
  code; no forwarding helpers.
- The crossing path is measured: `foreignCrossingsPayRotation` (1,092 us, 2.24 MB/op),
  `foreignCrossingsAnsweredInPlace` (603 us, 1.52 MB/op), and every row in `ProtoBench`
  (`kyo-kernel/jvm/src/jmh/scala/kyo/proto/bench/ProtoBench.scala`) is the class to run on
  both tips, `-f 1` then `-f 3` outside the drift band, `-prof gc` for `gc.alloc.rate.norm`.
- The lift equilibrium: no new implicit-lift summons in core, inlined-from files; verify the
  clean batch build.

## 6. What is already in place, for context

Fixed and verified on JVM, JS and Native unless noted (JS and Native deferred to a final
sweep for S8 and the gate):

- S1 (`dcb877587f`): `Loop.repeat` and `Loop.indexed` match `Pending`.
- S2 (`ccafba44c9`): `Stack.findExact` at exit and in `rebound`.
- S3 (`1ede100a1b`, `66294c5fca`): the `Snapshot` node's continuation receives the live
  stack; `restore` writes the join in place; `Forked` copies; the evaluator rebuilds the
  context after a `Snapshot` node.
- S4 (`ccafba44c9`): `Stack.settle(snapshot)` removes a debt by identity from whichever lane
  holds it, eval lane included.
- S5 (`ccafba44c9`): `ContextEffect.handle` always builds the region node; `lazy val state`.
- S6 (`ccafba44c9`): a per-eval epoch on the pooled stack; the trace carrier dedupes per eval.
- S8 (`164eb68f47`): `Forked` forwards `reenter`; the bracket forks its live cell.
- Q1 (`164eb68f47`): the debugger gate is a compile-time read of a system property through
  `CompileTimeFlag.boolean` in kyo-data; zero bytecode footprint when off.

The reading audits: `soundness-audit.md` (the first pass), `eff-issue-12-audit.md` (17
entries, all pinned in the "eff issue 12 pins" and "reading audit pins" groups),
`todo-analysis.md` (the fifteen notes). The backlog `backlog.md` closes with "Pending work,
consolidated".
