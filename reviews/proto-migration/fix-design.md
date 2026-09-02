# Fix design: S9, entry 9, S10

Tip read: `1035557542`. Brief: `open-issues.md`. This document is a derivation in the kernel
skill's sense for the three open soundness issues, written so a live review can proceed from it
alone: the law as an equation in the values the kernel already has, the surface, the shape, the
forks with one recommendation each, the pins, the rows, and the edit sequence. Nothing here has
been run; section 9 names what only a run can decide.

Sections:

0. The recommendations in one paragraph each
1. Derivations: S9, entry 9, S10
2. The multi-shot law
3. Cross-thread visibility of the mark
4. Where the mark lives
5. The one rule for context reads, entry, exit, and rebound
6. Pins whose expectation changes, and the new pins
7. Benchmark rows, the evidence ladder, the allocation delta
8. The edit sequence
9. What reading could not decide, one experiment each

## 0. The recommendations

**S9.** The debt a crossing leaves in the answering handler's lane is discharged on the value the
two sides share, the `Stack.Snapshot`, by a mark `installed` writes and every drain reads. The
mark replaces S4's lane scan: `Stack.settle` and `settleIn` are deleted, `installed` writes
`entries.settle()`, and `expandOwed` (the one expander behind `drainDiscarded`, `drainOwed`, and
`Eval.release`) skips a settled snapshot with its subtree. The lane no longer removes a debt on
resume, so `dump` drops the settled debts at the end of the lane before owing its own, which keeps
the crossing row's lane at one entry without a scan. The snapshot becomes `Array[AnyRef]` with one
trailing slot holding the mark; `Span` goes, since a mark is a write and `Span` is the promise
that there are none.

**Entry 9.** Keep today's behavior and record it as the raw-hook face of Q4 and of finding 15: a
raw region revived after its owner's exit drained it fires `release` then `done`. The refusing
alternative is rejected on evidence, not taste: it would refuse every escaped continuation whose
owner exited, which four green pins and the fiber contract require to resume.

**S10.** The context becomes what the stack already is: the sequence of live context regions,
innermost first, with one node per region (shadowing, not replacement). Every consumer takes the
first node whose tag is a subtype of the tag asked for. Entry conses, exit removes the region's own
node (which is the head), a dump removes one node per dumped context region, an install and a
rebuild cons in region order. `Stack.findExact` and the exit-side recomputation of S2 disappear
because an exit no longer recomputes anything. The change lives in `Context.scala` and the four
`Eval` sites that maintain the context; kyo-data's `TypeMap` is untouched, because its contract is
a keyed set for `Env` and `Layer`, and a region list is a different thing.

**The multi-shot law** (section 2): `done` fires once per shot that completes a region; `release`
fires only for regions whose snapshot was never installed, and then at least once. A debt settled
by one shot is never drained by a later shot's abandonment. This is what the bracket's cell already
does, and the mark makes raw regions agree with it. One pin records it and flips today's
behavior.

## 1. Derivations

### 1.1 S9. A crossing resumed in a nested eval is released again at the owner's exit

**The law, as an equation.** Write `R(v)` for a context region with hooks `done` and `release`
around a body `v`, `H` for the answering handler below it, and `op` for the operation the body
raises. The crossing is the evaluator's reading of

```
H(R(op.map(k)))  ==  H(clause(op, x => Park(k(x), SC)))        SC = dump(R)
```

where `Park(k(x), SC)` is the region rebuilt around the answer (backlog B2: "walk the region stack
outermost-in and rebuild each entry", `installed` is that walk). `R`'s hooks are two exits of one
extent: `done` when the body's value leaves the region, `release` when the extent is abandoned. The
debt is the second exit held in reserve:

```
H.exit(lane = [SC])  ==  H.exit(lane = [])  ;  if never installed(SC) then release(SC)
```

The law S4 fixed for one stack, and the brief restates: a region dumped by a crossing and
re-installed by a resume that completes normally fires `done` once and `release` never. The
equation makes "never installed" the only thing the exit may consult, and "installed" is an event
that happens wherever the park is evaluated: the same stack, a nested eval, another thread. So
the fact must be recorded on something all of those reach, and the only value they share is `SC`
itself: the lane holds it, the crossing arrow captured it, every park over it carries it.

**Why the two other candidates fail on the equation, not on cost.** Candidate 2 (a stack remembers
the stack active on its thread when borrowed; `settle` walks the chain) makes the S4 law hold per
thread rather than per value: the resume handed to another thread stays at-least-once, so the law
would depend on which pooled stack a resume happened to land on, which is not a law. It also makes
one eval mutate another's lanes, and a resume on a second thread would do that concurrently; the
one precedent for a pooled-stack reference outside its eval, `EffectTrace.seen` with `seenEpoch`
(S6), is a read-only identity check, and does not transfer. Candidate 3 (rule raw hooks
at-least-once across evals and flip the pin) rules a spurious firing to be law: at-least-once
(finding 15) covers a genuine abandonment reached twice; S9's release fires with no abandonment,
after the region completed. The bracket's cell masks it, which is why only raw hooks show it, and
a raw hook that tracks its own state sees a double edge exactly as the cell would have.

**Surface.**

Changes:

- `Stack.scala`, `object Stack`: `Snapshot` becomes `opaque type Snapshot = Array[AnyRef]` with a
  trailing slot; `wrap` goes; extensions `regions`, `isEmpty` recomputed for the slot, accessors
  unchanged; two new extensions `settled: Boolean` and `settle(): Unit`; `Snapshot.empty` is a
  one-slot array; `Builder` allocates `regions * 4 + 1` and `result()` keeps the slot.
- `Stack.scala`, `class Stack`: `dump`, `snapshot`, `contextual` allocate the extra slot; `dump`
  drops the settled debts at the end of the lane before appending; `settle` and `settleIn` are
  deleted.
- `Eval.scala`: `installed` writes `entries.settle()` where it called `stack.settle(entries)`;
  `expandOwed` skips a settled snapshot and its subtree; `release`'s `Park` arm skips the park's
  entries when they are settled.
- Tests: StackTest's six `settle` pins are replaced by pins on the mark and the prune; the S9 pin
  goes green unchanged; three new pins (sections 2 and 6).

Must not change: `Kyo.Park` (fields `value`, `entries`, `owed`), `SuspendArrow.crossing`,
`Handler`, `Effect.Cell` and `bracket`, `Isolate` (it uses only `Builder`, `isEmpty`, and the
accessors), `EffectTrace` (accessors), `Stack.owe`, `oweBelow`, `takeOwed`, `takePopped`,
`takeEvalOwed`, `owesAny`, `find`, `scratch`, the `loop` signature, and every drain call site in
`Eval` (they keep calling `drainDiscarded` and `drainOwed`; the skip is inside `expandOwed`).

**Shape.** Three properties are made true by structure rather than by call sites remembering:

1. *One writer, one place.* `settle()` is called at exactly the line `stack.settle(entries)` sits
   today (Eval.scala:253), after the `reenter` loop succeeds and before `oweBelow` and the pushes.
   A refused re-entry leaves the snapshot unsettled, so a refused park is still drained by its
   owner, which is today's sequence for the bracket (the cell was closed by whichever drain came
   first) and at-least-once for a raw hook, unchanged.
