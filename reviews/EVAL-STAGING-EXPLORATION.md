# Staging the eval into the handle-site expansions: exploration

Prompt, verbatim: "how about we simplify things by 'staging' Eval itself in via JIT in inlined
classes? the answers impls should be a concern of Eval and might even be able to reuse code
better. Explore"

Every claim below is grounded in a read of the current sources (Handler.scala, Eval.scala,
ArrowEffect.scala, Debugger.scala) on the working branch.

## 1. Naming the mechanism precisely

"Staging via JIT" decomposes into two layers, and only one of them is ours to choose:

- **The JIT specializes per method, never per class.** An answers loop written once on the
  abstract `Handler` class would be compiled once, with the clause a megamorphic virtual call
  inside it; HotSpot does not clone a method per receiver class. So inheritance cannot produce
  the per-site specialization. The staging annotation available to us is Scala `inline`: each
  handle site expands its own copy of the loop with the clause spliced in as code, and THAT is
  what hands the JIT monomorphic call sites, static clause binding, and a scalar-replaceable
  outcome box.
- The codebase already states this as its design: Handler.scala's template comment reads "Each
  call site still emits its own copy with the clause statically bound, which is the mechanism
  the design exists for; only the source location is shared."

So the question is not whether to stage (the kernel already stages) but **what source text is
the thing being staged, and who owns it**. Today the staged text is a parallel machine
maintained in Handler.scala; the proposal is that it be the eval's own delivery step.

## 2. Why "stage the whole Eval" is bounded by recorded evidence

Two prior measurements close off the maximal version:

- **The inline-eval experiment already ran and lost.** Eval.scala records it: "the eval is ~555
  instructions and HotSpot refuses to inline it at any call site, so an inline definition bought
  nothing at runtime and emitted a private copy of the whole interpreter per call site.
  PendingTest alone carried 132 of them." The reason it bought nothing is structural: at an eval
  call site nothing is statically known, so the copy specializes over nothing.
- **The compiled unit's inlining budget is finite and already contested.** Eval.scala again: "at
  1.4KB the budget starved and Integer.valueOf stopped inlining on the hot paths, which the
  stateful rows paid four times over." A per-site copy of the whole drive would be far over
  every budget; its callees would stop inlining inside it and the copy would run slower than the
  shared drive.

Site knowledge exists at exactly one place: a handle site knows its handler family, its clause,
and its effect tag. That knowledge pays only inside the same-tag answer window (consecutive
operations of the handled effect under at most one plain continuation entry). The arms of the
eval that handle everything else (regions, parks, foreign effects, budget) gain nothing from
site knowledge and must stay shared. **"Staging Eval" properly scoped means: the eval's
same-tag delivery window becomes one staged template that Eval owns, and the per-site
expansions instantiate it.** That is precisely the answers machinery, re-derived instead of
parallel-maintained.

## 3. What is actually duplicated today (read, not estimated)

The three loop templates (`answersCont`, `answersLoop`, `answersLoopState`, Handler.scala
130-393) share a verbatim skeleton:

1. the `armed && Safepoint.stopped(slot)` bail arm (resuspend, kind=1, cont=null),
2. the clause try/catch with the `ClauseThrew` cell protocol,
3. the `n = 128` window countdown and its `Effect.defer(next, id)` bail,
4. the `nextAnswer` result dance (1 continue with in/k from the cell, 2 captured value, 0 bail),
5. the `Out` writes on every exit arm.

What differs per family is a kernel of 10 to 25 lines:

- **Cont**: the clause consumes `(input, k)` and returns region currency; no destructure; the
  cell's cont lane is the exception lane only.
- **Loop**: the clause returns `Outcome`; destructure into suspended clause (kind=2),
  `Continue(ans)` with a suspended-answer sub-arm (`ans.map(a => k(a))`), and done (kind=3).
- **LoopState**: the Loop kernel plus `st` threaded through every exit arm.

The same triplication repeats on the eval side: `dispatchContFast`, `dispatchLoopFast`,
`dispatchLoopStateFast` (plus the general `dispatchLoop`/`dispatchLoopState` and the
`clauseSuspended` siblings) each carry a copy of the escape/attach choreography, and the three
gate pairs in the loop are identical. And the protocol itself is already declared to be the
eval's: "The text lives here because the protocol is the eval's: what the cell means, when
state commits, how a bail re-enters the eval."

