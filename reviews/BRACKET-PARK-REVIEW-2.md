# Review 2: Bracket, Park, and finalizers

Held-out review of `reviews/BRACKET-PARK-DESIGN.md` (second attempt). No source was changed, nothing was built,
nothing was committed. Every `file:line` below was opened during this review against the worktree
`/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus`.

**Tree state note.** The tree moved under this review. `git diff --stat` shows uncommitted narrowing of
`Kyo` (`KyoInternal.scala:12`, `:14`), `Handler` (`Handler.scala:9`, `:26`) and `Stack` (`Stack.scala:11`, `:172`)
to `private[kyo]`, on top of `fd472bc3b6`. `ArrowEffect.scala` is 295 lines, not the 297 the document's header
claims; `Eval.scala` (233), `Pending.scala` (316) match. Where a finding depends on visibility I say so and give the
evidence rather than asserting a compiler outcome I did not observe.

---

## 1. Verdict

**REWORK.**

The document is a large improvement on the first attempt and closes six of its seven blocking findings outright.
The minting rule is now sound: `Finalizer`'s constructor is confined, the walk cannot synthesise one, and the value
half of the obligation is genuinely carried by the node classes. What does not hold is the arrow half. Once a
`Finalizer` becomes a stack entry it is an `Arrow` like any other, and `Stack.dump(pos)`'s `wrap` guard
(`Stack.scala:114-121`) merges runs of entries into a single chain-valued slot. After one region fold or one
park-and-resume, a finalizer can sit inside an `Arrow.Chain` entry, at which point `dump()`'s new `Barrier` boundary
(`Stack.scala:129-132`) does not see it, the fold carries it out of the stack into a value the drive is about to
apply, and a throw from that application reaches a boundary that scans a stack the finalizer is no longer on. That
is prior finding B3 in a new location, and section 5, which is the section that would have caught it, asserts a
round-trip property that is false and pins it with a test (34) that passes while the property is broken. Separately,
the design answers "what does a park owe when the bracket has not been consumed" with "nothing", which is right when
`acquire` has not run and wrong when `acquire` has run and its trailing continuations are still on the stack; test 29
asserts that leak as correct behaviour and it contradicts `Effect.bracket`'s own scaladoc in section 3.6. Finally,
the design puts two `private[kernel]` symbols (`Finalize.unwind`, `Captured`) inside `Eval.apply`, which is `inline`,
against a rule this codebase states in a comment at `EffectTrace.scala:36-38` and has paid for twice
(`3b00818614`, `4913bc8ca2`).

**Is the subclass split real?** Half real. In value position it is real and load-bearing: `Kyo.Ensure` names the
obligation as a field the walk reads without inspecting `contB`, and `Kyo.Park` names it as `owed` so the
abandonment path never re-derives it from the fold. Both are exactly the split the owner asked for, and both close
prior findings. In arrow position it is cosmetic: a `Finalizer` on the stack is distinguished only by the `Barrier`
marker, `Barrier` is consulted at exactly one site (`Stack.scala:130`), and `Stack.dump`'s own folding can put the
finalizer where that site cannot see it. The class carries the obligation right up to the moment `Stack` flattens
it into a chain, and then it stops.

**Count of paths where exactly-once does not hold: two**, beyond the one the document itself declares. Both are
"release never runs", neither is "release runs twice". Named in findings 1 and 2.

---

## 2. The prior review's seven blocking findings