2. *One reader, one place.* `expandOwed` is the only walk from a snapshot to hooks; `drainOwed`,
   `drainDiscarded`, and `release` all go through it. A settled snapshot contributes nothing and
   its `owed(i)` subtrees are not entered, because `installed` re-owed those onto the live lanes
   of the eval that installed it (Eval.scala:268, 275), and that eval's exits, unwinds, and parks
   already carry them. `release`'s `Park` arm reads the park's `entries` directly (Eval.scala:454)
   and gets the same test.
3. *The lane keeps no invariant a scan would enforce.* Today the lane is kept exact by `settle`
   removing the resumed debt; a nested eval cannot reach it, which is the bug. Under the mark the
   lane is a superset that the drain filters, and its only job is retention. `dump` drops the
   settled tail before appending (`Chunk.last` on an `Append` is O(1); `dropRight(1)` on an
   `Append` returns its `chunk` or `Chunk.empty` without allocating, `Chunk.scala:127-140`), so a
   handler answering crossings in LIFO order, which is every benchmark row and the fiber loop,
   holds one snapshot in its lane at a time, and an out-of-order consumption is dropped at the
   next dump that finds it at the tail or at the exit that takes the lane.

The shared `Stack.Snapshot.empty` needs no guard: the evaluator's `Park` arm dispatches
`entries.isEmpty` before `installed` (Eval.scala:181), so it is never settled, and a settled empty
snapshot would skip nothing since it has no regions. `Isolate.fork` returns the capture's own
zero-region array when empty, not the shared instance, and it takes the same arm.

**Forks that survive.** One: the multi-shot law, section 2, because it decides whether the mark is
per snapshot (what the carrier gives) or per re-owed occurrence (what a per-shot reading needs).
The carrier and the mark's value are decided in section 4 on allocation and on the review's own
rules, not as forks.

**Interactions.**

- *S4.* Subsumed. `Stack.settle` with its identity scan across lanes and `settleIn`'s `toIndexed`
  copy go; the S4 pins stay green because the mark is independent of which lane holds the debt
  (the pending-outcome `oweBelow` and the resume-inside-a-nested-region cases).
- *S3.* The isolate's `Kyo.Park(inner, forked)` installs a `Builder` snapshot that no lane ever
  owes; it is settled on first install and nothing consults that. `Forked` copies stay silent to
  hooks; the in-place join reads the live stack by handler identity and never a snapshot.
- *S8.* `reenter` runs before the mark, so the bracket's `Closed` on a released cell is raised on
  an unsettled snapshot, and the refused park's `release(kyo, ex)` plus the owner's later drain
  reach the same closed cell: exactly once by the CAS, as today.
- *Entry 9.* Unchanged: the owner's drain runs before the remainder's park is installed, so the
  snapshot is unsettled at the drain and `release` fires; the later install re-enters the region
  and `done` fires. Section 1.2.
- *S10.* Independent: the mark is on the snapshot, the context is a list; `rebound` reads the
  snapshot's handlers through accessors that do not move.
- *Q3.* Unchanged: a park resumes with its captured bindings; the mark says nothing about state.
- *Finding 21.* `scratch` untouched.

### 1.2 Entry 9. A raw context region is revived after its release fired

**The law.** The sequence in the pin is `clause`, `release cfg 1`, `done cfg 1`. Two exits of the
region's extent fire, in that order, for one captured `(handler, state)`. The question is whether
the second is a defect the kernel must refuse, as the bracket's cell refuses with `Closed`.

