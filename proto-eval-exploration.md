# Eval exploration: typed, compositional, smaller

The charter for re-approaching the evaluator as a whole under the kernel skill
(`kyo-kernel2/.claude/skills/kernel/SKILL.md`). Five paths, explored wide, each as isolated experiments. This
document is the working record: scope, expectations, skill analysis, and tracking live here.

## The bar

**Smaller wins.** The result that matters is a smaller kernel: fewer parallel mechanisms, fewer erased patterns,
fewer casts, fewer lines, with the suite green and the boards flat. A rewrite that is cleaner but larger has
missed the point; the old kernel's one-line suspended-clause case is the exhibit that the right composition
*shrinks* code. The guard against overcorrection: smaller means fewer concepts and no duplicated dispatch, never
golfed code, removed protections, or dropped tests. Every protective measure the skill lists stays; the size
ledger below counts what legitimately shrinks.

Non-negotiable gates, from the skill, applied to every experiment:

- Full proto suite green (110 at baseline; acceptance tests never weakened).
- The board rows the touched path can affect, flat against the references below.
- Bytecode diff on any hot method brushed: zero new `nest` calls, no size drift on the fused-row line.
- Compile bench when a change touches expansion sites (inline surface).
- Casts only within the skill's closed categories, necessity compiler- or test-verified.
- Hot/cold rule: types come back behind the first branch; the first branch and its fast arms keep their bytes.

Working discipline: all design decisions are made in the main session's context (the skill guardian). Opus
agents may be used for analysis (reading, auditing, proposing); Sonnet for mechanical execution (applying a
specified diff, running suites, collecting numbers). No agent decides a design question.

## Baseline (2026-08-16, commit b345a1412d)

| metric | value |
|---|---|
| proto source lines | 2275 total; Eval 236, ArrowEffect 249, Arrow 150, Pending 299, Loop 641 |
| `asInstanceOf` | 37 (Loop 15, Stack 7, Arrow 6, Eval 4, Pending 4, CanLift 1) |
| `@unchecked` | 42 (Eval 17, Loop 10, ArrowEffect 9, Pending 5, Arrow 1) |
| erased type-lambdas (`[X] =>> Any`) | 14, all in Eval |
| suite | 110 tests green |
| runtime board | bench-results/proto-kernel-v19.json |
| compile board | compile-bench-v1.json + v2 (reds rerun) + v3 (for-comp re-port) |

## Path 1: the named outcome driver

**Scope.** Replace the suspended-clause dispatch (`rehandled` in Eval) with a named adapter Transform, sibling
of `resume`: union currency in, union currency out, no `map` expansion in the loop, no silent lift; `done`
passes through with representation untouched; the re-arm arm (`case p: Arrow => Chain(p, this.chain(next))`) is
the old kernel's `v.map(self)` made literal. Add the interior tiering, mirroring the `handleCont` arm's three
shapes: empty interior (no capture), unmarked interior (chain fold), marked interior (segment). Add the same-tag
pipeline test (stacked regions of one effect, the inner clause suspending on it per operation, Stream.map
shaped) and a multi-shot replay of the dispatcher; add the emitting-handler bench row.