So the ownership the proposal asks for is the ownership the file comment already concedes.

## 4. The design this points to

**One staged delivery template, owned by Eval, instantiated per site.**

- Eval (or an Eval-adjacent internal object) carries a single `inline def` skeleton: poll, run
  the clause step, deliver the answer, classify the next shape, bail per the cell protocol. It
  is parameterized by an `inline` family kernel (the 10-25 line difference above) and the
  clause. The three `ArrowEffect.handle*` expansions instantiate it; each site still emits its
  own compiled copy with the clause statically bound. Nothing about the staging mechanism or
  the measured fast-path numbers changes by construction; what changes is that the protocol has
  one source of truth, located with its owner.
- The dispatch trio collapses around one gate predicate, one k computation, one `h.answers`
  call, and one escape choreography (this is the advisor's dispatch-dedup lane reached from the
  other side).
- Post-P1 (canonical shapes), `nextAnswer` collapses toward one compose rule and moves to Eval
  ownership as the shape-classification step of the staged window.
- The `Out` cell stays: it is the side channel across the megamorphic `h.answers` boundary, and
  the throw path needs it precisely when no return value exists. Its lanes simplify once the
  shape dance does.

**What it deletes**: two of the three loop skeletons, two of the three fast dispatches and much
of the general pair, the triplicated catch/escape blocks, and (post-P1) most of `nextAnswer`'s
arms. Order 300-400 lines of the densest protocol code in the module, while keeping the
answers-loop performance that D4 would have to re-earn. It is strictly less radical than D4 and
strictly more than the advisor's lane 3.

## 5. Risks and costs, named

- **Inline nesting compile time.** The concessions table records that inline nesting measurably
  inflates compile time; it is the reason the `*With` overloads copy their bodies instead of
  delegating. The unified skeleton adds one expansion level (handle* expands the skeleton which
  beta-reduces the family kernel). Today handle* already expands `answersLoop`, so the delta is
  one inline-lambda application per site, but this is a claim to measure, not to assert: the
  harness records compile-time figures per leg.
- **Bytecode identity.** The staged skeleton must expand to the same size class as today's
  loops or the JIT profile shifts. The check is the full bench class on both variants plus the
  bytecode reader on one expanded `answers` method.
- **The family kernels are semantic, not cosmetic.** The suspended-answer sub-arm exists only in
  the outcome families; Cont has no destructure at all. The parameterization must keep the
  kernels as supplied code, never force them through a common shape that erases an arm.
- **State commit points.** LoopState writes `st` at every exit arm; a skeleton that owns the
  exits must give the kernel a hook at each one, or state commits drift. This is the subtlest
  part of the unification and the one to pin with tests first (the existing handleLoopState
  interaction pins in MaskTest and ArrowEffectTest cover replay and threading).

## 6. How it sequences with the running plan

1. **P1 first** (fusion law + gate word, built and suite-green): canonical delivery shapes are
   what let the skeleton's classify step be one rule. Unifying before P1 would triplicate the
   current nextAnswer dance into the template's contract.
2. **P0 still runs** (fast paths off, full class): if the eval-general path were close, deleting
   the machinery (D4) would beat unifying it, and the unification would be wasted motion. P0 is
   the cheap arbiter between "delete it" and "own it once".
3. **Then the unification as its own measured step**: one variable (source refactor, no
   protocol change), full bench class both variants, compile-time figures read, bytecode of one
   expansion compared.

## 7. Bottom line

The proposal is sound and lands on a real seam: the staging mechanism is already the design,
but the staged text is a parallel machine whose protocol belongs to the eval by the file's own
admission. Scoped to the same-tag delivery window, "stage Eval into the inlined classes" means:
one Eval-owned inline skeleton, three small family kernels, one dispatch choreography, one
shape rule. It deletes the duplication D1 exposes without giving up the numbers D4 would
gamble. The maximal reading (a full per-site eval) is closed off by the recorded inline-eval
failure and the budget arithmetic, and should stay closed.
