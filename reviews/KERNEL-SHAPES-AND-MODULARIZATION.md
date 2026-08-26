# Computation shapes and the modularization of Eval

Prompt, verbatim: "do a broader exploration of how we could modularize Eval itself for a simpler
impl that leverages this optimization pattern. Aren't there other hot parts we could put the root
in inlined code? can we share more impls? can we simplify Eval and related code that way? read the
kernel skill once more and explore this", extended mid-exploration with "maybe do a study of
'computation shapes' as well".

Sources read for this document: the kernel skill in full, Eval.scala in full (every arm and
helper), Handler.scala's templates and protocol, ArrowEffect's suspend/suspendWith/handleCont
expansions, Pending.scala's map/flatMap expansions, Arrow.scala's hierarchy, Safepoint's
enter/exit, Debugger, and the measured bytecode sizes from the compiled classes of this tree.

---

# Part I: the computation-shapes study

## 1. The shape grammar

What a value in flight can be, as three tiers:

**Settled tier**: a raw value, or `Nested` wrapping a `Boxed` payload. Zero-allocation, delivered
strictly.

**Node tier** (the `Kyo` kinds, each the reification of a combinator, per the skill):

| node | reifies | carries |
|---|---|---|
| `Suspend(tag, input, cont)` | an effect operation | cont: `Id` (suspend) or a site `Step` (suspendWith) |
| `Defer(value, contA, contB)` | a pending application | two arrow slots, contB usually `Id` |
| `Handle(value, handler, cont)` | a region install | the site-generated handler |
| `Catching` | a recovery scope | node is its own `Region` entry |
| `Binding` / `Bindings` | a scoped value / a rebind | release, resume |
| `Park` | an ended slice | entries, states, marks, finalizers snapshots |

**Arrow tier** (what a continuation slot or stack entry can hold): `Id`; a site `Step`
(TransformBase anonymous class); `BindingStep`; `AndThen(Step, Cont)` (normalized, fused-strict);
`Chain(a, b)` (unnormalized, applies by deferring, may carry regions); and the `Region` entries
(`Handler`, `Catching`, `Binding`, `Finalizer`), which are deliberately not `Step`s so they can
never fold out of scan reach.

## 2. Producers and the shapes they build

- A `map`/`flatMap` site, strict with budget: no node at all; the result is the callee's own
  shape, delivered through `cont.head(f(v), cont.tail)` in the site's expanded `run` method.
- The same site over a suspension or with a drained budget: `Defer(v, siteStep, cont)` via
  `Effect.defer` (contB is `Id` at chain heads, the downstream continuation mid-chain).