| # | prior finding | status | evidence |
|---|---|---|---|
| B1 | the abandonment walk mints a fresh `Finalizer` | **closed** | the walk has no minting arm (`Walk.drain`, section 3.4); the constructor is `private[internal]` and the only `new Finalizer` is in `Kyo.Bracket.apply` (section 3.1) |
| B2 | the walk cannot tell a settled acquire from a pending one and hands `release` the wrong value | **closed for the wrong-value half, relocated for the leak half** | nothing but `Finalizer.resource` ever reaches `release`, and it is set from `v.unsafeGet` on the settled arm only. But the design answers the underlying question ("what does an unconsumed `Bracket` owe") with "nothing" for both the pending and the settled-but-unconsumed case; see finding 2 |
| B3 | a finalizer folded into a captured continuation is unreachable from both recovery paths | **relocated, still open** | closed at four sites (`map` to `Effect.defer` at 3.5d/e, `Captured` at 3.5f/g, `Finalize.unwind(k, ex)` at 3.5i, `unwind` at 3.5f/h). Reopened by `Stack.dump`'s `wrap` guard; see finding 1 |
| B4 | `collect` never descends `Kyo.Defer.value` | **closed** | `Walk.drain`'s `Defer` arm is `push(k.contB); push(k.contA); pushValue(k.value)`, and `push` prepends into a LIFO worklist so the value drains first, which also fixes the ordering half of B4 |
| B5 | `sealed trait Barrier` cannot be implemented cross-file | **closed** | section 3.2 drops `sealed`, uses `private[kernel] trait Barrier`, and states the Scala 3 rule and the `Arrow.Transform` precedent (`Arrow.scala:71`, extended from `Handler.scala:9` and `ArrowEffect.scala:38`) |
| B6 | `finalizeResources` cannot join the existing inline extension block | **closed** | section 3.7 opens a second `extension [A, S](self: A < S)` block and cites `Pending.scala:19` plus the inline members at `:22`, `:51`, `:73`, `:95`, `:117`, `:289`, `:292`, `:298`, all of which I confirmed |
| B7 | `drive` returning `Any < Nothing` is a representation question, not a detail | **closed** | section 3.5j decides it: the drive returns the union, `apply` and `settle` unnest through `.unsafeGet`, `partial` returns the union uncast on that axis. That is the `1cf05637e8:20` shape the prior review asked for, and test 20 pins it with `A = Int < Any` |

---

## 3. New blocking findings

### F1. `Stack.dump(pos)`'s `wrap` guard merges a finalizer into a chain-valued entry, and `Barrier` then does not see it

**Claim.** After any `dump(pos)` fold whose result is later re-pushed, a `Finalizer` can cease to be a stack entry
and become part of an `Arrow.Chain` stored in one slot. `dump()`'s boundary tests the raw entry
(`Stack.scala:130`), an `Arrow.Chain` is not a `Barrier`, so the bounded fold crosses it, the finalizer leaves the
stack inside the value handed to `head(curr, tail)` (`Eval.scala:192-194`), and a throw from that call reaches
`drive`'s catch, which scans a stack the finalizer is no longer on. The release never runs. This is the exact hazard
section 3.2's own comment describes for `dump()` ("a finalizer inside that fold is reachable from nothing when the
throw reaches the drive's boundary"), reintroduced through the other `dump`.

**Evidence, mechanism.** `dump[A, B, S](pos)` is `dump(pos, true)` (`Stack.scala:100`). The fold runs deepest to
shallowest, `i` from `pos - 1` down to `0` (`Stack.scala:103`, `:123`). At each step:

- `Stack.scala:114-117`: `acc match case c: Arrow.Chain[?, ?, ?, ?] if wrap && !handlers && !(c.b eq Arrow.Id) =>
  new Arrow.Chain(c, Arrow.id)`.
- `Stack.scala:122`: `handlers || e.isInstanceOf[Handler[?, ?, ?, ?]]`, so the guard is live only until the first
  `Handler` is folded, that is, for the deepest handler-free run.

Re-pushing walks the right spine and stores each left element whole: `count` at `Stack.scala:47-51` and `fill` at
`:53-64`, where `fill`'s `put((head + i) & mask, c.a)` stores `c.a` in one slot even when `c.a` is itself a `Chain`.
A `Chain(chain, Arrow.id)` therefore round-trips as **one** entry, not as the entries it was built from.

**Evidence, worked case.** Take the shape section 4 row 1 depends on. `Effect.bracket(a)(r)(u).map(f).map(g)` at top
level. The drive reaches the settled arm with the `Bracket` popped, `Bracket.apply` builds the `Ensure`, and the
`Defer` arm (`Eval.scala:41-44`) pushes `contB = finalizer.chain(cont)` then `contA = use`. Stack, index 0 at the
top: `[use, finalizer, f, g]`. Now `dump(4)`:

- `i=3`, `e=g`: `acc` is `Arrow.Id`, `below = Arrow.id`, `link = g.chain(Arrow.id) = g` (`Arrow.scala:19-21`).
- `i=2`, `e=f`: `acc = g`, not a `Chain`, `link = Chain(f, g)`.
- `i=1`, `e=finalizer`: `acc = Chain(f, g)`, `wrap && !handlers && (g ne Arrow.Id)` all hold, so
  `below = Chain(Chain(f, g), Arrow.id)` and `link = Chain(finalizer, below)`.
- `i=0`, `e=use`: `acc` is a `Chain` with non-`Id` right half, guard fires again,
  `link = Chain(use, Chain(Chain(finalizer, ...), Arrow.id))`.

`count` on that gives 2 (`use`, then `Chain(finalizer, ...)`), and `fill` stores the second whole. The stack after
the round trip is `[use, Chain(finalizer, Chain(Chain(f, g), Arrow.id))]`. The finalizer is not an entry.

