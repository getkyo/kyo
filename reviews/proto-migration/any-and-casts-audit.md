# `Any` and casts in the kernel: full audit

Subject: `kyo-kernel/shared/src/main/scala`, `kyo-kernel/jvm-native/src/main/scala`,
`kyo-kernel/js-wasm/src/main/scala`, `kyo-kernel/jvm/src/main/scala`, as the tree stands at
worktree `effervescent-painting-backus` with the LoopHandler/LoopStateHandler split uncommitted.

Method: `grep` enumeration of every `Any`, `asInstanceOf`, `@unchecked` and `null` in those roots,
then each site read in context. Rubric: the closed set of acceptable cast categories and the
preference ladder in `kyo-kernel/.claude/skills/kernel/SKILL.md` ("Type safety: the representation
and its contract", "The cast discipline"), plus `kyo-kernel/CONTRIBUTING.md` "Cast discipline".

Nothing here was compiled. Every claim marked "needs a compile" is a variance or inference
argument that a build has to confirm; the skill's own rule is that a removal is verified by the
compiler or a test, never by reasoning alone, and it records a removal that compiled and was still
wrong.

## 1. Counts per file

Counts exclude comment-only lines. `Any` counts every occurrence of the type name in code,
including `Any < Any`, `Arrow[Any, Any, Any]`, `[?]`-free type arguments and `Any`-typed
parameters. `@unchecked` counts type patterns.

| File | Lines | `Any` | casts | `@unchecked` | `null` |
|---|---:|---:|---:|---:|---:|
| `kyo/kernel/internal/Eval.scala` | 568 | 48 | 39 | 28 | 0 |
| `kyo/kernel/internal/Handler.scala` | 360 | 44 | 26 | 14 | 2 |
| `kyo/kernel/Loop.scala` | 799 | 37 | 36 | 25 | 0 |
| `kyo/kernel/internal/Debugger.scala` | 59 | 42 | 0 | 0 | 0 |
| `kyo/kernel/debug/ConsoleDebugger.scala` (jvm) | 150 | 23 | 0 | 0 | 0 |
| `kyo/kernel/Isolate.scala` | 407 | 18 | 6 | 3 | 0 |
| `kyo/kernel/debug/DebugSession.scala` (jvm) | 40 | 16 | 0 | 0 | 0 |
| `kyo/kernel/internal/Stack.scala` | 286 | 11 | 9 | 0 | 15 |
| `kyo/Kyo.scala` | 2778 | 7 | 0 | 0 | 0 |
| `kyo/kernel/internal/Implicits.scala` | 75 | 6 | 2 | 0 | 0 |
| `kyo/kernel/internal/PendingInternal.scala` | 138 | 4 | 1 | 2 | 0 |
| `kyo/kernel/Arrow.scala` | 147 | 3 | 4 | 3 | 0 |
| `kyo/kernel/internal/CanLift.scala` | 94 | 3 | 1 | 0 | 4 |
| `kyo/kernel/internal/Context.scala` | 46 | 2 | 1 | 0 | 0 |
| `kyo/kernel/ContextEffect.scala` | 307 | 2 | 0 | 2 | 0 |
| `kyo/kernel/Pending.scala` | 425 | 2 | 1 | 2 | 0 |
| `kyo/kernel/ArrowEffect.scala` | 904 | 1 | 2 | 6 | 0 |
| `kyo/kernel/Effect.scala` | 133 | 1 | 5 | 2 | 0 |
| `kyo/kernel/internal/Nested.scala` | 22 | 1 | 3 | 1 | 0 |
| `kyo/kernel/internal/EffectTrace.scala` | 253 | 1 | 0 | 0 | 3 |
| `kyo/kernel/internal/package.scala` | 29 | 1 | 0 | 0 | 0 |
| `kyo/kernel/internal/Safepoint.scala` (jvm-native) | 219 | 0 | 0 | 0 | 5 |
| `kyo/kernel/internal/Safepoint.scala` (js-wasm) | 114 | 0 | 0 | 0 | 1 |
| `kyo/kernel.scala`, `kyo/Closed.scala`, both `Report.scala` | 79 | 0 | 0 | 0 | 0 |
| **total** | | **273** | **136** | **88** | **30** |

Three files hold 74% of the casts and 47% of the `Any`: `Eval.scala`, `Handler.scala`,
`Loop.scala`. Two of the three are mid-edit.

## 2. Where the edit is visibly mid-flight

`git status` shows `ArrowEffect.scala`, `Loop.scala`, `Eval.scala`, `Handler.scala` and
`internal/StackTest.scala` dirty. What the working copy does that `HEAD` does not:

- `Handler.LoopHandler` was split in two: a stateless `LoopHandler` answering `Outcome` and a
  `LoopStateHandler` answering `Outcome2` (`Handler.scala:59`, `Handler.scala:123`). `answersLoop`
  is the new copy of `answersLoopState` (`Handler.scala:204` and `Handler.scala:280`).
- The retyping the brief names is real and it moved the erasure inward, it did not delete it.
  `HEAD` had `k0: Arrow[Any, Any, Any]` and `Outcome2[State, Any, B < S] < S` in the `answers` and
  `answersLoopState` signatures; the working copy has `k0: Arrow[O[X], A, E & S]` and
  `Outcome2[State, A < (E & S), B < S] < S`. That removed 3 casts from `Eval` (the old
  `k.asInstanceOf[Arrow[Any, Any, Any]]`, `reentry` and `reentry2` re-typings) and 2 `Any`s from
  each of the four `ArrowEffect` wiring sites. It added 9 casts inside `answersLoop` and 8 inside
  `answersLoopState` (`.asInstanceOf[A < (E & S)]` on every value handed back), because the loop's
  own `k` stays `Arrow[Any, Any, Any]`.
- Net for the retyping so far: signature-level `Any` down, body-level cast count up. That is the
  right direction under the ladder (the erasure is now stated once, at the walk's two `var`s, with
  a comment, instead of leaking into three public-ish signatures), but it should be recorded as a
  trade rather than as a reduction.
- `Loop.unnest` was widened from `unnest[A, B, O](v: Outcome2[A, B, O]): O` to a five-parameter
  union over all four `Outcome` arities (`Loop.scala:146`). Four of the five parameters are
  phantom: the body only reads `O`. See M5.
- Unfinished edges visible in the tree: `Eval.scala:73` and `Eval.scala:96` carry `TODO` comments
  questioning the duplicated `atTop` arms, `Handler.scala:48`, `Handler.scala:62` and
  `Handler.scala:203` carry `TODO`s asking whether these methods belong in `Eval`.
  `Eval.scala:567-569` declares `IY`, `OY`, `EY` which nothing references.

## 3. Findings grouped by mechanism

### M1. The entry stack's erased lanes (29 casts, the largest single group)

`Stack` keeps four parallel lanes and hands each back at its erased type:
`handler(i): Handler[?, ?, ?]`, `state(i): Any`, `continuation(i): Arrow[?, ?, ?]`
(`Stack.scala:152-156`), and `Stack.Snapshot` packs the same four into one `Span[AnyRef]` read
back through casts (`Stack.scala:249-257`). Every consumer re-types on read.

Sites:

- state lane: `Eval.scala:160`, `:183`, `:264`, `:303`, `:325`, `:332`, `:342`, `:361`, `:386` (9).
- continuation lane: `Eval.scala:105`, `:115`, `:137`, `:153`, `:167`, `:177`, `:200`, `:217`,
  `:257`, `:319`, `:400` (11).
- handler lane: `Eval.scala:263`, `:331`, `:384`, `:481`, `:549` (5).
- the packing itself: `Stack.scala:118`, `:142`, `:185`, `:237` (writing `Any` into `Array[AnyRef]`),
  `Stack.scala:252`, `:254`, `:255` (reading the packed span) (7).

The erased-arrow currency `Arrow[Any, Any, Any]` at `Eval.scala:246` and `Eval.scala:297` belongs
to the same group: the resume arrow's input type is the previous region's output, which no
signature can name.

Reason it is there: a stack entry's `State`, its handler's `B`, and its continuation's input are
three names for one type that varies per entry, and the lanes are arrays.

Category: erasure-forced, the exact case the skill names ("array element re-typing at the storage
boundary (`Stack`)").

Can it be removed: only by changing the representation. The one path that actually removes them is
to make `State` a type member of `Handler` rather than a class type parameter, and to store one
entry object per region holding `handler`, `state` and `continuation` at a single skolem, so
`entry.handler.done(entry.state, ...)` type-checks by existential opening with no cast. That is a
public-shape change to `Handler` (a `private[kernel]` type, so not user-facing) and it costs one
allocation per region push where the parallel arrays cost none, plus an extra indirection on every
lane read inside `Eval.loop`. `Eval.loop` is the method whose size CONTRIBUTING calls a performance
contract, and `Stack`'s lane design is itself a recorded concession. This must be measured before
it is proposed, not argued.

A cheaper half-step that does not remove the casts but concentrates them: give `Handler` erased
entry points (`doneErased(state: Any, v: Any)`, `recoverErased`, and on `ContextHandler`
`releaseErased`, `reenterErased`, `doneErased`) whose one-line bodies carry the cast. That is the
idiom CONTRIBUTING already points at (`Debugger.scala:47-49`, "make the hook generic and push the
burden onto the implementor"). It deletes the 5 handler-lane casts outright and turns 9 state-lane
casts into 5 one-line implementations, at the price of one virtual call on the region-exit and
recovery arms of the eval. Also needs measurement.

### M2. The eval's phantom type members, and three dead ones

`Eval.scala:560-569` declares `IX`, `OX`, `EX`, `VX`, `CX`, `AX`, `Y`, `IY`, `OY`, `EY` as abstract
type members used as existential stand-ins in `@unchecked` patterns and cast targets.

This is the good spelling and should be called out as such: it is what keeps `Any` out of 28
pattern positions in the interpreter that would otherwise read `Handler.LoopHandler[Any, Any, ...]`.
No change wanted.

Defect: `IY`, `OY` and `EY` (`Eval.scala:567-569`) have no reference anywhere in the module. Dead
declarations. Delete.

### M3. The park payload's row, spelled `Any < Any` where `Any < Nothing` conforms (4 casts, removable)

`Pending.Park.value` is declared `Any < Any` (`PendingInternal.scala:113`). Because `<[+A, -S]` is
contravariant in `S`, `X < S` is **not** a subtype of `Any < Any`, so every construction site
casts:

- `PendingInternal.scala:57`  `Effect.defer(v, kc, resume).asInstanceOf[Any < Any]`
- `Isolate.scala:262`  `Pending.Park[...](inner.asInstanceOf[Any < Any], forked)`
- `Eval.scala:280`  `v.asInstanceOf[Any < Any]`
- `Eval.scala:281`  `Effect.defer(v, contA, contB).asInstanceOf[Any < Any]`

`Any < Nothing` is the supertype of every `X < S`: covariant `A` gives `X <: Any`, contravariant
`S` gives `Nothing <: S`. All four casts then go by plain subsumption, which is ladder step 1
(variance and ascription), the step the skill says two casts in this codebase's history already
turned out to be.

`Any < Nothing` is also the currency CONTRIBUTING already names for the evaluator ("whose currency
is `Any < Nothing`"), so the change makes the code match its own documentation.

Change: `PendingInternal.scala:113` `val value: Any < Nothing`, and the local at `Eval.scala:279`
`val parked: Any < Nothing`. `Park` is `private[kyo]` and constructed only inside the kernel
(checked: no `Pending.Park` reference in `kyo-core` or `kyo-prelude` sources), so no public
signature changes.

Residual: `Eval.scala:243` `kyo.value.asInstanceOf[T < S2]` and `Eval.scala:284`
`parked.asInstanceOf[A < S]` stay either way; those narrow `A` from `Any`, which is erasure-forced.

Needs a compile. The variance argument is mechanical, but `Park[+A, -S]`'s field is in a covariant
position and the type mentions neither parameter, so the variance checker should be satisfied.

### M4. The answer-fusion walk (18 casts, 2 `null`s; 16 must stay, 2 removable)

`Handler.answersLoop` (`Handler.scala:204-278`) and `Handler.answersLoopState`
(`Handler.scala:280-358`) walk consecutive same-tag suspensions, fusing them. The walk's two
carriers are erased on purpose: `var in: Any` (`:215`, `:293`) and `var k: Arrow[Any, Any, Any]`
(`:216`, `:294`).

Sites: `Handler.scala:216`, `:221`, `:226`, `:231`, `:238`, `:245`, `:246`, `:259`, `:262`, `:268`
and the mirror set `:294`, `:299`, `:305`, `:310`, `:317`, `:324`, `:325`, `:338`, `:341`, `:347`.

Reason: the walk crosses operations of different `I`/`O` instantiations, so no single `I[C]` names
the input in flight, and `k`'s input type changes on every fusion step. The invariant that makes it
sound is that `k`'s **output** is always the region's `A`, which is what the eight
`.asInstanceOf[A < (E & S)]` re-assert.

Category: erasure-forced, with an existential-instantiation liberty at `in.asInstanceOf[I[C]]`
(the next operation's input is fed to a clause instantiated at the first operation's `C`; sound
because the clause is polymorphic, `[X] => I[X] => ...`).

Can they be removed: no. The comment added by the in-flight edit ("The walk fuses across operations
of different types, so the input and the continuation in flight are erased; the answer handed back
is the region's A") is the right justification and should stay.

Removable within this group: `Handler.scala:217` and `:295`,
`var result = null.asInstanceOf[Outcome[...]]`. That is not a member of the closed set at all; it
is an uninitialized-local idiom, and at a primitive `O` it silently means `0`. The cast-free
spelling is to lift the `while` body into a `@tailrec` local def that returns the result, dropping
both the `var result` and the `var running`. Both methods are `inline` and expand at every region
call site, and `ArrowEffectBytecodeTest` pins that expansion, so the rewrite needs a bytecode
measurement before it is proposed.

### M5. `Loop.unnest`'s widened signature, and two gratuitous `Any` cast targets (removable)

`Loop.scala:146`:

```scala
private[kyo] def unnest[A, B, C, D, O](v: Outcome[A, O] | Outcome2[A, B, O] | Outcome3[A, B, C, O] | Outcome4[A, B, C, D, O]): O
```

Four of five parameters are phantom; the body reads only `O`. The union exists so one helper serves
both handler kinds after the split. Six callers: `Eval.scala:113`, `:148`, `:175`, `:211`,
`Handler.scala:91`, `:155`.

Two of those six spell the cast target as `Any` where the real type is in scope and is spelled out
at the sibling site:

- `Eval.scala:113` `done.asInstanceOf[Outcome[Any, Y < S2]]` versus `Eval.scala:148`
  `outcome.asInstanceOf[Outcome[OX[VX] < (EX & S2), Y < S2]]`
- `Eval.scala:175` `done.asInstanceOf[Outcome2[VX, Any, Y < S2]]` versus `Eval.scala:211`
  `outcome.asInstanceOf[Outcome2[VX, OX[VX] < (EX & S2), Y < S2]]`

The casts themselves stay (they also strip the `< S2` wrapper on the settled arm, which is a
representation assertion), but the `Any` in them is not forced by anything: spelling the same type
as the sibling removes two `Any`s and makes four arms read identically.

Better still, split `unnest` back into two tightly typed helpers, one per `Outcome` arity actually
used by a handler, which removes the five-parameter union and its four phantoms from a
`private[kyo]` signature. `Outcome3` and `Outcome4` have no handler and never reach `unnest`; they
are unwrapped inline in `Loop`'s own drivers.

### M6. Casts standing in for the implicit lift (13 casts; 12 stay, 1 removable)

Kernel files may not summon the lift macro (the stale-symbol cascade), so a raw value entering the
union is cast instead.

Must stay, category "macro-emitted under analysis" / lift substitution:

- `Nested.scala:19`, `:20` (`nest`'s two arms). The skill records that the cast-free spelling here
  is an infinite recursion, because `object Nested` is not a transparent scope for the alias.
- `Implicits.scala:33` (`lift`'s primitive arm; `Int`/`String`/... admit no `Boxed` subtype, decided
  at compile time).
- `Loop.scala:164`, `:176`, `:193`, `:214`, `:239` (`continue` constructors). Sound because
  `Continue` extends nothing but `Serializable`, so the lift's boxing arm is provably unreachable.
- `Loop.scala:246`, `:256`, `:267`, `:278`, `:289` (`done`). The `Continue`-valued arm wraps in
  `Done` and casts; the other arm already avoids the cast by calling `Nested.nest`, which is the
  correct asymmetry and worth keeping visible.

Removable: `Implicits.scala:35`, `Nested.nest(v).asInstanceOf[A < S]`. `Nested.nest[A, S]` already
returns `A < S`; with the type arguments ascribed the cast is a no-op the compiler can drop. Left
un-ascribed, `S` infers to `Nothing` and the result still conforms by contravariance. Ladder step 1.
Needs a compile: the risk the skill names is exactly that a cast removal at a lift site compiles and
corrupts, so this one must be checked against the nesting tests, not just against `compile`.

Documentation gap in this group: the skill and CONTRIBUTING both state that a cast standing in for
the lift must say why the value cannot be `Boxed`. In this tree none of the eleven `Loop` sites
carries that sentence, and neither does `Nested`. CONTRIBUTING's anchors for those sentences
(`Loop.scala:141-144`, `Loop.scala:227-229`, `Nested.scala:24-26`) now point at unrelated lines.

### M7. Reference-identity knowledge about `Arrow.Id` (9 casts, all stay)

`Arrow.scala:24`, `:26` (`chain`'s two short circuits), `Arrow.scala:42` (`Id.apply`'s
`cont` short circuit), `Arrow.scala:49` (`id[A]` sharing one `Id[Any]` singleton),
`Effect.scala:87`, `:89`, `:100`, `:102`, `:104` (the `defer` overloads collapsing an `Id` link).

Reason: `this eq Identity` implies the arrow's input and output types coincide. `Arrow[-A, +B, -S]`
is variant, so a type pattern `case _: Arrow.Id[a]` yields bounds, not the equality, and GADT
reasoning does not produce it. `Arrow.id[A]` is the same shape as `Nil`/`None` without the
covariance that lets those share an instance for free; `Id[A] <: Step[A, A, Any]` puts `A` in both
positions, so `Id` cannot be made variant.

Category: reference-identity knowledge, named verbatim in the skill's closed set. All nine stay.
Written as `isInstanceOf` guards rather than type patterns, which is deliberate here: the typed
pattern would need the same cast in its body and would cost a bind.

### M8. Union-arm discrimination (88 `@unchecked` patterns, all stay)

`case kyo: Pending[A, S2] @unchecked => Effect.defer(...)` and its variants at 88 sites across
`Pending.scala`, `Arrow.scala`, `ArrowEffect.scala`, `ContextEffect.scala`, `Effect.scala`,
`Loop.scala`, `Handler.scala`, `Eval.scala`, `Isolate.scala`, `PendingInternal.scala`.

These are not defects. They are ladder step 2, the spelling the ladder prefers over a cast: the
runtime class test is real, only the type arguments are unchecked, and the claim is visible at the
site. CONTRIBUTING requires this exact spelling from any new method over the union. The correct
finding is that the module is consistent here.

Two of them do more than discriminate and deserve reading as casts:

- `ArrowEffect.scala:272` `case first: FirstSuspended[I, O, E, A, E & S] @unchecked` together with
  `ArrowEffect.scala:268` `cont0.asInstanceOf[Arrow[O[C0], A, E & S]]`. The cast narrows the
  continuation's output from `A | FirstSuspended[...]` back to `A`. Sound because only the clause
  ever produces a `FirstSuspended` and the continuation handed out is applied outside the region.
  Category: existential/instantiation assertion. Stays, and needs the one-line justification
  CONTRIBUTING requires for a cast that asserts an existential instantiation.
- `ArrowEffect.scala:275` `done(a.asInstanceOf[A])`, narrowing the other arm of the same union.
  Scala cannot exclude an erased class arm from a union by test. Erasure-forced. Stays.

### M9. `Isolate`'s invariant `Remove` (3 casts, all stay)

`Isolate.scala:401` `Contextual.asInstanceOf[Isolate[Remove, Keep, Restore]]`, `:402`
`$next.asInstanceOf[Isolate[Remove, Keep, Restore]]`, `:299`
`entries.handler(i).asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]`.

`Isolate[Remove, -Keep, -Restore]` (`Isolate.scala:80`): `Keep` and `Restore` are contravariant, so
`Contextual extends Isolate[Any, Any, Any]` already conforms in those two positions with no cast.
`Remove` cannot be given a variance: it is contravariant in `capture`'s result
(`A < (Remove & S)`, a contravariant slot of `<` in a covariant position) and covariant in
`isolate`'s parameter (`v: A < (S & Remove)`, a contravariant slot in a contravariant position).
So `Isolate[Any, ...]` genuinely does not conform to `Isolate[Remove, ...]` and the two macro-emitted
casts are forced by the abstraction's shape, not by an accident of spelling.

`Isolate.scala:299` is the `Stack.Snapshot` storage boundary again (M1) reached from the isolate
side.

`Isolate.scala:325` `joined.asInstanceOf[AnyRef] ne parent.asInstanceOf[AnyRef]` is M11.

### M10. `Debugger`'s `Any`-typed seam (42 `Any` in `Debugger.scala`, 39 in the jvm debug package)

`Debugger.scala:9-29` declares eleven hooks taking `Any`, and `:46-56` mirrors them as inline
forwarders. `ConsoleDebugger` and `Counting` override them at `Any`.

This is the deliberate shape and CONTRIBUTING names it as the idiom to follow: the hooks take `Any`
precisely so the evaluator never casts to call them, the default bodies are empty, and with no
subclass on the classpath class-hierarchy analysis devirtualizes the calls to nothing. The
alternative (typed hooks) would push casts into `Eval.loop`, which is the wrong direction.

No change. Worth stating in the report so this block of 81 `Any` occurrences is not read as debt:
it is 30% of the module's `Any` count and none of it is a defect.

### M11. `Any` reaching an identity comparison or an `AnyRef` array (6 casts)

- `Stack.scala:83` `indexed(j).asInstanceOf[AnyRef] eq snapshot.asInstanceOf[AnyRef]` (2 casts).
  `Snapshot` is `opaque type Snapshot = Span[AnyRef]` and `Span[+A] = Array[? <: A]`, so it is not
  statically an `AnyRef` outside `Span`'s scope and `eq` does not apply. Removable only by `Span`
  exposing an identity comparison, which is a `kyo-data` change.
- `Isolate.scala:325` (2 casts), identity comparison of two `Any`-typed handler states to skip a
  redundant `setState`. Forced by the `Any` state lane (M1).
- `Eval.scala:499`, `:511`, `:536` (3 casts), pushing `Any`-typed states into an
  `ArrayBuffer[AnyRef]`, and `Eval.scala:549` reading one back as a `ContextHandler`. Same lane.
- `Stack.scala:118`, `:142`, `:185`, `:237` (4 casts), writing the `Any` state lane into
  `Array[AnyRef]`.

The eight `Any`-to-`AnyRef` casts are static only (both erase to `Object`), so they cost nothing at
runtime; they are noise rather than risk. Declaring the states lane `Array[AnyRef]` collapses the
four write-side casts into one in `push`/`setState`, but does not remove them. Not worth a change on
its own; it falls out of M1 if M1 is done.

### M12. Dead `Stack.sink` (1 `Any`, 1 `null`, 2 writes on the hot arms) (removable)

`Stack.scala:20` `var sink: Any = null`, cleared at `Stack.scala:107`, written at `Eval.scala:122`
and `Eval.scala:184` (`stack.sink = outcome0`), read nowhere in `kyo-kernel` main or test sources.

It is a write-only field that pins a strong reference to the last clause outcome on a pooled
`Stack` until `clear()`. Delete the field and both writes. This also removes two stores from the two
non-`atTop` loop-handler arms of the interpreter.

### M13. `Pending.eval`'s cast (1 cast, removable)

`Pending.scala:405`:

```scala
case kyo: Pending[?, ?] => Nested.unnest[A](Eval(kyo.asInstanceOf[A < Any]))
```

The typed spelling is `case kyo: Pending[A, Any] @unchecked => Nested.unnest[A](Eval(kyo))`: the
runtime test is identical, the type claim becomes visible in the pattern instead of hidden in a
cast, and inside `object <` the alias is transparent so `Pending[A, Any]` conforms to `A < Any`
directly. Ladder step 2. Needs a compile; `eval` is `inline`, so the expansion has to be checked as
well as the definition.

### M14. Erasure at the union's read edge (`Nested.unnest`) (3 casts, all stay)

`Nested.scala:11-14`. `unnest[A](v: Any): A` takes `Any` on purpose: typed `A`, the implicit lift
would be in scope at every call site, and lifting an already union-represented value corrupts it.
The two casts in the body are `O[A]`-style erasure.

This is the single most load-bearing `Any` in the module, and in this tree it is undocumented.
CONTRIBUTING states the rationale and anchors it at `Nested.scala:24-26`; the file is 22 lines long
and carries no comment at all. A reader who "cleans up" that `Any` to `A` breaks the representation
invariant silently. Add the sentence back.

### M15. `Loop`'s driver casts (25 casts, all stay)

`Loop.scala:324`, `:331`, `:333`, `:371`, `:378`, `:380`, `:420`, `:427`, `:429`, `:471`, `:478`,
`:480`, `:509`, `:511`, `:539`, `:541`, `:571`, `:573`, `:605`, `:607`, `:641`, `:643`, `:675`,
`:682`, `:683`.

Three shapes, repeated once per arity:

- `res.value.asInstanceOf[O < S]` on the `Done` arm. `Done`'s payload is erased, and a `Continue`
  cannot be `Boxed`, so the cast doubles as the lift substitution. Erasure-forced.
- `res.asInstanceOf[O < S]` on the settled arm. This one is a **representation assertion** in the
  skill's sense and is load-bearing: the value arrived through `Loop.done`, which already nested it,
  so the cast is what blocks the conversion from nesting it a second time. Replacing it with
  `Nested.unnest` (the reflex a reviewer will have) strips a level that must not be stripped.
- `v.asInstanceOf[Outcome[A, O] < S]` inside each `Step.apply`, changing the row from the arrow's
  `S2` to the driver's `S` on an arm known to hold a settled value. Representation assertion on the
  row.

All stay. The repetition across five arities is the recorded `*With`-overload concession shape
(copies kept textually parallel), and they are parallel here.

### M16. Casts that are the eval's own boundary (5 casts, all stay)

`Eval.scala:243`, `:254`, `:261`, `:284`, and `Handler.scala:113`, `:180`. Narrowing the eval's
erased currency back to the caller's `A < S`, or the settled non-`Continue` arm back to the
region's `Outcome`. Erasure-forced at the point where a `@tailrec` loop over a heterogeneous ADT has
to hand a typed result back. Irreducible without typing the interpreter itself, which is the
representation the module is built on.

### M17. Remaining single sites

- `Context.scala:27` `Maybe(b.value.asInstanceOf[A])`. The context binding stores `Tag[Any]` and
  `Any` (`Context.scala:43`) and re-types on read after a `<:<` tag test. Erasure-forced, and the
  tag test is what makes it sound. Stays. `bind`'s typed signature (`Context.scala:20`) is what
  makes the pairing sound and should not be weakened to `Any` to save the cast at
  `Eval.scala:361`.
- `CanLift.scala:59` `'{ null.asInstanceOf[CanLift[A]] }`. `opaque type CanLift[A] = Null`, so the
  instance is `null` by construction and the cast is the macro-emitted spelling of it. The four
  `null`s at `CanLift.scala:69`, `:71`, `:73`, `:75` are the same evidence encoding. Zero-cost.
  Stays; this is "macro-emitted under analysis".
- `Effect.scala:112` `private val unitValue: Unit < Any = ()`. `Unit` admits no `Boxed` subtype, so
  the value crosses into the union unwrapped with no cast at all. This is the model the other
  lift-substitution sites should be compared against.
- `Safepoint.scala` (both platforms): zero `Any`, zero casts. The six `null`s are the
  `AtomicReferenceArray` slot table's empty entry, the `ThreadLocal[Integer]` cache, and the
  js-wasm no-op `beginSlice`. All at a JDK storage boundary, all guarded by `eq null` tests.
  Nothing to do.
- `EffectTrace.scala`: zero casts, one `Any` (`pushValue(v: Any)` at `:156`, which walks a union
  value by class). The three `null`s are `Exception(null, null, false, false)` and a
  `StackTraceElement` with no source file. Nothing to do.
- `Kyo.scala`: seven `Any`, all of them `f: A => Any < S` in `foreachDiscard` signatures
  (`:251`, `:550`, `:1014`, `:1331`, `:1863`, `:2347`) plus `unit: Unit < Any` (`:47`). `Any` as
  "result discarded" is the right type there. Zero casts in 2778 lines.

## 4. The removable set, in order

Each step is independent of the ones after it except where noted.

1. **Delete dead declarations.** `Eval.scala:567-569` (`IY`, `OY`, `EY`), `Stack.scala:20` and the
   `sink = null` at `:107`, plus the two writes at `Eval.scala:122` and `:184`.
   Removes: 1 `Any`, 1 `null`, 2 stores on interpreter arms. No type change, no measurement needed.
2. **`Park.value: Any < Nothing`** (`PendingInternal.scala:113`) and the matching local at
   `Eval.scala:279`. Removes 4 casts (`PendingInternal.scala:57`, `Isolate.scala:262`,
   `Eval.scala:280`, `Eval.scala:281`) by subsumption, and makes the code agree with the currency
   CONTRIBUTING already documents. Needs a compile.
3. **Ascribe `Nested.nest[A, S](v)` in the lift** (`Implicits.scala:35`). Removes 1 cast. Needs a
   compile *and* the nesting tests, because this is the exact site class where a removal has
   compiled and been wrong before.
4. **Typed pattern in `Pending.eval`** (`Pending.scala:405`). Removes 1 cast. Needs a compile of the
   inline expansion, not just the definition.
5. **Spell the known `Outcome` type at `Eval.scala:113` and `:175`** instead of `Any`, matching
   `:148` and `:211`. Removes 2 `Any`s, no cast count change, four arms become identical.
6. **Retighten `Loop.unnest`** (`Loop.scala:146`) into per-arity helpers. Removes a five-parameter
   union with four phantom parameters from a `private[kyo]` signature. Depends on nothing; do it
   with step 5 since the callers are the same six lines.
7. **Replace the two `null.asInstanceOf` result initializers** in `answersLoop` and
   `answersLoopState` (`Handler.scala:217`, `:295`) with a `@tailrec` local def returning the
   result. Removes 2 `null`s and 2 casts. Gated on a bytecode measurement, because both methods are
   `inline` and `ArrowEffectBytecodeTest` pins their expansion.
8. **Erased hooks on `Handler` and `ContextHandler`** (`doneErased`, `recoverErased`,
   `releaseErased`, `reenterErased`). Removes the 5 handler-lane casts outright and concentrates 9
   state-lane casts into 5 one-line bodies. Gated on measuring the added virtual call on the eval's
   region-exit and recovery arms.
9. **The entry-object redesign** (`Handler.State` as a type member, one entry value per region).
   The only path that removes M1's remaining ~20 casts. Gated on measuring one allocation per region
   push against the current zero-allocation parallel lanes, and on the effect on `Eval.loop`'s
   method size.

Steps 1 through 6 remove 6 casts, 3 `Any`s and 1 `null` with no performance question attached.
Steps 7 through 9 are where the volume is, and none of them is a cast-count argument: each is a
measurement.

## 5. The set that must stay

| Sites | Count | Category | Why the type system cannot carry it |
|---|---:|---|---|
| M1 lanes, minus what steps 8 and 9 reach | ~20 | erasure-forced | A stack entry's `State`, its handler's `B` and its continuation's input are one type that varies per entry, and the lanes are arrays. |
| M4 fusion walk | 16 | erasure-forced + existential instantiation | The walk crosses operations of different `I`/`O`, so no single type names the input or the continuation in flight. |
| M6 lift substitutions | 12 | macro-emitted under analysis | Kernel files may not summon the lift macro; each value is provably not `Boxed`. |
| M7 `Arrow.Id` identity | 9 | reference-identity knowledge | `f eq Identity` implies the input and output types coincide; variance blocks GADT recovery, and `Id` cannot be made variant. |
| M15 `Loop` drivers | 25 | erasure-forced + representation assertion | The settled arm's value is already union-represented; the cast is what blocks a second nest. |
| M16 eval boundary | 6 | erasure-forced | The interpreter's currency has to be narrowed back to the caller's `A < S` somewhere. |
| M9 `Isolate` `Remove` | 2 | variance-forced | `Remove` occurs contravariantly in `capture`'s result and covariantly in `isolate`'s parameter, so no annotation is legal. |
| M11 identity and `AnyRef` | 6 | storage boundary | `Span` is opaque so `eq` does not apply; the `Any` lane meets `Array[AnyRef]`. Static-only, no runtime cost. |
| M8 `handleFirst` | 2 | existential instantiation | Only the clause produces a `FirstSuspended`; a union arm cannot be excluded by test at an erased `A`. |
| M14 `Nested` | 3 | erasure-forced | `unnest` must take `Any` or the lift comes into scope at every call site. |
| M17 singles | 2 | erasure-forced, macro-emitted | Tag-tested context read; `CanLift`'s `Null` evidence. |

Total staying after every step above: about 103 of the current 136.

## 6. Documentation drift found while auditing

Not casts, but they bear directly on whether the surviving casts stay defensible.

CONTRIBUTING's "Cast discipline" section anchors five rules at line ranges that no longer hold in
this tree, and in each case the justifying comment the rule points at is absent from the file:

- `Nested.scala:20-30` and `:24-26` (the precondition on `unnest`, and why it takes `Any`):
  `Nested.scala` is 22 lines and carries no comment.
- `Loop.scala:141-144` and `:227-229` (why a cast standing in for the lift is equivalent to it):
  those lines are now the `Done` class and a `done` overload; none of the eleven lift-substitution
  casts in `Loop` carries the sentence.
- `Stack.scala:248-250` (the erasure justification at the top of the walk that performs it): those
  lines are now the `Snapshot` extension, with no comment.
- `Isolate.scala:433-435` (the "erasure-forced" module idiom): the file is 407 lines.
- `ArrowEffect.scala:364-367` (a cast that widens or asserts an existential says which liberty it
  takes): that range is now inside the rewritten `handleLoop`; the two casts that actually take a
  liberty are at `:268` and `:275` and carry no such sentence.

CONTRIBUTING also describes files this tree does not have (`internal/Finalizer.scala`,
`KyoInternal`'s seven-shape `Kyo` ADT, `Eval.scala:740`), so it is written against the pre-proto
kernel throughout, not only in these five places. Whoever lands the proto owes it a pass; until
then the module's cast rules are stated in a document whose every anchor is wrong, which is the
condition under which a reviewer starts removing load-bearing casts.