- `suspend`: a bare `Suspend` with `cont = Id`. `suspendWith`: a `Suspend` whose cont IS the site
  step; note this is exactly the "fold the transform into the suspension's own cont" form (the
  advisory's D2), already present in its sound, narrowed shape.
- An effectful `Loop` iteration: `Defer(bodyShape, loopStep, Id)`; after P1's fusion law,
  `Defer(Suspend, AndThen(map, loopStep), Id)` when both arrows are steps.
- `handle*` with a settled body: no node; the strict arm delivers `done(unnest(v))` at the site
  (verified in handleCont). With a pending body: `Handle(v, handler, Id)`.
- `Stack.dump`: an `AndThen` run for step-only captures, a `Chain` when a region or a non-step
  entry is present.
- An answers-window bail: `Effect.defer(next, Id)` or a rebuilt `Suspend` (resuspend) under the
  same wrapper.
- The eval's clause-suspended re-entries: two Eval-owned anonymous `Defer with Step` classes.

## 3. Consumers and the shapes they accept

| consumer | classifies by | accepts |
|---|---|---|
| `Eval.loop` | node kind | all eight node arms plus settled, heat-ordered |
| the three gates | position + entry type | pos 0, or pos 1 with a `Cont` non-`Region` entry (post-P1) |
| `nextAnswer` | node + arity + identity | bare same-tag `Suspend`; one-level `Defer` with `contB eq Id` and at most one non-identity arrow |
| `parkable`/`receiver` | arrow class | walks `Chain`/`AndThen` heads hunting `BindingStep` |
| `dump`'s fold | entry type | `Step` runs fold to `AndThen`; anything else chains |
| the deliver arm | entry class | `HandlerLoopState`, `Handler`, `Binding`, generic entry |

## 4. One meaning, several spellings: where the incidental complexity lives

The essential shape variety is the node grammar itself (combinator reifications) plus the
Step/Region split (fold-safe vs scan-visible). The incidental variety is that one semantic value
can arrive at a consumer in several spellings, and every consumer grows arms per spelling:

1. **A region-free composition of length two**: `Defer(Defer(v, f, Id), g, Id)`,
   `Defer(v, f, g)`, and `Defer(v, AndThen(f, g), Id)` are one value in three spellings. This
   exact multiplicity is the effectful-Loop 4x (nextAnswer accepts none of the first two beyond
   arity one). P1's fusion law canonicalizes toward the third.
2. **Three carriers for "what runs after contA"**: the `contB` slot, a pushed stack entry, and an
   `AndThen` tail. Each consumer that meets a continuation must know all three.
3. **The bail wrapper** `Effect.defer(next, Id)`: a Defer whose only job is re-entering the eval;
   semantically it IS `next`, and every consumer that meets it pays an arm for it.
4. **suspend vs suspendWith**: `Suspend(cont = step)` is the collapsed spelling of
   `Defer(Suspend(Id), step, Id)`; both reach nextAnswer and it carries arms for both (`sc`/`ca`).
5. **Chain vs AndThen at capture**: necessary (region correctness) but leaks into every walker.

**The canonicalization principle that falls out**: producers may build any spelling in O(1), and
the smart constructors normalize exactly when the facts are statically known (P1's law is the
first instance; `suspendWith` was always the second). Consumers then classify by node kind plus
the sealed arrow tier and nothing else, which is property 6 of the reflection
(O(node kinds), not O(construction paths)) made concrete.

## 5. Heat, per shape

Hot: the settled strict tier (per-site `run` methods, budget bracket, `cont.head` chains); the
same-tag answers window; the `Defer` and `Suspend` arms; delivery into a popped entry. Warm:
capture (`dump`), region install, the general single-answer path. Cold: park, unwind, bindings
rebind, orphan drains, finalizers. The eval's own arm ordering already encodes this ("the three
arms below are last on purpose").

---

# Part II: roots and fragments: the kernel's compilation model, finished

## 6. The inventory of compilation roots as they stand

Reading the whole surface, the kernel is ALREADY a two-tier compilation architecture, built
tier by tier over the campaign but never named as the organizing principle:

- **Per-site roots for the strict tier.** Every `map`/`flatMap` site expands an anonymous
  `TransformBase` plus a local `run` method: the strict arm (union test, budget enter/exit,
  `cont.head` delivery) is per-site bytecode. Chains of maps fuse by per-site `run` calling the
  next site's apply through `cont.head`, a virtual call whose per-site profile is typically
  mono- or bimorphic, so the JIT devirtualizes and inlines whole strict chains into one root.
  Shared fragments staged into these roots: `Safepoint.enter`/`exit` (@static, the cold half
  out of line in `enterPark`), `Effect.defer` (the cold arm, non-inline by design),
  `Nested.unnest`.
- **Per-site roots for the answer tier.** The handle-site `answers`/`run`/`answer` overrides
  (~350 bytecode bytes measured), with `nextAnswer` (~245 bytes), `resuspend`, `Effect.defer`
  staged in by the JIT.
- **One shared root for the general drive.** `Eval.loop` (~1500 bytes, never inlines, by
  design), with the bulky and cold dispatches out of line, each compiled with its own budget
  (the file states this doctrine explicitly).

## 7. Question 1: are there other hot parts that could take a site root?

Each candidate examined against the inventory:

- **The handle-site settled arm**: already site-rooted (handleCont's `case _` delivers
  `done(unnest(v))` with no node and no eval).
- **Kernel combinators (Loop, bracket)**: already site-rooted through their inline expansions
  and site-minted arrows (the landed shared re-entry arrow is this pattern).
- **The suspension entry (find + gate + dispatch)**: no. It needs the live stack, its cost is
  once per window (amortized), and the skill explicitly marks the drive's megamorphic sites as
  expected, not chaseable.
- **The general path's per-answer re-entry**: no site knowledge exists (arbitrary shapes from
  arbitrary regions). The lever is not a new root; it is keeping shapes canonical so fewer
  answers fall off the rooted tier. The effectful-Loop 4x was exactly a canonical-shape failure,
  not a missing-root failure.
