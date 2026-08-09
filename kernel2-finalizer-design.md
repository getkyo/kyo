# Kernel2 finalizers: issues, attempts, and the proposed direction

Status: design report for review. The kernel2 migration moved finalization from the old
kernel's fiber-level machinery (`Safepoint.ensure` + interceptor list, executed by the
scheduler) into kernel-level `Bracket` nodes interpreted by the drive. A test campaign
against kyo-core surfaced a family of defects in that structural encoding. This report
records the defect classes, the sequence of structural designs attempted and why each
failed, and the proposed replacement: finalizers saved and executed by the eval loop,
supplied as explicit parameters to the eval entry points, with preemption likewise
becoming a function parameter of the partial drive.

All claims below are backed by tests that exist in the tree today, most of them written
red-first. Kernel2 and kyo-prelude JVM suites are green at HEAD; kyo-core has two
remaining red groups, both reduced to failing kernel-level pins.

## 1. The semantic contract (as pinned)

The pins in `kyo-kernel2/shared/src/test/scala/kyo/kernel/EffectTest.scala` and
`PendingSchedulerTest.scala`, plus the kyo-core SyncTest/ScopeTest expectations, define
the contract any design must meet:

- Release runs exactly once per acquisition: on use completion, on a short-circuit that
  discards the rest of the computation, on a throw (including a throw after a park and
  resume), and when a boundary discards a parked or preempted remainder.
