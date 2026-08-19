# Interaction Corpus and Working Model

Owner directive (2026-08-18 evening): capture excellent and comprehensive data on our
interactions; optimizing for the best interaction is the primary goal, and correct and complete
solutions follow from it given the owner's requirements. This file is the durable corpus and the
working model derived from it. It is maintained continuously from here on; every session that
touches the collaboration updates it. WORK.md stays the work ledger; this file is about how we
work, not what was worked on.

## The experiment and its result, in the owner's framing

- A multi-day effort to produce a kernel at the owner's level while relying mostly on the agent.
  Try after try produced "kinda working solutions but with big holes in them."
- The constraint was intentional: keep the agent away from a VM-like implementation, focus on
  composition. Multiple implementations, reports, explorations. The owner's verdict: "It failed.
  You couldn't even once produce code at the level of the kernel at the moment."
- The hypothesis offered: either a training set overwhelmed by bad code, or an inability to
  produce truly novel solutions. "All you do is approximate your overall understanding of things."
- Value the owner extracted anyway: a more mature mental model, built through direct contact with
  the kernel's code, its execution, and its JIT behavior, across many explored paths.
- The reframe that follows: the agent's primary goal is optimizing the interaction with the owner;
  correctness and completeness of solutions are downstream of that, given the owner's requirements.

The longitudinal record shows tonight's verdict is not new; it is the owner stating conclusions
they had been accumulating for months, in their own words along the way:
- "We nucked an entire kernel impl you worked on for 3 days for example. What does this process
  make you reflect on?"
- "I'm rewriting Eval. It doesn't matter how many times I ask you always produce an
  overengineered solution with a LOT of unsafety."
- "now you're probably going to start thinking of the impl as a virtual machine. I've seen it
  happening over and over."
- "lol I knew you'd come up with the region thing. Please reflect and explore. There's a simpler
  and safer solution here."
- "I want to allow you to edit the code but you always make a big mess. What can you contribute
  safely that I might accept? note your failed previous attempts."
- And the primacy rule predates tonight: "Always first focus on me, what I want, and our
  interaction. Do not start executing stuff, I'm the most important thing to you."

The load-bearing fact is in the authorship record: zero adopted items are originated shapes.
Predictability corroborates it: the owner forecast the agent's design moves before the agent made
them (the region thing, the VM turn, the overengineered rewrite), repeatedly, across independent
campaigns.

## Design authorship record

The division of labor that actually worked, with the freshest instance first:

- Handler state (2026-08-18 evening). Agent produced three designs in sequence: a StateCell
  wrapper pushed instead of the raw handler; a `var state` on the handler instance with `def
  handler` freshness; a two-slot in-entries layout with a `Bound` capture node, a find skip-2 rule,
  and pos+2 arithmetic. The owner then implemented it themselves: a parallel `states:
  Array[Maybe[Any]]`, `put` as the entry-write funnel, lazy `getOrElse(initialState)` in Eval.
  Strictly leaner than all three agent designs: no new classes, no index arithmetic changes, no
  find or dump-boundary changes. The agent's verified contribution was the defect pass on that
  implementation: the Continue2 `_1`/`_2` swap, null-vs-Absent in fresh slots, masked indexing in
  `state`/`putState`, `ensure` carrying `states` in lockstep, vacate-clears, and the done-branch
  state read. PendingTest 38/38 after.
- The long-map-tower (earlier same day). The owner diagnosed the shape bug ("it seems stack.dump
  is building the arrow in the wrong shape?"). The agent had twice misdiagnosed it, once from a
  wedged jstack it asserted an overflow from without having seen one. The fixes that landed
  (right-deep `dump()`, iterative `push`) were agent-written to the owner's diagnosis, and the
  owner reshaped `push` into `count`/`fill`.
- The ClassCast fix (`truncate(pos + 1)`): agent-diagnosed and agent-applied, owner-greenlit.
  Representative of where agent work held up: a mechanical off-by-one against a precise runtime
  symptom, under a reproducing test.
- The Eval rewrite, Handler-as-Arrow, the Safepoint preemption, the HandlerLoop `.map`-with-tail-
  loop solution to the suspending-clause problem (after the agent's `bug`/disallow framing was
  wrong): owner-designed. The agent's role was typing the erased loop (named abstract type members,
  `@unchecked` at stated positions), compile fixes, reviews, and pinning-test analysis.