The bracket refuses on a state-level fact: the resource is closed. The kernel has no such fact for
a raw region and must not invent one on the snapshot, because "the snapshot's owner exited and
drained it" is precisely the state of every escaped continuation the kernel promises to resume:

- ContextEffectTest "a binding below the answering handler is the resume site's, one above it is
  the captured one": the `Cfg` region above the handler is drained at the handler's exit (release
  is a no-op there) and resumed later from the stash; expected `(100, 2)`.
- EvalTest "resumes after its region completed, in a fresh evaluation, each shot from capture-time
  state", "resumes under a later region of the same tag", "stays valid after its eval completes".
- IsolateTest "the continuation is a complete value: it resumes more than once, anywhere", "a
  binding crossed at the suspension is present in a detached resume".
- ArrowEffectTest "a handler whose clause suspends outward travels with the continuation and
  answers ahead of the handler at the resume site".

A mark that refused a released snapshot would flip all of these to `Closed`, and the fiber
machinery (a continuation handed to a scheduler after the region that captured it has exited) is
the same shape. So the drain at the owner's exit is the kernel's at-least-once safety net for a
holder that drops the continuation, and a later revival is the holder saying it did not. That is
finding 15's ruling ("the kernel guarantees reachability at least once per edge; exactly-once
belongs to the state") seen from the revival side, and Q4's raw-hook face: the bracket refuses
because its state can; a raw region has no state to refuse on and is revived.

**Surface.** No code. The pin stays as written and green, and the `open-issues.md` entry closes
with this ruling. The S9 mark does not touch it: the drain precedes the install.

**Fork.** None survives; the alternative is rejected on the pins above.

### 1.3 S10. A context read at a supertype tag ignores an inner subtype binding

**The law.** Dispatch already has it: `Stack.find` walks from the top and takes the first entry
whose tag is a subtype of the tag asked for (Stack.scala:171). A read at tag `T` sees the state of
the innermost live context region whose tag `<:< T`, exact or not:

```
read(T) under regions r_n ... r_1 (innermost first)  ==  state(first r_i with tag(r_i) <:< T)
```

and entry derivation is the same read: `derive(read(T))`. The old kernel resolves bindings on the
stack as well (`kyo/kernel/internal/Stack.scala:214-224`, `lookup`), innermost first; it matches
tags exactly there, which is the kernel's older law for bindings, and the S10 pin rules the
proto's law to be the subtype-aware one dispatch uses. The reads-and-dispatch disagreement in the
brief is the defect; the ruling is the direction.

**Why the walk-from-the-head candidate is correct by inspection only.** The brief's candidate
walks the `TypeMap` from its head taking the first `<:<` match, because `addErased` prepends.
The head is the newest *modification*, and modifications are not region entries: `contextExit`
re-adds the exact key of the next region of that tag (`ctx.update(hc.tag, stack.state(j))`,
Eval.scala:288), which moves an *outer* binding to the head. Regions `[Cfg = 1, CfgSub = 2,
Cfg = 3]`: when `Cfg = 3` exits, the map is `{Cfg -> 1, CfgSub -> 2}` with `Cfg` at the head, and
a read at `Cfg` answers 1 where the innermost related live region holds 2. The exact-first rule of
today gives 1 too. No walk order over a map with one key per exact tag can recover region order
after an exit, because the exit destroyed it. The candidate fixes the two nestings in the pin and
not the third, which is the "correct by inspection" the skill names.

**The shape: the context is the stack's context regions, in order.** A `TypeMap[Any]` is a
persistent list of `Node(tag, value, next)`; `TypeMap.Node` and `TypeMap.removeExact` are
`private[kyo]` and reachable from `kyo.proto.kernel.internal`. The context stops using
`TypeMap.add` (which removes the old exact key) and `TypeMap.get` (exact first, then oldest
first), and becomes a list with one node per live context region:

- `bind(tag, value)` conses `Node(tag.erased, value, self)`: no removal, so an inner region of the
  same tag shadows the outer one and the outer node stays where it was.
- `get(tag)` walks from the head and returns the first node whose `tag <:< tag.erased`.
- `remove(tag)` is `TypeMap.removeExact`, which drops the first exact node from the head.

Every maintainer of the context then preserves region order by construction:

| site | today | under the list |
|---|---|---|
| region entry (Eval.scala:169-173) | `derive(ctx.get(tag))`, `ctx.update(tag, st)` | `derive(ctx.get(tag))`, `ctx.bind(tag, st)` |
| region exit `contextExit` (:281-289) | `findExact` on the stack, then `update` or `remove` | `ctx.remove(hc.tag)`: the exiting region is the top entry, so its node is the head, and `removeExact` returns `next` with no allocation |
| dump `rebound` (:411-424) | per dumped context region: `findExact`, then `update` or `remove` | per dumped context region: `remove(hc.tag)`: the dumped regions are the topmost, so removing one head-most exact node per dumped region of a tag removes exactly those |
| park install (:269) | `c.update(hc.tag, st)` in entries order | `c.bind(hc.tag, st)` in entries order, outermost first, so the head is the innermost |
| `rebuilt` (:297-306) | `update` bottom-up | `bind` bottom-up |

`Stack.findExact` has no caller left and is deleted. S2's law ("a region's exit never re-adds a
key owned by a related-tag region") holds because an exit adds nothing. The `stack` parameter of
`rebound` becomes unused and goes; its five call sites shrink by one argument.

