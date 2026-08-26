# Held-out advisory on the kernel simplification

Produced by a held-out Fable advisor (analysis only) briefed to challenge
`reviews/KERNEL-SIMPLIFICATION-REFLECTION.md` rather than answer inside its framing. The advisor
read the reflection, the kernel skill, the eight current-kernel sources plus ArrowEffect.scala,
Nested.scala, and EffectTrace's walk, the CPS sibling on origin/main, and the parked patch lane
diff (dfe6c7f3a8), and verified the reflection's mechanism map against source by hand before
writing. Its report follows verbatim.

---

## 0. What I verified independently

I reconstructed the effectful-Loop shape from source rather than trusting the report. `Loop.apply`
(kyo-kernel/shared/src/main/scala/kyo/kernel/Loop.scala) wraps the body's result with
`Effect.defer(kyo, arrow, Arrow.id)`; the body `ask.map(f)` is `Defer(Suspend, mapArrow, id)`; so
each iteration hands the handler `Defer(Defer(Suspend, map, id), loopStep, id)`. `Handler.nextAnswer`
(internal/Handler.scala) accepts exactly a bare same-tag `Suspend` or a one-level `Defer` with
`contB eq Id` and at most one non-identity arrow; the nested shape hits the inner `case _` and bails
with code 2. The eval's fast gates (internal/Eval.scala, the three `pos == 0` /
`pos == 1 && entry(0).isInstanceOf[Arrow.Step]` pairs) reject the two-entry stack. Every answer then
pays `stack.dump` plus a fresh `Defer` plus an eval re-entry. The recursive-method baseline builds
`Defer(Suspend, map, id)` per step, which `nextAnswer` accepts with `out.cont = contA`, so the whole
row runs inside the compiled `answersCont`/`answersLoop` while-loop. The report's mechanism map is
accurate, and the whole gap is exactly "the fast path handles region-free compositions of
length <= 1 and the loop builds length 2".

## 1. Essential versus incidental complexity: my own framing

The report frames the problem as "shapes built by one tier must be re-classified by every consumer".
I agree with the diagnosis but the framing invites the wrong fix (fewer tiers). My sharper reading:

**The essential complexity** is three commitments, each with a measured or pinned justification:

1. **Regions must be eval-visible.** `Stack.find`, `lookup`, the drain, and `parkable` scan entries;
   a region folded into an arrow is invisible (`Arrow.Step`'s scaladoc says exactly this). This is
   what deletes the CPS sibling's per-handler-per-foreign-operation re-wrap: in origin/main's
   `ArrowEffect.handle*`, every suspension of a *different* effect allocates a `KyoContinue` per
   installed handler it crosses. The stack makes crossing a region free. That is the two-tier
   design's genuine win and it is essential.
2. **Building must be O(1) per combinator.** `Defer`'s two slots and `Chain` are
   unallocated/unbalanced composition; normalization happens once, at capture (`dump`). Universal
   construction-time normalization is structurally unavailable: `AndThen` is a head-first cons list,
   so appending a step after an existing run is O(depth), and the skill's own catalog records this
   exact failure ("Fusing composition into wrappers... O(depth)... stack overflow at 100k maps").
3. **The handler's answer loop wants the clause statically bound.** `answerStep`/`answersLoop` exist
   because the outcome box crossing a virtual call costs one allocation per answer once handler
   classes are polymorphic (the morphism probe, recorded in `Handler.Out`'s comment).

**The incidental complexity** is that the kernel already has the type vocabulary for "safe to fuse"
(`Step`, `Region`, `Cont` in kyo/Arrow.scala, with `Cont` sealed to `AndThen | Id` and `Region`
deliberately not a `Step`), but the consumers do not classify by it. They classify by *position*
(`pos == 0`, `pos == 1`), *identity* (`eq Arrow.Id`, the sc/ca arity dance in `nextAnswer`), and
*arity* (`contA`/`contB`). The `Out` cell's `kind`/`cont`/`input`/`state` lanes are the CPS driver's
local variables reified into a mutable protocol because the drive was centralized into one eval;
that is a cost of commitment 3, paid once, but the three near-identical dispatch families and their
triplicated catch/escape blocks are duplication, not design.

So the target is not "one canonical form everywhere" (D3) but: **every composition fact needed at
delivery is established at construction and carried in the sealed type, and every consumer
classifies by that type**. Position and identity tests are the smell; `Cont`/`Region` tests are the
design.

## 2. The five questions

**Q1. Is the two-tier split worth keeping?** Yes. The CPS sibling's handling machinery is an order
of magnitude smaller (`handleLoop` with state is ~25 lines, no Out cell, no nextAnswer, no dump),
and that is real evidence about what single-currency buys. But its cost model is also visible in the
same file: a `KyoContinue` allocation per transform-over-suspension *and* per foreign suspension per
installed handler, and state threaded only because the handler owns its own drive. The Arrow
kernel's stack deletes the per-handler crossing tax and the dump-once capture; those are the
measured wins the two-tier split exists for. Keep the split; fix the meeting points.

**Q2. If canonical, Defer (D1) or the suspension's cont (D2)?** D1's carrier, depth-bounded. D2 as a
universal law is dead on arrival: folding each map into the suspension's cont is a right-append into
a head-first `AndThen`, O(depth) per map, the exact fused-wrapper failure already measured and
catalogued. D2 also puts a payload type test on the hottest build path and re-mints a four-field
node per wrap. Note that D2 already exists in narrowed form: `Handler.resuspend` is precisely
"re-mint the suspension with a composed cont" and is fine where it lives, at bail sites.

What breaks in D1 that the report has not listed:

- **The existing gate does not admit D1's shape.** The report's claim "an effectful loop iteration
  sits at pos == 1 and the EXISTING gate admits it" is false as written. The fused entry is an
  `AndThen`, and `AndThen extends Cont` only, never `Step` (Arrow.scala); the gate tests
  `entry(0).isInstanceOf[Arrow.Step]` and rejects it. The gate must learn `Cont`, which is in fact a
  simplification: `Cont` is the designed name for "normalized region-free run", and `Cont` and
  `Region` are structurally disjoint.
- **Universal construction-time composition is O(n^2) on user map chains** (right-append). D1 must
  be depth-bounded: fuse only when the payload is `Defer(v, step: Step, id)` and the incoming cont
  is a `Step`. The report's parenthetical "composing at construction is still one node, so the
  argument may be void" is only true at depth 1.
- **The fold in `Stack.dump` does not know `AndThen` as a head.** An `AndThen` entry under further
  steps currently folds via `e.chain(acc)` into a `Chain`, which applies by deferring. Once fusion
  puts `AndThen`s on the stack, three-map chains would capture as `Chain(AndThen, step)` and regress
  the trailing-map rows unless `dump` gains a bounded append arm (O(1) because the constructor's
  guard bounds fused depth at 2). This is the one place D1 genuinely touches a fourth file.
- Minor: `Debugger.onDefer` reads `kyo.contA.frame`; a fused `AndThen` reports `Frame.internal`
  instead of the map's frame (session-only degradation). `EffectTrace` and `Eval.receiver` already
  walk `AndThen` correctly (verified: EffectTrace.scala line 317, Eval.receiver), and `parkable`'s
  `BindingStep` detection survives because `receiver` descends into `AndThen.t`.

**Q3. Is D4 realistic?** Not in full, on my reading of what the loops buy: (a) the clause statically
bound with the outcome destructured in the same compiled method (scalar replacement survives handler
polymorphism), (b) monomorphic k-application inside one JIT unit, (c) no push/find/dump per answer.
An eval-only path can keep (a) by retaining the per-site `answer` methods while deleting the
`answers` retention loops (call this D4a), but cannot recover (b) or (c): the drive itself never
inlines (~1500 bytes, recorded in the skill), so everything per-answer crosses its megamorphic
dispatch. My prior is the 9ns rows regress 2x or more eval-only. But this is precisely measurable
for almost nothing, because `debugger.fastPathsAllowed` already gates all three fast dispatches: a
one-line flip prices the eval-general-path ceiling on every row, today, with no design work. Run
that before believing me or the report.

**Q4. A smaller cut that achieves property 2 without touching the representation?** Strictly without
representation changes: no. Any fix must change which shapes exist at delivery, because the bail is
a shape mismatch. But the smallest representation-adjacent cut is much smaller than the report's D1:
three law-shaped edits, no new node kinds, no new gate arms:

1. **The map-fusion law in the 2-arg smart constructor** (`Effect.defer`):
   `defer(Defer(v, f: Step, id), g: Step) = Defer(v, AndThen(f, g))`. This is the equation the
   report says was never written: `Loop(a)(run) = run(a).map(step)`, with fusion
   `defer(defer(v,f),g) = defer(v, f;g)` for region-free f, g. Its home is the constructor, not
   Loop, and it therefore also fixes `Loop.indexed`, `repeat`, `whileTrue`, and every user-written
   recursive combinator with the same shape, which no Loop-local patch would.
2. **The gate word**: `Step` becomes `Cont` at the three `pos == 1` gates (one shared predicate;
   `Cont` excludes every `Region` by construction).
3. **`nextAnswer`'s else branch composes instead of bailing** (the AndThen form of parked patch #2),
   covering `suspendWith`-built operations whose fused cont makes both arrows non-identity; one
   allocation per operation, equivalent to construction cost moved to delivery.

Plus the bounded `AndThen` append arm in `dump`'s fold from Q2. With edits 1+2 alone,
`suspend`-built loops (the benchmarked shape) sit inside `answersCont` with zero `nextAnswer`
changes, because the existing `ca` non-identity arm already accepts `Defer(Suspend, AndThen, id)`.
Allocation parity holds: per iteration, {Defer, Defer, map-arrow, Suspend} becomes
{Defer, AndThen, map-arrow, Suspend}.

**Q5. Probe order** is in section 4.

## 3. Ranking: simplification per unit risk, and what each deletes

1. **D1-staged (the three edits above, then the slot deletion).** Highest value per risk. What it
   deletes: the entire parked lane becomes unnecessary (the flatten case, the `compose` helper and
   `plainRun`, the third gate arm); `nextAnswer`'s identity-arity dance collapses to one compose
   rule; and it opens stage 2: deleting `Defer.contB`, the 3-arg `Effect.defer` overload, one push
   in the eval's Defer arm, and one line of EffectTrace's walk, contingent on measuring the
   mid-chain deferring arms (a `Chain` or `AndThen` allocation appears where two slots were free;
   that is the two-slot design's one honest payment and it may or may not matter). Risks are the
   four items in Q2, all named, all pinned by existing tests (EffectTest "failure in map", LoopTest
   "stays data", the nesting suite, park replay).
2. **D4a, then D4, gated on the flag probe.** If the eval-general path is within ~1.5x on the hot
   rows, this is the largest deletion available: `answersLoop`, `answersLoopState`, `answersCont`,
   `nextAnswer`, `resuspend`, the `Out` cell's cont/input lanes and kind protocol,
   `dispatchContFast`/`dispatchLoopFast`/`dispatchLoopStateFast`, all nine gate arms, the `answers`
   overrides in six `ArrowEffect` expansions, and `Stack.out`: roughly 600 lines of the most
   protocol-dense code in the module, plus the triplicated catch/escape blocks. If the probe says
   3x, the answers loop is essential complexity and gets documented as such, which is also an
   answer.
3. **Eval dispatch dedup (not in the report's list).** Post-D1 the three families' gate pairs are
   identical; hoist one "compute k or fall through" prelude above the family match and each family
   keeps one fast and one general arm. Deletes six of nine gate arms and two of three catch-block
   copies. Small, mechanical, low risk.
4. **D2**: rejected as a universal law (right-append); its narrowed form already exists as
   `resuspend`. No probe warranted.
5. **D3**: horizon only. Its headline deletions (Chain-vs-AndThen, dump, the Defer arms) only pay if
   D4 also lands, and it must reinvent what the stack's side arrays do for free: in-place handler
   state (`putState`), region marks, the finalizer side-channel, park snapshots. The CPS sibling
   solved state by keeping it in the driver's locals, which is unavailable while the drive is
   centralized. Do not open this campaign on current evidence.
6. **D0**: the honest floor; now genuinely available because the mechanism is fully named, but D1's
   probe is cheap enough that D0 should not be taken before it runs.

## 4. Probe order and the decisive experiment for the top direction

- **P0 (calibration, near-zero cost):** flip `fastPathsAllowed` to false in a throwaway worktree,
  run the full KernelBench class both legs through the bench-harness. Decides D4's ceiling and
  quantifies exactly what the answers loops protect, row by row.
- **P1 (the decisive probe for D1):** implement edits 1+2 only (~15 lines), full class, as a
  declared three-sha chain per the harness rules: baseline, +fusion-law, +gate-word, so each step is
  attributable. **Decisive number:** the effectful Loop row lands within ~1.5x of the
  recursive-method row (from ~33ns toward ~9-13ns/step) at ~128 B/op, with the pure Loop rows
  byte-identical and the trailing-map and stateful rows flat. **Decisive counterexample:**
  EffectTest "failure in map" or any nesting/park test red, or a trailing-maps regression (the
  dump/Chain interaction from Q2), which would mandate the bounded append arm before judging.
- **P2:** +nextAnswer compose rule (edit 3) as a fourth sha, judged on the suspendWith rows.
- **P3:** the contB/3-arg deletion probe, judged on mid-chain suspension rows.
- **P4:** D4a, only if P0 said the general path is close.

## 5. Factual flags on the report

1. **Wrong as stated:** "the eval's Defer arm then pushes ONE entry, so an effectful loop iteration
   sits at pos == 1 and the EXISTING gate admits it" (D1). The fused entry is an `AndThen`, which is
   a `Cont`, not a `Step`; the existing gate tests `Step` and rejects it. D1 requires the gate
   change; fortunately the change is itself a simplification.
2. **Incomplete to the point of misleading:** "composing at construction is still one node, so the
   [O(1)-accumulation] argument may be void". True only at depth 1. Universal construction-time
   composition right-appends into `AndThen` at O(depth) per wrap, the exact failure the skill's
   optimization catalog records. This kills D2-universal and bounds D1, and the report's D1/D2
   sketches acknowledge neither.
3. **Unlisted risk:** `Stack.dump`'s fold treats a non-`Step` entry via `chain`, so fused `AndThen`
   entries under further steps capture as deferring `Chain`s; three-plus-length user chains would
   regress delivery unless the fold gains a bounded append arm. No probe in the report covers this;
   P1's full-class run does.
4. **Verified accurate** (worth saying, because I checked them against source): the `nextAnswer`
   two-shape claim, the pos gate description, the Chain-defers-on-purpose subtlety and its
   EffectTest pin, the four-carriers table, and the per-family fast/slow triplication. The measured
   numbers (38.6ns/152B before, 128B after b6abb47462, 9ns/64B baseline) I could not re-derive
   without runs and treat as recorded.

**Bottom line:** the two-tier design is essential and stays; the incidental complexity is that three
meeting points classify shapes by position, identity, and arity instead of by the sealed vocabulary
the module already defines. The correct next act is P0 plus P1: one composition law in the smart
constructor and one gate word, measured as a declared chain, which either collapses the
effectful-Loop cliff into the existing machinery while deleting the need for every parked patch, or
produces the counterexample that redirects to D0 with the deviation fully priced.