- Release runs BEFORE anything composed after the region, on every path. This includes
  steps mapped after the enclosing handler (`Abort.run(ensure(f)(v)).map(g)`: `g` must
  observe the finalizer's effects), on the success path, the abort path, and the throw
  path, at any handler nesting depth.
- Release receives the outcome: `Absent` on success, the `Result.Error` when the use
  settled to the rotated error currency or threw, the boundary's error on a discarded
  remainder.
- Finalizers are effectful computations running in the ambient context: locals bound
  around the ensure reach the finalizer; only its own `Abort` converts to a throw.
- Handler coverage: operations inside acquire, use, release, and the downstream all
  reach their handlers, with the handler state reached at region exit (a stateful
  handler's downstream sees post-use state).
- Discard semantics: a handler short-circuit skips the downstream of the region but
  still releases; a stop answered inside a region settles it (answer at the op site,
  then release, downstream skipped).
- Nested regions release innermost-first, both in-band and on discard.
- Acquire-by-name runs exactly once per drive of the same value.

## 2. Defect classes found in the structural encoding

1. Composition ordering. In a fused-continuation kernel, `map` extends the chain end.
   With the region extent defined implicitly as "the whole cont chain", extension lands
   inside the region: downstream ran before release (the original 12 SyncTest ensure
   failures).
2. Rotation currency corruption. A per-node composition field (`after`) forced every
   traversal to know about composition; the rotation arm routed the release fold
   through a loop composed with `after`, applying user code to the release's discarded
   `Unit` completion (`ClassCastException` under any handler; wedged every kyo-test
   leaf since the runner wraps leaves in ensure).
3. Short-circuit ordering. A ctl handler that answers without resuming discards the
   continuation carrying the region's exit structure. The settled answer then flows
   linearly through the chain, running post-handler steps before control returns to
   the region arm's release. Reproduced in-band, runner-free: the downstream observed
   pre-release state on the abort and throw paths.
4. Park/preempt/discard leaks. Releases must survive foreign parks (continuation
   rewiring), preemption (region rebuilds), and interrupts (finalization walks over
   discarded remainders). Holes found and fixed along the way: a throw after park and
   resume skipped release; a preempted region rebuild was invisible to the finalize
   walk (interrupt storms leaked semaphore permits and wedged interrupt tests);
   remainders carried behind constant steps were unwalkable.
5. Orthogonal scheduler bugs found by the same campaign (fixed, not design-relevant):
   the IOTask park protocol registered its wakeup before publishing the remainder
   (replays and lost wakeups at ~0.02%/iteration, the root cause of the suite wedges),
   and a null-completed promise threw MatchError at the join poll.

## 3. Attempts and why each failed

The lineage, in order. Every step was driven by a reproduction; the failure mode of
each is itself pinned or documented by a repro that ran.

a. `BracketAndThen` node (bracket + after field) with dedicated arms in the drive,
   rotation, and observation. Failed: every traversal must interpret composition, and
   the rotation arm corrupted the release currency (defect class 2). Structural
   conclusion: composition-as-node-field leaks into every traversal.

b. Region-exit crossing minted by `Bracket.map` (a private suspension carrying the
   region result; downstream fuses onto its continuation) with a transparent top-level
   drive arm. Failed: the transparency arm consumed crossings inside open regions
   (any loop at any depth), inverting release ordering everywhere.

c. `Composed` wrapper guaranteeing one crossing per composition chain. Failed: kernel
   plumbing composes onto rebuilt region nodes at drive time (park resume, boundary
   continuations); each such composition minted a second crossing with no interpreter,
   wedging every leaf.

d. Born-in crossing: every use chain ends with the crossing at construction;
   `Bracket.map` is plain extension; any append fuses behind the crossing by ordinary
   suspension fusion. Sound for pure composition and everything the drive interprets
   directly; still HEAD's base design. Failed one level up: rotation wraps the chain,
   burying the crossing inside the rotation transform, so drive-time fusion of
   post-handler steps lands outside it; short-circuits then hit defect class 3.

e. `Sequenced` node for buried-crossing brackets (composition held node-side, applied
   by the drive after the region completes; rotation composes it onto wrapped chains
   with the release fold on a separate raw loop). Fixed single-handler in-band
   ordering (pinned green). Failed under a second handler: the outer rotation
   re-buries the composition into the outer chain; the recursion has no fixed point
   (each level's fix re-creates the problem one level up).

f. Activation-identity crossings (region arms mint tokens bound into the drive-threaded
   context; exit steps stamp crossings at run time; arms release only their own).
   Solves attribution, and rebuilds stamp correctly for free. Failed on the settle-exit
   step needed for short-circuits: after a normal completion the same step runs
   post-close and stamps whatever region is ambient (an open outer region's token),
   releasing the outer region early. No sound emission rule was found.

Root cause, stated once: ctl-format rotation is monomorphic in the handler's result
type, so the chains that must statically carry the handler are the same chains that
post-handler composition fuses into; a short-circuit discards the only structurally
sound exit marker (the region's own crossing). Structure alone cannot express "after
the region" on the discard path. Six designs failed on six different faces of that
one constraint.

## 4. Proposed direction: finalizers in the eval loop, ambient inputs as parameters

The proposal (user-directed): stop encoding finalization in arrow wiring. The eval
loop saves and executes finalizers; the drive's ambient inputs become explicit
function parameters:

- `evalPartial` (the partial drive under the runtime boundary) takes preemption as a
  function parameter, replacing the Safepoint wiring, mirroring the old kernel's
  `stop` parameter to `handlePartial`.
- All eval entry points take a finalizer sink/store parameter. The boundary owns the
  store (the fiber, via IOTask, exactly as the old kernel's fiber-level list); pure
  `eval` supplies a local one.

Mechanics sketch:

1. The drive threads a finalizer stack. `Effect.bracket` lowers to plain chain steps:
   an acquire step pushes `(release, resource)`, the use-end step pops and runs the
   release with the outcome read from the value. Success-path in-band ordering is
   ordinary wiring; downstream composition can fuse anywhere because release no
   longer lives at a structural boundary. The `Bracket` node and its drive arm can
   disappear.
2. Short-circuits rebalance at the rotation seams. The `Rotate` re-entry transforms
   are kernel code: each snapshots the stack depth before applying its chain; if the
   output is settled (a short-circuit skipped the pop steps) and the depth exceeds
   the snapshot, the seam pops and runs the excess finalizers, outcome
   `outcomeOf(settled)`, before passing the value on. Release-before-downstream on
   the discard path, innermost-first under nesting, with zero handler cooperation.
3. Throws rebalance at the existing catch sites (the catching guard and the drive's
   boundary) with a `Panic` outcome.
4. Parks and preemption hand the excess to the boundary: at slice exit with a pending
   remainder, the outstanding entries above the slice's entry depth move to the
   boundary's store (IOTask's fiber-level list). Discard runs them (with the
   boundary's error); resume pushes them back before re-entering. The finalization
   walk over remainder structure disappears.
5. Deletions: the exit crossing and its effect, the park front-rewiring, the region
   rebuilds (resume and reacquire), `Sequenced`, the buried-crossing flag, the
   finalization walk, and the drive's bracket protocol arms.

Everything in section 1 stays pinned; the pins adjudicate the rework.

## 5. Open questions for review

1. Seam completeness: are the rotation re-entries, the catching guard, the drive
   catch, and the boundary ALL the exits a discarded region's value can take? Is
   there a path where a settle crosses no kernel seam before user code runs?
2. Depth-balance soundness: can a legitimate (non-discarding) flow present a settled
   value at a seam with excess depth (e.g. a handler answering with a value while
   the region legitimately continues elsewhere: multi-shot resumption, `Batch`-style
   stored continuations)? What are the correct semantics there, and does the old
   kernel's fiber-level design have the same answer?
3. Once-per-drive: values are re-drivable (`v.eval` twice acquires twice, pinned).
   Push/pop steps are chain steps, so re-driving re-pushes; does any path re-run a
   pop without its push or vice versa (segment boundaries, `optimize` re-chunking)?
4. Outcome fidelity: the pop step reads the outcome from the settled value flowing
   through it; the seam rebalance uses `outcomeOf(settled)`; parks use the boundary
   error. Does the error-aware contract (SyncTest error-aware pins) hold on every
   combination, in particular a rescued throw whose rescue value is a success?
5. Effectful finalizers: the stack executes releases as computations. At seams and
   boundaries, what drives them (a nested drive?), and what happens when a finalizer
   itself parks (release with Async)? The bracket-node design threaded suspended
   releases through the drive; the stack design needs an equivalent story.
6. Context fidelity: finalizers must run in the ambient context (Locals pin). The
   stack stores closures; the seam runs them under its current context. Is that the
   right context on every path (in particular boundary discards)?
7. Interaction with `handlePartial`'s slice protocol and nested drives (Cascade):
   entry-depth snapshots per drive invocation; is save/restore across nested drives
   sufficient, and does the IOTask park race fix's write-free tail survive the
   boundary handoff?
8. Performance: push/pop per region on the hot path vs the node machinery it
   replaces; the seam depth check per rotation re-entry. The arena benchmark
   comparison (old vs new kernel) is the campaign's next deliverable and will
   measure this directly.

## 6. Held-out review outcome (amendments adopted)

A held-out reviewer adjudicated the direction against the code and pins. Verdict:
build it, amended. The root-cause claim holds (with the honest qualifier: impossible
within the kernel's two commitments, monomorphic rotation and fused single-type
continuations, which are its performance foundation); both standing red pins trace
green under the seam-rebalance mechanics. One reframing and three blocking
amendments:

Reframing: the pinned contract is deliberately STRONGER than the old kernel. At the
baseline commit the old suite IGNORES the ensure-on-Abort.fail tests; old finalizers
ran at fiber end on the discard path, not in-band. The in-band release-before-
downstream-with-outcome contract on short-circuits is new strength, to be decided
and pinned, not inherited.

Amendment 1: parks must keep remainders self-contained. Instead of handing
outstanding entries to the boundary, the drive sews them into the parked remainder
as one flat head node that re-pushes them into whatever store the next drive
supplies (pinned resume idioms re-drive the value through different entry points).
The store is drive-local; the boundary owns only the remainder; discard finds the
sew node with a shallow walk. Most deletions survive; the deep walk shrinks to sew
discovery.

Amendment 2: the store's carrier between the eval entry point and the seams must be
decided explicitly (see section 7). Rotate seams execute deep inside chain
application where an evalLoop parameter is not in scope, and the store must span
handlePartial's per-bounce evalLoop calls. A Context-carried reference is
disqualified naively: Rotate.at substitutes a captured context, so a region opened
inside a scoped fun-format answer would push into a stale store.

Amendment 3: per-entry once-guard (the old kernel's compareAndSet Ensure), so a
seam pop racing a stored continuation's later resume, or the sew/discard paths,
release exactly once; depth arithmetic tolerates spent entries.

Adopted specifications from the review: effectful seam releases are sequenced into
the chain as pending values (never nested-drive evaled), so a suspending release
still precedes the downstream and can park normally; the pop step masks preemption
like today's release arms; entries capture (context, handlers) at push and releases
run under the captured pair everywhere, which makes boundary discards context-
correct; drains snapshot the store first (re-entrancy); the seam checklist is
exhaustive and includes handleLoop's pending-outcome transform and the drive's own
normal exit alongside the Rotate re-entries, the catching guard, traced, and the
drive catch; parking releases on discard-walk paths remain synchronous-eval only,
stated as a restriction. Known heuristic to pin: outcomeOf reads any settled
Result.Error as the region's failure, misreporting a Result.Error-valued success.

Pin red-first before implementing: the two standing ordering pins; a stored
continuation short-circuit resumed later (once-guard, region observed closed); park
then resume via a different entry point with a short-circuit after resume; a
Result.Error-valued success through a pop; a suspending release at a short-circuit
seam; nested regions discarded at one seam innermost-first; a stateful handleLoop
short-circuit over an open region; a finalizer opening a region during a drain;
Locals observed at a boundary discard.

## 7. The one open ruling: store carrier at the seams

The public contract is fixed by the direction: every eval entry point takes the
finalizer store as a parameter (and evalPartial takes preemption as a function
parameter). The open question is how the store travels from the entry point to the
seams inside chain application:

- Option A: thread it through Arrow application (Transform.run, Step.run, apply)
  alongside context and handlers. Uniform with the existing ambient-as-parameter
  design; the largest signature change, reaching the inline application sites that
  expand at user code.
- Option B: a drive-scoped slot installed by evalLoop from its store parameter and
  restored on exit (the shape of the old interceptor). No signature change below
  the entry points; concedes parameter-purity at the one point where drive-scoped
  ambience is the honest description; proven by the old kernel.

## 8. Evidence base

- Kernel pins (EffectTest, PendingSchedulerTest, ArrowEffectTest): ordering pure and
  under handlers (stateful included), across parks, effectful release, discard-settle
  semantics, nested regions, per-drive activation, discard of preempted rebuilds,
  in-band ordering single-handler (green) and under an outer handler (red, the
  standing reproduction), catching over regions with deferred throws under nested
  guards.
- kyo-core: SyncTest ensure group red in-suite (same root cause as the red kernel
  pins); interrupt tests wedge (not yet re-diagnosed against HEAD; suspected same
  family); IOTaskTest park-race hammer pin green; fiberTrace tests ignored by ruling
  (structural render pending a frame walk).
- Suites: kyo-kernel2 JVM and kyo-prelude JVM fully green at HEAD.
- Commits: 3a95636fa8 (exit crossing design), 23cb1d551d (park protocol race, ensure
  through the release row, poll null arm), c430cc3ed6 (Sequenced, finalize-walk
  holes).