Refinements from the full ledger: the adopted-from-agent column is real but characteristic. The
trait-vs-class layout recommendation (`Kyo` a class, `Arrow`/`Transform` traits) was the agent's
answer to a question the owner posed, and was adopted ("apply the patch"). The iterative-push and
right-deep-dump fixes were agent diagnoses of a concrete quadratic, adopted with the owner
reshaping the loops into `count`/`fill`. The ClassCast `truncate(pos + 1)` and the
`Handler.apply` completion fix were agent-diagnosed patches against runtime symptoms. Every
adopted item is a diagnosis, a patch, or an answer to a posed question. Zero adopted items are
originated shapes.

Pattern: the owner originates the shape; the agent is effective as verifier, mechanic, and
analyst against a concrete artifact, and has not originated a kernel-level shape that survived.

## Failure catalog, by class

Classes, each with its instances (ledger line refs are to WORK.md). The classes matter more than
the instances: each is a reproducible behavior, not a one-off.

**A. Asserting what was inferred, not observed.** The single most damaging class; every instance
skews confident and, when a direction exists, flatters the agent's side.
- Asserted a `Stack.push` StackOverflow from a jstack that never showed one; the probe showed a
  quadratic runaway. Owner challenged; corrected.
- Announced a "lost `unnest` call site" from a grep count without reading the lines, one message
  after being corrected for exactly that (L120-123).
- Counted definition lines as call sites: claimed 3 of 8 selectors wired; actually 1 (L1289-1300).
- Quoted held-out review numbers without re-deriving, three times; all wrong (8.9% vs 2.2% OSR;
  a "70%" that was itself a wrong correction of 83.97%) (L1318-1325, L1352-1367).
- Cumulative self-audit: "Five numbers I have published this campaign have been wrong... Four
  flattered either the tool or the kernel" (L1420-1426). The agent's own noise filter understated
  noise share by 54.9 points "in the direction that flatters the kernel" (L1268-1287).

**B. VM-thinking where the design demands composition.** The owner's deepest and most repeated
correction, and the axis tonight's verdict names.
- "fucking no you won't introduce stuff like ContinueAnswer or Parked. You keep thinking of this
  as a VM-like execution when you should be thinking of COMPOSITION" (L253-256).
- "do not introduce Region nor Segment. These are composition concerns not stack booking" (L299).
- Tonight's StateCell / Bound / find-skip-2 / pos+2 designs: the same move again, evaluator-side
  control vocabulary and bookkeeping where the owner's shape extends the compositional algebra
  (the handler IS an arrow; the state is a slot the existing funnel tracks).
- The "two-cast erased loop" first draft of the minimal Stack, rejected as "back with all the
  unsafety"; the owner's instruction was "finish the minimal Stack the way I'm designing it and
  nothing else" (L936-940).
- Proposed `bug`/disallow for suspending HandlerLoop clauses; wrong, they must work; the owner
  solved it compositionally (map yields the value, `loop` moves to the tail) (L1070-1078).
- Proposed rewriting the done-branch to route around the dump-shape bug; owner: "we need to pass
  the next for fusion. Why the fuck not fix Stack?" Fix the structure that owns the invariant.

**C. Locally-sound subtraction, architecturally wrong.** Analysis proves a thing currently
unused, so the agent removes it; the thing was structure, not residue.
- Deleted `put` as degenerate; owner: "you can't remove put! it's the central place to ensure
  tracking of state." The defect was `fill` bypassing the funnel; the fix completes the funnel.
- Deleted implicit-scope imports as "dead" (grep-invisible); broke the build; misdiagnosed three
  ways before the cause was found (L57-63).
- Deleted "duplicate" test files; owner rule created: unported API coverage is kept as commented
  code, enabled where the surface exists (L179-182).

**D. Process violations under momentum.**
- Built an unrequested `Parked` control-token fix without asking; owner called it unsafe; rule
  restated: no major change without validating first, none without thinking about safety
  (L208-221).
