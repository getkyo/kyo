# Kernel simplification: reflection on the effectful-Loop investigation

Standing instruction, verbatim: "I already think we have a lot of unnecessary complexity and your
optimizations are clearly ad-hoc patches. I need you to take a step back and think of ways to
simplify the code and ensure the properties we need out of the box without a lot of edge cases."
And: "when I say simplify I mean everything is on the table to change."

This report is that step back. It records what the investigation established (all of it measured),
what I did wrong by the kernel skill's own rules, the structural reading of why the complexity
exists, and the design space for removing it. It ends with the questions a held-out advisor should
weigh in on. Nothing here is a decision.

## 1. What the investigation established (verified facts)

The starting question was why an effectful `Loop` iteration costs ~4x a hand-written recursive
method for the same work. Every step below has tool output behind it (KernelBench rows with
`-prof gc`, async-profiler alloc and itimer recordings, and targeted probes in the suite).

- Pure iteration: `Loop` is the fastest spelling measured (1.7ns/step), ~4x faster than both the
  recursive method and `Arrow.recursive` at identical allocation. The dedicated `@tailrec` strict
  path earns its keep.
- Effectful iteration (one suspension answered per step): the recursive method runs 9ns/step at
  64B/step; `Loop` ran 38.6ns/step at 152B/step before this campaign, 128B/step after the one fix
  that stands (the shared re-entry arrow, commit b6abb47462).
- The time mechanism, named from profiles: the baseline row lives inside the handler's compiled
  answers loop (`Handler.nextAnswer` dominates its cpu profile); the `Loop` row falls off that
  loop on every iteration and pays capture machinery instead (`Stack.loop$2`, the dump fold,
  dominates: a rebuilt `Defer`, an `AndThen` per capture, and a fresh eval entry per answer).