**Where it belongs.** `Context.scala`, not `TypeMap`. `TypeMap.get`'s exact-then-oldest rule and
`add`'s replace-on-rebind serve `Env` and `Layer`, where a map is a set of provided services with
one provider per key and no nesting; changing them would change user-visible `Env` semantics for a
need they do not have. The context needs shadowing and region order, which is a list, and the
list primitives already exist in kyo-data as `private[kyo]`. kyo-data is not edited.

**Surface.**

- `Context.scala`: `get` becomes a non-inline walk (it has a loop) returning `Maybe[A]`; `update`
  becomes `bind` (a cons; the old name says replace, which it no longer does); `remove` unchanged;
  `apply`, `contains`, `updateErased` deleted (no main-source caller; `get` covers the tests).
- `Eval.scala`: the five sites in the table; `rebound(entries, ctx)`.
- `Stack.scala`: delete `findExact`.
- Tests: ContextTest restructured onto `get`, `bind`, `remove` with the same values plus one
  shadowing pin; the S10 pin goes green; two new pins (section 6).

Must not change: `TypeMap`, the `loop` signature (the `ctx` register stays; section 9 names the
experiment that would remove it), `Stack.find`, `ContextEffect.handle*`, `Isolate`
(`join` writes the stack and the evaluator rebuilds; the rebuild now conses).

**Forks.** One is presented in section 5 with the recommendation: keep a context list (this
design) against reading from the stack directly and deleting the context. The law itself
(innermost among related, exact or not) is the ruled direction and the alternative, exact first,
is what dispatch does not do, so it is not a fork.

**Interactions.** S3: `rebuilt` after a `Snapshot` node conses the joined states in stack order.
Q3: a park's bindings cons above the resume site's and are removed at their exits, uncovering the
site's own. S9: none. Brackets: every bracket is a `Finalize` region; nested brackets are now
several `Finalize` nodes instead of one replaced key; entry allocates one node as today, exit
allocates nothing where today's `update` allocated a node.

## 2. The multi-shot law

**The question.** `installed` re-owes `entries.owed(i)` onto the live lane on every install
(Eval.scala:268, 275), so a second shot of an outer resume places the same inner snapshot object
in a lane again. If shot one resumed that inner debt and shot two abandons before reaching it,
today's drain releases the inner regions in shot two, although those regions completed in shot
one. Under a per-snapshot mark the drain skips them.

**The two existing pins constrain the `done` side only.** "each shot re-establishes a hooked
region and completes it before the clause continues" fixes `done` as per shot: the captured
`(handler, state)` is pushed again per shot without re-derivation and completes again. "each shot
of a crossing drains the debts it re-installs" asserts `done outer` twice and no release; traced,
its inner debt is settled by the `Say` resume before the `Ask` crossing dumps, so the `Ask`
snapshot carries no inner debt and the pin never drains a re-owed debt. Its name over-claims; its
assertions hold under both laws. Neither pin decides the `release` side.

**The law recommended.** `done` fires once per shot that completes a region. `release` fires only
for regions whose snapshot was never installed, and then at least once (finding 15). A debt settled
by one shot is never drained by a later shot's abandonment. Three reasons, in order of weight:

1. The region instance a hook can observe is `(handler, state)`, and it is shared across shots:
   `installed` pushes the captured state and never re-derives (entry 8). A per-shot release would
   hand the same instance `done` in shot one and `release` in shot two, which is the S9 symptom
   delivered on purpose, and a hook that tracks its own state (a cell) would see a double edge.
2. It is the bracket's law already. A bracket in the inner position completes in shot one and its
   cell's `drain` in shot two is a failed compare-and-set; the mark makes a raw region observe the
   same sequence. Brackets and raw regions agreeing on drains is the consistency the brief asks
   for; refusal (entry 9) stays state-level because the kernel has no resource to close.
3. The carrier that reaches a nested eval and another thread is per snapshot. A per-occurrence
   mark would need a cell per re-owed lane entry and a path from the park to that occurrence,
   which does not exist: the park carries the snapshot.