**Evidence, reachability.** Two independent routes, neither exotic:

1. **Park and resume.** `Eval.reify` (section 3.5b) is `stack.dump[Any, Any, Nothing](stack.size)`, the `pos`
   overload, `wrap = true`. Section 4's row "park between `acquire` and `release`, later resumed" claims "the resume
   pushes the fold back and `Stack.push` flattens it into entries, so the normal path applies". The normal path does
   apply, because a chain entry re-defers and re-pushes when popped (`Arrow.scala:91-92`). The throw path does not.
2. **A region fold, no park needed.** `Eval.scala:57` is `val k = stack.dump[OX[CX], AX, EX & S](pos)`, also
   `wrap = true`. The clause applies `k(o)`, which is `Chain.apply(v) = Effect.defer(v, a, b)`
   (`Arrow.scala:91-92`), the drive pushes `contB` and `contA`, and the same merged slot appears. So a bracket
   acquired inside a `handleCont` region reaches the unprotected shape on the ordinary path.

**Why it matters.** It is the throw path, the second row of section 4's table, and the row test 8 is written for.
Test 8 ("a throw inside `use` with a `dump()`-sized run of entries above the finalizer still releases") will pass,
because it does not put a fold-and-repush between the acquire and the throw. So the design ships with the hole
unpinned.

**What must change.** The fold must stop merging at a barrier, not only at a handler. The minimal form is one word
at `Stack.scala:122`: accumulate `handlers || e.isInstanceOf[Barrier]` rather than
`handlers || e.isInstanceOf[Handler[?, ?, ?, ?]]`. With `Handler extends Barrier` (section 3.3) that subsumes
today's behaviour, and re-running the worked case above gives `[use, finalizer, Chain(f, g)]`, with the finalizer
back as its own entry. It changes the round trip only in the direction of more entries, never fewer, so it cannot
merge a handler that survives today. Verify it against `StackTest`, and rewrite test 34 to assert entry identity for
a `Finalizer` sitting in a handler-free run of at least two entries below it, which is the case the current test 34
does not construct.

---

### F2. A park taken after `acquire` has run but before the `Bracket` is consumed owes nothing, and the design asserts that as correct

**Claim.** Section 4's opening sentence and `Effect.bracket`'s scaladoc (section 3.6) both state the guarantee as
"**If `acquire` completes, `release` runs exactly once, on every path**", naming the abandonment path explicitly.
That is false for the window between the user's acquire side effect running and `Bracket.apply` consuming the
settled value. In that window the `Bracket` is an ordinary stack entry, it owes nothing by construction, and
`Finalize.owed` collects nothing. `finalizeResources` on the resulting park releases nothing, and the resource is
open. Test 29 ("a park holding an unconsumed `Bracket` releases nothing") asserts this as the specification.

**Evidence.** The `Bracket` is pushed as `contA` by `Eval.scala:41-44` and is only replaced by an `Ensure` when the
settled arm pops it at `Eval.scala:191-194`. The poll the design adds is at the loop head (section 3.5a,
`if (stop ne null) && stop() then reify(stack, curr)`), so it fires on every turn strictly between those two
events. `Walk.drain`'s `Bracket` handling is the `Kyo.Defer` arm in value position and `case _ => ()` in arrow
position (section 3.4), so neither position produces anything.

**Evidence, the window is not one turn.** The design's framing treats an unconsumed `Bracket` as "acquired
nothing", which is right when `acquire` has not run. But `acquire` is an arbitrary `A < S`, and its own
continuations sit **above** the `Bracket` on the stack, so they run first. For
`Effect.bracket(Sync.defer(open()).map(validate))(close)(use)`, `open()` has run and the handle exists from the
moment the `Sync` node settles, and `validate` then occupies as many drive turns as it likes, possibly suspending on
effects a region answers. Every one of those turns is polled, and a park at any of them abandons an open resource
with `owed` empty. Prior review question 2 asked exactly this and was answered for only one of its two readings.

**Why it matters.** It is the same abandonment path the row-`Any` concession exists to serve, and the headline
guarantee is stated without the qualifier. A scheduler dropping a fiber is precisely the caller that will hit it.

**What must change.** Two parts, and the second is a question for the owner (section 6, Q1).

1. Restate the contract accurately wherever it appears (section 4's opening sentence, `Effect.bracket`'s scaladoc,
   the concession table): the guarantee begins when `Bracket.apply` consumes a settled acquire, not when the user's
   acquire side effect runs. Say plainly that composition inside `acquire` is outside the guarantee, and that the
   safe shape is a single indivisible acquire step with all composition moved into `use`.
