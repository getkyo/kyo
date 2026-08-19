# Held-out review of HANDLER-AS-ARROW.md (Fable, 2026-08-18)

Reviewer: `handler-arrow-review`, analysis only. Verbatim output.

Analysis complete. Below is the review; I read every file listed, the previous evaluator at `2da854f5aa` (via `git show`), the two bench rows, and the WORK.md/CONTRIBUTING history. I ran nothing and edited nothing.

# Held-out review: Handler-as-Arrow (reviews/HANDLER-AS-ARROW.md, option 2)

Paths below are under `/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/`; `Eval.scala` = `kyo-kernel2/shared/src/main/scala/kyo/proto/Eval.scala` (the in-progress rewrite), "old" = `git show 2da854f5aa:kyo-kernel2/shared/src/main/scala/kyo/proto/Eval.scala`.

## Summary of verdicts

| Item | Verdict |
|---|---|
| A1 | HOLDS, with the match-ordering note; the named tests pin it only weakly (interior handler at depth 1); a depth-2 pin is given in C1 |
| A2 | HOLDS WITH CHANGE: under option 2 the dump's `HandleLoopState` case must emit a state-carrying handler (`resumed(h, states(i))`); "state stays in the array" cannot cross a dump by itself |
| A3 | HOLDS, confirmed from the code (`Eval.scala:229-231` reads the slot below the popped one); "state composes with done" does not pin it, "threads state through operations" does |
| A4 | HOLDS (log citation for EvalTest:236 is `inner, outer`, not `outer, inner`) |
| A5 | HOLDS; discriminating tests are those whose answer raises the interior's tag |
| A6 | DOES NOT HOLD as a dichotomy: "do not flatten a Chain" breaks A1 (C1). The real choice is a chain-level marker vs the old Handle re-wrap. Recommendation in B2 |
| A7 | HOLDS WITH CHANGE: `Stack.push`'s flatten (`Eval.scala:47-49`) recurses on the Java stack for a right-deep chain; the 1M tests overflow at `push`, not at `apply` |
| A8 | HOLDS WITH CHANGE: (i) `find` before `push(cont)` or the returned `Defer(kyo, dump(-1))` applies `cont` twice; (ii) the empty-stack return must be the un-lowered currency (`Eval.scala:219-220` shadows `v`); (iii) `.eval` must strip via `lower` |
| A9 | HOLDS |
| A10 | HOLDS, but the pending-clause path is missing an item: a settled answer must re-install the region too (`Eval.scala:189` `k(o)` does not); "every clause suspension re-arms the region" fails on the file as written |
| A11 | HOLDS |

Missing items: A12 (settled-answer re-wrap on the pending-clause path), A13 (`HandleLoopState` state on the pending-clause path goes through `resumed(h, c._1)`, there is no `pos` left to `setState`), A14 (identity on settled currency, `Nested` preserved), A15 (`Continue._1` must be read as currency without the implicit `lift`, `Eval.scala:182,199`), A16 (every match over `Arrow | Handler` tests `HandleLoopState`, then `Handler`, then `Arrow`).

---

## A. Items A1..A11

**A1. Flatten re-installs a handler as a handler. HOLDS.**
`push(f: Arrow)` (`Eval.scala:45-54`) must add `case h: Kyo.Handler => push(h)` before the catch-all; `Kyo.Handler` and `Arrow.Chain` are disjoint runtime types, so ordering with the Chain case is free. Note that `find` (`Eval.scala:86-94`) matches on the runtime type of `entries(i)`, so a stateless handler that lands on the stack through the plain-arrow arm is still *found*; what the routing buys is the `states` slot (`Present(initialState)`, `Eval.scala:59-64`) and dump's `HandleLoopState` case. Consequence for the pins: "a capture crossing an inner region resumes it without re-running its body" (EvalTest:148) and "a clause raising a foreign effect ..." (ArrowEffectTest:884) both have the interior handler at chain depth 1, where `Handler.apply(pending, next) = Defer(pending, this, next)` pushes it directly; they pass with or without the routing. The stateful A2 tests are what pin the routing. A depth-2 pin (interior handler with two entries above it, remainder re-raises) does not exist in the suite; C1 gives it.

