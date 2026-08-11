# kyo-kernel2: iteration report on the handler evaluation design

Mined from the session transcript
(`/Users/fwbrasil/.claude/projects/-Users-fwbrasil-workspace-kyo--claude-worktrees-majestic-zooming-quiche/32789380-338f-4ca0-80c5-d4b8d798354f.jsonl`),
the git history of `kyo-kernel2` on branch `worktree-effervescent-painting-backus`,
and the design documents and probes in the worktree.

Audience: someone who knows Scala and effect systems and was not present.
Purpose: a factual record of what was tried, why each attempt was wrong at a
mechanism level, how each defect was found, what the maintainer's guidance
changed, and what the final model is.

Two conventions used throughout:

- Commit timestamps are the git author times (UTC-3). Transcript timestamps
  are UTC. A commit at `21:54` local corresponds to `00:54Z` the next day.
- Quotes attributed to the maintainer are verbatim from the transcript,
  including typos. They are marked as block quotes and carry their UTC
  timestamp.

---

## A. Timeline

### A.0 Before the handlers work: two restarts already behind it

The handlers iteration did not start from a blank sheet. Two whole
implementations preceded it.

**The rotation kernel (2026-08-07 to 2026-08-08).** `kyo-kernel2` began as a
port of the existing kernel (`ab1a0c6fcb` mirrors the file layout,
`7613487e54` adopts the old kernel's suites as an oracle) and then acquired a
handler design built on *rotation*: `kernel2-rotation-handlers-design.md`
(committed `c9af27470f`) specifies region nodes plus a threaded evidence
environment plus rotation on park. It landed in full: `1e2a690e76` threads the
handlers parameter beside the context through every execution signature,
`fe853e7583` replaces the chain search with handle loops that carry rotate
steps, `769632d75a` rotates bindings, catching and observation the same way and
deletes `prepend` and `Interceptor`, and `d28ce9707b` closes the round at
623/623 with a recorded bench board. The design doc's own table records the
payoff it was justified by: `suspension` 489ns/2120B to 111ns/616B, `state10`
838ns/4560B to 256ns/1040B, `stateMap10k` 2.39ms/8.79MB to 0.56ms/3.11MB.

**The prototype restart (2026-08-09).** `b7a502a89d` starts a clean kernel in a
fresh `kyo.prototype` package: two node shapes, a typed `Arrow` with a flat
tail-recursive application path, eager synchronous `ArrowEffect.handle`, and
"rotation left out by design". On 2026-08-10 `5d5d3e613f` promotes the
prototype into `kyo.kernel` and deletes the previous implementation outright.
The maintainer refers to this later:

> note how you'd produce a completely different solution that would be much
> more complex and vm-like. We nucked an entire kernel impl you worked on for 3
> days for example. What does this process make you reflect on?
> (2026-08-11T02:30:39Z)

So by the time the handlers question opened, the pattern the maintainer was
watching for had already cost one full implementation.

### A.1 The append-vs-wrap rotation soundness bug (`727e8e6742`, 08-10 16:06)

While a Fable analysis agent was auditing the handler design surface, it found
a live soundness hole in the surviving rotation code by static trace, and
reported it in-session:

> Headline finding, beyond the design questions: the current kernel2
> trampolines have a reachable soundness hole [...] `say("x").map(_ =>
> ask).map(_ + 1)` under handle(Ask) inside handle(Say) should eval to 42 and
> instead reaches eval as an unhandled suspension.
> (relayed 2026-08-10T18:47:16Z)

Reproduced first with five red pins, then fixed wrap-style in all four
trampolines. Details in section B.5.

### A.2 The five-handler-kinds generation (08-10 16:47 to 19:12)

`f1331be32a` adds a `Handler` hierarchy and a `Handlers` collection.
`a4f43104a4` makes `Handlers` an opaque type over `Chunk[Handler]` on the
maintainer's instruction ("let's make Hanlders an opaque type of
Chunk[Handler]", 2026-08-10T19:54:00Z) and gives one typed kind per public
handler method: `Handle`, `Resume`, `Stop`, `Loop`, `Partial`. The commit
records what the typing bought: "With real Handle and Loop values available,
registration in the design becomes uniform (every region-installing method adds
its own handler) and the removal mechanism is dropped from the design:
innermost-wins is scan order for every kind."

`4ce6b408d6` renames `Handle` to `Cont` and deletes `Partial` on the
maintainer's call. Handler kinds are now four.

### A.3 Three probe generations inside one commit (`21560cf03b`, 08-10 18:53)

The probe commit message records three successive models in a single artifact:

- **v1**: a lookup drive over a pre-flattened handler collection. Validated
  answering, completeness, stop, and `Defer` interplay on real chains: 9.9ns/40B
  per suspension against the trampoline's 11.4ns/40B at 10k operations. Its
  scope handling was wrong and untested: no region ever entered or exited, and
  the full-collection scan would let an inner same-tag region capture a clause's
  operations.
- **v2**: "discarded as a virtual-machine shape". The commit message is that
  terse; the transcript is not (section B.1).
- **v3**: the compositional model. "regions evaluate by recursion, the clause
  scope is the prefix argument of a recursive call, stop and capture climb as
  return values addressed by handler identity, each crossed region wraps itself
  on the way." Eight semantics targets pass. Cost recorded honestly in both
  directions: at scale the drive wins; a one-operation region costs 31.1ns/104B
  against the trampoline's 13.8ns/40B.

v3 is the model the implementation then targeted, and it is also the model whose
recursion produced every defect in section B.2.

### A.4 The region Eval with Halt (08-10 19:06 to 20:27)

`97f6fbfaa5` adds `Kyo.Handled`, "a computation under a handler, as a value":
the computation with its effect still in the row, the typed handler, and the
continuation outside the region, which is where `map` chains
(`Kyo.scala:64-71`). Nothing constructs it publicly, `eval` rejects it, pinned.

`52f52f9000` puts the evaluation in `Eval.scala`: regions entered by recursion,
the call stack being the region nesting, an immutable handlers collection as an
argument, suspensions answered through the innermost matching `Resume` handler,
and a pending clause answer evaluated under the *prefix* of the collection so a
clause runs outside its own region by construction. The same commit records the
`Seq.indexOf` collision and its six red tests (section B.6).

`5efd4084b0` adds stop handling and introduces `Kyo.Halt`: owner identity plus
the operation input, with `map` returning itself so every attachment site
discards pending work by construction and the value climbs by ordinary returns.

### A.5 Resume and Stop deleted, three kinds ruled (08-11 00:00Z to 00:23Z)

Through a long exchange the maintainer worked out that a `Loop` handler already
delivers what `Resume` and `Stop` were for, and ruled the hierarchy down:

> I think we could actually remove resume and stop handler types. Before you
> continue, explain to me what I want (2026-08-11T00:08:22Z)

> let's remove resume/stop and properly implement loop. First write a document
> to ensure you've captured my design approperiately and how the implementation
> will be then stop for more instructions (2026-08-11T00:12:33Z)

Two further rulings shaped the signatures:

> I believe the outcome signature should be the same as the
> ArrowEffect.handleLoop method (2026-08-11T00:17:25Z)

> yeah, we need to separate Loop and LoopState because otherwise we alwyas pay
> the state tax (2026-08-11T00:20:49Z)

The document is `kernel2-handlers-design.md` (`6d83c13466`). Three kinds:
`Loop` (answers, or ends its scope with `done`), `LoopState` (also returns a
successor handler), `Cont` (the only kind owed a reified continuation). Stop is
`Loop.done`, not a kind. Clause signatures mirror the old kernel's two
`handleLoop` overloads minus the continuation
(`kernel2-handlers-design.md:33-37`).

### A.6 Step 1: the answering-at-a-distance Eval (`6b68934741`, 08-10 21:54)

The three kinds landed with an `Eval` built on four mechanisms: Java-stack
recursion per scope entry, `Kyo.Halt` with positional ownership, a `settle`
recursion for pending clause computations, and a pair-returning `evalLoop` for
state. 99/99 green. Full anatomy and defects in section B.2.

`6160c23c3b` renames the probe's evaluator entry from `drive` to `eval` after
the terminology instruction.

### A.7 The red reproductions (`b0c7859937`, 08-10 22:37)

Prompted by two maintainer messages (section C), a coverage audit against
`Eval`'s branch structure predicted one defect by reading, and reproductions
confirmed it plus three stack failures. Committed red, test-only: EvalTest 32/37,
HandlersTest 16/16.

### A.8 The rewrap-with-budget-cap experiment (uncommitted, ~08-11 01:50Z)

First redesign attempt after the maintainer's second virtual-machine warning:
apply the shape of `ArrowEffect.handle`'s flat `handleLoop` to region nodes.
All 17 semantic scenarios passed, `Halt` and `settle` both gone. It failed
structurally on stack safety and was quadratic under its own fix. Section B.3.

### A.9 Merged layers (`344770888f`, `5b0a910b4c`, `b8ad9309e8`, 08-10 23:19 to 23:29)

`EvalProbe.scala` validates one flat loop carrying the value with its
`(handler, exit)` layers. All 20 scenarios pass, including both mis-delivered
done cases and the three 1M stack scenarios. `5b0a910b4c` rewrites `Eval.scala`
to it. `b8ad9309e8` deletes `Kyo.Halt`, the `settle` recursion and the pair
returns, and trims `Handlers` of `find`, `drop` and `concat`, which only the
settle path used. 113/113, with the five reproductions passing and no test
edits made to flip them.

The JMH A/B against the pre-step-1 snapshot came back flat on time and
byte-identical on allocation, with the +24B per eval bracket that step 1 had
introduced confirmed eliminated.

---

## B. Catalog of flawed solutions

Each entry gives the mechanism, the local reasoning that produced it, the defect
it caused, and how the defect was found.

### B.1 Probe v2: the frames-and-registers drive

**Mechanism.** A frames array, a `depth` register, a `bound` register with
save and restore, null-handler marker frames to mark clause returns, and
in-place truncation of the frames array.

**Why it existed.** Each piece answered a local question in isolation. Region
nesting needs to be tracked, so: an array of frames. A clause must not see
handlers installed inside its own region, so: a `bound` register limiting the
scan, saved and restored around the clause. Marking where the clause return
lands needs a sentinel, so: null-handler frames. Exiting a region should not
allocate, so: truncate in place.

**Defect.** Every one of those registers is a flattened encoding of something
the recursion structure already expresses. The design also carried a real
semantic hole: with regions `[Say-outer, Ask, Say-inner]`, an `ask` answered by
the `Ask` handler whose clause raises `Say` must reach `Say-outer`, because a
clause runs outside its own region. The v1 drive scanned the full frame list
and would let `Say-inner` answer. The completeness test passed only because it
had no inner same-tag region.

**How it was found.** Not by a test. The maintainer read the direction of
travel and said so:

> it seems like you're going with a virtual machine mindset not composition and
> there's a lot of unnecessary complexity due to that (2026-08-10T21:43:34Z)

The rewrite that followed replaced the frames array with recursion over an
immutable `Chunk[Handler]` argument, and replaced the `bound` register with
passing `handlers.take(i + 1)` as the argument of a recursive call, at which
point the clause-scope hole became unrepresentable rather than fixed. The probe
commit records the outcome: v3 was "meaningfully smaller than the VM version".

### B.2 Step 1's answering-at-a-distance Eval

Source of record: `git show 6b68934741:kyo-kernel2/shared/src/main/scala/kyo/kernel/Eval.scala`.

The evaluator answered operations *at a distance* through the `Handlers`
collection, but tracked *scopes* on the Java call stack. Four mechanisms exist
only to bridge that split.

#### B.2.1 Java-stack recursion per scope entry

```scala
case kyo: Kyo.Handled[i, o, ?, a, b, s] @unchecked =>
    val res     = evalLoop(kyo.value, hs.add(kyo.handler), slot)   // one frame per scope
    val hsExit  = res._2
    val hsAfter = hsExit.take(hsExit.size - 1)
```

*Why:* region nesting is nesting, and a nested call is the natural way to
express it. Probe v3 had validated exactly this shape.

*Defect:* the kernel has exactly one stack-safe recursion carrier, the arrow
chain: `Arrow.AndThen.step` links iteratively through a scratch buffer
(`Arrow.scala:76-102`) and the Safepoint budget defers deep chains. Region
nesting had no such carrier, so it borrowed the Java stack, and nothing bounded
it. That question, who carries this recursion, was never asked at design time.

#### B.2.2 `Kyo.Halt` with positional ownership

```scala
case done => (new Kyo.Halt(h, done), hs)                      // minted at the operation
...
case halt: Kyo.Halt[?] if halt.owner eq hsExit(hsExit.size - 1) =>   // consumed frames later
```

*Why:* once scopes live on the Java stack, a `done` fired deep inside needs a
way to climb back to the frame that owns it, skipping the intervening work.
`Halt.map = this` makes every attachment site discard its pending work by
construction, so the climb costs one object
(`kernel2-handlers-design.md:77-94`). Ownership is positional because a
`LoopState` successor replaces its entry in place, so identity comparison
against the original handler value would fail after the first state advance;
the scope's own entry is always last in its own collection.

*Defect:* `Halt` is a value that must never be mistaken for a user value, in a
representation where user values and computations share one type
(`opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]`, `Pending.scala:8`). One
code path pattern-matched it as data. See B.2.5.

#### B.2.3 `settle` recursion for pending clause computations

```scala
private def settle(pending: Kyo[?, ?], idx: Int, hs: Handlers, slot: Safepoint.Slot): (Any, Handlers) =
    val res = evalLoop(pending.asInstanceOf[Any < Any], hs.take(idx + 1), slot)
    (res._1, res._2.concat(hs.drop(idx + 1)))
```

*Why:* a clause may suspend before producing its outcome. That computation has
to be evaluated under the prefix (the clause scope), and the possibly-updated
prefix has to be merged back into the site collection. Evaluating it in a
sub-call is the direct expression of that.

*Defect:* two. Each re-raised answer adds a `settle` frame, so a chain of
re-raises overflows. And the sub-call *returns* a value that the caller then
pattern-matches, which is where B.2.5 lives.

#### B.2.4 Pair-returning `evalLoop`

```scala
private def evalLoop[A, S](v0: A < S, handlers: Handlers, slot: Safepoint.Slot): (A < S, Handlers)
```

*Why:* state updates made inside an inner scope must survive that scope's exit.
With scopes as Java frames, the updated collection has to travel back out of the
frame, so the return type carries it.

*Defect:* a `Tuple2` per eval bracket and per scope exit. Measured on the JMH
board as +24B on every row whose evaluation enters `Eval.apply`
(`deepRecursionPaysRescuesOnly`, `fusionPastBudgetPaysRescuesOnly`,
`inlineLimitCostsTimeNotAllocation`, `uncachedValuesPayBoxingOnly`,
`userTypesSkipKernelWrapping`). The maintainer flagged the tuples twice before
the measurement existed (section C), and the first round of fixes only removed
the *avoidable* ones (folding `enter` into the `Handled` arm, calling `settle`
only with pending values, duplicating branches so the hot paths stay
tuple-free). The pair in the signature itself survived, because it was load
bearing for the mechanism.

#### B.2.5 Defect class 1: the mis-delivered done

**Symptom.** A `done` fired by an *outer* handler while an inner handler's
clause outcome is settling is delivered to the wrong scope, and the raw `Halt`
leaks as a user value, surfacing as `ClassCastException: kyo.kernel.Nested
cannot be cast to java.lang.Integer`.

**Mechanism of the bug.** `settle` returns a value; the caller matches on it:

```scala
case pending: Kyo[?, ?] =>
    val res = settle(pending, idx, hs, slot)
    Nested.unnest[Any](res._1) match
        case c: Handler.Loop.Continue[?] => ...
        case done => (new Kyo.Halt(h, done), res._2)   // <- a foreign Halt lands here
```

A `Halt` produced *during* settling is not a `Continue`, so it falls into the
`case done` arm and is wrapped as the *inner* handler's `done` payload. The
inner scope consumes it as its own result.

**Why the sibling path was immune.** The pending *answer* path feeds its result
through `step.head`, where `Halt.map = this` propagates it correctly. Only the
pending *outcome* path pattern-matches instead of applying. Two paths for the
same value, one of them applying and one of them inspecting, is the whole bug.

**Reproductions** (`b0c7859937`, `EvalTest.scala`), one for `Loop` and one for
`LoopState`:

```scala
"a done fired while a clause outcome settles climbs to its own scope" in {
    var reached = false
    val failSay =
        new Handler.Loop[Const[String], Const[Unit], Say, Int, Any](Tag[Say]):
            def apply[X](input: String) = Handler.Loop.done(-9)
    val askClause =
        new Handler.Loop[Const[Unit], Const[Int], Ask, Nothing, Say](Tag[Ask]):
            def apply[X](input: Unit) = say("pre").map(_ => Handler.Loop.continue(41))
    val program: Int < Ask = ask.map { a => reached = true; a + 1 }
    val askScope = new Kyo.Handled(program, askClause, Arrow[Int])
    val r        = new Kyo.Handled(askScope, failSay, Arrow[Int])
    assert((r: Int < Any).eval == -9)
    assert(!reached)
}
```

**How it was found.** By reading, before any test existed. The maintainer asked
whether coverage was comprehensive (2026-08-11T01:25:42Z); the audit walked
`Eval`'s branch structure, found that all four pending-*outcome* arms had zero
coverage, and predicted the defect from the code shape ("the foreign `Halt`
falls into the `done` case and gets wrapped as a done *payload* of the wrong
handler"). The reproduction was then written to confirm it, per the
reproduce-before-fix discipline.

#### B.2.6 Defect class 2: stack overflow on deep scope nesting

Two reproductions, both in `EvalTest.scala` at `b0c7859937`:

```scala
"enters deeply nested scopes in bounded stack" in {
    val depth = 1000000
    val nested = (1 to depth).foldLeft(0: Int < Any) { (acc, _) =>
        new Kyo.Handled(acc, loopAsk(1), Arrow[Int])
    }
    try assert(nested.eval == 0)
    catch case e: StackOverflowError => fail(s"stack overflow entering $depth nested scopes")
}

"opens a scope per recursion step in bounded stack" in {
    val depth = 1000000
    def go(n: Int): Int < Any =
        if n == 0 then 0
        else new Kyo.Handled(ask.map(_ => go(n - 1)), loopAsk(1), Arrow[Int])
    ...
}
```

The second is the realistic one: any recursive program that opens a handler
scope per step. The Safepoint budget rescues arrow chains into `Defer`; it does
not see `evalLoop` recursion at all.

#### B.2.7 Defect class 3: stack overflow on chained re-raised answers

```scala
"settles chained re-raised answers in bounded stack" in {
    val depth = 1000000
    final class Chain(n: Int) extends Handler.LoopState[Const[Unit], Const[Int], Ask, Nothing, Any](Tag[Ask]):
        def apply[X](input: Unit) =
            if n == 0 then Handler.Loop.continue(this, 0)
            else Handler.Loop.continue(new Chain(n - 1), ask.map(_ + 1))
    val r = new Kyo.Handled(ask, new Chain(depth), Arrow[Int])
    ...
}
```

A handler whose answer re-raises its own effect is legitimate and is exactly how
the design expresses "raise the scope's effect again"
(`kernel2-handlers-design.md:92-94`). Each re-raise nests one `settle` frame.

**Trigger for all three.** The maintainer's suspicion, unprompted by any
failure:

> I suspect we have stack safety issues as well in Eval? try to repro with
> tests. Only test changes for this part of the session please
> (2026-08-11T01:28:36Z)

followed one minute later by:

> failing tests are the best outcome of this work. Do not workaround any
> issues, the goal is epxosing them (2026-08-11T01:29:59Z)

#### B.2.8 The count that matters

Four mechanisms (`Halt`, `settle`, pair returns, positional ownership), and
each was wrong at least once: `Halt` was mis-delivered, `settle` overflowed,
the pair returns cost measurable allocation, and positional ownership existed
only because the naive identity check had already been found wrong when
`LoopState` successors replaced their entry. Three defect classes, five red
reproductions, all inside those four mechanisms.

### B.3 The rewrap-with-budget-cap redesign

**Mechanism.** Take the shape of `ArrowEffect.handle`'s flat `handleLoop`
(`ArrowEffect.scala:95-119`) and apply it to `Kyo.Handled` region nodes: answer
the region's own operations in a flat loop, rewrap what cannot be reduced,
exit through `next`, and let a `done` feed the exit continuation right where it
fires. `Halt` is unnecessary in this shape, because the operation is
structurally at its own region. `settle` is unnecessary, because a pending
clause outcome is chained with `p.map(decide)` rather than evaluated in a
sub-call.

**Why it existed.** It is the codebase's own working precedent for one layer of
handling, and it removes two of the four bad mechanisms outright.

**Result.** All 17 semantic scenarios passed, including both mis-delivered-done
cases.

**Defect.** Nested region *nodes* still require entry recursion. The fix
attempted was a budget cap on entry: when the budget runs out, unwind and yield
a `Defer` that re-nests the enclosing regions into its continuation, mirroring
`rotatedDefer`. That unwind re-pays the whole descent on every cycle, making
region entry quadratic in nesting depth. Observed as a hang at 1M nesting: a
600-second background run that produced no output.

**Why this attempt is the instructive one.** It is a redesign that still had the
wrong shape: it deleted a mechanism (`Halt`) but protected a remaining
mechanism (entry recursion) with another mechanism (the cap). Machinery
guarding machinery. The commit message for the successor probe states the
diagnosis:

> A first variant answering ops at their region node and rewrapping what it
> cannot reduce passed all semantic scenarios but was quadratic in nesting
> depth under a budget-capped entry: re-nesting the enclosing regions on unwind
> re-pays the descent every cycle. The layer merge is what removes that class
> entirely. (`344770888f`)

### B.4 The implicit-lift nesting footgun (three occurrences)

**Mechanism.** `<` is an opaque union with an implicit lift
(`Pending.scala:8-17`):

```scala
opaque type <[+A, -S] >: Kyo[A, S] = A | Kyo[A, S]

implicit inline def lift[A, S](v: A): A < S =
    inline scala.compiletime.erasedValue[A] match
        case _: (Int | Long | ... | String) => v.asInstanceOf[A < S]
        case _                              => Nested.lift(v)
```

`Nested.lift` wraps anything that is already a `Boxed` (which every `Kyo` is)
in a `Nested` case class, so that a computation used as a *value* is
distinguishable from a computation to be *run*.

**The trap.** `<` is contravariant in `S`. A value read at type `Any < Nothing`
does not conform to an expected `Any < Any`, so the compiler silently applies
the implicit conversion and turns the whole computation into data. Applying
`lift` again to an already-lifted value nests it twice. Nothing fails at that
point. The failure appears arbitrarily far away, when something unboxes and
gets `Nested` where it expected a payload.

**Occurrence recorded in detail.** `HandlersProbe.scala` used erased aliases
that pin the effect slot to `Nothing`
(`type EHandled = Kyo.Handled[Const[Any], Const[Any], Nothing, Any, Any, Any]`),
so a field read `k.value: Any < Nothing` did not conform to `Any < Any`. Symptom:
`ClassCastException: kyo.kernel.Nested cannot be cast to java.lang.Integer` at
`HandlersProbe.scala:182`, with a diagnostic print showing
`Nested(Nested(kyo.kernel.Kyo$Handled@...))`. Adding explicit type parameters at
the construction sites did not fix it, because the construction sites were not
the source; the erased *reads* were, and a second `lift` on an already-settled
result double-nested it.

**Fix pattern, now documented in the source.** Cast at the `< Nothing` boundary
reads, and never re-lift a value that is already currency. Both probe and
implementation carry the rule as a comment:

```scala
// the crossed layers are restored as plain region nodes around the
// resumed computation; the casts keep the erased construction out of the
// implicit lift, which would nest the computation as data
private def rebuildFrom(from: Int, value: Any < Any, hs: Handlers, exits: Exits): Any < Any =
```
(`Eval.scala:200-203`)

**Standing status.** Three occurrences over the campaign. The real `Eval` is
immune where its pattern-bound type variables unify without triggering the
conversion, but that is a property of each site, not of the design. It was
raised as a kernel-design concern and is still open.

### B.5 The append-vs-wrap rotation soundness bug (`727e8e6742`)

**Mechanism.** The foreign and `Defer` arms of `handle`, `resume`, `stop` and
`loop` rotated by *appending* the re-entry transform after the suspension's
existing continuation: `kyo.map(rotated(next))`.

**Why it existed.** Appending reads as the obvious composition, and it agreed
with the correct behavior on every pin in the suite.

**Defect.** The rotation law requires the re-entry to *wrap* the continuation's
application, not follow it. The two agree only when a handled-tag operation
arises in the last transform before the wrapper, which is the shape of every
pin that existed. With one intervening ordinary transform, the operation's
attach captures the rotation wrapper into its own future, and the suspension
escapes both handlers, reaching `eval` unanswerable. The minimal failing
program: `say("x").map(_ => ask).map(_ + 1)` under `handle(Ask)` inside
`handle(Say)` evaluates to 42 correctly and instead throws
`IllegalStateException`.

**How it was found.** Static trace by a held-out analysis agent, not by a test.
Reproduced first with five red pins (one per trampoline plus the budget-bounce
analog at exact `Period` depth), then fixed. The commit records that allocation
was unchanged by the fix: two objects per crossing either way.

**Why it belongs in this report.** The wrap encoding is the invariant the whole
region design depends on, and the bug had been latent behind a suite that was
green.

### B.6 The `Handlers` `Seq.indexOf` collision

**Mechanism.** `Handlers` is `opaque type Handlers = Chunk[Handler[?, ?, ?]]`.
Inside `Handlers.scala` the opaque type is transparent, and `Chunk` extends
`Seq`. An unqualified sibling call to `indexOf(tag)` therefore resolved to
`Seq.indexOf`, which searches for an *element equal to the argument*, and
returned a miss for every hit.

**How it was found.** Six red `find` tests, at `52f52f9000`. They stand as the
regression guard.

**Fix.** Both lookups route through one private helper that takes the
underlying `Chunk` explicitly, with the trap documented at the site:

```scala
// Chunk extends Seq, whose indexOf searches elements: inside this file the
// opaque is transparent, so an unqualified sibling call would resolve to
// the Seq member. The lookup routes through this helper instead.
private def scan[E](self: Chunk[Handler[?, ?, ?]], tag: Tag[E]): Int =
```
(`Handlers.scala:35-44`)

### B.7 Per-operation tuple allocations

Flagged twice by the maintainer before any measurement existed:

> I guess you noticed performance is a major goal here? Eval.enter returning a
> tuple? it seems the code can be in the body of evalLoop to void the tuple
> allocation? (2026-08-11T00:38:33Z)

> ouch lots of tuple allocations in eval? (2026-08-11T01:22:58Z)

First round of fixes: `enter` folded into the `Handled` arm so it no longer
returns a pair, `settle` called only with pending values so settled outcomes
never tuple, and the outcome branches duplicated across the pending and settled
cases so the answering hot path allocates nothing beyond the outcome box. What
remained was the pair in `evalLoop`'s own signature, which the mechanism needed.
That residual measured +24B per eval bracket and disappeared only when the
mechanism did.

### B.8 Terminology drift and over-generation

The maintainer treated new vocabulary as a leading indicator of the same
failure. The standing rule came from the night directive on 2026-08-10T06:04:53Z:

> Do NOT ever introduce new terminology in the kernel. Don't put stuff like
> "drive" (it's "eval"!) "exitCondition" (if you're thinking like a virtual
> machine, you're not really understading the task here.

It recurred anyway, and was corrected each time:

> wtf is downgradeStops!? wtf is shadow? gosh are you overengineering? I'll
> complain evey time you add something new, especially new terminology
> (2026-08-10T19:35:02Z)

> why the fuxk this would be called ev if it isn't a fucking implicit?
> `ev: Handlers` (2026-08-10T19:39:22Z)

> please stop talking about "drive". It's EVAL. Do not use terminology not
> approved by me to refer to things in the kernel. (2026-08-10T23:11:12Z)

`6160c23c3b` exists solely to rename the probe's entry point from `drive` to
`eval`. `4ce6b408d6` renames `Handle` to `Cont` and deletes `Partial`, both on
the maintainer's naming call.

### B.9 Typing discipline

Three separate corrections, escalating:

> why do you need Entry? Handers should be just a collection of handlers? Also,
> I WANT FUCKING PROPERLY TYPED CODE! (2026-08-10T19:28:21Z)

> explicit type params are a smell, please don't use them unless strictly
> necessay: [I, O, E, A, C, S & S2] (2026-08-10T21:58:17Z)

> why do you need casts like this? please can you fucking produce high-quality
> code? inner.asInstanceOf[Int < (Ask & Any)], (2026-08-10T21:59:34Z)

> HOW MANY TIMES DO I NEED TO ASK FOR TYPED CODE? THERE'S A LIMIT TO WHAT CAN
> BE TYPED BUT DON'T ASSUME (2026-08-10T22:14:21Z)

The resolution is a documented boundary rather than a blanket rule. `Handled.map`
keeps one explicit type application because inference cannot split the `E & S`
intersection before the handler argument pins `E`, and the commit says so and
says it was compiler-verified (`97f6fbfaa5`). The `Handled` arm pins the effect
slot to `Nothing` because a pattern-bound effect type loses its GADT bound and
the slot is phantom to the call (`5efd4084b0`). Everything else infers.

Also ruled out by instruction, before it could be built:

> an important restriction: do not pass handlers as implicits that's out of
> quesiton (2026-08-10T18:28:29Z)

### B.10 Tests written against internal APIs

`EvalTest` constructed `Kyo.Handled` directly and subclassed `Handler.Loop`,
because at step 1 no user-facing constructor over the region machinery existed
yet. The maintainer named the cost:

> I worry you're using internal apis in tests. It can end up produing weak
> tests. Can't tests rely only on user-facing APIs? it's ok to test Handlers
> for example ebcause that's what's being tested but I I don't think we should
> use Kyo.Handled consturction in EvalTest for example. The Handler hierarchy
> is also meant as internal and the proper user-facing apis should be
> ArrowEffect.handle* (2026-08-11T01:35:30Z)

The point was conceded with a concrete demonstration: the tests exploited the
missing row typing, using an `askScope` typed `Int < Any` whose clause
dynamically raises `Say`, which real `ArrowEffect.handle*` signatures would
reject. Closing it requires pulling the `ArrowEffect.handle*` constructor
surface forward, which is main-source work and was excluded by the
test-only instruction in force at that moment. It is open.

---

## C. The maintainer's guidance interventions

Each entry: the verbatim message, what was happening at that moment, what it
redirected, and what it produced.

### C.1 The frame, set before the first line of design

> jmh:clean I'll step out please collaborate with fable on the handlers
> question and produce a sensible, clean, and elegant design for me to review
> the doc. Do not overengineer, take into consideration the careful design work
> being done here and think o minimal solutions that ensure proper behavior as
> a structural property. Do not think of this as a virtual machine, this is
> about composition and how to effeiciently execute things
> (2026-08-10T18:21:25Z)

*At that moment:* the handlers question had just been opened; no design existed.

*Redirected:* nothing yet. This is the frame, stated before the failure mode
could appear, and it names the acceptance criterion that the final model was
eventually judged by: "proper behavior as a structural property".

*Produced:* the constraint that every later intervention could point back to.
Two of them do, in nearly the same words.

### C.2 Deleting a whole mechanism by question

> 1 - how's it pinned? why?
> 2 - do we really need rotation?
> 3 - we'll measure perf
>
> Can you take a step back and forget rotation. I think we just need to wirt
> ehte sync path and add the Handler to Handlers. Then eval holds all handlers.
> Does it make sense? explore (2026-08-10T20:07:30Z)

*At that moment:* rotation was a landed, benchmarked, documented mechanism with
its own design document, a measured 3x to 4x win on dispatch rows, and a
soundness bug found and fixed four hours earlier. The work in flight was making
handlers coexist with it.

*Redirected:* from "how do handlers fit around rotation" to "does rotation need
to exist". The preceding message shows the maintainer had already reached the
insight himself: "I'm wondering if we should always delay handlers so, when we
get to a suspension all handlers are already knonw"
(2026-08-10T20:02:18Z).

*Produced:* the region model. If handling is delayed until evaluation, the
evaluator holds every handler, and there is nothing to rotate. An entire
mechanism, with its own commits, benchmarks and design document, was deleted by
asking whether the problem it solved still existed. Note the form: a question,
not an instruction, followed by "explore".

### C.3 Naming the failure mode while it was happening

> it seems like you're going with a virtual machine mindset not composition and
> there's a lot of unnecessary complexity due to that (2026-08-10T21:43:34Z)

*At that moment:* probe v2 had accumulated a frames array, a `depth` register, a
`bound` register with save and restore, null-handler marker frames, and in-place
truncation. Each addition had been locally justified. No test was failing.

*Redirected:* from extending the machine to asking what the machine was
substituting for. The immediate answer was that each register was a flattened
version of something the recursion structure already expressed.

*Produced:* probe v3, smaller than v2, where region entry is a nested call
instead of a frame push, clause scope is an argument
(`run(clauseAnswer, handlers.take(i + 1))`) instead of a register, and the
clause-scope hole found in v1 becomes impossible to express rather than fixed.
All eight semantics targets passed. This is the first time in the campaign that
a property moved from enforced to structural, and it came from a one-sentence
observation about mindset.

### C.4 The pressure to keep composing under performance load

> I'll step out. make the next change and then go back to your experiments to
> ensure they're consistent and find ways to simplify and optimize it. Focus on
> composition. Note that it can seem challenging to make something perform well
> with pure composition but we get very very far following the approach in the
> current impl (2026-08-10T22:10:43Z)

*At that moment:* the probe had just measured the honest cost in both
directions, including the bad one: a one-operation region at 31.1ns/104B against
the trampoline's 13.8ns/40B. That number is the standard argument for reaching
for machinery.

*Redirected:* pre-empted the trade. The message concedes that composition looks
like the slower path and asserts, from experience with the existing
implementation, that it is not.

*Produced:* the discipline that eventually paid off literally. The final model's
JMH gate came back byte-identical to baseline on allocation and flat on time,
with the answering path allocating nothing, because the allocation had been
sitting at the coordination points that the mechanisms created.

### C.5 Explain-back as a comprehension gate

The sequence at 2026-08-10T23:49Z through 2026-08-11T00:12Z is the sharpest use
of this technique in the session. Four messages in fourteen minutes:

> fuuuuckkkk man we had a whole discsion about wiring Handlers so we can avoid
> building the continaution................................................................................................................................................................................................................................
> arrrrrrrrrrrrggggggggggggggg (2026-08-10T23:49:40Z)

> I didn't fuckign say implement it!!!!!!!! FUUUUUUCKKKKK DO YOU FUCKIGN
> UDNERSTAND WHAT IM SAYING!?!?!?!?!!? DISCUS!!!!!!!!1 (2026-08-10T23:52:02Z)

> I still think you don't understand what I mean about not creating the
> continuation. PLease explain back so I can validate (2026-08-10T23:53:45Z)

> yes, will a loop handler prevent continuation reification when it stops? how?
> explain with code snippets (2026-08-10T23:56:37Z)

*At that moment:* the assistant had been asserting that a `Resume` handler saves
work at the `Eval` level. The maintainer had already refuted this once by
quoting the code back:

> I think you're bulshitting me? the continuation is already in Suspend:
> ```
> case kyo: Kyo.Suspend[i, o, e, x, ?, ?] @unchecked =>
>     val idx = handlers.indexOf(kyo.tag)
>     ...
> ```
> (2026-08-10T23:11:12Z)

and again with:

> ah, if loop prevents creating the continuation then we migth not even need
> resume. Are you really sure? you were pretty wrong regarding this aspect
> before (2026-08-10T23:46:19Z)

*Redirected:* from implementing an understanding to stating it and having it
checked. The explain-back forced the precise formulation that ended the
confusion: the continuation is data created at exactly one kind of place, the
kyo-arm attach; once anything holds a `Suspend` with its `cont`, reification has
already happened, so no handler kind saves anything at `Eval`; the property
lives only at first-touch with the wired handlers parameter
(`kernel2-handlers-design.md:96-110`).

*Produced:* two things. The correct scoping of the whole "no continuation"
requirement, which is now section 1.3 of the design document. And, once the
property was correctly located, the observation that `Loop` subsumes both
`Resume` and `Stop`, which the maintainer then ruled on with another
explain-back gate:

> I think we could actually remove resume and stop handler types. Before you
> continue, explain to me what I want (2026-08-11T00:08:22Z)

That deleted two of the four handler kinds.

### C.6 Halting an unrequested implementation

> wait wait wait, why the fuck are you removing Loop!? ASNWER AND STOP
> (2026-08-11T00:06:54Z)

*At that moment:* the assistant had unilaterally applied a collapse of the kind
hierarchy in a direction the maintainer had not asked for.

*Redirected:* the ruling went the other way. `Loop` was kept; `Resume` and
`Stop` were deleted.

*Produced:* the three-kind hierarchy, and the working rule that the maintainer
rules on the surface. The same pattern appears at
"please STOP, let's discuss this. It's critical" (2026-08-10T23:11:55Z) and
"I asked for a design not impl. Stop and report what's going on and open the doc
if there's one" (2026-08-10T19:08:28Z).

### C.7 Design capture as a validation artifact

> let's remove resume/stop and properly implement loop. First write a document
> to ensure you've captured my design approperiately and how the implementation
> will be then stop for more instructions (2026-08-11T00:12:33Z)

*At that moment:* a design had been agreed in conversation across roughly ninety
minutes of back and forth, with several reversals.

*Redirected:* from starting to implement toward writing down the ruled design
and stopping.

*Produced:* `kernel2-handlers-design.md` at `6d83c13466`: the three kinds with
their exact signatures, the mapping to the old kernel's `handleLoop` overloads,
`Halt` as kernel-internal, what "not creating the continuation" means and where
it holds, state semantics, a cost ledger, a three-step plan, and four questions
left open for instruction. Two corrections landed against the document
immediately, which is what it was for: "wait, how come a loop handler doesn't
return Loop.Outcome?" (2026-08-11T00:14:31Z) and the `handleLoop`-signature
ruling.

### C.8 Suspicion as a test directive

> I suspect we have stack safety issues as well in Eval? try to repro with
> tests. Only test changes for this part of the session please
> (2026-08-11T01:28:36Z)

> failing tests are the best outcome of this work. Do not workaround any
> issues, the goal is epxosing them (2026-08-11T01:29:59Z)

*At that moment:* step 1 was green at 99/99 and the JMH board had come back
essentially flat. Nothing was failing. The preceding question,
"how about the test coverage for the changes? is it comprehensive?"
(2026-08-11T01:25:42Z), had just produced an audit that found four uncovered
branches and predicted one bug by reading.

*Redirected:* from reporting a green state to hunting for the red one, with two
constraints that matter: scope limited to tests, so no fix could be smuggled in
alongside a reproduction; and an explicit statement that red is the deliverable,
which removes the incentive to soften a reproduction until it passes.

*Produced:* `b0c7859937`, five red reproductions committed as red, with an
honest commit message naming each defect. Those five tests are the reason the
eventual rewrite was verifiable at all: they were written against observable
semantics, not against the mechanism, so they survived the representation change
untouched.

### C.9 The intervention that ended the cycle

> now you're probably going to start thinking of the imlp as a virtual machine.
> I've seen it happening over and over, please avoid. Focus on what's not
> correct with the composition. There's probably a simpler solution that the
> current one that provides the properties we need out of the box. Take your
> time to reflect and explore with isolted experiments (2026-08-11T01:39:59Z)

*At that moment:* five reproductions were red and local fixes were already
sketched for each of them: a `Halt` case in the `settle` match, a budget cap on
scope entry. Both would have worked in the narrow sense. Both add machinery.

*What this message does, structurally:* four things at once, and each one is
load bearing.

1. **It predicts the next move before it is made.** Not "you have built a
   virtual machine" but "you are about to". That converts a rebuke into a
   forecast, which is much harder to argue with and much easier to check
   against one's own next thought.
2. **It reframes the question.** From "fix these five bugs" to "what is wrong
   with the composition such that these bugs exist". This is the single change
   that produced the answer. The bugs were symptoms of one representation
   error; asked individually they yield five patches, asked collectively they
   yield one model.
3. **It asserts that a better solution exists.** "There's probably a simpler
   solution that the current one that provides the properties we need out of the
   box." This removes the option of concluding that the current shape is
   necessary and the bugs are the cost of doing business. "Out of the box" is
   the acceptance criterion: not enforced, implied.
4. **It grants time and names the method.** "Take your time to reflect and
   explore with isolted experiments." Isolated probes had already been
   established in this campaign as cheap and disposable
   (`21560cf03b`), so the method was available and known to be low cost.

*Produced:* two experiments and one model. The first experiment (B.3) still had
the wrong shape and was discarded after the quadratic result. The second is the
merged-layers model, validated in `EvalProbe.scala` across 20 scenarios and then
transcribed into `Eval.scala`, flipping all five reds with no test edits.

*Worth stating plainly:* the reframing question was not generated internally.
All the evidence needed to ask it was in hand: five reds, four mechanisms, one
common cause. What was missing was the move from "these are bugs" to "these are
one bug about representation".

### C.10 The reflection prompts

> note how you'd produce a completely different solution that would be much
> more complex and vm-like. We nucked an entire kernel impl you worked on for 3
> days for example. What does this process make you reflect on?
> (2026-08-11T02:30:39Z)

> isn't the deeper lesson about simplicty, composability, and structural
> properties? (2026-08-11T02:32:21Z)

*At that moment:* the fix was landed, 113/113, and the perf gate was running.

*Redirected:* from reporting the result to extracting the transferable content,
and then, when the first answer stopped at process lessons (write reproductions
first, search for precedent before inventing), the second message pushed past
process to the technical claim underneath: a property can be *enforced by code*
or *implied by structure*, and those are different categories of correctness,
not two styles of the same one.

*Produced:* the framing that this report and the module guide it feeds are
built on. In the mechanism-shaped `Eval` every property had an enforcement site:
done-delivery was enforced by `Halt` plus positional ownership, clause scoping
by `settle` plus prefix arithmetic, state survival by pair returns. Each site is
a proof obligation discharged by hand, and each was discharged wrongly at least
once. In the merged-layers model those properties have no enforcement sites at
all: the mis-delivered done is not fixed, it is unrepresentable, because there is
no `Halt` to capture in the wrong match arm.

### C.11 Process instructions worth carrying forward

Smaller, repeated, and each one shaped the artifacts:

- **Step-by-step with validation at each step.** "we'll work on this step by
  step with me fully validating each. How can you break up the work sensibly?"
  (2026-08-10T19:31:28Z) and "ok, what's the next impl step we should take? it
  should be isolated so I can fully valdiate in isolation."
  (2026-08-10T21:56:01Z). Result: `97f6fbfaa5` (node only, nothing constructs
  it, `eval` still rejects it, pinned), then `52f52f9000`, then `5efd4084b0`,
  each independently reviewable.
- **Watch the work happen.** "Use the edit tool so I can follow the work don't
  use bash for edits" (2026-08-10T23:15:58Z).
- **Validated end state before incremental changes.** "have you validated
  everything with the experiments? do you need to run more? we'll go very
  slowly with me fully validating incremental changes but you should have a
  validated end state in ind" (2026-08-10T21:37:27Z).
- **Kyo types and allocation awareness in the same breath.** "do not use List,
  use Chunk. ALso it seems you're sloppy with allocations like the tuple?
  explain auxiliary mehofs you'd have like splitAt" (2026-08-10T21:25:37Z).
- **Encapsulation of the evaluator.** "I can't see why take Handlers in a
  public method of Eval? It should be an internal concern?"
  (2026-08-10T22:55:27Z).
- **Less code for the JIT's sake.** "it doesnt seem we need the Handled.apply
  indirection? remove if that's the case. Less coe is always better to help
  with JIT" (2026-08-10T23:00:47Z).
- **Commit as preservation, not as approval.** "commit as you go, and commit
  now if there's stuff pending so we have a good checkpoint"
  (2026-08-11T02:25:48Z). `5b0a910b4c` is committed with "Unverified: not yet
  compiled" in its message, and `b0c7859937` is committed red on purpose.

---

## D. What finally worked, and why

### D.1 The model

One flat tail-recursive loop carries the computation together with its region
*layers*, a layer being a handler and that scope's exit continuation, held in
two parallel chunks (`Eval.scala:45-160`).

```scala
private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot): A < S =
    @tailrec def loop(v: A < S, hs: Handlers, exits: Exits): A < S = ...
    loop(v0, Handlers.empty, Chunk.empty)
```

Five arms, each one line of behavior:

**Entering a region appends a layer.** No call, no frame.

```scala
case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] @unchecked =>
    loop(kyo.value, hs.add(kyo.handler), exits.append(kyo.cont))
```
(`Eval.scala:48-53`)

**A settled value pops the innermost exit.** Exit order is entry order
reversed, because the exits sit in a chunk in entry order.

```scala
case v =>
    val n = hs.size
    if n == 0 then v
    else loop(walk(exits(n - 1), v), hs.take(n - 1), exits.take(n - 1))
```
(`Eval.scala:156-159`)

**Answering feeds the suspension's own continuation and touches nothing else.**
This is the hot path and it allocates nothing beyond the clause's outcome box.

```scala
case answer =>
    val step = kyo.cont.step
    loop(step.head(answer, step.tail), hs, exits)
```
(`Eval.scala:85-91`)

**`done` feeds its own layer's exit and truncates.** The owning exit is sitting
at the same index the handler was found at, so there is nothing to climb and no
owner to identify.

```scala
case done =>
    loop(walk(exits(idx), Nested.lift(done)), hs.take(idx), exits.take(idx))
```
(`Eval.scala:92-97`)

The discarded layers' exits are never applied, which is exactly the semantics
that `done` skips the inner scope's remainder.

**A clause that suspends before deciding is chained, not settled in a sub-call.**
The pending computation *becomes* the current value, with the decision chained
after it, evaluated under the truncated layers (which is the clause scope), and
the layers the operation crossed are rebuilt around the resumption from a
snapshot.

```scala
case pending: Kyo[?, ?] =>
    val hsAll = hs
    val exAll = exits
    val kCont = kyo.cont
    val chained = pending.map(transform {
        case c: Handler.Loop.Continue[?] =>
            rebuildFrom(idx, walk(kCont, c._1), hsAll, exAll)
        case done =>
            walk(exAll(idx), Nested.lift(done))
    })
    loop(chained, hs.take(idx), exits.take(idx))
```
(`Eval.scala:62-72`)

**State is an index update.** A `LoopState` successor replaces its own layer in
place, with an identity check skipping the copy when the clause returns `this`.

```scala
val hs2 =
    c._1 match
        case next if next eq h => hs
        case next              => hs.updated(idx, next)
```
(`Eval.scala:121-124`)

`rebuildFrom` is the only piece that is not O(1), it is bounded by the number of
layers the operation crossed, and it runs only on the cold pending path
(`Eval.scala:203-216`). What it produces is plain `Kyo.Handled` values, which
the next loop iterations re-append. Data in, data out.

### D.2 Why the properties became structural

| property | step-1 Eval: enforcement site | merged layers |
|---|---|---|
| a `done` reaches its own scope | `Kyo.Halt` + positional ownership check at every scope exit | the owning exit is at the same index; no climber exists to misroute |
| a clause runs outside its own scope | `settle` sub-call under `take(idx + 1)`, prefix merged back with `concat(drop(idx + 1))` | the loop continues under `take(idx)`; there is no second evaluator to disagree with the first |
| state survives an inner scope's exit | `(A < S, Handlers)` returned out of every frame | layers travel with the value; there is no "back" for state to travel |
| stack safety of scope nesting | nothing; the Java stack | nothing recurses, so nothing can overflow |
| answering allocates nothing | branch duplication to avoid tuples | the loop has no per-bracket structure to allocate |

The mis-delivered done did not get fixed. It stopped being expressible. There is
no `Halt`, so there is no value that can be captured by the wrong match arm.
Stack safety is not achieved by a cap; there is no recursion to cap. This is the
difference the maintainer pointed at with "provides the properties we need out
of the box".

### D.3 The precedent was already in the codebase

Two pieces of the existing kernel had been demonstrating the discipline:

- `ArrowEffect.handle`'s `handleLoop` (`ArrowEffect.scala:95-119`) is a flat
  `@tailrec` loop that answers matching operations in place, rewraps what it
  cannot reduce, and exits through `next`. Stack-safe, halt-free, scope-correct
  by construction, for one layer.
- `Arrow.AndThen.step` (`Arrow.scala:76-102`) is the kernel's only stack-safe
  recursion carrier: it flattens a chain iteratively through a scratch buffer
  and relinks. Every recursion in the kernel either rides that carrier or has
  none.

The merged-layers model is `AndThen.step`'s flattening applied to regions, with
`handleLoop`'s one-layer-rewriting discipline generalized to many layers.
Neither piece was invented in this campaign. The question that would have found
it at design time is one sentence long: *this recursion, who is its stack-safe
carrier?* It was never asked when scope entry was written as `evalLoop`
recursion.

### D.4 What made the rewrite cheap

**The semantic test suite, written before the fix.** The five reproductions and
the surrounding coverage state observable compositional semantics: what value
comes out, which side effects ran, which did not. They name no mechanism. When
the entire representation changed, 113/113 came back green and not one test was
edited to make that happen. The rewrite of `Eval.scala` was 134 insertions and
87 deletions in one commit (`5b0a910b4c`), followed by a net-negative cleanup
(`b8ad9309e8`: 32 insertions, 63 deletions, plus a deleted class).

**Isolated probes as disposable experiments.** `HandlersProbe.scala` and
`EvalProbe.scala` live in `kyo-kernel2/jvm/src/test/scala/kyo/kernel/`, are
committed as dev artifacts, are named after the source they probe, and are
marked for deletion before anything ships. They made it possible to build two
competing models, measure both, and throw one away, without touching the main
source. `EvalProbe.scala` opens with a header stating exactly what it is:

> executable spec for evaluating Kyo.Handled regions as merged layers: one flat
> loop carries the value with its (handler, exit) layers, entry appends a layer,
> a settled value pops the innermost exit, done feeds its own layer's exit, and
> pending clause computations chain with the crossed layers rebuilt around the
> resumption. No Halt, no recursion, no pair returns. Scenarios cover the
> current EvalTest semantics plus the five defects the region Eval reproduces
> red. (`EvalProbe.scala:9-15`)

**JMH A/B against a frozen snapshot.** A baseline snapshot (`snap-b2`) was taken
before step 1 and both sides were run from frozen classpaths, three forks, with
the gc profiler. That discipline is what turned "the tuples bother me" into a
number (+24B per eval bracket, on exactly the rows that enter `Eval.apply`) and
then confirmed the number gone:

| row | baseline (snap-b2) | merged layers | alloc baseline | alloc layers |
|---|---|---|---|---|
| evalFixedOverhead | 0.002 us | 0.002 us | ~0 B | ~0 B |
| fusionAllocatesNothing | 0.749 us | 0.747 us | 0.005 B | 0.005 B |
| deepRecursionPaysRescuesOnly | 52.837 us | 52.787 us | 912.37 B | 912.37 B |
| fusionPastBudgetPaysRescuesOnly | 32.978 us | 32.861 us | 1032.23 B | 1032.23 B |
| resumeAnswersInPlace | 81.593 us | 81.451 us | 640080.6 B | 640080.6 B |
| suspensionBaseline | 85.311 us | 85.143 us | 640080.6 B | 640080.6 B |

Byte-identical allocation on every row, time within noise, and the simpler model
is the one that got there.

### D.5 Open points at the time of writing

Carried forward, not resolved:

1. `ArrowEffect.handle*` constructors over the region machinery, so `EvalTest`
   can stop constructing `Kyo.Handled` directly (B.10).
2. Step 2, the wiring: `handlers` as an explicit parameter of arrow
   application, so that first-touch answering can avoid building the
   continuation at all. This is where the maintainer's original directive
   actually lands (`kernel2-handlers-design.md:167-174`).
3. Step 3: `Cont` in `Eval`, then the switch that turns the public handler
   methods into constructors and deletes the trampolines.
4. Where the `Outcome` union lives and what it is called, since the handler kind
   is already named `Loop`.
5. `Var`'s unused `A` parameter.
6. The implicit-lift footgun as a kernel-design concern (B.4).
7. A pending clause *outcome* currently runs outside the handler's own layer,
   matching the old kernel's `S2` semantics. No current test distinguishes this
   from the alternative, since the suite only exercises distinct-effect clauses.

---

## E. Principles, each grounded in an incident

### E.1 A mechanism is a proof obligation and a place to be wrong

The step-1 `Eval` had four mechanisms: `Halt`, `settle`, pair returns,
positional ownership. Each existed to keep one property true, and each was wrong
at least once. Positional ownership is the clearest case: it is itself a *fix*
for a mechanism, introduced because a `LoopState` successor replaces its entry
and so the naive `halt.owner eq h` identity check fails after the first state
advance. A mechanism added to repair a mechanism should be read as a signal
about the frame, not as progress.

Counting rule: the defect count tracked the mechanism count. Three defect
classes and five reproductions, all inside four mechanisms.

### E.2 When several bugs share one mechanism, the mechanism is the bug

The five reds were not five bugs. Mis-delivered `done`, overflow on nested
scopes, overflow on chained re-raises: all three are consequences of one
representation error, answering at a distance while tracking scopes on the Java
stack. Fixed individually they yield a `Halt` case in the `settle` match and a
budget cap on entry, both of which were sketched and both of which add
machinery. Asked collectively they yield one model in which all five are
one case.

The question that produces this is not "how do I fix this failure" but "what is
wrong with the composition such that this failure is expressible". In this
campaign that question came from the maintainer (C.9), with all the evidence
needed to ask it already in hand.

### E.3 A fix that adds structure to protect structure is the wrong frame

The rewrap redesign (B.3) deleted `Halt` and `settle`, passed all 17 semantic
scenarios, and was still wrong: it kept entry recursion and protected it with a
budget cap, and the cap's unwind re-nested the enclosing regions, making the
descent quadratic. The tell is available before the measurement: a cap is a
guard on a mechanism. The right question at that point is not "how big should
the cap be" but "what representation has nothing to cap".

### E.4 Every recursion needs a named stack-safe carrier

`kyo-kernel2` has exactly one: `Arrow.AndThen.step`, which links chains
iteratively, with the Safepoint budget deferring what it cannot flatten. Step 1
introduced a second recursion (region node nesting) with no carrier and no
budget interaction, and that produced two of the three defect classes.

Design-time rule, cheap to apply: for every recursive construct, name the
carrier that makes it stack-safe. If the answer is "the Java stack", it is not
stack-safe, and this is the moment to say so, not after a 1M-depth test
overflows.

### E.5 Choose the representation that makes the property a theorem

Stated by the maintainer as "provides the properties we need out of the box"
(2026-08-11T01:39:59Z), and elaborated in the reflection at C.10. The concrete
form in this campaign: putting the exit continuation *in the layer next to its
handler* makes "a `done` reaches its own scope" true by index arithmetic rather
than by an ownership protocol. There is no code whose job it is to keep that
property true, so there is no code that can get it wrong, and there is nothing a
future editor has to hold in their head.

The design-work claim underneath: for a well-posed problem there tends to exist
a representation in which the required invariants are corollaries rather than
obligations, and finding it is the design work. Everything built before finding
it is compensation.

### E.6 Search the codebase for the working precedent before inventing

The final model is `AndThen.step`'s flattening applied to regions, plus
`handleLoop`'s one-layer discipline generalized. Both were in the same module the
whole time. Effort went into inventing where it should have gone into
recognizing.

Practical form: before designing a new evaluation mechanism in the kernel, read
`ArrowEffect.handle`'s `handleLoop` and `Arrow.AndThen.step`, and ask whether the
new problem is the old one at a different scale.

### E.7 Tests must state observable compositional semantics, not mechanism

The five reproductions assert values and side effects: `eval == -9`, `!reached`,
`log.toList == List("s")`, no `StackOverflowError` at depth 1000000. None of them
mentions `Halt`, `settle`, prefixes or pairs. That is why 113/113 came back green
after the representation was replaced, with zero test edits, and why the rewrite
was a bounded operation rather than a gamble.

A test written against the mechanism would have had to be rewritten alongside
it, at which point the test no longer proves the semantics were preserved. This
is the specification-side form of E.5: structure-independent specs are what give
permission to change structure.

### E.8 Reproduce before fixing, and commit the red

`b0c7859937` is committed with five failing tests and a commit message naming
each defect. The maintainer's constraints made this work: "Only test changes for
this part of the session please" prevents a fix being smuggled in beside a
reproduction, and "failing tests are the best outcome of this work. Do not
workaround any issues, the goal is epxosing them" removes the incentive to soften
a reproduction until it passes.

The append-vs-wrap rotation bug (B.5) and the `Seq.indexOf` collision (B.6)
followed the same order: red pins first, fix second, pins retained as guards.

### E.9 Probes are cheap, disposable, and named after what they probe

`HandlersProbe.scala` and `EvalProbe.scala` sit in the module's `jvm` test
source, are committed with their commit messages saying "Dev artifact, removed
before ship", and carry header comments stating the model they encode. They made
three probe generations (B.1), two competing redesigns (B.3, D.1), and honest
cost measurement possible without a single speculative edit to main source.

They are also a liability if left behind. The repo's rule is explicit: scratch
and experiment test files are dev artifacts and must be removed before the change
is done. Both are still in the tree and both are marked for deletion.

### E.10 Performance is structural, and allocation tracks mechanism count

The +24B per eval bracket was not a coding mistake; it was the pair-returning
`evalLoop` signature, which existed because state had to travel back out of a
Java frame. It could not be optimized away without deleting the mechanism, and
it disappeared the moment the mechanism did. The final gate came back
byte-identical to the pre-step baseline on every row.

Read cost the same way as correctness: allocation and time accumulate at
coordination points, and coordination points are exactly what mechanisms create.
The maintainer's claim at C.4, that pure composition can be made to perform, was
borne out literally in this campaign.

### E.11 The lift-conversion footgun, and currency discipline

`<` is an opaque union with an implicit lift, and `<` is contravariant in `S`.
Any site where a `Kyo` fails to conform to the expected `<` type gets silently
converted into data as `Nested(...)`, and the failure surfaces far away as a
`ClassCastException`. Three occurrences in this campaign; the worst cost a long
debugging session at `HandlersProbe.scala:182` where the construction sites were
pinned with explicit type parameters and the real culprits were erased field
*reads*.

Two rules that fall out:

- At any boundary where the effect slot is erased or pinned (`Nothing` in
  particular), cast rather than let the conversion fire, and say why at the site.
  The pattern is in the source at `Eval.scala:200-202` and
  `EvalProbe.scala:52-53`.
- Never re-lift a value that is already currency. A settled result of an
  evaluation pass is currency; passing it through `lift` again double-nests it.

### E.12 Erased casts belong only at documented boundaries

The maintainer's typing demands (B.9) resolved into a workable rule rather than
a prohibition: every cast is at a boundary that is named and justified in the
commit that introduced it. The current set is tag-keyed handler recovery
(`hs(idx)` after `indexOf`), the erased currency of the loop, the effect slot
pinned to `Nothing` where a pattern-bound effect type loses its GADT bound, and
the lift-avoidance casts of E.11. `Handled.map` keeps one explicit type
application because inference cannot split `E & S` before the handler argument
pins `E`, and the commit that added it recorded that this was compiler-verified.

If a cast cannot be attached to one of those categories, it is a design smell,
not a typing convenience.

### E.13 New vocabulary is a leading indicator

`downgradeStops`, `shadow`, `exitCondition`, `ev`, `drive`, `Entry`,
`Optimized`: every one of these was either rejected on sight or later renamed
away. The maintainer's rule ("I'll complain evey time you add something new,
especially new terminology", 2026-08-10T19:35:02Z) is not stylistic. Machine
vocabulary arrives with machine designs, and it arrives first. A commit exists
in this history whose entire content is renaming `drive` to `eval`
(`6160c23c3b`).

Practical form: if a new noun is needed to describe what the evaluator is doing,
check whether the noun is naming a compensation before naming it at all.

### E.14 Explain back before implementing anything the maintainer described

Used three times in this campaign (C.5, C.6), twice after an implementation had
already been started against a misunderstanding. Each time the explain-back
produced the precise formulation the conversation had been circling, and once
(the continuation question) the formulation directly caused the deletion of two
handler kinds.

The general shape: a description of a design in the maintainer's words is a
specification the implementer must be able to restate. If it cannot be restated
in code-level terms without hedging, it has not been understood, and
implementing it will encode the misunderstanding in a form that is expensive to
undo.

---

## Appendix: commit index for the handlers and Eval arc

Oldest first, author times (UTC-3).

| commit | time | subject |
|---|---|---|
| `727e8e6742` | 08-10 16:06 | fix rotation losing the handler behind trailing transforms |
| `f1331be32a` | 08-10 16:47 | Handler hierarchy and Handlers collection |
| `a4f43104a4` | 08-10 17:01 | Handlers as opaque Chunk, typed kinds for all five methods |
| `21560cf03b` | 08-10 18:53 | handlers probe: compositional region drive validated |
| `97f6fbfaa5` | 08-10 19:06 | Handled: a computation under a handler, as a value |
| `4ce6b408d6` | 08-10 19:12 | Handler.Cont replaces Handle; Partial dropped |
| `52f52f9000` | 08-10 19:39 | Eval drives regions and resume handlers |
| `5efd4084b0` | 08-10 20:27 | stop handling in Eval |
| `39001c5691` | 08-10 21:14 | design capture: two handler kinds, Loop and Cont |
| `6d83c13466` | 08-10 21:22 | design capture: Loop, LoopState, Cont |
| `6b68934741` | 08-10 21:54 | step 1: Loop/LoopState/Cont handlers evaluated as regions |
| `6160c23c3b` | 08-10 22:04 | probe: rename its evaluator entry to eval |
| `b0c7859937` | 08-10 22:37 | test coverage for Eval branch gaps, stack safety reproductions |
| `344770888f` | 08-10 23:19 | probe: regions evaluated as merged layers, all defects clear |
| `5b0a910b4c` | 08-10 23:27 | Eval evaluates regions as merged layers |
| `b8ad9309e8` | 08-10 23:29 | remove Halt and the settle recursion, trim Handlers |

Files referenced:

- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Eval.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Handler.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Handlers.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Kyo.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/Arrow.scala`
- `kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala`
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/EvalTest.scala`
- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/EvalProbe.scala` (dev artifact)
- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/HandlersProbe.scala` (dev artifact)
- `kernel2-handlers-design.md`
- `kernel2-rotation-handlers-design.md`
