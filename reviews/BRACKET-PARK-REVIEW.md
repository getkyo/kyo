# Review: Bracket, Park, and finalizers on the Stack

Held-out review of `reviews/BRACKET-PARK-DESIGN.md`. No source was changed, nothing was built, nothing was
committed. Every `file:line` below was opened during this review against the worktree
`/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus` at `de1f91712d` (main sources are
clean at that commit; the modified files in the working tree are all tests). Where a citation resolves only against
the document's declared base `0ef6d2726c`, that is stated.

---

## 1. Verdict

**REWORK.**

The central reduction is sound: `Kyo.Defer` plus an `Acquire` arrow really does encode a bracket under the drive as
written, and a park really is `Effect.defer(residual, stack.dump(size))` with no new node kind. I traced both
against `Eval.scala:37-40`, `:156-197` and `Stack.scala:33-64`, `:100-133` and found no semantic gap in the
reduction itself. What does not hold is the layer the whole design rests on: **exactly-once**. The design treats
"the `Finalizer` is an entry, and its `AtomicBoolean` is the token" as sufficient, but the entry is not the only
representation a finalizer takes. On the abandonment path the walk mints a *fresh* `Finalizer` from an `Acquire`
(section 5.6, `fromArrow`'s `case q: Acquire`), so a resume and an abandonment do not share a CAS and both release.
On the throw path a finalizer that `dump(pos)` folded into a captured continuation is reachable from neither
`Finalize.unwind` (it is not on the stack) nor `Finalize.collect` (it is inside a `map` arrow's closure at
`Eval.scala:97` and `:143`), so it leaks. The abandonment walk also cannot tell a settled acquire from a pending
one and will call `release` on a `Kyo` node, and it never descends into `Kyo.Defer.value`, which is exactly where
`Acquire.apply` puts a fresh `Finalizer` one loop turn before the poll can park. Four of the seven blocking
findings below are instances of one root cause: the design never fixed *where a finalizer's identity lives*, and
"entry-ness gives it to us for free" is true only for the resume path, which is the one path that was never at
risk.

---

## 2. Blocking findings

### B1. The abandonment walk mints a fresh `Finalizer`, so `finalizeResources` is neither idempotent nor race-safe against a resume

**What the document claims.** Section 5.6, third bullet: "**Safe twice**, and what makes it safe is the
`Finalizer`'s CAS, not the walk. The walk is pure: it collects and mutates nothing, so composing twice is free, and
the second composition holds the same `Finalizer` instances, already flipped, so every `run()` returns false
without doing anything. The same mechanism covers the harder case: a park value resumed on one thread and finalized
on another. Exactly one side runs each release, and the CAS decides which." Section 8.7 records the same property,
and tests 29, 30 and 33 pin it.

**What is actually the case.** The walk does not always hold the same instances. `Finalize.fromArrow`, as specified
in section 5.6, has two producing arms:

```scala
case f: Finalizer[?, ?]  => acc.append(f)                              // shares the instance
case q: Acquire[?, ?, ?] => acc.append(new Finalizer(q.release, pending))  // mints a new one
```

The second arm allocates a new `AtomicBoolean` on every call, so two walks over the same park value produce two
independent guards. Worse, the resume path mints its own: section 3.4's `Acquire.apply` does
`Effect.defer(use(a), new Finalizer(release, a).chain(next))`. So for a park whose stack still carries an `Acquire`
rather than a `Finalizer`, the resuming drive's guard and the abandoning walk's guard are different objects and
**both releases run**.

That park state is reachable and is the one test 31 names. The drive pushes `Acquire` in the `Defer` arm
(`Eval.scala:37-40`) and only replaces it with a `Finalizer` when the settled arm pops it
(`Eval.scala:187-195`). The poll the design adds at the top of `loop` (section 4.3) fires between those two turns,
so `reify` captures a stack whose head is `Acquire`.

**Why it matters.** Exactly-once is the primitive's entire reason to exist, and this breaks it on precisely the
path (parked, then raced between a scheduler resume and a scheduler drop) that motivated the row-`Any` correction.

**What would have to change.** The exactly-once token has to be minted where the resource is, once, and be the same
object on every path that can reach it. The natural shape is for `Acquire.apply` to be the only site that can
produce a `Finalizer`, and for the walk to be unable to synthesise one; that in turn means a park taken with an
`Acquire` still on the stack owes nothing (nothing was acquired yet, see B2), which is the correct answer and
removes the arm entirely.

---

### B2. The walk cannot distinguish a settled acquire from a pending one, and hands `release` the wrong value

**What the document claims.** Section 5.6, in the `collect` code and its comment: "a park caught between acquire
settling and use starting: the resource is the `Defer`'s own value and the `Acquire` arrow is at the head of
`contA`. See 8.5". Test 31 is "the acquiring drive never resumes: park between acquire settling and `use` starting;
`finalizeResources` still releases. This is the `Acquire`-in-the-walk case of 5.6 and the one a naive walk misses."

**What is actually the case.** Three separate defects in the same two lines.

1. **The pending case is not excluded.** `collect`'s `Defer` arm passes `k.value` as `pending` unconditionally.
   If the park landed *inside* `acquire` rather than after it, `k.value` is a `Kyo` node, not a resource, and the
   walk still appends `new Finalizer(q.release, pending)`. `finalizeResources` then calls `release` on a
   computation that never produced a resource. Reachable: `Effect.bracket(acquire)(...)(...)` with a suspending
   `acquire` builds `Defer(acquire, Acquire, Arrow.id)` (`Effect.scala:26-30` after the identity drop at `:33`);
   the drive pushes `Acquire` and loops on `acquire` (`Eval.scala:37-40`); a poll at the next loop top parks with
   `curr` still pending and `Acquire` on the stack.

2. **The settled case is not unnested.** `k.value` is a union value. The delivery reader is `unsafeGet`
   (`Pending.scala:289-292`), which strips one `Nested` level; the walk passes the raw union straight into
   `release`. The kernel's representation contract is "unnest exactly once at delivery", and this is a delivery
   site.

3. **Any `Acquire` that is not the head of `contA` gets `()` as its resource.** `fromArrow` resets the carried
   value on the tail half: `fromArrow(c.b, fromArrow(c.a, acc, pending), ())`. A stack of
   `[<acquire's own continuation>, Acquire]` is ordinary (it arises whenever `acquire` is itself a `Defer`, since
   the drive pushes its `contA` above the already-present `Acquire`), and folds to
   `Chain(cont, Chain(Acquire, ...))`. The `Acquire` then reaches `fromArrow` with `pending = ()`, and the walk
   appends `new Finalizer(release, ())`.

**Why it matters.** Two of the three produce a call to user code with a value that is not the resource. That is
worse than a leak.

**What would have to change.** Same fix as B1: the walk must not be able to synthesise a `Finalizer` from an
`Acquire`. A park with an unconsumed `Acquire` owes nothing, because nothing has been acquired.

---

### B3. A finalizer folded into a captured continuation is unreachable from both recovery paths

**What the document claims.** Section 5.2 justifies the `Barrier` marker with: "If a `Finalizer` is inside that run,
it leaves the stack and lives inside a value in flight. Should the arrow that received it then throw, the finalizer
is unreachable: the boundary `catch` looks at the stack, and it is not there any more." Then, in the same section:
"The *other* `dump` (`dump(pos)`, `Stack.scala:100`, the capture path) must keep folding finalizers in, and does:
that is how a captured continuation carries the resources its remainder still owes." Section 8.5 calls that "the
positive half" and treats it as already correct.

**What is actually the case.** The hazard section 5.2 names for `dump()` is identical on `dump(pos)`, and the
document never asks the question there. The drive has five `dump(pos)` sites, all of which hand the fold out of the
stack:

- `Eval.scala:53-55`: `val k = stack.dump[OX[CX], AX, EX & S](pos)` then `h.run(kyo.input, k(_))`. If the clause
  throws, `Eval.scala:56-59` attaches and rethrows to the boundary, where `Finalize.unwind(stack, ex)` scans a
  stack from which `dump(pos)` already removed and nulled every slot in `[0, pos)` (`Stack.scala:112-113`, `:124`).
  The finalizer is inside `k`, which is a dead local. Leak.
- `Eval.scala:70` and `:113`: `k` is captured into the anonymous `Defer`'s `apply` and used at `:82` / `:125`.
- `Eval.scala:96-97` and `:142-143`: `loop(r._1.map(k))`. Here it is worse than "off the stack". `map` wraps `k`
  inside a fresh anonymous `Arrow.Transform` closure (`Pending.scala:20-39`), so the folded chain, and the
  `Finalizer` in it, are not structurally visible at all. `Finalize.fromArrow`'s `case _ => acc` walks straight
  past it, so a park taken in that window also loses the finalizer on the abandonment path.

**Why it matters.** This is the throw path, which the design lists as one of the three motivating paths ("throw
inside use", section 2.1) and pins with tests 8 to 11. Those tests do not reach it: none of them puts a
region-mediated suspension between the acquire and the throw, so in all of them the `Finalizer` is still an entry
when the exception reaches the boundary.

**What would have to change.** Either a live-finalizer side list the boundary `catch` consults independently of the
stack, or a rule that a fold across a finalizer is not allowed to escape the drive without a compensating
registration. The current design has neither, and the `Barrier` marker only covers the no-argument `dump()`.

---

### B4. `Finalize.collect` never descends into `Kyo.Defer.value`, and that is exactly where `Acquire.apply` puts a fresh `Finalizer`

**What the document claims.** Section 5.6's `collect` handles `Kyo.Defer` as
`fromArrow(k.contB, fromArrow(k.contA, acc, k.value), ())`, walking both continuations and never the value. The
`Kyo.Handle` arm, three lines below, does recurse: `fromArrow(k.cont, collect(k.value, acc), ())`.

**What is actually the case.** The asymmetry is a hole on the design's own hot shape. `Acquire.apply` (section 3.4)
returns `Effect.defer(use(a), new Finalizer(release, a).chain(next))`. That value is returned to the drive at
`Eval.scala:190` and becomes `next`, then `loop(next)` at `:195`. The poll the design adds at the top of `loop`
fires on the very next turn, so `reify` produces
`Defer(Defer(use(a), Chain(Finalizer, ...)), <stack fold>)`. The outer `Defer`'s `value` holds the only reference
to that `Finalizer`, and `collect` never looks there. `finalizeResources` returns `()` and the resource leaks.

The same omission breaks the ordering guarantee: section 5.6 states "Order is innermost first", which requires the
value half to be visited before the continuation half, since the value is what runs first.

**Why it matters.** It is a leak on the ordinary shape, one loop turn wide, on the path the primitive exists for.

**What would have to change.** `collect`'s `Defer` arm must visit `value` first, the way the `Handle` arm does.
That alone is not sufficient (see B3, where the finalizer is inside a closure and no structural walk can find it),
but it is necessary.

---

### B5. `sealed trait Barrier` cannot be implemented from `Handler.scala` and `Finalize.scala`

**What the document claims.** Section 5.2: "`// Arrow.scala or Stack.scala, whichever the owner prefers as the
home` / `private[kyo] sealed trait Barrier          // Handler and Finalize.Finalizer are the only implementors`".
Section 6's table repeats the placement: `kyo/Arrow.scala` gains `Barrier`, `kyo/kernel/internal/Handler.scala`
gains `Handler` also extends `Barrier`, and `Finalizer` lives in the new `Finalize.scala`.

**What is actually the case.** Scala 3 `sealed` permits direct extension only from the same source file. `Handler`
is declared at `Handler.scala:9` and `Finalizer` would be in `Finalize.scala`, so neither can extend a `Barrier`
declared in `Arrow.scala` or `Stack.scala`. This does not compile as specified.

**Why it matters.** It is the marker every other part of section 5.2 and the concession table depend on.

**What would have to change.** Drop `sealed` (and rely on `private[kyo]` for the closure claim, with the comment
naming the two implementors), or put `Barrier`, `Handler` and `Finalizer` in one file. The first is the smaller
change and matches how `Arrow.Transform` is already used across files (`Handler.scala:9`, `ArrowEffect.scala:35`).

---

### B6. `finalizeResources` cannot be a non-inline member of the existing `<` extension block

**What the document claims.** Section 5.6: "`// Pending.scala, inside the existing extension block. Deliberately
NOT inline: this is a boundary entry point a scheduler calls, never a user-composition site`" followed by
`extension [A, S](self: A < S) / private[kyo] def finalizeResources: Unit < Any = Finalize.compose(self)`. Section 6
repeats "add ... to the existing extension block, non-inline".

**What is actually the case.** The existing block is declared `extension [A, S](inline self: A < S)`
(`Pending.scala:17`), and every method in it is `inline` (`:20`, `:42`, `:64`, `:86`, `:108`, `:129`, `:280`,
`:283`, `:289`). An `inline` parameter is only legal on an `inline` method, so a non-inline `def` cannot be added
to that block; it needs its own `extension [A, S](self: A < S)` block. Note also that the design's own snippet
writes `extension [A, S](self: A < S)` without `inline`, which is not "the existing extension block".

**Verification status.** I did not compile this. The rule is Scala 3's, and the design's snippet and its prose
disagree with each other independently of the rule.

**Why it matters.** It is stated as a placement decision in two places and both are wrong; the fix is trivial but
the design should not ship an instruction that does not compile.

---

### B7. `Eval.drive` returning `Any < Nothing` is a representation-contract question, not "a small design choice"

**What the document claims.** Section 11: "**`Eval.drive` returning `Any < Nothing` rather than `Any`** is a change
from today's `apply`, which returns the settled `A`. `partial` needs the pending form and `apply` needs the settled
one, so one of them unwraps. Which side pays is a small design choice I did not settle; it interacts with the
`unsafeGet` at `Eval.scala:157`."

**What is actually the case.** Today's `loop` returns `Any`, and the settled arm returns `curr.unsafeGet`
(`Eval.scala:157`, `:197`), which has already performed the single permitted unnest (`Pending.scala:289-292`). If
`drive` is retyped to `Any < Nothing` and `partial` casts that result to `A < S`, an already-unnested value is
handed back into a `<`-typed position. When `A` is itself a computation (a `Boxed` payload the lift wrapped through
`Nested.lift`, `Nested.scala:10-13`), the caller's next drive will read the user's data as a node. That is the
double-delivery defect the skill's representation contract exists to prevent.

The prior implementation avoided it by construction: `loop` returned the union value and only `apply` unnested,
`Nested.unnest[A](loop(v, armed = false, neverStop))` at `1cf05637e8:20`, with `partial` at `:33` returning
`loop(...)` uncast on that axis. Today's `loop` does not have that shape, so `partial` on top of it is not the
byte-identical port section 7 claims it is (row 2 of the cast table says "Byte-identical to the prior
implementation at `1cf05637e8:33`"; the prior cast is `v.asInstanceOf[A < Any]`, the design's is
`v.asInstanceOf[Any < Nothing]`, and the return-side casts differ in what they assert).

**Why it matters.** The skill records that "one such removal passed the compiler and failed nine nesting tests".
This is the same axis, and section 11 files it as an unsettled detail rather than as the correctness question it is.

**What would have to change.** Decide it in the design: either `drive` returns the union and `apply` unnests (the
prior shape), or `partial` re-lifts through `Nested.lift`. Pin it with a park whose result type is itself a
computation, which the restored corpus does not contain.

---

## 3. Non-blocking findings

### N1. `Acquire.apply` runs `use` through the one-argument `Arrow.apply`, bypassing the safepoint budget

Section 3.4's `Acquire.apply` calls `use(a)`, which is `Arrow.apply(v: A): B < S` and, for the `Arrow(f)` form,
`f(v)` directly (`Arrow.scala:36`). Every other strict arm gates the equivalent call on the depth guard:
`Pending.scala:30-36` (`map`), `Arrow.scala:42-48`, `Handler.scala:16-22`. `Acquire` is the one entry that fuses
into user code without an `enter`/`exit` pair, so the fusion depth it adds is unaccounted. Section 5.3 relies on
the same one-argument form for `release(resource)` and calls that out; it does not call out `use`.

### N2. Section 6's mechanical check is false for the design's own new file, and its lift premise is weaker than stated

Section 6 states the check as "**every `<`-typed expression in the new code is produced by `Effect.defer`, by an
`Arrow` application, or is a value the drive already holds at `<` type. No bare value is written into a `<`
position anywhere.**" Two counterexamples are in section 5.6's own code: `Finalize.compose` returns `Unit < Any`
with a `then ()` branch, and `Finalize.runAll` returns `Unit < Any` with a trailing `if primary ne null then throw
primary` whose type is `Unit`. Both are bare values in `<` positions and both fire the implicit lift.

Separately, the consequence section 6 draws is over-stated. `CanLift.derived` is a plain `inline given` returning
`null` (`CanLift.scala:29`); only `derivedSingleton` splices a macro (`:41`), and the comment at `:35-40` says so
explicitly: "Keeping the macro on this narrow path means ordinary lifts never expand a macro, so units of this
module do not suspend compilation waiting for the macro classes." `Unit` and the drive's abstract `BX` both resolve
through `derived`, so "summons the lift in a new kernel file, which is exactly the move the skill says deepens the
cascade" does not follow for the types in play. The `Eval.drive` extraction may still be right; the argument given
for it is not.

### N3. The `Eval.drive` extraction reverses a deliberate owner decision and has an undisclosed EffectTrace interaction

Section 0 item 5 and section 6 present the extraction as "a hard requirement, not a nice-to-have". Beyond N2, the
extraction changes observable behavior that the in-flight EffectTrace work deliberately designed around.
`EffectTrace.scala:119-122` records: "With `Eval.apply` inline, the drive's own frames no longer appear under
`kyo.kernel.internal.Eval`: `loop` expands into the caller and its physical frames carry the caller's class name,
so the first entry below filters nothing at a site that expanded the drive. There is no correct fix here". A
non-inline `drive` restores those frames and makes `isPlumbing`'s first predicate (`:126`) live again, changing
what a spliced trace shows. Section 6 mentions EffectTrace only to say the new `unwind` composes with the per-arm
catches. Section 11 correctly says the extraction needs the full benchmark class; it should also say it changes
trace output.

### N4. `Eval.scala:127` is a wrong citation

Section 6: "`Eval.apply`'s body contains lift-summoning positions (for example `next(apply(o.unsafeGet),
Arrow.id)` at `Eval.scala:89` and `:127` ...)". Line 89 is correct. Line 127 is `def apply[D, S2](`; the second
occurrence of that expression is at `Eval.scala:134`.

### N5. `SafepointConcurrencyTest.scala:206-252` does not pin what section 4.2 says it pins

Section 4.2: "Without `arm` (that is, under `Eval.apply`) a stop drains nothing and the drive never looks, which is
what `SafepointConcurrencyTest.scala:206-252` pins: under `Eval`, `burn(Period * 4)` completes and returns 0 even
with the slot degraded." The test in that range is "the overflowed slot ignores budget operations and misses
preemption" (`SafepointConcurrencyTest.scala:210`), and what it degrades is slot *availability*: `Slots` holder
threads occupy the table so the probe resolves to `Overflowed`. No stop is requested against the probe before
`evalResult = Eval(burn(Period * 4))` (`:240`); `stoppedResult` is read before the eval and asserted false, and
`assert(!Safepoint.stop(probe))` runs after it. The test carries no evidence about the `arm` coupling. The
conclusion in section 4.2 is independently correct (I verified the coupling at `Safepoint.scala:98-103` and
`:134-135`), but the cited evidence does not support it.

### N6. Two citations and one label on the `Eval.partial` consumer are wrong

Section 1 cites `kyo-kernel2/jvm/src/test/scala/kyo/kernel/internal/SafepointConcurrencyTest.scala:88`. The
`Eval.partial` call is at `:92`; `:88` is `//     @volatile var done    = false`. Test 24 in section 9 reads
"**budget park mid-path:** `SafepointConcurrencyTest.scala:77-102` unchanged, the live consumer". The block spans
`:77-106`, it is commented out and therefore is not "unchanged" (restoring it is the work), and calling it a
"budget park" contradicts section 4.2's own table, which establishes that budget exhaustion never parks. This case
is a stop-protocol park.

### N7. Test 18 claims a guard the restored corpus cannot exercise

Section 4.3 correctly identifies that the `Suspend` arm pushes `kyo.cont` before it searches (`Eval.scala:42-43`)
and that reifying `kyo` itself would stack the continuation twice. Test 18 says the existing case pins it. It does
not. Every parked case in the restored corpora suspends through `ArrowEffect.suspend`, whose `cont` is
`Arrow.id[O[C]]` (`ArrowEffect.scala:24`), and `Stack.push` drops identity entries (`Stack.scala:35`). With an
identity `cont`, reifying `kyo` and reifying the bare operation are indistinguishable. The guard only bites for
`ArrowEffect.suspendWith`, whose `cont = this` (`ArrowEffect.scala:39`), which is the same shape `d4e59ffa56`
records as having exposed four bugs ("four red tests, all with suspendWith"). A dedicated `suspendWith` park case
is required.

### N8. `Stack.compact(n)` is not `truncate(n)` past the bottom, which test 38 asserts

`truncate` guards each step on `head != tail` (`Stack.scala:137`), and `StackTest.scala:238-243` pins "past the
bottom stops at empty" with `stack.truncate(10)` on a one-entry stack. `compact` as specified in section 5.4 has no
such guard: it walks `i` from `n-1` down to `0` regardless of `size` and sets `head = w`. Test 38 ("`compact(n)`
with no finalizer in the range is `truncate(n)`") fails for `n > size`. Both call sites pass `pos + 1 <= size`
(`Eval.scala:100`, `:147`), so this is a spec defect rather than a live bug, but `compact` is being added as a
general `Stack` method next to a sibling that does guard.

The write-cursor safety argument in section 5.4 is correct; I re-derived it and it holds, including the
all-survive case where each entry writes to its own slot.

### N9. Multi-shot capture has two cases and the document states one

Section 8.3: "A park value is a complete value, so it can be driven twice, and both drives run `use`'s remainder
against the same resource. Exactly one releases ... the alternative (release per shot) would need a re-acquire the
kernel cannot perform." That is the case where the capture is taken *below* the `Finalizer`. When the capture is
taken while `acquire` is still suspended, `dump(pos)` folds the `Acquire` arrow itself into `k`, and each shot
re-runs the acquire remainder, calls `Acquire.apply` again, and mints its own `Finalizer` and its own resource. So
the kernel does re-acquire per shot in that case, and the sentence quoted above is false for it. Test 36 covers
only the first case.

### N10. Multi-shot below the finalizer is a use-after-release, stated only as a released-once path

Under the design, the second shot of a `handleCont` capture runs `use`'s remainder against a resource whose release
has already completed (the CAS at section 5.3 makes the second `Finalizer.run()` a no-op and `apply` still runs
`next`). Section 8.3 states the exactly-once half and does not state that the remainder then observes a released
resource. Test 36 asserts "both shots run to completion", which will pass while the hazard is present. This is
plausibly the right semantics, but it belongs in the scaladoc and in the concession table as a documented hazard,
not only as an exactly-once win.

### N11. The `wrap` guard in `dump` is not analyzed

Section 8.5 asserts "Entry boundaries must survive the round trip" and that the park's whole-stack fold uses
`wrap = true`, "which is the right one", citing `Stack.scala:114-121`. The guard is
`if wrap && !handlers && !(c.b eq Arrow.Id)` (`Stack.scala:116`), and `handlers` becomes true as soon as a
`Handler` is folded (`:122`), which for a whole-stack park fold happens early. So the `wrap` behavior the document
relies on is disabled for every entry folded above the first handler seen. I did not establish that this breaks
anything: the separate arm at `:120` that wraps the deepest chain entry is not gated on `handlers`, and
`Stack.push`'s `fill` stores each chain's left element whole (`:53-64`), so ordinary right-nested folds round-trip.
Test 42 asserts the property without the analysis; the analysis should be in the design.

### N12. The prior `Effect.bracket` was `inline`; the design specifies non-inline without noting the change

`3a95636fa8:Effect.scala:56` and `:88` declare `private[kyo] inline def bracket[R, A, S]`. Section 6's table
specifies "non-inline" with no mention that this reverses the prior shape. Per the skill, `inline` is a
public-surface and compile-cost decision that belongs to the owner in both directions.

### N13. The test-corpus citations are stale against the current tree

`EvalTest.scala:206`, `:193-262` and `:259-261` resolve correctly against the declared base `0ef6d2726c`, which the
document states up front. They do not resolve against the current tree: `EvalTest.scala` moved 1190 lines since
that commit, and the commented partial-evaluation block is now at `:949-1024`, with the identity assertion
`assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])` at `:1021`. `ArrowEffectTest.scala:446` and `:594` are
now `:448` and `:600`. The main-source citations (`Eval.scala`, `Stack.scala`, `Pending.scala`, `Arrow.scala`,
`Effect.scala`, `Handler.scala`, `Safepoint.scala`, `EffectTrace.scala`) all still resolve.

### N14. The `EffectTrace` sweep is not covered in the per-file table

The brief asks what a barrier-like non-`Handler`, non-`Chain` entry does to `EffectTrace`. Answer: `Finalizer` and
`Acquire` are both `Arrow.Transform`s, so the `drain` dispatch (`EffectTrace.scala:264-290`) reaches them at
`case a: Arrow[?, ?, ?] => frame(a.frame)` (`:288-289`), after the `Handler` arm at `:281` and the `Chain` arm at
`:285`. Section 5.3 sets `Finalizer.frame = release.frame` and section 3.4 sets `Acquire.frame = use.frame`, so a
trace crossing a live bracket will show the release site and the use site as effect frames. That is defensible, but
it is new user-visible trace content and section 6's table lists no change to `EffectTrace.scala` and no note about
it. `Builder.entries` (`:247-256`) reads slots through `Stack.entry(i)` and is unaffected structurally.

---

## 4. Claims checked and confirmed correct

These do not need re-litigating.

**The reduction, node bookkeeping.**
- `Kyo.Defer[A, B, +C, -S]` and `Kyo.Handle[E, A, B, +C, -S]` at `KyoInternal.scala:15` and `:28`, exactly as
  section 3.1 states, and the variance annotations section 3.1 proposes for `Bracket` do type-check by inspection
  (`S` occurs only in contravariant-of-`Kyo`/`Arrow` result positions, `C` only in `Arrow`'s covariant slot).
- The `Defer` arm is `stack.push(kyo.contB); stack.push(kyo.contA); loop(kyo.value)` at `Eval.scala:37-40`.
- `Effect.defer(v, a, b)` drops an identity `b` (`Effect.scala:32-34`), `Effect.deferInline` builds a
  `Kyo.Defer[A, A, A, S]` with both continuations `Arrow.id` (`Effect.scala:20-24`), `Stack.push` drops
  `Arrow.Id` (`Stack.scala:35`), and `Arrow.chain` drops an identity right half (`Arrow.scala:19`). The
  settled-acquire collapse in section 3.3 therefore costs what the document says it costs.
- The settled arm's fall-through is `case head => val tail = stack.dump[Any, Any, EX & S](); head.asInstanceOf[
  Arrow[Any, ?, EX & S]](curr, tail)` at `Eval.scala:187-195`, and `Acquire` and `Finalizer`, being
  `Arrow.Transform`s and not `Handler`s or `Chain`s, do reach it. The full trace in section 3.4 is faithful to the
  code.
- `Handle.value: Kyo[A, E & S]` versus `Bracket.acquire: Kyo[A, S]`: the asymmetry section 3.3 explains is real,
  and `ArrowEffect.scala:67` is indeed `case _ => onDone(v.unsafeGet)`.

**The composition corollary.**
- `map` on a pending value builds `Effect.defer(kyo, arrow, next)` (`Pending.scala:26-28`), so
  `bracket(...).map(f)` wraps the bracket node rather than extending its use chain, and `f` lands below the
  finalizer on the stack. The contrast with `3a95636fa8` is real: `Bracket.map` there rebuilt the node extending
  `cont` (`3a95636fa8:KyoInternal.scala:118-127`), and the region-exit crossing at
  `3a95636fa8:KyoInternal.scala:133-164` existed for exactly that reason. Section 2.1's second corollary and
  section 10's rejection of `Kyo.exitStep` both hold.

**Park and the Stack.**
- `Stack.dump(pos)` folds `entries(0).chain(entries(1).chain(...))`, nulls each slot and advances `head`
  (`Stack.scala:102-126`), so a park holds arrows and no `Stack`; `Stack.release` calls `clear()`
  (`Stack.scala:186`, `:145-149`) and finds an empty ring. Section 4.4's first two bullets are correct.
- `Stack.local` is a `ThreadLocal[Pool]` (`Stack.scala:193-197`) and `clear()` resets `head`/`tail` to 0, so a
  resume on a foreign thread borrows a clean stack. Section 8.3's three per-thread items are correctly enumerated.
- A stateful region comes back at its live state: `dump` swaps in `Handler.HandlerLoopState(h, state)`
  (`Stack.scala:107-111`) and `put` re-seeds through `states(idx).getOrElse(f.initialState)` (`Stack.scala:23-31`),
  which is exactly what the commented case at `ArrowEffectTest.scala:600` requires.
- `Handler extends Arrow.Transform` (`Handler.scala:9`), so a region folds and re-pushes as an ordinary entry and
  `Stack.find` locates it again (`Stack.scala:89-98`). Section 4.1's two "details" are both true.
- The bare-operation reify in section 4.3 addresses a genuine hazard (`Eval.scala:42-43` pushes `kyo.cont` before
  `find`), and the reconstruction is a construction rather than a cast: `IX, OX, EX, CX` are bound by the arm's
  pattern at `Eval.scala:41`. Only the test claim about it is wrong (N7).

**The barrier and the region-discard site.**
- `Stack.dump()`'s boundary tests `isInstanceOf[Handler[?, ?, ?, ?]]` at `Stack.scala:130`, and the fold does hand
  the run to an arrow that can throw; the `Barrier` need is real for that site. `Stack.find` tests `Handler` by tag
  (`Stack.scala:95`) and needs no change, as section 5.2 says.
- `stack.truncate(pos + 1)` fires on the `Loop.done` arms at `Eval.scala:99-101` and `:146-148`, exactly the two
  sites section 5.4 names, and `compact`'s pop order does produce innermost-first release with no new drive
  semantics.
- `Stack.entry(i)` (`Stack.scala:87`) is `private[kernel]` and is precisely the accessor `unwind` needs; a
  `Finalize` object in `kyo.kernel.internal` can call it.

**Types and rows.**
- `A < S` conforms to `Any < Nothing` by variance (`<[+A, -S]` at `Pending.scala:12`), so `finalizeResources` needs
  no cast on that axis.
- The row-`Any` non-conformance argument in sections 3.2 and 8.4 is correct: `<` is contravariant in `S`, so
  `Unit < Sync` does not conform to `Unit < Any`, and `Arrow[A, Unit, Any] <: Arrow[A, Unit, S]` for every `S`.
- `Arrow(f).apply(v: A)` calls `f(v)` directly (`Arrow.scala:36`), so an ordinary side-effecting release has
  already run when `release(resource)` returns and takes the `case _` arm with no drive. Section 5.3's third
  property holds.
- `Finalizer.apply`'s row arithmetic is right: `Arrow.Transform[B, B, Any]` requires `C < (Any & S2)`, which is
  `C < S2`, and `Arrow.Id.apply(v, Arrow.id)` short-circuits to `v` (`Arrow.scala:101-105`), so a `Finalizer`
  compacted to the top of the stack with nothing between it and the next barrier passes the value through
  unchanged.

**Prior art and rulings.**
- Every commit citation resolves and every quotation is verbatim: `c2d5db5072`'s "the Parked carrier was a control
  token in the drive's value channel ...", `d4e59ffa56`'s subject and its "Kyo.Park, Stack.copy*/pushAll/
  regionAbove, Eval.fold/park/restore go.", `1cf05637e8:41`'s `final private class Parked(val v: Any)`.
- `3a95636fa8` citations all resolve: `KyoInternal.scala:33-36` (`outcomeOf`), `:118-127` (`Bracket.map`),
  `:133-164` (the exit crossing), `Finalize.scala:91-106` and the comment at `:99-100`, `Pending.scala:38`
  (`finalizeBracket` and its comment, quoted correctly), `Eval.scala:43-47` (the per-bracket `try`).
- `Sync.scala:115` and `:107-115`, `Scope.scala:66`, `:157-191`, `:184-187` all resolve and say what section 8.1
  and section 10 say they say.
- The CPS `Ensure` idiom is exactly as described: `sealed abstract class Ensure ... extends AtomicBoolean with
  Function1[...]` at `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:142-155`, and the
  comment "ensures the function is called once even if an interceptor executes it multiple times" at `:174-175`. A
  spent `Ensure` is transparent (`:148-149`).
- Section 8.1's technical argument against a kernel-level outcome parameter is sound and independently checkable:
  `outcomeOf` reads a settled `Result.Error` (`3a95636fa8:KyoInternal.scala:33-36`), and the `Loop.done` arm at
  `Eval.scala:99-101` discards the interior with no value for the bracket to inspect. Same program, two positions,
  two answers.

**Safepoint.**
- `Safepoint.stop` CASes `Thread` to `Stop(thread)` at `Safepoint.scala:137-159`; the armed-slot drain is at
  `:98-103` and is quoted verbatim and correctly; `arm` is one-directional (`:134-135`); `Eval.apply` has no poll
  anywhere in `Eval.scala:35-199`. Section 4.2's mechanism description is right, only its cited evidence is not
  (N5).
- `save` then `arm` in that order preserves the caller's armed state in `saved` and restores it in the `finally`;
  this matches `1cf05637e8:31-32`.

**Process shape.**
- Recommending against the `Bracket` node while keeping sections 3.1 to 3.3 as the specification, and marking it
  "needs sign-off", is the correct handling of an item that conflicts with a shape the owner gave. Same for the
  `Park` node against the `d4e59ffa56` ruling.
- Section 8.4's throw policy (run all, first failure propagates, later ones suppressed, a finalizer that threw is
  not retried) matches the prior art it cites and the three rejected alternatives are each rejected for a stated
  reason.

---

## 5. Questions for the owner the design should have asked

1. **Where does a finalizer's identity live?** The design assumes the stack entry is it. A finalizer folded into a
   captured continuation, or into a `map` arrow's closure at `Eval.scala:97`, is not on the stack and is not
   structurally visible, so neither `unwind` nor the abandonment walk can reach it. Is a drive-side registry of
   live finalizers acceptable, or is a leak on the throw-through-a-capture path the intended behavior?
2. **What does a park owe when the `Acquire` has not yet run?** The design's walk says "one release"; the honest
   answer is "nothing, no resource exists". Confirming that removes B1's minting arm and B2 entirely.
3. **Do you want the drive de-inlined?** Section 6 asserts it is forced. It is not (N2), it reverses your `inline`
   decision, it changes what `EffectTrace.isPlumbing` filters (`EffectTrace.scala:119-136`), and it has no
   measurement. If the goal is only "`Finalize` must not expand the drive", a `private[kernel]` non-inline entry
   point used only by `Finalizer.run`, with `Eval.apply` unchanged, is a smaller question to answer.
4. **Which side unnests?** `Eval.drive` returning the union with `apply` unnesting (the `1cf05637e8:20` shape), or
   `partial` re-lifting through `Nested.lift`. This is a representation decision, not a detail.
5. **Is per-shot acquire the intended semantics for a capture taken above the acquire point,** and is
   use-after-release on the second shot of a capture taken below the finalizer acceptable? Both are reachable and
   the design commits to only one of them.
6. **Is `Effect.bracket` inline?** The prior art was (`3a95636fa8:Effect.scala:56`, `:88`).
7. **Should the region-discard path have its own mechanism at all?** `compact` is a second way for a finalizer to
   change position, and it exists only because the discarded interior's finalizers must survive. Routing a
   discarded interior through the same abandonment path as a dropped park would leave `Stack` untouched and remove
   one of the three sites the section 8.5 invariant has to police.