What "replays are independent" (the skill's representation contract) still means: the entries,
states, and continuations of a snapshot are immutable and every shot replays them; the mark is
bookkeeping about the *debt*, the same role the cell plays for the bracket, and a shot's values
never depend on it.

**The pin that records it** (ContextEffectTest, "reading audit pins"):

```scala
"a debt settled by one shot is not drained when a later shot abandons it" in {
    val log             = ListBuffer[String]()
    var first           = true
    val body: Int < Ask = hooked(log, "cfg", 1)(ask.map(_ + 1))
    val viaAsk: Int < Say = ArrowEffect.handleCont(Tag[Ask], body)(
        [C] =>
            (_, cont) =>
                say("y").map { _ =>
                    if first then
                        first = false
                        cont(1)
                    else -1
                },
        a => a
    )
    val r: Int < Any = ArrowEffect.handleCont(Tag[Say], viaAsk)(
        [C] => (_, cont) => cont(()).map(a => cont(()).map(b => a + b)),
        a => a
    )
    assert(r.eval == 1)
    assert(log.toList == List("done cfg 1"))
}
```

Trace: stack `[Say, Ask, Cfg]`; `ask` crosses `Ask`, dumping `Cfg` into `SI` owed to `Ask`'s lane;
the clause's `say` crosses `Say`, dumping `Ask` with `[SI]` in its lane into `SC`. Shot one installs
`SC` (settled), re-owes `SI` onto `Ask`'s live lane, the clause remainder resumes `cont(1)`, which
installs `SI` (settled): `done cfg 1`, value 2. Shot two installs `SC` again, re-owes `SI`, the
remainder returns `-1`, `Ask` completes and its lane is drained: `SI` is settled and skipped. The
result is `2 + (-1)`. Today's value is `List("done cfg 1", "release cfg 1")`; the pin flips it.

## 3. Cross-thread visibility of the mark

The mark is written by `installed` and read by drains. Three cases:

- *Same thread, nested eval* (S9's pin): program order; the read sees the write. Deterministic.
- *Another thread that the clause joins before returning* (the cross-thread pin in section 6):
  `Thread.join` is a happens-before edge, so the drain at the owner's exit sees the mark. Deterministic.
- *Another thread racing the owner's exit*: no ordering exists in either direction. A stale read
  is always "unsettled": the slot is a single reference store, so a reader sees either the old
  value or the new one, never a torn one, and the new one is only ever written when the snapshot
  is settled. So the only anomaly is a drain that releases a snapshot another thread is installing
  or has completed: `release` after or alongside `done`, which is exactly today's behavior on that
  race and the at-least-once ruling for raw hooks. For a bracket the cell's compare-and-set makes
  the race exact regardless of the mark. A stale read can never suppress a release that is owed.

What each option would cost on the crossing path, where `installed` runs once per resumed
crossing (`foreignCrossingsPayRotation`, 10,000 per op):

| mark | write in `installed` | read in drains | what it buys |
|---|---|---|---|
| plain slot | a reference store | a load and an `eq` | the three cases above |
| volatile | a StoreLoad fence per install | an acquire load per drained snapshot | fewer duplicate releases on the unsynchronized race, for raw hooks only |
| atomic (CAS) | a CAS per install | a CAS per drain to claim | exactly-once on the mark, but not on the hooks: a drain that claimed would have to refuse the concurrent install, which is the state-level refusal entry 9 rejects for raw regions |

Recommendation: a plain slot. The volatile buys nothing a ruling wants, on the path the brief
names as measured; the atomic changes the law. On JS and Wasm there is one thread; on Native the
same reasoning holds under its memory model.

## 4. Where the mark lives

`Stack.Snapshot` is `opaque type Snapshot = Span[AnyRef]` (Stack.scala:233), four slots per region,
built over an array by `wrap`, with a shared `Snapshot.empty`. Four carriers were weighed:

| carrier | allocation per snapshot | reads | verdict |
|---|---|---|---|
| a `final class Snapshot(entries: Array[AnyRef]) { var settled }` | one more object, 24 B | one more indirection per slot | rejected: the crossing path is measured on allocation (Q7 names the snapshot array among the five sampled classes) |
| a write through `Span.toArrayUnsafe` | none | none | rejected: `Span` is the statement that the array is not written; writing through the unsafe accessor so that other holders observe the write is the exact thing the type forbids, and a reviewer stops there |
| a header slot at index 0 | 8 B (one slot) | every accessor gains `+ 1` | rejected on diff size: the four accessors, `Builder.add`, `dump`, `snapshot`, `contextual` all shift |
| a trailing slot | 8 B (one slot) | accessors unchanged; `regions` becomes `(length - 1) / 4` | recommended |

So: `opaque type Snapshot = Array[AnyRef]`, one opaque layer instead of two, since `Snapshot` was
already opaque and private to the kernel and used nothing of `Span` beyond `size` and `apply`. The
extensions:

```scala
def regions: Int      = (self.length - 1) / 4
def isEmpty: Boolean  = regions == 0
def settled: Boolean  = self(self.length - 1) eq TRUE
def settle(): Unit    = self(self.length - 1) = TRUE
```

with `import java.lang.Boolean.TRUE`. The unset slot is the array's own initial state and is
never tested for; the only test is `eq TRUE`. The mark's value is the JVM's boxed `true`, so no
sentinel object and no new vocabulary is introduced; `settle` and `settled` are the words the
kernel already uses for this event. The self-reference variant (`self(last) = self`) was considered
and rejected as a trick a reader has to decode.

Sizes: `dump` allocates `count * 4 + 1`, `snapshot` `size * 4 + 1`, `contextual` `count * 4 + 1`,
`Builder` `regions * 4 + 1` with `result()` returning `entries` when `count == entries.length - 1`
and `copyOf(entries, count + 1)` otherwise (the copy pads the slot with the unset value).
`Snapshot.empty` is `new Array[AnyRef](1)`. The `Span` import leaves `Stack.scala`.

The 8 B per snapshot is the whole representation cost; section 7 nets it against the 48 B the
deleted `toIndexed` copy allocated per resume.

## 5. The one rule for reads, entry, exit, and rebound

**The rule.** The context is the sequence of live context regions on the stack, innermost first,
one node per region. A read and an entry derivation take the first node whose tag is a subtype of
the tag asked for. An exit removes the exiting region's node, which is the head. A dump removes
one node per dumped context region. An install and a rebuild cons in region order. Nothing
recomputes a binding from the stack, because nothing needs to: the list is the stack's context
projection at every point.

Reads (`Context.get`, Eval.scala:58), entry (`derive(ctx.get(tag))`, :169), the exit
(`contextExit`, :286-288) and the dump (`rebound`, :417-418) therefore end up on one rule, and
the two exact recomputations of S2 stop existing rather than being made consistent.

**Where the change lives.** `Context.scala`; `TypeMap` is not edited (section 1.3).

**Pins whose expected value changes.** None of the green ones. The two red pins go green at the
values they already assert. ContextTest keeps its values and loses `apply`, `contains`, and
`updateErased`, whose pins are restated through `get`; "replaces an existing binding" keeps its
assertion (`24`) and is renamed to say shadows, with a follow-up assertion that `remove` uncovers
`42`. Every subtype-tag pin in the suites was checked: ContextEffectTest "an inner region at the
supertype tag leaves no binding behind" (S2) traces to `-1` under the list; the IsolateTest,
ArrowEffectTest, and ArrowEffectMaskTest subtype pins are arrow effects and do not read the
context.

**The fork, with the recommendation.** Reading from the stack directly (`stack.find(tag)` then
`stack.state(idx)`) would delete the context entirely: the `ctx` register in `loop`, `rebound`,
`rebuilt`, the context half of `contextExit` and `installed`, and every node allocation on
region entry. It is the old kernel's shape. It costs a `Tag.<:<` miss per arrow handler standing
between a read and its binding, and `Tag.<:<`'s miss path (`checkTypes(Subtype)` after
`fastPathEqual`) has no number attached in this codebase; it also changes the `loop` signature,
which reaches every benchmark row. Recommendation: the list now, as the S10 fix, because it is
correct by construction, touches no measured row, and reduces allocation on every exit; the stack
read as the one experiment in section 9 that could retire the context, run alone.

## 6. Pins whose expectation changes, and the new pins

**Existing pins that change.**

| suite, pin | today | after | reason |
|---|---|---|---|
| ContextEffectTest "a crossing resumed in a nested eval inside the clause completes its region without a release at the owner's exit" | red, `List("done cfg 1", "release cfg 1")` | green, `List("done cfg 1")` | S9: the drain skips the settled snapshot |
| ContextEffectTest "a read takes the innermost binding whether its tag is exact or a subtype" | red on `subInner`, answers 1 | green, 2 and 2 | S10: the read takes the first related node |
| StackTest "settle removes the debt a resumed dump left" | `takeOwed(0).isEmpty` after `settle` | deleted | `settle` on the stack no longer exists |
| StackTest "settle removes the matching debt wherever it sits in the lane and leaves the others" | one left | deleted | same |
| StackTest "settle finds a debt owed below the top lane" | both lanes empty | deleted | same |
| StackTest "settle finds a debt owed on the eval's own lane" | eval lane empty | deleted | same |
| StackTest "settle of an unrelated snapshot is a no-op" | one left | deleted | same |
| StackTest "a dumped entry's own debts travel inside the snapshot", "takePopped reads the lane of the entry just popped", "owe appends", "oweBelow at the bottom" | as written | unchanged values | accessors unchanged; only `regions` arithmetic moved |
| ContextTest "apply returns the bound value" | `context(Tag) == 42` | restated as `get(Tag) == Maybe(42)` | `apply` deleted |
| ContextTest "contains" group (four pins) | `contains` | restated as `get(...).isDefined` | `contains` deleted |
| ContextTest "through the erased tag binds the same slot" | `updateErased` | deleted | `updateErased` had no main-source caller |
| ContextTest "replaces an existing binding" | `get == Maybe(24)` | same value, renamed "shadows the existing binding, and remove uncovers it", plus `remove` then `get == Maybe(42)` | the list shadows |

No other green pin changes value. In particular unchanged: ContextEffectTest "a handleFirst
remainder re-enters a raw region the region's end already released" (entry 9), "each shot of a
crossing drains the debts it re-installs", "each shot re-establishes a hooked region", both S4
pins; EvalTest "a raw release hook fires once for a region resumed from a dump and then unwound",
"a double abandonment reaches a raw hook twice and a bracket once", "a parked value owes its
regions' releases innermost first"; every EffectBracketTest pin (all cell-exact, and none
evaluates a park and then releases the same park); every IsolateTest pin; EvalConcurrencyTest "a
captured continuation resumes on other threads, each shot independent" (a benign race writing the
same value).