**Expectations.** Slightly smaller than the landed `rehandled` (the map lambda, its Frame plumbing, and the
Continue2 nesting flatten into the adapter's arms). Streaming-shaped programs drop from three array copies per
element to zero (empty tier) or a node fold (unmarked tier); the per-element cost lands in the same allocation
class as the old kernel's per-element wrap. Settled paths untouched by construction.

**Skill analysis.** Composition: the dispatcher is the operational reading of the equation already documented in
`proto-effectful-clauses.md`; the change removes user-boundary machinery (`map`'s unnest/lift contract) from a
currency position, which the skill's "conversions are not free safety" rule flags in the landed version.
Casts: one representation assertion (the `done` pass-through, resume-pattern), category "representation
assertion"; the silent lift in the landed `done => done` arm is removed, a net safety gain. Concession shape:
the per-suspension rebuild is the concession (reconstitution price of the answer-only protocol); justification
is the settled path's zero cost; protection is the tiering plus the pinning tests; the bench row is the measure.

**Kill criteria.** Acceptance set regresses; handler rows move; the tiering complicates the dispatcher beyond
what the three `handleCont` shapes already justify.

## Path 2: types return to their owners

**Scope.** Match-then-typed-final-call. The dispatch tails of the three handler kinds move into final methods on
their own classes (`Handler.HandleCont`, `HandleLoop`, `HandleLoopState`), where `I, O, E, A, B, S` are in
scope natively; Eval's arms become instanceof-then-call. `Suspend` gains a typed `answer` method so the
raw-payload contract becomes a signature instead of a convention. `Handle` owns its marker layout (the
push-cont-push-marker pairing) so Eval stops knowing it. Hot fast arms (the settled-answer in-place paths)
remain inline branches of the loop.

**Expectations.** The largest erasure cleanup available: most of Eval's 17 `@unchecked` and 14 erased lambdas
exist to re-state types the handler classes never lost. Eval shrinks substantially; ArrowEffect/Arrow grow less
than Eval shrinks (the logic exists once either way, but the erased re-statements disappear). Call sites are
monomorphic after the match, so machine code is unchanged where it matters.

**Skill analysis.** Composition: nodes and handlers are reifications of combinators; their evaluation step
living on them makes each node its own meaning, which is the composition stance applied to code layout.
Casts: this path deletes erased patterns rather than adding casts; the typed methods need none. Concession
shape: the concession is a `private[proto]` machine interface (what the owners may do to the stack); it must
stay minimal, justified per member, and is itself the protective measure (owners cannot reach machine state
except through it). Watch: the interface growing into a second evaluator API is the failure mode.

**Kill criteria.** The machine interface needs more than a handful of members; any row moves; the split makes
the dispatch harder to follow than the erased original (size is the measure, not aesthetics).

## Path 3: typed helpers for the unowned logic

**Scope.** The old-kernel transcription applied where path 2 has no owner: cross-cutting evaluator logic
(`settle`, the capture ladder, `dump`, the adapters) becomes polymorphic private defs whose method type
parameters re-bind what the wildcard match erased (capture conversion: match `case s: Suspend[?, ?, ?, ?, ?, ?]`,
call `onSuspend(s)`, and the helper's signature names the types). Placement per the hot/cold rule: helpers take
the cold tails; hot lines stay in the loop.

**Expectations.** The remaining erased lambdas and unchecked patterns in Eval concentrate here or disappear;
`Any` recedes from signatures into the loop's top-level match only. Modest size change either direction;
the win is legibility and compiler-checked dispatch bodies, not lines.

**Skill analysis.** Composition: neutral (this is typing, not semantics). Casts: replaces erased-pattern
assertions with compiler-verified bindings; any surviving cast must re-justify under the categories. Concession
shape: none new; the experiment must show the helper boundary sits off every hot line (bytecode diff of the
loop method).

**Kill criteria.** A helper boundary lands on a hot line (suspension rows move); capture conversion cannot
express a dispatch that spans two matches (documented, that slice stays inline).

## Path 4: typed currency through the loop

**Scope.** Re-type the loop's state and helper signatures from `Any` to union currency: `cur` as `Any < Any`
(or a method type parameter where a helper is generic), settle/dump/resume signatures carrying type parameters,
expected-type patterns + `@unchecked` at inspection points, `unnest` only at the wrapper. Goal: representation
round trips become unwritable in the loop because currency and payload have different types.

**Expectations.** Zero bytecode change, by requirement. Several of Eval's remaining casts become widenings or
disappear; the two representation assertions at re-delivery sites remain (they are load-bearing per the skill)
but become the *only* places currency is asserted rather than typed.

**Skill analysis.** Composition: neutral. Casts: pure reduction; each removal compiler- or test-verified per the
skill's rule, with the known trap front of mind: more precise types create more positions where the implicit
lift can silently fire, so the zero-`nest`-call bytecode diff is the hard gate, not a formality (this exact trap
produced the infinite-recursion near-miss and the nine-test silent failure). Concession shape: none new.

**Kill criteria.** The opaque-outside-companion wall forces more assertions than it removes; any bytecode drift
on the fused line.

## Path 5: the integrated rewrite, held by a conformance net

**Scope.** After 1 to 4 report: rewrite Eval as a whole, composing the winners into one coherent evaluator: one
dispatch protocol whose settled arms are byte-preserved fast paths, owners' methods, typed currency, the named
driver. Before the rewrite lands, build the net that makes it safe: a conformance suite running a shared
program corpus through kyo-kernel and the proto, asserting identical results, covering the hostile axes
(multi-shot, crossings, nesting, effectful clauses, budget parks, state threading). Final sign-off: full board
plus compile bench against the baseline references.

**Expectations.** This is where "smaller" is measured for real: the target is a net reduction against the
baseline table above, concentrated in Eval, with no board movement. If the integration cannot beat the current
Eval on size, the paths' individual landings stand and the full rewrite is declared not worth it, which is an
acceptable outcome of the exploration.

**Skill analysis.** Composition: the rewrite exists to make the evaluator read as the realization of stated
laws, arm by arm. Casts: end-state inventory must be at or below the post-path-4 count, every survivor
categorized. Concession shape: the conformance suite is the protective measure this path adds; the fast paths
are the concessions, each traceable to the law it specializes.

**Kill criteria.** Net size increase; any conformance divergence from kyo-kernel that is not a documented,
user-approved design difference (the handler surface, dynamic tag scoping); board or compile-bench regression.

## Divergence policy

Exploring a path not on this list is allowed and expected when the work justifies it; wide is the goal,
wandering is not. Requirements, all of them, before work starts on a divergent path:

1. **A written justification in the tracking log**: the observation that motivates it (a kill criterion fired,
   two paths converged, an experiment exposed a simpler composition than anything listed, a skill rule conflict
   emerged), and which listed path it replaces, merges with, or augments.
2. **The same shape as the listed paths**: scope, expectations, skill analysis, kill criteria, gates. A
   divergent path without kill criteria is not a path, it is a tangent.
3. **It must serve the bar**: state explicitly how it could make the kernel smaller or safer. "Interesting" is
   not a justification.
4. Design remains in the main session's context; agents follow the same analysis/mechanical split.

## Tracking

Update this section as work proceeds; every experiment gets a row, every decision a line. Killed paths keep
their entries and their reasons.

### Status

| path | status | notes |
|---|---|---|
| 1. named outcome driver | pending | starts first; design agreed in session |
| 2. types to their owners | pending | run after 1; HandleLoop first as the probe |
| 3. typed helpers | pending | after 2, on what 2 leaves unowned |
| 4. typed currency | pending | after 3; strictest gate (bytecode identical) |
| 5. integrated rewrite + net | pending | only after 1 to 4 report |

### Experiment log

| date | path | experiment | result | numbers |
|---|---|---|---|---|
| 2026-08-16 | (pre-charter) | E1: dispatch in place, region kept | failed as predicted | interior stole clause effect; `completed was true` |
| 2026-08-16 | (pre-charter) | E2: capture + recompose via map | green, landed 22c675072d | 110/110; settled paths untouched |

### Decisions

- 2026-08-16: charter written; baseline recorded; order 1 -> 2 -> 3 -> 4 -> 5.
- 2026-08-16: the landed map-based dispatch is superseded by path 1's named driver (silent lift in the `done`
  arm; representation round trip; user-boundary machinery in a currency position).

### Size ledger

| checkpoint | proto lines | casts | @unchecked | erased lambdas | suite |
|---|---|---|---|---|---|
| baseline (b345a1412d) | 2275 (Eval 236) | 37 | 42 | 14 | 110 |