2. Add the pinning test the current corpus lacks: park inside a composed `acquire` after the resource exists, then
   `finalizeResources`, and assert what the contract now says. Test 29 as written asserts the leak for the
   *pending* case, which is correct; it must be split so the settled-but-unconsumed case is a separate, named case
   rather than folded into the same sentence.

---

### F3. The design names two `private[kernel]` symbols inside `Eval.apply`, which is `inline`

**Claim.** `Finalize.unwind` and the `Captured` trait are both specified `private[kernel]` (sections 3.2 and 3.4)
and both are named from inside `Eval.apply`'s body, which is `inline` and re-typechecked at every expansion site,
including sites outside package `kyo`. Nothing currently reached from that body is narrower than `private[kyo]`.

**Evidence, the rule is stated in the source.** `EffectTrace.scala:36-38`:

> The class and its two entry points are public because `Eval.apply` is `inline`: its body is re-typechecked at
> every expansion site, including sites outside package `kyo`, so every symbol the drive names has to be reachable
> there. The carrier's mutable fields stay `private[kyo]`; no inline method touches them.

The same rule is recorded at `Pending.scala:24-29` and `ArrowEffect.scala:4-5`, and was paid for in `3b00818614`
("map and Eval did not compile from outside package kyo") and `4913bc8ca2` ("keep Arrow.Transform, Chain and Id
private; unqualify the inlined references").

**Evidence, the current inventory.** Every `private[kernel]` symbol in the module is at
`Effect.scala:9`, `Pending.scala:17`, `Stack.scala:87`, `Implicits.scala:8`, `CanLift.scala:44`. None of the five is
reached from `Eval.apply`'s body: `Stack.entry` is read only by `EffectTrace.Builder.entries`
(`EffectTrace.scala:248-257`), a non-inline private class, and `fromKyo` is used from non-inline `@static def`s in
`Effect.scala:26-39`. So the design introduces the first such reference, and six call sites of it:
`drive`'s catch, the two `Loop.done` arms (3.5h), the two suspended-clause `case v` arms (3.5f/g), and the
`handleCont` catch (3.5i).

**Why `Captured` is the harder half.** `Finalize.unwind` is a term and could in principle be widened. `Captured` is
a **type**, named in a `new ... with Captured` position inside the inline body. That is exactly the shape
`4913bc8ca2` had to work around for `Arrow.Transform`, and the workaround there (reach it through an import rather
than as a selection from `Arrow.type`) does not change the symbol's access, only its spelling. `Captured` sits at
`private[kernel]`, one level narrower than `Arrow.Transform`'s `private[kyo]`, which was itself already the problem.

**Also new, and lower risk but unverified.** `Eval.reify` is specified `private def` and is called from the inline
`drive`; `drive` is itself `private inline def` called from the inline `apply`. Today `Eval.apply` names no private
member of `Eval` except the `private inline given Frame` at `Eval.scala:33`, which expands to `Frame.internal` and
so is not a test of accessor generation. I did not compile this and do not assert it fails; I assert it is new and
untested.

**What must change.** Decide the visibility of `Barrier`, `Captured`, `Finalize` and its entry points explicitly, in
the design, against the `EffectTrace.scala:36-38` rule, and say what the check is. If the answer is that the drive
must not name them, the design needs a different shape for the region-discard and boundary unwind calls, for example
routing them through a symbol that is already public at the same visibility as `EffectTrace.attach`. This is a
compile-level blocker, not a style point: the module is mid-migration on exactly this axis right now, and the
uncommitted narrowing of `Kyo`, `Handler` and `Stack` to `private[kyo]` in the working tree means the design's
premises here need re-derivation before implementation, not after.

---

## 4. Non-blocking findings

**N1. Section 5's stated property is false.** "Whole-stack `dump(size)` followed by `push` restores the same entry
sequence" does not hold. A three-entry handler-free stack `[X, Y, Z]` folds to
`Chain(X, Chain(Chain(Y, Z), Arrow.id))` and re-pushes as two entries; the derivation is in F1. The section's own
argument identifies the right guard (`Stack.scala:116`, `:122`) and then reaches the wrong conclusion. The
consequence section 5 fears (`Stack.find` off by one, `Stack.handler(pos)` casting a non-handler) is in fact **not**
reachable, because the merged run is by construction the deepest handler-free run and indices above it do not
shift; so section 5 is wrong in both directions. Rewrite it around the property that actually matters, which is
barrier identity, and fold test 34 into F1's replacement.

**N2. Test 34 does not pin what section 5 names.** "a `Chain` entry both above and below a handler" is a case where
the guard is already inactive above the handler and where `Stack.scala:118-121` handles the deepest entry. It
passes while the property is broken.

**N3. `Effect.scala:75` is a wrong citation.** Section 3.6 says it replaces "the commented signature at `:75`".
`:75` is the closing `*/` of the `detach` scaladoc. The commented `bracket` signature is at `Effect.scala:82`. Note
also that the parked signature there reads `inline release: A => Unit`, so the design's `A => Unit < Any` is a
change against the in-tree parked signature as well as against `3a95636fa8`; section 3.6 justifies the change but
attributes it only to the older prior art.

**N4. Section 3.5a's "its expansion is unchanged" is false and section 8 says so.** 3.5a opens with "`Eval.apply`
stays `inline` and its expansion is unchanged", and the concession table repeats "`apply`'s expansion is
byte-unchanged". The expansion gains the loop-head poll, the `reify` branch and the `Finalize.unwind` call in the
catch, and section 8's first bullet makes every benchmark row mandatory for exactly that reason. The two claims can
be reconciled (the `driveTo` extraction alone does not change the expansion) but as written they read as
permission to skip the measurement. State it once, in the form section 8 uses.

**N5. `EffectTrace.splice` now runs in nested drives and in `partial`, and section 8 does not name it.** Today
`splice` is called only at `Eval.apply`'s boundary (`Eval.scala:209-215`). The design moves it into `drive`'s
catch, which `settle` and `partial` also expand. A release that throws inside `Finalizer.run` therefore splices in
the nested drive and splices again in the outer one. `splice` caches `carrier.physical` (`EffectTrace.scala:135-141`
region) so it is close to idempotent, but section 8 lists two user-visible behaviour changes and this is a third.
It also interacts with the TODO already sitting at `Eval.scala:211`, which asks whether the tracing mechanism
assumes enrichment happens only at the end.

**N6. `Effect.bracket`'s `node(acquire)` allocates for no stated reason.** `Kyo.Defer.value` is `A < S`
(`KyoInternal.scala:17`), so `Bracket.acquire` could be `A < S` directly and a settled acquire would settle on the
next turn with no wrapper. The design types it `Kyo[A, S]` and adds `node` to satisfy that typing, citing the
`Kyo.Handle.value` asymmetry (`ArrowEffect.scala:59-70`) as precedent. But `Handle.value: Kyo[A, E & S]` exists so
the drive can push a handler; there is no equivalent need here. One allocation per bracket with a settled acquire,
for a constraint the design imposed on itself.

**N7. Non-inline `Effect.bracket` evaluates `acquire` at the composition site.** The prior art and the in-tree
parked signature both take `inline acquire: A < S`, which substitutes the expression into a `def` and so evaluates
it at drive time. The design's by-value parameter evaluates it when `bracket` is called. Section 3.6 discusses the
inline question in terms of `Kyo.Bracket` nameability and the `Function1` indirection, and does not name this.

**N8. `Ensure.contB` is a `def`, so `finalizer.chain(cont)` allocates a fresh `Arrow.Chain` per read.** The drive
reads it once, but `EffectTrace.Builder.drain` reads `d.contB` at `EffectTrace.scala:279` and any future reader
will too. A `val` costs nothing here and removes the question.

**N9. `Walk`'s `Ensure` arm skips `contA` while the `Defer` arm pushes it.** Section 3.4's `Ensure` arm is
`push(k.cont); push(k.finalizer); pushValue(k.value)`, so `use` is never walked. That is almost certainly right (a
user arrow cannot hold a kernel `Finalizer`), but it is an asymmetry with the arm three lines below and it is not
commented, so the next reader has to re-derive it.

**N10. `finalizeResources` returns a value, and dropping that value is a silent leak.** `Finalize.compose` returns
`Unit < Any` and defers `runAll` into a node. Nothing in the design or the tests covers "the caller never drove
it". Section 2's argument for eager release ("no window in which the guard is spent and the work has not happened")
does not apply, because `compose` spends no guard, so the two are consistent; but if the entry point exists for a
scheduler that is dropping a continuation, a `Unit` return that runs the releases eagerly would remove a whole class
of caller error. Worth stating why the value form was chosen.

**N11. Section 2's row-`Any` sentence is loose.** "only a total release can run at the moment of the flip" is not
what row `Any` gives. A `Unit < Any` can still be a `Kyo.Defer` or `Kyo.Handle` chain that has to be driven, which
is exactly why `Finalizer.run` has the `case k: Kyo` arm and `Eval.settle` exists. What row `Any` actually
guarantees is that driving it cannot reach an unhandled suspension, so it can be settled with no handlers
installed. The concession table row states this correctly; section 2 does not.

**N12. Test 26 asserts a leak as correct behaviour and will fail the day it is fixed.** "a `handleCont` clause that
drops its continuation does not release. Named so it reads as the specification it is." A test that asserts the
absence of a release is a lock on the current limitation. If the intent is documentation, assert the observable
consequence (`use` ran, `release` did not) with a comment naming it as a known limitation rather than a property,
so a future fix reads as a test to update rather than a regression.

**N13. Test 13's break condition does not match the test.** "100k sequential brackets in one drive complete without
a stack overflow. *Breaks if:* `Bracket.apply` ever fuses instead of returning a node." Sequential brackets do not
nest, so the depth the test exercises comes from the drive's trampoline, not from `Bracket.apply`'s node-ness. The
shape that would catch fusion is 100k *nested* brackets, or a bracket in a `Loop`.

**N14. `Span.empty[Finalizer[?, ?]]` needs a `ClassTag` for a wildcard applied type.** `Span.empty` is
`def empty[A: ClassTag as ct]` (`kyo-data/shared/src/main/scala/kyo/Span.scala:47`). I expect synthesis to succeed
since the type erases to `Finalizer`, but I did not compile it and the design does not mention the constraint.

**N15. `Handler` is `private[kyo]` in the working tree and `Barrier` is `private[kernel]`.** Section 3.3 adds
`with Barrier` to `Handler.scala:9`. A class extending a strictly less visible parent is a shape I did not verify
compiles here. Cheap to settle, worth settling before it becomes a build failure attributed to something else.

**N16. `scan`'s pre-test.** `@tailrec def any(i: Int): Boolean = i < n && (interesting(stack.entry(i)) || any(i + 1))`
is tail-recursive only because `&&` and `||` desugar to `if`. It compiles, but the reader has to check; a `while`
loop matches the rest of `Finalize` and the three walkers the design cites as precedent.

---

## 5. Checked and correct, so this is not re-litigated

**Citations.** I reopened every `file:line` the document gives against the current worktree. All of the following
resolve and say what the document says they say: `KyoInternal.scala:16-22`, `:21`, `:34-40`, `:46-57`;
`Eval.scala:36-220`, `:41-44`, `:45`, `:46`, `:48-53`, `:57-64`, `:74`, `:75`, `:77-94`, `:86`, `:87`, `:93`,
`:100-101`, `:103-105`, `:117`, `:118`, `:120-139`, `:129`, `:130`, `:138`, `:146-147`, `:150-152`, `:160-201`,
`:162`, `:174`, `:183`, `:185`, `:191-199`, `:192-198`, `:194`, `:201`, `:208`, `:217`; `Stack.scala:23-31`,
`:33-45`, `:37-41`, `:53-64`, `:81-82`, `:87`, `:89-98`, `:100`, `:116`, `:120`, `:122`, `:128-133`, `:129-132`,
`:130`, `:135-143`, `:145-149`, `:172-199`, `:186`; `Arrow.scala:19-21`, `:37`, `:71`, `:78`, `:91-92`, `:98-119`,
`:130-134`; `Pending.scala:14`, `:17`, `:19`, `:22`, `:24-29`, `:30-37`, `:51`, `:73`, `:95`, `:117`, `:289`,
`:292-295`, `:298-301`; `Handler.scala:9`, `:29`; `ArrowEffect.scala:27`, `:38`, `:42`, `:59-70`;
`Effect.scala:20-24`, `:26-30`, `:32-39`; `CanLift.scala:30`, `:34`; `Implicits.scala:10-15`, `:21-24`;
`EffectTrace.scala:127`, `:161`, `:190`, `:248-256`, `:259-294`, `:281`, `:289-290`;
`EvalTest.scala:949-1024`, `:956-970`, `:972-981`, `:983-985`, `:987-997`, `:993`, `:999-1015`, `:1017-1023`,
`:1021`; `SafepointConcurrencyTest.scala:81-106`, `:92`. The only wrong citation I found is N3.

**The minting rule.** `Kyo.Bracket.apply`'s settled arm is the only `new Finalizer` in the change list, the
constructor is `private[internal]`, and no other specified code path can produce one. The walk collects and never
synthesises. I traced all four recovery entry points (`owed`, both `unwind` overloads, `compose`) and none of them
can mint. B1 is genuinely closed, not moved.

**The `Walk`'s ordering.** `push` prepends and `drain` removes the head, so the last item pushed drains first. Each
arm pushes in reverse run order and the collected order is innermost first: `scan` pushes `entry(n-1)` down to
`entry(0)` so `entry(0)` drains first; the `Defer` arm gives value, then `contA`, then `contB`; the `Park` arm
pushes `owed` descending then `value`, so the value's own finalizers precede `owed`. All three are correct.

**Termination of the walk.** Every self-referential slot the kernel mints is a continuation slot, so a `Bracket`
reached through `contA` and a `suspendWith` node reached through `cont` both arrive in arrow position and terminate
at `case _ => ()`. That is the same structural argument `EffectTrace.scala:158-160` and `:165-172` make, and it
holds here for the same reason. The worklist is unbounded and uncapped, which is right: a dropped finalizer is a
leak, not a truncated rendering.

**`Park.owed` cannot drift from `Park.cont`.** `reify` reads `Finalize.owed(stack)` through the non-destructive
`Stack.entry` (`Stack.scala:87`) before `dump` nulls the slots (`Stack.scala:112-113`), in that order, over the
same range. The walk's `Park` arm deliberately does not descend `cont`, so no double-collection inside one walk,
and duplicates would be harmless anyway because of the CAS.

**Nested brackets release innermost first.** The outer `Ensure` pushes its finalizer before the inner bracket is
reached, so the outer finalizer sits deeper and is popped last; `scan` drains index 0 upward, which is the same
order. Section 4's last row is right.

**Nested drives.** `Finalizer.run` entering `Eval.settle` borrows a distinct `Stack` from the thread-local pool
(`Stack.scala:177-183`, `:193-197`) and saves and restores the safepoint state, so the outer drive's stack and
finalizers are untouched. `save` then `arm` in that order preserves the caller's armed state
(`Safepoint.scala:122-135`).

**The eager-release claim, mechanically.** It holds, though not for the reason section 2 gives (N11). The path is:
`Arrow(f).apply(v)` calls `f(v)` directly (`Arrow.scala:37`), a pure release has already run and takes `case _`, and
a release built out of `Effect.defer` returns a node that `Eval.settle` drives to completion **inside** the winning
`run()` call. So the CAS and the work are in the same call and there is no half-spent state, which is what the
section is trying to say.

**`runAll`'s throw policy.** `runAll(fs, ex)` with a live primary suppresses every failure onto it and rethrows
nothing, so `drive`'s catch cannot replace the original exception. `runAll(fs, null)` on the discard paths
propagates the first failure and suppresses the rest, and the drive's own catch then re-scans and the CAS makes the
already-run finalizers no-ops. Both are correct and the interaction between them is correct.

**B7's resolution is right.** `Eval.scala:161` `val r = curr.unsafeGet` has exactly two readers, the
`HandlerLoopState` delivery at `:167` and the empty-stack return at `:201`; the `Handler`, `Chain` and `head` arms
all pass `curr`. So 3.5j's removal is behaviour-preserving for those three arms, keeps the unnest at the one
delivery site, and returns the union to `partial`. `apply` and `settle` then unnest once through `.unsafeGet`.
That matches the representation contract and `1cf05637e8:20`.

**`AtomicBoolean` is available on all four platforms.** `Safepoint.scala:3` already imports
`java.util.concurrent.atomic.AtomicReferenceArray` from `shared`, and `build.sbt:772-775` cross-builds
`kyo-kernel2` for JS, JVM, Native and Wasm. The brief's concern is not live. `AtomicBoolean` also composes cleanly
with `Arrow.Transform` and `Barrier` (a class plus two traits) because `Arrow` is `sealed` but `Transform`
(`Arrow.scala:71`) is not.

**The lift analysis in 3.4 is correct.** `CanLift.derived` (`CanLift.scala:30`) is a plain `inline given` returning
`null`; only `derivedSingleton` (`:34`) splices. `Unit` is not `Singleton` and not `Singleton & Product`, so
`derived` resolves and no macro expands, and `Implicits.lift` (`Implicits.scala:10-15`) takes the primitive arm for
`Unit`. `Finalize.scala` therefore does not suspend a compilation unit, and the design's conclusion is right.
`Frame` is in `kyo-data`, a different module, so summoning it in `Finalize.scala` is not a same-module macro either.

**The variance and row arithmetic.** `A < S` conforms to `Any < Nothing` (`<[+A, -S]`, `Pending.scala:14`).
`Arrow.Transform[B, B, Any]`'s two-argument `apply` returns `C < (Any & S2)`, which is `C < S2`.
`Arrow[A, B, S] <: Arrow[A, B, S & S2]`. `Kyo.Park[Any, Any, Nothing]` types every field at the drive's currency
and reaches `Any < Nothing` through `<.fromKyo`. `Bracket`, `Ensure` and `Park`'s declared variances all check by
inspection, and the `Defer with Transform` combination is the shape `Eval.scala:77-78` already uses.

**The `pos < 0` park reconstruction.** `Eval.scala:46` pushes `kyo.cont` before `find`, so reifying the bare
operation with `cont = Arrow.id` is correct and does not stack the continuation twice; `IX, OX, EX, CX` are bound by
the arm's pattern at `:45`, so it is a construction and not a cast. Test 17's reasoning about `suspendWith`
(`ArrowEffect.scala:42`, `cont = this`) versus `suspend` (`:27`, `cont = Arrow.id[O[C]]`, dropped by
`Stack.scala:35`) is exactly right and answers prior finding N7.

**The three-argument `defer` substitution.** `Effect.defer(r._1, k, h)` (`Effect.scala:32-39`) pushes `h` then
flattens `k` above it, which is the same entry order the current two-node form
(`Effect.defer(r._1.map(k), h)`) reaches one turn later, minus a node and an anonymous class, with the interior
structural for the whole answer. I traced both and they agree.

**`Captured` covers the window it claims to.** The dispatchers are their own `contA` (`Eval.scala:81`, `:124`) and
so are stack entries; `interesting` tests `Captured` and `Walk.drain` has a `Captured` arm before the `Node` arm.
The window between `dump(pos)` at `:74`/`:117` and the answer settling is genuinely covered.

**The region-discard sites are the right two, and the suspended-clause `case v` arm is a real gap the first design
did not have.** `stack.truncate(pos + 1)` at `Eval.scala:104` and `:151` are the only truncation sites, and the
`case v` arms at `:87` and `:130` do drop `k` with no other reference to it.

**Concession shape.** Ten of the eleven rows in section 7 carry justification, minimal scope, protection and a
pinning test, which is the shape the skill requires. Two rows correctly label their justification as an unmeasured
hypothesis and route it to section 8. Section 8 names the rows the change reaches (loop head, node hierarchy,
region rebuild, the stateful row, the `Barrier` interface check, the unnest removal, the discard-path scan, the
nested-drive frequency) and says plainly that no number in the document is measured. That is the correct handling
per the skill, and the benchmark obligation should not be re-argued at implementation time; it should be run.

**Section 6's cast list.** One surviving cast, the `partial` in/out pair, correctly categorised as erasure-forced
and matched to `1cf05637e8:33`. I checked the six "found unnecessary" items and agree with all six. The
"verify at implementation time" item on `Eval.apply`'s existing in-cast is handled the right way round: removing a
cast is verified by the compiler, never argued.

---

## 6. Questions for the owner

1. **Where does the bracket's guarantee begin?** At the moment the user's `acquire` side effect runs, or at the
   moment `Bracket.apply` consumes a settled acquire? The design's scaladoc says the first and its mechanism
   implements the second, and the gap is as wide as whatever the user composed onto `acquire` (F2). If the answer
   is the second, `Effect.bracket`'s scaladoc has to say that composition belongs in `use`, and test 29 has to be
   split.

2. **What visibility do `Barrier`, `Captured` and `Finalize`'s entry points get?** `EffectTrace.scala:36-38`
   records that every symbol the drive names must be reachable from an expansion site outside package `kyo`, and
   the design specifies two of these at `private[kernel]`, narrower than anything the drive names today (F3). This
   interacts with the uncommitted narrowing of `Kyo`, `Handler` and `Stack` to `private[kyo]` now in the working
   tree, so it wants deciding as one question rather than three.

3. **Is `Effect.bracket` `inline`?** Carried forward from the design's own section 10, unchanged. Add to it that
   non-inline also changes when `acquire` is evaluated (N7), which the section does not mention.

4. **`finalizeResources` visibility.** Carried forward from section 10, unchanged.

5. **Should `finalizeResources` run the releases rather than return a `Unit < Any` the caller may drop?** (N10.)
   The prior art returned a value; the design's own eager-release argument points the other way for the entry point
   a scheduler calls while it is dropping something.

6. **Is the `wrap` guard at `Stack.scala:116` still needed at all?** F1's fix turns it off one step earlier. I
   could not find, from the code or from either design document, what the guard is for: it merges rather than
   preserves entry boundaries, and the deepest-entry arm at `Stack.scala:118-121` is the one that actually keeps a
   chain-valued entry whole. If the guard has no live consumer, deleting it is smaller than amending it, and it
   removes the whole class of hazard F1 describes.
