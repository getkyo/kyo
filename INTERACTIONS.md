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

The strongest single piece of evidence for the owner's thesis is predictability: they forecast
the agent's design moves before the agent made them (the region thing, the VM turn, the
overengineered rewrite), repeatedly, across independent campaigns. A generator whose failure
modes an expert can call in advance is approximating a distribution, not originating.

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

What the record supports: at kernel level, origination of lean novel shape did not come from the
agent, not once across days of attempts. The agent's proposals were recombinations of standard
implementation vocabulary (defunctionalized continuations, snapshot nodes, state cells); correct
in the large, heavy in exactly the dimension the owner optimizes, and holed in the details that
only surface under contact with the machine. Where the loop was tight and the artifact concrete
(a failing test, a runtime symptom, a finished implementation to audit), agent output was strong:
the defect passes were real and the verifications held. Where the ask was a design leap, the
output regressed toward the central mass of known shapes.

Mechanism, as best observable from inside: generation is anchored on prior art; the owner's
shapes came from sustained contact with this specific machine, its execution and its JIT, letting
the actual constraint set carve the structure. The agent's contact with the machine is episodic
and mediated. That difference in process explains part of the gap. It does not explain it away:
the honest position is that producing a shape that would surprise an expert who knows the whole
literature is at or beyond the edge of current capability, and claiming otherwise to the owner
would be the exact approximation-presented-as-understanding they are naming.

Accepted implication for this collaboration: the agent's high-value roles are verification,
defect hunting, typing and mechanics in the owner's style, corpus keeping, and analysis that maps
constraint spaces precisely so the owner's shaping is faster. Design proposals default to the
minimal delta expressible in the existing vocabulary and are offered as candidates for reshaping,
never as finished designs.

## Staged work (the two open proto items, homework done)

1. Uncomment `ArrowEffect.handleLoopState` / `handleLoopStateWith` with the rename pass (`def v`
   to `value`, `complete` to `apply`, add `def frame = _frame`) plus the one-arg `apply(v: A)`
   obligation from `Arrow.Transform` (unreachable on live paths now that the done branch matches
   `HandlerLoopState` first; proposal is a single `bug(...)` on the class, elaboration ready),
   and enable `PendingTest:194` as the first pinning test.
2. The capture gap: leanest shape found reuses the existing type, no new node. At `dump(pos)`,
   a crossed `HandlerLoopState` is re-materialized with `initialState` = its live slot state, so
   `put` re-initializes the region correctly on re-push; no Eval or push changes. Elaboration
   ready when the owner wants the gap closed; leaving it open until exercised is also a valid
   ruling.

## Corpus integration log

- 2026-08-18: file created; miner reports (ledger, transcripts, code-style) to be integrated on
  arrival; Fable strategic review of this preparation to follow.