**New pins.**

StackTest, "dump":

- "a snapshot starts unsettled and settle marks it": `dump(1)` then `!snapshot.settled`,
  `snapshot.settle()`, `snapshot.settled`.
- "dump drops the settled debts at the end of the lane before owing its own": dump `first`,
  settle it, dump `second`; `takeOwed(0)` holds `second` only.
- "dump keeps a settled debt that sits under an unsettled one": dump `first`, dump `second`,
  settle `first`, dump `third`; `takeOwed(0)` holds all three in order.
- "the eval lane keeps a settled debt until it is taken": `oweBelow(0, ...)`, settle,
  `takeEvalOwed()` still returns it (the drain, not the lane, skips).
- "the empty snapshot has no regions": `Stack.Snapshot.empty.regions == 0` and `isEmpty`.

ContextEffectTest, "reading audit pins":

- "a debt settled by one shot is not drained when a later shot abandons it" (section 2).

EvalConcurrencyTest (JVM):

- "a crossing resumed on another thread and completed before the owner exits fires done once and
  no release": the S9 program with the clause starting a thread that evaluates
  `answerAsk(0)(cont(41))` and joining it before returning; `List("done cfg 1")`. The join is the
  happens-before edge; the suite already uses threads and joins, which is the deviation from the
  no-blocking rule that suite carries.

EvalTest, "partial evaluation and parking":