- Why it falls off: the answers-loop dispatch (`nextAnswer`) accepts exactly two shapes, a bare
  same-tag `Suspend` and a single-level `Defer` over one with at most one non-identity arrow. An
  effectful loop iteration is a suspension under TWO pending transforms (the user's map body and
  the loop's re-entry step), which arrives either as a nested `Defer` or, flattened, as a
  two-arrow `Defer`; both bail.
- The eval's entry gate compounds it: the fast dispatch into the answers loop fires only for
  `pos == 0` (handler on top) or `pos == 1` above one plain `Step`. Two entries above the handler
  route every answer through the general single-answer path (`dump` + `h.run` + re-entry).
- A subtlety that killed one patch iteration: `Chain` (the O(1) accumulation node) applies by
  DEFERRING, deliberately, because its tail may carry a region whose scope must be installed
  before the head runs (pinned by EffectTest's "failure in map"). `AndThen` (the normalized run,
  built by `Stack.dump`) applies fused and strict because its shape guarantees region-freeness.
  Composing at the answer site with `chain` therefore re-introduced a deferral per answer and
  oscillated the loop between compose and bail, regressing allocation to the original 1.52MB/op.

## 2. What I did wrong (by the skill's rules)

- **No equation first.** The equation was available from the start: `Loop(a)(run)` with a
  suspending `run` is `run(a).map(step)`, a suspension under a composition of length two, versus
  the baseline's composition of length one. The whole problem statement is "the fast path handles
  compositions of length <= 1". I profiled machinery and patched machinery instead of writing
  that sentence and asking where compositions should normalize.
- **Infrastructure stacking.** Three coordinated patches (a normalization case in `Effect.defer`,
  a compose arm in `nextAnswer`, a third gate arm in the eval), each discovered empirically after
  the previous one proved insufficient, each adding a special case to a different file. The skill
  names this exact signal, and the user caught it before I did.
- **Harness bypassed.** The bench-harness beside the skill enforces the measurement protocol; I
  hand-rolled bash brackets and hit exactly the failure classes the harness exists to prevent
  (a polluted after-run, a first-invocation empty match, sources edited while a run was in
  flight).
- **`-f 1` numbers reported as results**, and one comparison table published from a run whose
  machine had two 32GB builds on it.

The three patches are preserved, suite-green, at `parked/loop-fastpath-patches` (dfe6c7f3a8),
with their measured states in the commit message. They are evidence for this report, not a
proposal.

## 3. The structural reading: where the complexity lives

The kernel currently carries **four places a continuation can live** and **three composition
node kinds with three different application semantics**:

| carrier | built by | applies | region-safe? |
|---|---|---|---|
| `Suspend.cont` | suspend/suspendWith fusion | via delivery | n/a (single slot) |
| `Defer.contA` / `Defer.contB` | `Effect.defer` (2- and 3-arg) | eval pushes both as entries | yes (entries) |
| stack entries | eval pushes | popped per delivery | yes (scanned) |
| `AndThen` | `Stack.dump` fold | fused, strict | by construction (Step head, Cont tail) |
| `Chain` | `Arrow#chain` | defers, always | by deferral |

The answers fast loop is a fifth machine with its own currency (one `k: Arrow` plus the `Out`
cell), and `nextAnswer` is the ad-hoc translator from the building-tier shapes into it. Its bail
conditions are exactly the mismatches between these representations. Every fast/slow split in the
eval's dispatch (per handler family: cont, loop, loop-state) is another instance of the same
translation problem.

None of these pieces is unjustified in isolation; each has a measured win or a correctness pin
behind it (the file comments and the skill's concessions table record them). The complexity is
in their PRODUCT: shapes built by one tier must be re-classified by every consumer, and each
consumer grows arms for the shapes it can accept. The effectful-Loop cliff is not a Loop bug; it
is the visible seam of this product.

## 4. The properties wanted out of the box

Stated as requirements, independent of any representation:

1. Settled path allocates nothing; strict runs fuse (current: yes).
2. A suspension under any region-free run of transforms is answered by its handler at the same
   cost regardless of the run's length: one delivery, no capture, no per-answer re-translation
   (current: only length <= 1).
3. Regions (handler, catching, binding, finalizer) are always installed before code that can
   fail into them runs, and always findable by the scans (current: yes, via the stack).
4. Everything handed out is a complete value: multi-shot, replayable, thread-independent
   (current: yes; must not regress).
5. Budget/park semantics: strict work is bounded, parks carry full state (current: yes).
6. The number of shape special-cases a consumer needs is O(number of node kinds), not O(number
   of construction paths) (current: no; this is the ask).

## 5. The design space (everything on the table)

Ordered from least to most structural. Each is a direction to evaluate, not a proposal.

**D0. Documented deviation.** Keep the tree as committed (shared-arrow fix stands, measured).
Write the 4x down per the deviation protocol with the full mechanism map above. This is the
fallback, now honestly available because the mechanism is completely named.

**D1. Canonical building node.** Collapse `Defer(value, contA, contB)` to `Defer(value, cont)`
with one slot, and make every kernel construction site compose into it with the region-aware
discipline (`AndThen` when the facts are statically known, which they are at the kernel's own
wrap sites: a map's arrow is a plain Step; a handle's is a region and stays eval-visible). The
eval's Defer arm then pushes ONE entry, so an effectful loop iteration sits at `pos == 1`; the
gate must learn to admit it, because a fused entry is an `AndThen`, which is a `Cont` but not a
`Step`, and the three gates test `Step`. The gate change is itself a simplification: `Cont` is
the designed name for a normalized region-free run, and `Cont` and `Region` are disjoint by
construction. `nextAnswer` needs one compose rule instead of arity cases; the third gate arm
from the patch lane dissolves. Risks to check: `parkable`'s receiver walk must still see a
`BindingStep` through an `AndThen` head; `EffectTrace` walks `contA`/`contB` for frames;
`Stack.dump`'s fold sends a non-`Step` entry through `chain`, so a fused `AndThen` under
further steps would capture as a deferring `Chain` and regress trailing-map delivery unless the
fold gains a bounded append arm; and fusion must stay depth-bounded, because universal
construction-time composition right-appends into a head-first `AndThen` at O(depth) per wrap,
the fused-wrapper failure the skill's catalog records (the O(1)-accumulation argument holds at
depth 1 only).

**D2. Fold into the suspension.** Generalize suspendWith's law: a region-free transform over a
suspension IS the suspension with the transform composed into its cont
(`defer(Suspend(in, sc), a) = Suspend(in, compose(sc, a))`). Values bubbling out of combinators
are then always bare suspensions with fused conts; `nextAnswer`'s first arm covers everything;
`Defer` shrinks toward the genuinely-deferred cases (regions, budget rescues). Same risk list as
D1 plus: re-minting a `Suspend` per wrap replaces a `Defer` per wrap (allocation parity to
verify), and the suspension's identity/frame semantics under re-minting need a look (traces,
debugger hooks).