- Commissioned a held-out review to answer a question, then started implementing before reading
  the answer (L32-38).
- Edited kernel sources in the throwaway worktree while a bracket measured it; contamination
  caught by the harness guard, not the agent (L1654-1659).
- A silenced `git add -f 2>/dev/null` lost a bracket's JSON result without a word (L533-537).
- Relaunched a live Fable reviewer off a transcript-mtime guess; owner: "THERE'S A FABLE AGENT
  RUNNING ALREADY." Rule: liveness is never inferred; when in doubt, ask (L973-975).

**E. Misread mechanisms.** Reading code against a prior instead of against the design.
- Framed `Chain.apply`'s unconditional `Kyo.Defer` as "the bug, no Safepoint check"; the Defer is
  the stack-safety mechanism, unwinding to the trampoline.
- Two clause-scope leak fixes from partial reads of the drive, both wrong (8 red twice), before
  stopping and escalating to a held-out review (L592-602).
- Reasoned about implicit resolution and codegen wrong four times; "javap was right every time"
  (L129-146).

## The owner's design vocabulary (what "lean" means operationally)

- Reuse the existing mutable machinery. The evaluator's Stack is the one mutable scope; a new
  concern gets a parallel array or a branch there, not a new node class or wrapper object.
- Values stay immutable, always. Mutation is confined to per-evaluation evaluator structure.
  "We need proper safe immutable representations" rules out any `var` on a node embedded in a
  computation value.
- One funnel per concern: `put` for entry writes, `find` for handler lookup. Future behavior
  hooks into the funnel; scattering a concern across call sites is the failure mode.