- "abandoning a park that was already evaluated reaches no hook": `Eval.partial` a hooked region
  to a park, `p.eval` (done fires), `Eval.release(p, Boom)`: the log stays `List("done")`. Records
  that `release`'s `Park` arm skips settled entries, and why: the regions' lifecycle after an
  install belongs to the eval that installed them, and a later park of the same fiber carries them
  under a new snapshot.

ContextEffectTest, "tag subtyping":

- "an outer exact binding uncovered by an inner exit does not shadow a subtype binding between
  them": regions `Cfg = 1`, `CfgSub = 2`, `Cfg = 3`; a read inside the innermost and one after
  its exit: `(3, 2)`. This is the pin that separates the list from the head-first walk over a
  map; both fix the existing pin, only the list passes this one.
- "a region derives from the innermost related binding": `handleInheritable(Tag[Cfg], 1)(
  handleInheritable(Tag[CfgSub], 2)(handleInheritable(Tag[Cfg], 0, _ + 10)(read at Cfg)))` is `12`
  (today `11`: `derive` sees the outer exact key).

ContextTest:

- "a second binding of the same tag shadows the first and remove uncovers it": `bind(42).bind(24)`
  reads `24`; after `remove`, `42`; after a second `remove`, empty.
- "a read at a supertype takes the innermost related binding": `bind(Sub, 2)` over `bind(Base, 1)`
  reads `2` at `Base`; the reverse nesting reads the exact inner.

## 7. Benchmark rows, the evidence ladder, the allocation delta

**Rows the changes reach**, named before running, per the standard:

| row | reached by | expected |
|---|---|---|
| `foreignCrossingsPayRotation` | `dump` (prune plus the slot) and `installed` (the mark instead of `settle`) once per crossing, 10,000 per op | allocation down about 40 B per crossing: the `settleIn` path's `toIndexed` (`Compact` plus a one-element array, about 48 B) goes, the slot adds 8 B, the prune's `dropRight(1)` on a one-element `Append` allocates nothing; about 0.4 MB/op off 2.24 MB/op; time down by the deleted scan and copy (Q7's JFR named `settle` among the five sampled classes) |
| `emittingClausesPayRegionRebuild` | the same two sites per emitted operation, 1,000 per op | the same per-operation delta, about 40 KB/op |
| `foreignCrossingsAnsweredInPlace` | nothing (the settled answer dumps nothing) | flat; runs as the control that the in-place arm was not disturbed |
| `partialSuspensionBaseline` | nothing unless stopped | flat |
| every other row | `Stack` and `Eval` are shared machinery | flat; the whole class is the unit of the claim |

S10 reaches no existing row: no row reads a context effect, and `rebound` in the crossing rows
matches only `ContextHandler` entries, of which the benchmarks have none, so its loop body is
unchanged there. Two rows are added *before* the change, in their own commit, so both legs of the
bracket carry them (`Jmh extends Test`, so the control leg needs the row too):

- `contextReadsUnderBindings`: `Depth` reads of a context effect under three nested bindings of
  one tag with an idle `Ask` handler between the read and the innermost binding. Reaches
  `Context.get`, entry, and exit. Expected: reads pay one `<:<` hit instead of a `TypeMap.<:<`
  walk plus an exact walk; exits allocate nothing instead of a node.
- `contextRegionsPayEntryExit`: `NarrowDepth` region entries and exits of one tag inside a loop.
  Reaches `bind`, `contextExit`. Expected: one node per entry (as today), zero per exit (today one
  node plus the `findExact` walk).

**The ladder.** Whole `ProtoBench` class on both tips, same session, back to back, `-f 1` to find
suspects, `-f 3` on any row outside the drift band, `-prof gc` on the two crossing rows and the
two context rows to read `gc.alloc.rate.norm` against the predictions above; if a B/op number
disagrees with its prediction, the allocation-site profile on that row before any reasoning; if
time moves on a row where allocation did not, `PrintInlining` grepped for `installed`, `dump`,
`expandOwed`, and `Context$.get`. Run through `bench-harness` so the A/A null and the
three-sha chain are enforced: rows commit, S9 commit, S10 commit, each step attributable. The
`Debugger` gate is off. Report every row with its error, sorted by delta, in the standard table.

**The claims that need a number before they are claims.** The 40 B per crossing above is derived
from class layouts read, not measured; the time delta is a hypothesis from Q7's profile; the
context rows have no baseline. None is reported until the run.

## 8. The edit sequence

Each edit with the sentence to say when applying it. Order is dependency order: the
representation first so everything compiles against it, then the sites that use it, then the
independent S10 change, then tests. Rows for section 7 are added in a commit before edit 1.

1. `Stack.scala`, `object Stack`: `opaque type Snapshot = Array[AnyRef]`; delete `wrap`; drop the
   `Span` import; add `import java.lang.Boolean.TRUE`. "The snapshot needs one writable slot, and
   an opaque array is the one type here that admits a write; Span was the promise there is none."
2. `Stack.scala`, extensions: `regions = (self.length - 1) / 4`, `isEmpty = regions == 0`, add
   `settled` and `settle()`. "The mark is the last slot, read by `eq` against the boxed `true` and
   never tested against its unset state."
3. `Stack.scala`, `Snapshot.empty = new Array[AnyRef](1)` and `Builder` sizes `regions * 4 + 1`,
   `result()` keeping the slot. "Every snapshot carries the slot, so `regions` is one arithmetic
   everywhere."
4. `Stack.scala`, `dump`, `snapshot`, `contextual`: allocate `* 4 + 1`. "Same arithmetic at the
   three builders on the stack."
5. `Stack.scala`, delete `settle` and `settleIn`. "The debt is settled on the snapshot, so no lane
   is scanned; the pin that needed the scan is now the S9 pin."
6. `Stack.scala`, `dump`: before appending, drop the settled debts from the end of the lane
   (`@tailrec` on `lane.last.settled` with `dropRight(1)`). "The lane keeps only retention as a
   job, and a LIFO consumer holds one snapshot in it at a time without a scan or an allocation."
7. `Eval.scala`, `installed`: replace `stack.settle(entries)` with `entries.settle()`. "The one
   writer, at the line the scan sat, after `reenter` so a refused park stays owed."
8. `Eval.scala`, `expandOwed`: skip a settled snapshot and do not enter its `owed(i)`. "The one
   reader: a settled snapshot's regions are the installing eval's, and so are its inner debts,
   which that eval re-owed onto live lanes."
9. `Eval.scala`, `release`, the `Park` arm: guard the entries walk with `!entries.settled`. "The
   same test at the one place a snapshot is read without going through `expandOwed`."
10. StackTest: delete the six `settle` pins, add the five in section 6. "The representation pins
    follow the representation."
11. ContextEffectTest: the S9 pin unchanged; add "a debt settled by one shot is not drained when a
    later shot abandons it". EvalTest: add the evaluated-park pin. EvalConcurrencyTest: add the
    joined-thread pin. "The law of section 2 and the two cases section 3 calls deterministic."
12. `Context.scala`: `get` as a `@tailrec` walk taking the first `<:<` node; `update` renamed
    `bind` and consing `TypeMap.Node`; delete `apply`, `contains`, `updateErased`. "The context is
    the stack's context regions in order; a read is the first related node, exactly what dispatch
    does on the stack."
13. `Eval.scala`, the `Handle` arm, `installed`'s `install`, `rebuilt`: `update` to `bind`. "Same
    call, new name, because it conses."
14. `Eval.scala`, `contextExit`: replace the `findExact` and the `update`/`remove` with
    `ctx.remove(hc.tag)`. "The exiting region is the top entry, so its node is the head, and
    removing it uncovers whatever was below without recomputing anything."
15. `Eval.scala`, `rebound`: drop the `stack` parameter and the `findExact`; `c.remove(hc.tag)` per
    dumped context region; fix the five call sites. "One node per dumped region, removed from the
    head, because the dumped regions are the topmost."
16. `Stack.scala`: delete `findExact`. "No caller."
17. ContextTest: restate the `apply` and `contains` pins through `get`, rename the replace pin,
    add the two list pins. ContextEffectTest: the S10 pin unchanged, add the uncovered-binding and
    the derive pins. "The pins that separate a region-ordered list from a map."
18. Evidence: clean batch build (`kyo-kernelJVM/clean` then `compile`), the proto suites on JVM,
    then the bracket of section 7, then JS and Native in the final sweep the backlog schedules.

Adjudication preview for `flags.sh`: one cast changes, `n.value.asInstanceOf[A]` in `Context.get`
replacing today's `self.get[Any](...).asInstanceOf[A]`, erasure-forced at the untyped-map
boundary; the `Snapshot` accessor casts are unchanged (array element re-typing at the storage
boundary). No new lift summon: `Maybe(...)` is not the pending lift, and neither edited file gains
a `map`.

## 9. What reading could not decide, and the single experiment for each

1. **Whether reads should come from the stack instead of a context list**, retiring `Context`,
   `rebound`, `rebuilt`, and the `ctx` register. Decided by the miss cost of `Tag.<:<` against an
   unrelated concrete tag, which `checkTypes(Subtype)` pays after `fastPathEqual` fails and which
   nothing here has measured. Experiment: on the S10 tip, a throwaway variant where
   `SuspendContext` resolves through `stack.find` and `stack.state`, run on
   `contextReadsUnderBindings` with the idle handler count varied 0, 1, 8; the row decides whether
   the list is retained or the context is deleted in a follow-up with its own bracket.
2. **The size of the time delta on `foreignCrossingsPayRotation`.** The allocation delta is
   derived from layouts; the time delta rests on Q7's profile naming `settle`. Experiment: the
   bracket of section 7, `-f 3`, `-prof gc`, and if the time delta is inside the band, the
   allocation-site profile on both legs to confirm `Compact` left the profile.
3. **Whether `installed` stays the same size to the JIT** after the call to `settle` is replaced
   by an array store and the `dump` gains a loop. Experiment: `PrintInlining` on the crossing row,
   grepped for `installed$1`, `dump`, and `expandOwed`, both legs, byte counts side by side.
4. **How often the unsynchronized cross-thread race produces a duplicate release** under the plain
   mark, which is informational (the law admits it). Experiment: an EvalConcurrencyTest stress
   loop of the S9 program with the worker thread not joined, counting `release` per 10,000 runs on
   the tip before and after; the number is recorded, not gated.
5. **Whether any consumer above the kernel relies on a per-shot release of a raw region** once
   kyo-core moves onto the proto; nothing in the kernel does, and `Handler.release` is
   `private[kyo]`. Experiment: none now; the check is a grep of `ContextEffect.handle(` call
   sites passing `release =` when the swap plan's phase that ports `Sync` runs, against the pin of
   section 2.