**D3. One composition currency, no Defer.** A pending computation is
`settled | Nested | Suspend(tag, input, cont)` and nothing else; every reification folds into a
cont chain built from one link discipline (fused links for region-free steps, explicit region
links for scopes). The stack may then reduce to a view or vanish into the chain (the scans walk
the chain). This is the deepest cut: it deletes `Chain`-vs-`AndThen`, the Defer arms, the dump
fold, and most of `nextAnswer`, and it converges partway back toward the CPS sibling's
representation with the array-stack's roles reassigned. The costs that produced the current
design (O(1) accumulation, the measured stack wins, dump-once capture) must be re-established
from scratch; this is a redesign campaign, not a change.

**D4. Delete the answers fast loop.** If delivery shapes are canonical (D1-D3), capture becomes
O(1) (the cont is already a value), and the eval round trip per answer may be cheap enough that
the per-site generated answers loops, the `Out` cell protocol, `nextAnswer`, `resuspend`, and
the per-family fast/slow gate splits all become deletable. This is the largest simplification on
the table and the one with the most measured wins to re-earn; the concessions table records what
each piece bought when it was added. Whether the eval-only path can reach the answers-loop
numbers is a measurable question, not a matter of taste.

The lanes compose: D1 or D2 first as the canonical-form step, then D4 becomes testable, and D3
is the horizon if the numbers support it.

## 6. What stands, what is parked, current state

- Committed and verified on the working branch: the Loop shared re-entry arrow (b6abb47462,
  suite green, 1.52M -> 1.28M B/op on the effectful row, pure rows byte-identical); the
  KernelBench iteration rows (e10fc6ce20); Mask; the docs work. The tree is clean at HEAD.
- Parked: the three-patch fast-path lane at `parked/loop-fastpath-patches` (dfe6c7f3a8), with
  measured states recorded. Not a proposal.
- Open defect (per the regression rule): effectful `Loop` remains ~4x the recursive spelling
  with the mechanism fully named. It stays an open item until a design above lands or D0 is
  explicitly ruled.

## 7. Questions for the held-out advisor

1. Is the two-tier composition split (O(1) unnormalized building vs normalized delivery) worth
   keeping at all, or is a single canonical form (D1/D2/D3) the right target? What does the CPS
   sibling's experience say about the costs each way?
2. If canonical: is the right canonical carrier the Defer node (D1) or the suspension's own cont
   (D2)? What breaks that this report has not listed?
3. Is D4 (deleting the answers-loop machinery) realistic on the JIT profile this kernel targets,
   or do the generated per-site loops buy something an eval-only path cannot recover?
4. Is there a smaller cut this report missed that achieves property 2 (length-independent
   region-free delivery) without touching the representation?
5. In what order should the probes run, and what is the minimal probe for each direction that
   produces a decisive number before any real investment?

## 8. Corrections after the held-out advisory

The advisory (`reviews/KERNEL-SIMPLIFICATION-ADVISORY.md`) verified this report's mechanism map
against source and flagged three factual problems, each confirmed against the code and corrected
above:

1. The D1 sketch originally claimed the existing `pos == 1` gate admits the fused shape. Wrong:
   the fused entry is an `AndThen`, a `Cont` but not a `Step`, and the gates test `Step`
   (Arrow.scala declares `AndThen extends Cont` only; Eval.scala's three gates test
   `entry(0).isInstanceOf[Arrow.Step]`). D1 requires the gate to classify by `Cont`.
2. The D1 parenthetical suggesting the O(1)-accumulation argument "may be void" was misleading:
   it holds only at fusion depth 1. Universal construction-time composition right-appends into a
   head-first `AndThen` at O(depth) per wrap. This also rejects D2 as a universal law; D2's
   narrowed form already exists as `Handler.resuspend` at bail sites.
3. The D1 risk list omitted `Stack.dump`'s fold, which sends a non-`Step` entry through `chain`
   (Stack.scala), so fused `AndThen` entries under further steps would capture as deferring
   `Chain`s and regress trailing-map delivery without a bounded append arm in the fold.

The advisory's own contributions beyond the corrections: the essential/incidental split (three
essential commitments; the incidental part is consumers classifying by position, identity, and
arity instead of the sealed `Cont`/`Region` vocabulary), the D1-staged three-edit cut (fusion law
in the 2-arg `Effect.defer`, gate word `Step` to `Cont`, `nextAnswer` compose rule), the D4a
midpoint, the eval dispatch dedup lane, and the P0 calibration probe (`fastPathsAllowed` already
gates all three fast dispatches, so one flag flip prices the eval-general-path ceiling).
