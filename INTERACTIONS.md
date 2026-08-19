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

Pattern: the owner originates the shape; the agent is effective as verifier, mechanic, and
analyst against a concrete artifact, and has not originated a kernel-level shape that survived.

## Failure catalog

Each entry: what the agent did, what was correct, who found it.

1. Asserted a `Stack.push` StackOverflow from a jstack that never showed one; the probe later
   showed a quadratic runaway (100% CPU, 3.8 GB, no overflow). Owner challenged; ledger corrected.
   Root behavior: stating as observed what was inferred.
2. Framed `Chain.apply`'s unconditional `Kyo.Defer` as "the bug, no Safepoint check." The Defer is
   the stack-safety mechanism (unwinds to the eval trampoline). Owner corrected.
3. Proposed fixing the done-branch (one-arg apply) to route around the dump-shape bug. Owner:
   "fuck no, we need to pass the next for fusion. Why the fuck not fix Stack?" The fix belongs
   where the invariant lives.
4. Proposed `bug`/disallow for suspending HandlerLoop clauses; wrong, suspensions inside a
   HandlerLoop must work; owner solved it (map yields the value, `loop` moves to the tail).
5. Deleted `put` as redundant once vacate-clears made its `getOrElse` degenerate. Owner
   interrupted: "you can't remove put! it's the central place to ensure tracking of state."
   Locally sound analysis, architecturally wrong move: a concern's single home is worth keeping
   even while degenerate. The actual defect was `fill`'s bypass of the funnel, and the fix was to
   complete the funnel, not delete it.
6. Relaunched a live Fable reviewer after misreading an idle transcript as a dead agent. Rule
   derived: liveness is not read off a transcript's mtime; when in doubt, ask, never relaunch.
7. Recurring design heaviness: closures of the whole path space up front (Bound nodes, skip
   rules, wrapper cells) for paths nothing exercises, where the owner's move is a couple of
   pattern-matching branches on the live paths and a deliberate open edge on the dark ones.

## The owner's design vocabulary (what "lean" means operationally)

- Reuse the existing mutable machinery. The evaluator's Stack is the one mutable scope; a new
  concern gets a parallel array or a branch there, not a new node class or wrapper object.
- Values stay immutable, always. Mutation is confined to per-evaluation evaluator structure.
  "We need proper safe immutable representations" rules out any `var` on a node embedded in a
  computation value.
- One funnel per concern: `put` for entry writes, `find` for handler lookup. Future behavior
  hooks into the funnel; scattering a concern across call sites is the failure mode.
- Lazy defaults over eager protocol where they suffice (`getOrElse(initialState)`), eager init
  kept where it centralizes tracking (`put`).
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