- **Delivery into popped entries**: the callee already IS per-site code (the entry's own apply);
  the megamorphic call site in the eval is the expected cost of generality.

**Honest conclusion**: the hot tiers already have their roots. The gap this campaign found was
never a missing root; it was non-canonical shapes falling off the rooted tiers into the shared
drive. The modularization should therefore spend its effort on shape canonicalization (Part I,
section 4) and on sharing the machinery around the roots, not on minting new roots.

## 8. Question 2: what can be shared

Ordered by confidence, each one measurable variable:

1. **The escape choreography.** The push-cont/escape/attachThrow block appears in
   `dispatchContFast`, `dispatchLoopFast`, `dispatchLoopStateFast` (plus the general arms'
   variants and one inline copy in the HandlerCont general arm). One fragment, five deletions.
   Cold path (exception only): zero perf risk.
2. **The deliver-answer block.** The kind=1 "reattach the continuation, deliver the answer the
   way the general path would" block is triplicated across the fast dispatches and mirrored in
   the general ones. One fragment.
3. **The gate prelude.** Three identical gate pairs become one predicate plus one k computation
   above the family match (the advisor's dispatch-dedup lane).
4. **The clause-suspended re-entries.** `clauseSuspended` and `clauseSuspendedLoop` are
   near-identical anonymous classes differing by the state lane; a unification must prove
   allocation parity on the emitting rows first.
5. **The answers skeletons.** Source-level: one skeleton with per-family inline kernels (the
   verbatim-shared stop-poll, window countdown, nextAnswer dance, and Out writes stop being
   triplicated). Per the corrected staging exploration, the loop header and the clause stay
   spliced (root ownership, unconditional specialization); the bail-arm bodies can become
   Eval-owned fragments shaped like nextAnswer already is.
6. **The strict-tier splice itself (flagged, needs a ruling before any probe).** Every map site
   splices the same `run` boilerplate; a shared `TransformBase.apply(v, cont)` calling the
   abstract one-arg apply (the BindingStep pattern, which already exists for exactly this shape)
   would delete per-site bytecode across every kyo program. The cost model flips from
   guaranteed-by-splice to profile-driven devirtualization at `cont.head` sites, and the skill's
   catalog carries a direct warning ("inlining the delivery entry measured slower"). No recorded
   measurement exists either way for this specific trade; it is the highest-stakes candidate and
   the last to probe.

## 9. Question 3: what Eval looks like modularized

The target organization, stated as the principle the code already half-follows:

> **Hot repetition lives in per-site roots; generality lives in one shared drive; everything
> either of them calls is a fragment: small, straight-line, shared bytecode that the JIT stages
> into whichever root is hot. Shapes are canonicalized by the smart constructors at build time,
> so consumers classify by node kind and the sealed arrow tier only.**

Concretely, Eval.scala becomes: the drive loop with its heat-ordered arms (unchanged); ONE gate
prelude; three thin family dispatches over shared fragments (escape, deliver-answer, re-entry);
and the delivery protocol (the Out contract, nextAnswer collapsed post-P1 toward one compose
rule) owned and documented as the eval's own section. Handler.scala keeps the class hierarchy
and one skeleton template. Nothing about node kinds, region visibility, budget semantics, or
the complete-value contract moves; every fragment is the operational reading of an arm that
already exists, which keeps this inside the skill's composition-first rule (no new node kinds,
no new semantics, no new inline without sign-off; this direction removes splice rather than
adding it).

What gets deleted, summed across sections 4 and 8: the nested-Defer spelling at delivery (P1),
most of nextAnswer's arms, four escape-choreography copies, two deliver-answer copies, two gate
pairs, two answers skeletons, and possibly one clause-suspended twin. What is deliberately NOT
touched: the drive's megamorphic generality, the region-entry visibility contract, the strict
tier's guaranteed splice (pending the flagged ruling), and the answers windows' root ownership.

## 10. Probe plan

Ordered; each is one variable, chain-declared, full class through the harness:

1. **P0, P1** (built, suite-green, queued): the calibration and the canonicalization step.
2. **PS1** escape-choreography fragment: expect all rows flat (cold path).
3. **PS2** deliver-answer fragment: expect flat.
4. **PS3** gate prelude dedup: expect flat.
5. **PS4** answers-skeleton source dedup: expect flat runtime, measure compile time (the
   harness records it per leg); the *With concession says inline nesting can inflate it.
6. **PB** (from the staging exploration): the thin-stub window probe, priced by PrintInlining
   refusal strings and allocation.
7. **PF** the strict-tier shared-apply probe: only after an explicit ruling, because it trades
   a guaranteed property for a profiled one and the catalog carries a prior warning.

Section 8's items 1-4 are simplifications that stand on their own even if PB and PF both
refute; they delete duplication without moving any root or any splice.