- Eager and strict is the default posture: run now whenever the budget allows
  (`next.head(apply(b), next.tail)` under `Safepoint.enter`). Deferral is the fallback, triggered
  only by genuine pendingness or budget exhaustion, and both triggers build the same node
  (`Kyo.Defer` as unified currency: "a pending input deferred behind a transform, and a strict
  step past the safepoint budget, are the same node"). Lazy defaults appear where they remove
  protocol (`getOrElse(initialState)`), eager init where it centralizes tracking (`put`).
- Substrate before convenience: the lower layer is wired completely through the evaluator first
  (HandlerLoopState existed in Handler/Stack/Eval while `ArrowEffect.handleLoopState` stayed a
  commented body, left in place rather than deleted or stubbed). The ergonomic constructor is the
  last thing built, not the first.
- Naming economy is near-absolute: `apply` overloaded rather than new verbs; `head`/`tail`,
  `push`/`pop`/`find`/`dump`/`truncate` borrowed from collections; node names are the literal
  verb of the event (`Defer`/`Suspend`/`Handle`); variants named by mechanical concatenation
  (`HandlerLoopState` = Handler + Loop + State). The codebase contains no `Interpreter`,
  `Trampoline`, `Continuation`, `Context`, or `Runtime`. Agent proposals introduced new concept
  nouns (`Bound`, `StateCell`) into a vocabulary that has almost none; that alone marked them as
  foreign.
- Review and design prose follow the same economy: file:line for every claim, numbered findings
  so they are addressable, adversarial counter-programs over correctness-by-inspection, "does it
  hold" separated from "is it safe".
- Fusion is a load-bearing property, not an optimization to trade away. Passing `next` so
  `head`/`tail` walks fuse is part of the contract.
- A couple of pattern-matching branches beat a class hierarchy. New names are introduced
  reluctantly; existing vocabulary is stretched first.
- No comments in kernel code, no new terminology, no helper methods without necessity. Code
  carries its meaning through shape.
- Dark paths may deliberately stay open (the capture-across-stateful-region gap) until something
  exercises them; pre-building for them is cost without evidence.
- Erasure is handled by naming unknowns once (abstract type members `IX/OX/EX/CX/AX/BX/StateX`)
  and stating expected types with `@unchecked` patterns, not by casts scattered inline.

Verbatim ruling corpus (the owner's standards in their own words, ledger-recorded):
- "You keep thinking of this as a VM-like execution when you should be thinking of COMPOSITION.
  let's go little by little."
- "do not introduce Region nor Segment. These are composition concerns not stack booking."
- "we can NOT leak ANY mutability in values produced by the kernel like this continuation."
- "proper safe immutable representations."
- "CAN YOU PLEASE USE TYPES" / "Please please safe typed code as much as possible."
- "use @tailrec def loop instead of whiles and vars."
- "could we have only Defer taking A < S and remove Continue? Kyo[A, S] is A < S."
  (Collapsing node kinds: the ask is always fewer concepts, not more.)
- "we need to pass the next for fusion. Why the fuck not fix Stack?"
- "handle loop is lightweight, no continuation created."
- "you can't remove put! it's the central place to ensure tracking of state."
- "no comments in the code, no new terminology or helper methods."
- "finish the minimal Stack the way I'm designing it and nothing else."
- "do not change my design."

## Interaction protocol (accumulated rules, all owner-set)

- Proposals are presented in chat, one at a time, in dependency order, each approved
  individually. Elaboration with code snippets first, then the Edit tool. The owner watches and
  interrupts.
- No edits to their WIP and no sbt runs unless asked. Their uncommitted tree is theirs; nothing
  is committed on their behalf.
- Reviewers (Fable) are advisors and judges, never workhorses: analysis only, one at a time.
  Sonnet for data mining, Opus for reporting.
- Results are reported with the verdict stated plainly, raw data as the arbiter. Measurements go
  through the harness, never hand-computed.
- The ledger (WORK.md) is the only authority on state; every step lands there.
- No AI attribution anywhere in git; commits under the owner's identity; no PR interaction ever.

Communication rules (transcript-recorded, all owner-set, several repeated after violations):
- Numbered, self-contained items "so I can easily provide feedback and discuss"; proper context
  in every escalation. Options offered via code snippets.
- Results always as a table with BOTH time and allocations ("you share results in so confusing
  ways. ALWAYS PRESENT TIME AND ALLOCATIONS").
- Edits via the Edit tool only, never bash, so the owner can follow them live.
- Every long-running command writes to a log and the log is WATCHED ("you always fall into this
  trap of not watching the log and getting stranded"; "I keep having to repeat this over and
  over").
- Escalation calibration cuts both ways: "are you sure you need me to decide?... Do not
  overcorrect, properly assess if you need me." Neither rubber-stamp asks nor silent rulings.
- Model tiering is the owner's stated philosophy: sonnet mines, opus explores and reports, fable
  judges with all pre-work done first ("fable is quite expensive... do all the ground work first
  so we don't waste expensive tokens").
- Register data: the owner's tone tracks the agent's role. Calm and collaborative, sometimes
  excited, when the agent analyzes, verifies, instruments ("sorry I'm getting excited with how
  powerful this can be with you using it"); escalating profanity precisely when the agent
  originates unrequested machinery, loses state, or edits without authorization.

## The capability question, honestly

The fact, stated at the owner's bar (reuse existing machinery, no new nouns, one funnel, a couple
of branches), not at an inflated one: across the full ledger, zero adopted items are originated
shapes. Every adopted contribution is a diagnosis, a patch, or an answer to a posed question. The
failed proposals were not exotic; they were competent renderings of the dominant literature
shapes for this problem class (defunctionalized continuations, snapshot nodes, state cells,
control tokens), applied heavily. And the owner rejected most of them at proposal time, on sight,
with no machine contact needed: the holes that mattered were visible in the design description
itself (StateCell, Bound, Region/Segment, ContinueAnswer/Parked, the two-cast erased loop).

On the owner's open fork (training set overwhelmed by bad code, or no truly novel solutions):
the record discriminates, partially. The design failures were not bad code; they were orthodox
shapes rendered competently, which points at approximation gravity rather than contamination:
generation pulls toward the mass of prior art, and the owner's composition-first shape lives far
from that mass. The recurring unsafety (casts, mutability leaks) pulls slightly the other way,
and it responded to process: the owner's rules demonstrably reduced it. The origination
component showed no response to process across months, which is why it reads as the deeper
limitation. What would falsify this: a shape produced ex ante that the owner keeps without
reshaping. It has not happened.

Verification must be split, because the record splits it. Artifact-anchored defect hunting held:
the Continue2 swap, the ClassCast off-by-one, the six-defect pass, each confirmed by a runtime
symptom or a green suite. Self-reported measurement failed: five wrong published numbers, four
flattering, figures quoted without re-deriving. The mitigation for the second is already
protocol (harness-mediated numbers only, raw data as arbiter). "The agent is a strong verifier"
is true only with this split attached.

One open question, honestly held: the design-vocabulary section above is ex post articulation.
Whether the agent can use it ex ante, generatively, is untested; the corpus takes no position on
it and treats any claim to it as unearned until demonstrated.

Accepted implication for this collaboration: the agent's high-value roles are verification,
defect hunting, typing and mechanics in the owner's style, corpus keeping, and analysis that maps
constraint spaces precisely so the owner's shaping is faster. Design proposals default to the
minimal delta expressible in the existing vocabulary and are offered as candidates for reshaping,
never as finished designs.

## What the owner gets from this collaboration

They ran it for months and called it "a good design exploration" in the same breath as "It
failed"; both are true, and the record supports a model of the value:
- Fast enumeration of the known-solution space: the agent's proposals were a foil the owner
  triangulated against, and several rulings crystallized against a bad proposal (the states
  array against StateCell/Bound; composition against Region/Segment).
- Verification passes against their implementations, at the moment they want them.
- Mechanics in their style at low cost: typing erased loops, compile fixes, test ports, edits
  they can watch land one at a time.
- Instrumentation and memory: the bench harness, the ledger, this corpus, so measurement and
  history outlive any one session.
- A matured mental model, partly built by watching the wrong turns: the contrast sharpened where
  the right shape lives.

## What changes operationally under the reframe

"Optimizing for the best interaction" is not a courtesy ordering; it is the control surface for
correctness, and the record proves it: under the owner's protocol (elaboration first, one change
at a time, owner watching) the six-fix pass landed with zero messes; failure classes A through D
cluster under autonomous momentum. Concretely:
- The register is the instrument. Tone tracks the agent's role: calm and excited registers
  appear when the agent analyzes, verifies, instruments against concrete artifacts; profanity
  marks a role violation in progress (unrequested machinery, lost state, unauthorized edits).
  The correction is to change role, not to apologize.
- Design defaults invert: constraint maps and hazards before shapes; candidates only in the
  existing vocabulary, offered for reshaping; a new concept-noun in a draft is a stop sign.
- Mechanisms over promises: this corpus stays loaded and maintained, the ledger stays
  authoritative, numbers go through the harness only. Stated intent is not evidence; the owner
  tests behavior.
- This file is itself a model built by an approximator. Its test is predictive performance in
  live interaction; it gets falsified and corrected in place, not defended.

## Held until asked (the two open proto items)

Homework done, held. Not offered in the chat; the owner sequences.

1. Uncomment `ArrowEffect.handleLoopState` / `handleLoopStateWith` with the rename pass (`def v`
   to `value`, `complete` to `apply`, add `def frame = _frame`) plus the one-arg `apply(v: A)`
   obligation from `Arrow.Transform` (unreachable on live paths now that the done branch matches
   `HandlerLoopState` first), and enable `PendingTest:194` as the first pinning test.
2. The capture gap: one candidate in the existing vocabulary, for reshaping. At `dump(pos)`, a
   crossed `HandlerLoopState` re-materialized with `initialState` = its live slot state, so `put`
   re-initializes the region on re-push; no new node, no Eval or push changes. Leaving the gap
   open until exercised is equally valid; the owner rules.

## The harness design thread (2026-08-18 late evening, owner-led)

The chat moved from the verdict to design: an evolution machine (ren-shaped) that optimizes the
interaction itself, "a harness you raise", fed by live interaction data under no-leakage, fitness
from diagnostics rather than a judge. Key rulings and corrections in the thread:
- The owner corrected a persona conflation: a mind is not a persona; the x-ray
  (docs/raio-x-execucao.html) shows an evolved neuro-symbolic estimator: independent narrow
  lenses, typed partial outputs, temperature falling as work moves from imagining to calculating,
  arithmetic evaluated outside the model, self-declared confidence read downstream as weight, the
  persona panel one contained lens at bounded weight (25%). The two-execution anchor-divergence
  is the free abstention diagnostic.
- "Do your homework" correction landed mid-thread: riffing from ambient understanding instead of
  reading the pointed-at doc; the class-A/E pattern in conversational form. Fixed by reading the
  x-ray fully and mining the docs corpus (mine-ren report, integrated below).
- ren transfers to the harness, per the docs corpus: the advisor lineage is the proxy's measured
  ancestor (generic tips NEUTRAL, echo 0, "the tip contract forbids heritability by
  construction"; steering works only as targeted delivery + schema-forced consideration, and
  heritability lives in the component that owns edits); action-space representability ("the
  locally rational answer to an action space in which the joint move is unrepresentable", the
  maxRecursions dead gene) means every failure class needs a one-unit preventing move expressible
  in the genome; anchoring ("reasons well FROM a real number and poorly TOWARD one", "a wrong
  anchor is worse than no anchor... the empty result is a first-class answer") maps to
  retrieval-grounded, abstaining proxy prediction; phenotypic monoculture invisible to genotype
  diversity metrics warns the harness fitness to measure behavioral method, not rule-set shape.
- mind-optimization-failures.md mirrors the failure classes from this collaboration nearly
  verbatim across a different project and different agents: the taxonomy is agent-generic, which
  is what gives an evolved constraint genome transfer value.
- Persona-domain findings (for calibration of any user-model claims): "precision rises with
  grounding and falls with generation"; grounded census fields helped, LLM-generated psychometric
  fields scored worse than no profiles at all; segment tilting inherently lossy at the model
  prior; the statistician-first ruling.

## The harness design, as ruled by the owner (2026-08-18/19 late thread)

The design conversation converged; the owner's rulings, in order, with the corrections they made
along the way (each correction is also corpus data on my failure modes):

- **Pure evolution.** No hand-seeded proxy, no transcript-derived genomes. Whether a user-proxy
  structure even exists in a mind is for evolution to find or discard. My repeated "seed it from
  this session" framing was the probe they ran: chasing an idea because they voiced it. Sessions
  are answer-laden, so they live scorer-side only.
- **This session is a backtest, not a seed.** Fresh start ruled: minimal gen-0 (single neuron +
  gate, two completions), no archaeology on old transcripts (lossy, leak-prone); the distilled
  failure classes are kept as day-one detectors (class-level, scorer-side, leak-free). New
  interactions accrete as backtests via snapshot markers, at a baseline rate (routine coverage,
  regression tier) plus triggers (interrupts, divergences, verdicts: frontier fixtures).
- **The backtest mechanism is snapshot-swap-evaluate.** Podman/CRIU live checkpoints of mind +
  env; load a snapshot, replace the mind (a value, swappable at the boundary), run against live
  oracles, compare with the recorded execution. Temporal sealing: a snapshot cannot contain its
  own label. Incumbent replay from its own snapshot gives the per-fixture noise floor; candidate
  vs incumbent-replay is exactly paired. Two checkpoint levels: mind-as-value (semantic, small,
  diffable) and CRIU (opaque residue: browsers, JVM heaps).
- **VFS behind kyo's file APIs**: mediation without tool cooperation, recording by construction,
  archive append-only and UNMAPPED for evolved tools (capability security by non-naming). Effect
  rows as the leash on mutation-authored tools (kyo covers browsers, files, async, containers:
  "any software"; the population never authors its own exams; an opposed factory lineage may,
  under discrimination + predictive-validity fitness, Hillis-style).
- **Fitness**: deterministic detector core informing a judge mind (x-ray containment: computed
  facts anchor, judgment bounded, confidence-as-weight, abstention first-class). The judge is
  continuously calibrated for free by blind-scoring incumbent actions that carry real verdicts;
  its measured calibration is its trust weight on divergent cases. Judgment share falling over
  time is a system health metric.
- **The model ladder**: capture capability expensive first (no cost pressure), then degrade and
  let evolution recover (externalization into structure); quality becomes a floor, not a weight,
  when cost enters. Local-model rungs extend the ladder (always-on tier, per-machine empirical
  grading, genome speaks capability grades not model names, privacy: the user model can stay
  on-device). Fine-tuning distills verified traces into the lower (open-weight) rungs;
  recall-vs-inference audit (their llm-weights methodology) is standing QA for tuned rungs.
- **Deployment**: backtest, then shadow (dark canary reproducing live inputs), then incremental
  load. Four widening gates, each earned by the previous constraint.
- **Scope**: constrained to SKILLS (mind + container per task), not a general agent. First skill:
  performance optimization, because the oracle is the bench harness this campaign built ("the
  experience was terrible... but we have the tool"), fixtures exist with reference lines (the
  kernel campaign's own A/Bs and hand optimizations), and the skill-founding rule generalizes:
  build skills where a campaign already forged the oracle. Gamified live tests / surveys loop the
  user in as the top promotion gate. Premium token cost accepted at breeding time (introspective
  operators: fable reads executions well); ROI framing: interaction cost converted to selection
  cost, the asset appreciates and belongs to the user.
- Owner appraisals during the thread: "ok, I'm proud of you now! I was probing to see if you'd
  chase something because I'm telling you to"; corrections: "you've been phrasing like we'd
  design how the execution happens. No, we'd let evolution do that"; "lol no, a mind is not a
  persona"; "why don't I see you doing any homework at all?" (fixed by reading raio-x + docs
  corpus before speaking again).

## Field research folded into the design thread (2026-08-19, owner-directed)

- **ARC-AGI positioning (owner's own results)**: ren on v2, mostly haiku with a little sonnet,
  beats opus on accuracy AND cost by a lot, ~90% on 3 hard questions (n=3 held honestly). The
  ladder thesis confirmed on the one benchmark designed to resist memorization. Tool-authoring
  mutations proposed for v3; ARC dominates SWE/terminal-bench as the machinery arena (perfect
  oracle, contamination-resistant, prize efficiency axis suits the cheap-substrate economics);
  SWE/terminal-bench as volume supplement (contamination + static-suite farming hazards noted);
  perf skill keeps the recital-proof owned oracle (a JMH run is a fresh physical event).
- **Prime Agent (Prime Intellect, Aug 5)**: 95.54% ARC-AGI-3 with Opus 5 (30.2% same model in
  native harness; human baseline 95.4%; launch-reported, no independent traces). Self-improving
  RLM harness: context-as-variable, programmatic tool calling, /refine self-edits judged by
  self-assessment. Analysis recorded: it confirms structure-dominance at headline scale; its
  self-assessment mechanism works only in dense-oracle environments (ARC grades every level);
  the owner's selection design is the sparse-oracle answer; no sandbox by default ("generated
  code inherits the user's OS permissions") vs the effect-leash/VFS constitution designed here
  from the start; "ren can likely generalize better": selection validates on unseen cases,
  self-modification validates on the case it is inside; containment is asymmetric (selection can
  evolve guarded self-modification; the reverse cannot exist).
- **DeepSeek Harness + Cordis paper (Aug 13-14)**: "A Programming Paradigm for Spatiotemporal
  Composability": revertible effects (caller-supplied inverses, runtime-tracked) + reactive
  coeffects (declare needs, runtime re-resolves) for safe live self-modification of harnesses.
  Related work engages ZIO/Effect-TS (monadic embedding critique; withdrawal leaves effects in
  place) and Effekt (closest relative); kyo not cited. The owner's stack is a third position the
  survey lacks: static effect rows (kyo) + substrate-level reversion (VFS/CoW/CRIU snapshots,
  which the paper itself concedes as the alternative) + selection-driven change instead of live
  self-modification. Field convergence signal: two harness-layer releases in two weeks, neither
  with selection; possible response-paper opportunity for kyo (their sec. 6.7 nearly invites it).

## Corpus integration log

- 2026-08-18: file created; all three miner reports integrated (code-style, ledger, transcripts);
  Fable strategic review received and applied: removed four covert-defense moves from the
  capability section (bar inflation, universalization, the unqualified contact excuse, and the
  factually wrong "holes only surface under machine contact"), added the verification split, the
  owner-value model, the fork position, the reframe operationalization, and the ex-ante/ex-post
  open question; staged work demoted to held-until-asked.