**A2. Capture-time state per shot. HOLDS WITH CHANGE.**
Under option 2, the state lives in `states`, and a dumped chain is a value: the only place a state can ride in the chain is inside a handler object. So dump's `HandleLoopState` case must emit `HandleLoopState.resumed(h, states(i))` (the constructor that existed at `2da854f5aa`, `Kyo.scala` there), and `push(h: Handler)` then stores `Present(h.initialState)` = the captured state. Without the copy, "each shot ... capture-time state" (EvalTest:159) gives 0 (both shots re-answer with 0), not 101; "a crossed stateful region resumes with its in-flight state" (ArrowEffectTest:758) gives 1010, not 1011. This makes option 2 "one copy per dump of a stateful region (cold), zero per answer (hot)": consistent with the ruling, but the report should say the copy exists. `HandleLoopState.apply(v)` then must be `complete(initialState, v)`, which is right only for a fresh or resumed copy; see D4.

**A3. The settled arm reads the state of the entry it pops. HOLDS, confirmed.**
`Eval.scala:68-74`: `pop()` decrements `top` and nulls `states(top)`; `Eval.scala:229-231` then reads `stack.state(stack.size - 1)` = `states(top' - 1)`, the slot below the popped one (null for an arrow entry, another handler's state, or index -1 on an empty stack). "done observes the final state" (EvalTest:395; ArrowEffectTest:491) pops the last entry: `state(-1)` throws. "threads state through operations" (EvalTest:386) pins it the same way. "state composes with done" (ArrowEffectTest:546) ends by `Loop.done` (truncate, `Eval.scala:207`), never reaches the settled-arm read, so it does not pin A3.

**A4. `dump` keeps the handler at `pos`; the pending-clause path pops it itself. HOLDS.**
`Eval.scala:104-105` truncates to `pos + 1`; HandleCont (`:169-171`) and the pending answer (`:200-202`) need `h` at `pos`; only `:176` pops. The report's log for "an effectful answer runs under this handler with the interior parked" should read `inner, outer` (EvalTest:245); the fused-remainder variants are the `outer, inner` ones (EvalTest:327, 352).

**A5. The park stays parked. HOLDS.**
`Kyo.Defer(a, Arrow(k))` (`Eval.scala:185, 202`). The tests that discriminate `Arrow(k)` from `k` are those whose *answer* raises the interior's tag: EvalTest:236 (`inner, outer`), :262 (`inner, outer, outer`), :318 (`outer, inner`), :343 (`outer, outer, inner`), :446. EvalTest:330 ("... no outer handler for it") passes either way (its answer raises only `Ask`); it pins A1-in-the-park, not A5. Same for the analogous EvalTest:289.

**A6. Trailing maps stay linear. DOES NOT HOLD as stated.**
The quadratic is real. Trace of `trailingMapsStayLinear` (`YetAnotherProtoBench.scala:150-156`) under the current design: at step *i* the stack above the region is `[g_0 .. g_{i-1}, g_i, f_i]` because the previous capture's `Chain` was flattened back into *i* entries by `push`; `dump` walks *i+2* entries and allocates *i+2* `Chain` nodes; `cont(1)` returns `Defer(loop(i+1), rest)`, and `push(rest)` expands *i+1* entries again. O(i) per step, O(n²) total, in both time and B/op. Under the old evaluator the previous capture stayed one entry (`Transform(o => wrap(...))`, and the old `Stack.push` did not flatten), so each dump walked 3 entries.
The report's alternative "keep a Chain one entry and let its `apply` defer it" is not available: a handler at chain depth ≥ 2 then sits inside an opaque entry while the interior above it runs, and `find` cannot see it. C1 is the counterexample. So the choice is not "flatten or not"; see B2.

**A7. Right-deep dump. HOLDS WITH CHANGE.**
Right-deep is right for `Chain.apply(v) = b(a(v), Arrow.id)` (`Arrow.scala:51-52`): `a` is a leaf, `b` defers as one node. But the flatten `case c: Chain => push(c.b); push(c.a)` (`Eval.scala:47-49`) recurses on the *b* spine of a right-deep chain, non-tail. For "a long map tower on a pending suspension handles in bounded stack" (ArrowEffectTest:381) the dump is a 1M-deep right-deep chain; `cont(0)` = `Chain.apply(0)` = `Defer(1, rest)`; the loop's `push(rest)` recurses ~1M frames and overflows. Same for ArrowEffectTest:725 (`suspendWith` + 1M maps). The flatten must be iterative: walk the b spine pushing each `a`, then reverse the pushed range in place on both arrays (allocation-free), or use a scratch buffer as the old kernel's `AndThen.step` did (kyo-kernel2/CONTRIBUTING.md:116). Building left-deep instead would make `push` a tail loop but moves the recursion into `Chain.apply(v)`'s a-spine, which is what `cca4eb3751` fixed; keep right-deep.

**A8. Partial evaluation. HOLDS WITH CHANGE.**
(i) The Suspend case pushes `kyo.cont` before `find` (`Eval.scala:166-167`). If the miss returns `Defer(kyo, dump(-1))`, `cont` is inside the dump *and* still on the node; re-evaluation under a later handler pushes `kyo.cont` again and applies it twice. Do `find` first and push `cont` only on a hit (the miss returns `Defer(kyo, dump(-1))` with the node carrying its own cont), or pop `cont` back off before the dump (careful: it was skipped if `Id`).
(ii) `Eval.scala:219-220`: the `done = v => if stack.isEmpty then v.asInstanceOf[B < S]` lambda parameter shadows the loop's `v`. It returns the *stripped* payload as currency. Under the old signature (`Eval[A](v): A`) stripping one `Nested` was the contract; under `Eval[A, S](v: A < S): A < S` an evaluation of a settled value must be the identity on the currency, otherwise a computation held as a value comes back one level down and reads as *pending* to the next `lower`. Return the loop's `v`. Then "double nesting round trips one level per eval" (EvalTest:73) and "eval returns a pending payload without running it" (PendingTest:41) become `.eval` tests.
(iii) `.eval` (`Pending.scala:27-28`) casts `Eval(self)` to `A`; with a partial `Eval` it must `lower(pending = bug(...), done = a => a)`.
(iv) Today a miss is `stack.handler(-1)` (`Eval.scala:168`), an `ArrayIndexOutOfBoundsException`, not `bug`.
Tests that change: EvalTest:83-90, :540-543, :545-552; ArrowEffectTest:447-455 and :595-608 come back live (the latter needs A2's `resumed` copies inside `dump(-1)`).

**A9. Isolated by the pool. HOLDS.** `Stack.borrow/release` (`Eval.scala:128-156`), released in `apply`'s `finally` (`:25-28`), truncated to 0 on release. EvalTest:545 renames.

**A10. Region completion order. HOLDS, one item missing.**
The region's `cont` is below the handler (`Eval.scala:215-216`), `Loop.done` truncates to `pos` (`:207`), pins ArrowEffectTest:296 and EvalTest:180 as named. Missing: on the pending-clause path the current file re-installs the region only for a *pending* answer (`Eval.scala:183-188`) and applies `k(o)` bare for a settled one (`:189`). The old `outcome` always went through `inside(h, st, cont)(answered(c._1, k))`, so a settled answer whose remainder re-raised the tag found the region. "every clause suspension re-arms the region" (ArrowEffectTest:127, expects 11 and `x, x`) fails on the file: after `say("x")` settles to `Continue(1)`, `k(1)` runs `ask.map(b => ...)` with `h_ask` popped, `find` returns -1. Same for "a stateful clause that suspends threads its state through the park" (EvalTest:423). This is A12 below.

**A11. Variance and frames. HOLDS.** `Handler[E, A, +B, -S] extends Arrow[A, B, S]`: invariant `A` into contravariant position is legal, `+B`/`-S` match, `Function1[-A, +R]` likewise. `Arrow.frame` is abstract (`Arrow.scala:10`); `handleCont`/`handleLoop`/`handleLoopState` (`ArrowEffect.scala:47-118`) take no `Frame`, so either add `(using inline _frame: Frame)` as the `*With` variants have, or `Frame.internal`.

**Missing items**
- **A12.** Pending-clause path, settled answer: re-install the region. With Handler an Arrow the composition is `Kyo.Defer(answer, Arrow(k), h)` for both settled and pending answers: `push(h)` re-installs the region, `push(Arrow(k))` parks the interior above it, `loop(answer)`. This replaces the anonymous `Kyo.Handle` at `Eval.scala:184-187` and the `k(o)` arm at `:189` with one node.
- **A13.** `HandleLoopState` on the pending-clause path: `Continue2(state, answer)` must re-wrap with `resumed(h, state)`; `h` was popped, there is no `pos` to `setState`. The report's line 39 ("the same with Continue2, setState(pos, c._1)") is right only for the settled-clause path.
- **A14.** Identity on settled currency (A8 ii).
- **A15.** `c._1` at `Eval.scala:182` and `:199` is typed by the `Continue[?]` pattern as `Any`; `.lower` on it does not resolve, and the compiler inserts `lift` (`Pending.scala:17-20`), which boxes a pending answer as `Nested` and sends it down the *done* arm: the answer runs with the interior still on the stack (`loop(o, stack)`), and EvalTest:236 logs `inner, inner`. It compiles and is silently wrong. The old code cast `c._1.asInstanceOf[Any < Any]`. `Continue._1` is currency and must be read as such, never lifted.
- **A16.** Match ordering: with `Handler <: Arrow`, `dump`'s `case f: Arrow` (`Eval.scala:102`) and the settled arm's `case f: Arrow` (`:223`) catch handlers; `HandleLoopState`, then `Handler`, then `Arrow`, in every match over the union.

## B. The four open questions

**B1. Flatten vs Handle re-wrap: any observable difference beyond state?** No, once A12/A13/A15 are in and dump emits `resumed` copies. I walked: settled interior applied strictly (old `inside`'s done arm vs new `Handler.apply(settled, id)` completing without a push: same, and both run `done` outside the region, ArrowEffectTest:576 agrees); `Loop.done` inside a resumed capture (old `finish` = `cont(done, id)`, new truncate-then-pop: same); fused `Handle with Arrow` conts (below the handler in both); a HandleCont clause raising its own tag (region at `pos` in both); the pending-answer own-tag re-raise (EvalTest:248, same). On the specific sub-question: the same handler object *can* be on one stack twice (a clause stores `k`; the region body's remainder, running inside shot 1 with `h` live at *p*, calls the stored `k` again, pushing `h` at *p2*). It is not a problem: `setState(pos, ...)` addresses the position `find` returned, and under option 2 the handler object carries no live state, so two positions of one object are two independent regions. This is the same as the old evaluator (`Handle{handler = resumed(h, st)}` per shot, state per position). Under option 1 it would also be fine (a new object per state).

**B2. A6: the design to take.**
The two forces are: (a) a handler inside a dumped chain must be *installed before* the entries above it run, which only an eager expansion of everything above the deepest handler achieves; (b) re-expanding plain entries on every resume is what makes trailing maps quadratic. So:
- Full flatten (as written): (a) yes, (b) quadratic. Not acceptable; the bench row is the test.
- No flatten (chains one entry, unwind at pop): (b) linear, (a) violated at depth ≥ 2 (C1).
- Old design (handlers never in chains, `Handle` re-wrap, runs opaque): both hold, but it is the design the proposal exists to remove and it needs `dump` to know regions.
- **Recommended: expand on push exactly the chains that hold a handler; a handler-free chain is one entry.** The chain must know: one boolean on `Arrow.Chain` set in `chain` (`a` is a Handler or a chain with the bit, or `b` is), O(1), private, and dump is the only producer of handler-bearing chains. `push`: `case c: Chain if c.holdsHandler => expand (iteratively, per A7); case c: Chain => store`. Everything else stays: `Chain.apply(v, next) = Defer(v, this, next)` (a stored chain that reaches the settled arm goes `b(a(v), id)` → one `Defer` → `push(b)` → one entry again), right-deep dumps, `Arrow(k)` as the park (still needed: a handler-bearing `k` would otherwise be expanded above the answer). Trailing maps: dump walks 3 entries per step, `cont(1)` costs one Defer and one push, the final unwind is one Defer per level; the 1M tower is 1M loop iterations with no Java recursion. Two things to add for rigor: the expansion loop must be iterative (A7), and `Chain.apply(v)` should unwind an a-spine iteratively (`go(a', b'.chain(rest))`), because a stored chain can be the top entry when a bare suspension is dispatched over a non-empty range and then lands in an `a` position; that depth is shape-bounded and small in practice, but "the Java stack" is not a carrier. If the owner will not take a marker on `Chain`, the only other correct option is the old re-wrap; "just do not flatten" is not one.
- Optional, separate: `Chain.apply(v, next)` could apply strictly under the safepoint budget when `v` is settled (the old shape) to save one Defer per level on the final unwind; not needed for correctness or linearity, and the owner said `Chain` as written.

**B3. A8: the folded continuation of an unhandled suspension.** `dump(-1)`, everything as one right-deep arrow with handlers of other tags as links (stateful ones as `resumed` copies), and the returned value is `Kyo.Defer(kyo, dumped)` with `kyo` carrying its own `cont` and `dumped` *not* containing it (A8 i). On re-entry the flatten re-installs the other-tag regions in their original order at their captured states; then `push(kyo.cont)`, `find`, hit. The stack is empty on return (dump truncated it), `apply`'s `finally` releases it.

**B4. Collisions in ArrowEffect.scala.** None. `Suspend with Arrow` (`ArrowEffect.scala:33-43`) and the `Handle with Arrow` `*With` nodes (`:140, :175, :211`) are neither `Chain` nor `Handler`; they push as plain arrows and are found by nothing. Their `handler` is a separate `val`. The Handle case's `push(kyo.handler)` resolves to the `Handler` overload statically. The only thing the types newly allow is a `Handler` in a `cont` position; nothing does it, and if something did, `push(f: Arrow)`'s routing would install it as a region, which is a coherent meaning. The one real consequence is A16: match order.

## C. Adversarial

**C1. Interior region at chain depth 2 with a re-raise from above it.** Passes under full flatten (and under the old evaluator), fails under any "chain stays one entry" variant, and is not in the suite:
```scala
"a resumed capture re-installs an interior region under the entries above it" in {
    val inner: Int < Say = answerAsk(1)(
        ask.map(a => say("x").map(_ => ask.map(b => a * 10 + b))).map(_ + 100)
    )
    val r: Int < Any = ArrowEffect.handleCont(Tag[Say], inner)([C] => (_, cont) => cont(()), a => a)
    assert(Eval(r) == 111)
}
```
Stack at `say("x")`: `[h_say | h_ask, Arrow(+100), Arrow(f)]`; `k = Chain(f, Chain(+100, h_ask))`. `cont(())` = `Defer(Defer(ask, g), Chain(+100, h_ask))`. Full flatten pushes `h_ask` then `+100`, `ask` finds `h_ask`, 1, 11, 111 (old: 111 via `inside(h_ask)`). Opaque chain: `h_ask` inside one entry, `find` returns -1.

**C2. Pending clause, settled answer** (the file today, not the proposal): ArrowEffectTest:127 as traced under A10. Old: 11, `x, x`. File: `find` = -1 after `k(1)`.

**C3. Multi-shot over a stateful interior**: EvalTest:159 (101) and ArrowEffectTest:758 (1011). Old: pass. Proposal without `resumed` copies in dump: 0 and 1010. With them: pass.

**C4. Same handler object twice on one stack** (stored `k` re-entered from inside shot 1's remainder): both designs give the same value (state per position, e.g. `1002002` for a body `ask.map(a => say("x").map(_ => ask.map(b => if first then stored(()) else a*10+b)))` under `handleLoopState(_, 0, ...)((s,_) => continue(s+1, s), (s,a) => a*1000+s)`). No divergence.

**C5. `Loop.done` payload that is a computation held as a value** (PendingTest:145, :202): identical outcome in both, and for the same fragile reason: the payload leaves the clause boxed (the lift at the clause boundary, kyo-kernel2/CONTRIBUTING.md:137), `lower` strips it, and it is re-boxed only because `loop(done, stack)` (`Eval.scala:208`) and the old `cont(done, Arrow.id)` pass a non-currency-typed value into a currency slot and the compiler inserts `lift`. Not a divergence, but see D5.

**C6. Unhandled operation with other handlers on the stack**: old throws; proposal returns pending. When later handled, the other-tag regions come back in order with their states. No divergence to construct beyond throw-vs-pending, provided A8 (i).

I could not construct a program where the proposal *as corrected by A12, A13, A15, and dump-emits-resumed* differs from `2da854f5aa`.

## D. Unsafety in the proposal or the current file

1. **`Eval.scala:219-220`**: the shadowed `v` returns a payload as currency; a `Kyo` payload becomes a pending computation at the next `lower`. A value produced by the evaluator that lies about its level.
2. **`Eval.scala:182, 199`**: `c._1.lower(...)` compiles through the implicit `lift` and mis-scopes pending answers (A15). Evaluator internals must not depend on `lift` firing or not firing: `Continue._1` must be read as currency (cast), and the `Loop.done` payload at `:208` and the `case done => done` arm at `:191` are currently correct only *because* `lift` fires on an `Outcome`-typed value; if either is ever typed `Any < Any` "for cleanliness" the boxing disappears silently. Make both explicit.
3. **`Eval.scala:184-187`**: `def v = Kyo.Defer(a, Arrow(k))` allocates a fresh `Defer` and a fresh `Arrow(k)` on every read; a `Kyo.Handle` whose `v` is not stable. And the whole anonymous `Handle` is a mechanism where a composition does: `Kyo.Defer(answer, Arrow(k), h)` (A12), with `resumed(h, state)` in the stateful case (A13).
4. **`HandleLoopState.apply(v)` under option 2** is `complete(initialState, v)`, correct only for a fresh or `resumed` copy; on a stack-resident handler `initialState` can be stale. The guard is that the settled arm reads `states` and never calls `apply` on it, and that dump always substitutes a copy. That invariant is not expressed in a type; it should at least be stated where the report records option 2. Option 1 has no such hazard; that is the honest cost of the ruling.
5. **`Stack.push` recursion** (`Eval.scala:47-49`): program-shaped Java recursion (A7).
6. **`stack.handler(-1)`** (`Eval.scala:168`): AIOOBE on a miss instead of the partial return.
7. Casts: the array cast (`Eval.scala:36`), the `IX/OX/EX` erased patterns, and `case f: Arrow[Any, Any, S] @unchecked` are the erased-stack casts the brief allows. Nothing produced by the evaluator closes over the stack or an array: `k` is a chain of arrows and handler objects, `Arrow(k)` and the outcome arrow capture `h`, `k`, `a` only. Good.
8. Unfinished, not unsafe: the HandleCont arm (`Eval.scala:171`) returns `h.run(...)` without `loop(...)`; there is no `given Frame` for `Arrow(...)` in the file; `HandleLoopState` is `???`.
9. Perf, not unsafety: `find` calls `h.tag` through the handler per scan (the old kernel copied the tag onto the cell to keep this monomorphic, kyo-kernel2/CONTRIBUTING.md:94).
