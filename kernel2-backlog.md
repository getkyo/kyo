# kernel2 backlog

Queues: implementing, designing, awaiting ruling, next up, parked. Each item carries
its own context so it reads without the linked docs; the docs carry the full designs.
Done work is removed once acked. Last update: 2026-08-12.

## Implementing now

### Loop constructors return bare Outcome

Context: `Loop.continue`/`Loop.done` are the constructors handler clauses use to answer
an operation (continue with a new state and answer, or finish the region). In kernel2
they return `Outcome[...] < S`, pending-wrapped, with an explicit `Nested.lift` and an
extra type parameter on every constructor; your TODO at Loop.scala:183 asks why. The
answer from the analysis: an inference workaround from the deleted lower-bound era that
outlived its cause and broke signature parity with the old kernel.

Design: bare `Outcome[...]` returns matching origin/main exactly; the lift moves to the
use sites where the implicit conversion already fires; ten runner sites inside
Loop.scala adjust; handler clause types are unchanged (they consume pending positions,
bare values convert). Origin/main compiles 1,750 call sites over the same currency with
a stricter lift; one compile settles the inference question and gates the change.
(`kernel2-todos-design.md` section 4.)

### dispatchFirst

Context: the old kernel has a `private[kyo]` sibling of handleFirst that IOTask uses
for the interrupt-before-join cascade repair: look at the head of a computation, and if
the standing suspension is a given effect, run a side-effecting inspection of its input
without answering or changing anything. Kernel2 lacks it, and on kernel2 the standing
suspension can be wrapped in region nodes, so a head test must peel them.

Design: a `private[kyo]` inline entry beside handleFirst that walks `Handled` /
`HandledState` / `HandledFirst` values to the standing suspension, tests its tag, runs
the given function on a match, stops at a `Defer` or settled value, returns Unit,
computation untouched. (`iotask-kernel2-integration-r2.md` 5.5, origin/main
ArrowEffect.scala as the contract reference.)

### Exception enrichment (EffectTrace successor)

Context: kernel2 dropped the old kernel's always-on trace ring (16 frames recorded per
step through Safepoint, per-platform pools). Failures currently carry only the physical
stack. The design restores effect-level frames by reconstruction: at the few boundaries
an exception crosses, walk the live continuation chain and handler stack (Transforms
and suspensions already carry Frames) and splice synthesized frames into the
exception, with a suppressed carrier for accumulation and idempotence. Also unblocks
the five ignored fiberTrace tests (same walker over a fiber's residual), and includes
the standalone fix for the Eval save/restore leak (an escaping throw currently leaves
the thread's budget and armed bit as the aborted drive left them).

Status: an opus agent is implementing it end to end in an isolated worktree, taking
the design's own recommendations as provisional defaults for the unruled points (each
application recorded), full suite as the gate, JMH A/B only if the machine is
uncontended. Workflow on completion, per your note: I review the work and iterate
with the agent until the code is clean, elegant, and fully functional, then merge the
changes into this branch. Design: `exception-enrichment-design.md`.

## Designing now (context-isolation-design agent)

### ContextEffect isolation encoding

Context: forking must carry context effects (Local, Env: scoped values, always safe to
inherit) into the child. The old kernel did this generically: context values live in
the Context map, and the fork copies entries (`Isolate.internal.runDetached`/
`restoring`), with Noninheritable filtered out. Kernel2 deleted the map; context
effects are ordinary regions on the handler stack, so the generic copy has no direct
translation, and the Isolate design (kept as-is by ruling) needs a kernel2 encoding
for its "simple state copying" category.

Design in progress, two candidates plus better if found: (a) boundary inheritance: the
fork transplants standing context cells into the child (Noninheritable skips); no
instance involved; (b) derived per-effect instances over the as-is interface: capture =
read the value, isolate = `ContextEffect.handle(tag, state)(v)` child-side, restore =
identity, emitted mechanically by derive. The parity bar is the old kernel's exact
semantics: inheritable/noninheritable, Env's union-on-nest, Local's merge, a mixed
context-plus-stateful row through derive. Deliverable:
`contexteffect-isolation-design.md`.

## Next up

### Handlers encapsulation (your TODO at Handlers.scala:8)

Provenance: your review TODO, "can we encapsulate so the internal representation is
easier to evolve later?", sized by what has happened since: adding FirstNode for
handleFirst had to touch every place that enumerates the node classes (seven sites).

Context: Handlers is the evaluator's stack of installed handler regions, a linked
list of Node (stateless handler), StateNode (stateful), and FirstNode (one-shot).
Five operations walk it: find (locate the handler for a suspension's tag; hot, once
per answered operation), the settled-value pop, rebuild (turn a stack prefix back
into a value when parking), replace (functional state update), and the isolate design
adds a transplant. Every one pattern-matches the node classes today, and find reads
the tag through `handler.tag`, a megamorphic call because every handle site expands
its own anonymous handler class.

Design direction, per your note: no exposed hierarchy. `Handlers` stays the single
type, and the needs become methods on it: find, the pop, rebuild, replace and the
state update, and later the transplant. The node classes become private
implementation inside the object; Eval stops matching them anywhere and calls the
methods; the per-kind operation dispatch also goes behind the surface (the found
region hands back its handler through the existing Handler kinds, which are already
the public vocabulary). Internally the tag is a field so find is a walk of field
reads, closing the megamorphic call. Adding a future region kind then touches one
file. Constraint to hold: no dispatch regression on the hot path (JMH-gated,
sharedHandlerPaysDispatch is the row). The design doc's exposed-hierarchy shape
(`kernel2-todos-design.md` section 2) is superseded by this direction; internal names
are yours to rule at review.

### deepRecursion rescue-path time (implement, with a bar)

Context: `deepRecursionPaysRescuesOnly` measures trampolined recursion through the
safepoint budget's rescue path. Current position: 1.05x the old kernel's time against
a 0.43x allocation win on the same row. The time moved with the self-contained map
design: the whole rescue family did (fusionPastBudget 32.8 to 47.9 us against its own
earlier board, while staying at old-kernel parity), so the regression has a located
cause to root-cause rather than a mystery.

Status and bar, per your note: implement, diligently. Root-cause first (profile the
rescue path, attribute the time between the Defer mint, the reset, and the re-entry
dispatch), then the simplest fix that addresses the cause, then a JMH A/B on
deepRecursionPaysRescuesOnly and fusionPastBudgetPaysRescuesOnly proving a measured
improvement. Merge only if the code is simple and elegant to integrate; otherwise
park it with the findings recorded. Queued behind the two implementation agents so
the measurements are clean; I own this one.

### JS, Native, and Wasm compile check (delayed to a bigger sweep)

Context: kernel2 declares all four platforms with zero platform-specific sources, and
only JVM has ever been compiled. The shared Safepoint uses AtomicReferenceArray,
Thread.currentThread, and Thread.threadId(); whether every platform compiles and
links is unverified and gates any platform-parity claim. Per your note: do it, folded
into a bigger sweep rather than now.

## Parked (your call to revive)

### Bracket primitive

Context: acquire/use/release with the fixed contract: use fully interruptible; no
interruption between acquire finishing and use starting; release always executes
(normal completion, exception unwind, and discard of an interrupted fiber's
remainder) and runs to completion once started. Ruled: not served via Isolate
(release on a short-circuit that truncates the owning region is the inexpressible
core). IOTask adds R-B1 (discard entry synchronous, Unit), R-B2 (a throw escaping a
partial drive already ran what it unwound), R-B3 (a done-truncation runs the
discarded regions' releases).

Three designs on disk, judgment deferred by your call. `bracket-node-design.md`: a
`Kyo.Bracket` node that exists exactly when the resource exists; acquire chains into
one strict transform that reads the resource and allocates obligation and node with
no budget check between, so the gap closes by representation, no mask, effectful
acquire included; weakness: a captured continuation carrying a bracket segment is a
value fork. `bracket-handler-design.md`: a handler kind whose region state
accumulates obligations via a register op; Scope becomes a direct instance; needs a
mask region for the gap; weakness: taxes the handler-stack walk for every open
region's lifetime plus a round trip per resource. `bracket-effect-design.md` (the
probe): the contract is not expressible in the current algebra; the minimal kernel
assist is a per-cell disposal hook over truncation-discarded regions plus one catch
at the evaluator boundary; everything else in the siblings is convenience. When
revived, the ruling should decide that shared disposal walk explicitly (consumers:
bracket unwind, R-B3 truncation, enrichment frames).

### kyo-bench arena rows, old vs new kernel (task #8)

Context: kyo-bench holds the end-to-end arena benchmarks (the cross-framework rows).
Running them over both kernels is the test of whether the micro-board positions
matter in realistic workloads. Blocked regardless on the stack above the kernel
compiling against kernel2, so parking costs nothing today.

### Effect.catching as a region

Context: `Effect.catching(v)(recover)` is the kernel's failure-recovery combinator.
Its current mechanism, `guarded`, rewrites the computation as it runs: every resumed
step gets wrapped in a fresh guard arrow and every node that comes back is
re-allocated with a guarded exit, which is your TODO at Effect.scala:27 ("an
expensive workaround for something that should be handled in Eval or Arrow?"). The
analysis confirmed the cost (one guard allocation, one node re-allocation, and one
chain flatten per resumed step) and a semantic hole: the rewrite reaches region
exits but not region interiors, so a throw inside a nested handled region is not
recovered, which nothing in the API suggests.

Design: make the catching scope a region: a `Kyo.Caught` node holding value, recover,
and exit, entered like any region and popped through its exit; the failure path finds
the innermost catching region by walking the handler stack at the throw point.
Catching becomes one allocation; `guarded` and its per-node-kind arms are deleted;
the nested-interior hole closes because a nested region sits inside the catching
region on the same stack. A plain evaluator try cannot do this because a guarded
residual recovered in a second drive is pinned behavior: the scope must be a value
that parks and rebuilds, and a region is the only such structure.
(`kernel2-todos-design.md` section 1.)

Parked by your call ("not sure about this region thing"). If revived, it consumes the
same failure-path walk the bracket ruling decides.

### Defaulted redesign (optional context)

Context: `Kyo.Defaulted` is the optional-context mechanism: a suspension that carries
its own fallback, which the evaluator's find-miss arm answers with when no handler is
installed (`Local.get` works with no `Local.let` in scope because of it). Your TODO
at KyoInternal.scala:38 rejects it, and the diagnosis agrees: the fallback is typed
`Any`, the row claims an effect that may never dispatch to a handler, and the second
consumer (the definedness probe that `Env.run`'s union and `Local.let`'s merge need)
only exists because the old kernel's Context map, which answered "is there an outer
handler" with a map lookup, was deleted.

Design: split the two needs. Context answers carry their own definedness:
`ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[Maybe[A]]]`, so the probe
becomes an ordinary read. The optional fallback becomes a typed marker (`unhandled:
O[X]` on the suspension) replacing Defaulted in the find-miss arm, typed at the
operation's output instead of `Any`. (`kernel2-todos-design.md` section 3; parked
task #31's discussion input. Interacts with the ContextEffect isolation design in
flight: its capture reads change shape if this lands.)

### IOTask integration

Context: IOTask is the scheduler's task, driving a fiber's computation in preemptible
slices and owning interruption, completion, and the finalizer contract. The port to
kernel2 is designed in full and re-verified against the current kernel (r2): the
fiber boundary is a handler region installed outermost, so a parked re-raise leaves a
bare residual that `handlePartial` re-enters without nesting a second layer; the park
must be written as a pending outcome (the settled form, one character away, spins);
the field layout lands at baseline parity (32 bytes).

Status: parked. Its rulings when revived: R1 (a deadline parameter on `Eval.partial`,
without which single-threaded platforms cannot preempt a fiber that never suspends),
R2 (charge the settled-answer arm one budget step so answer-loops like `Async.Join`
over completed promises become preemptible; hot arm, JMH-gated), R3 (abandoned
releases run synchronously, Unit-returning; recommended), R4 (context stays a def on
a conditional subclass so the footprint trick survives), R5 (dispatchFirst; now in
implementation), R6 (below). (`iotask-kernel2-integration-r2.md`; summary
`backlog-sections/iotask.md`.)

### Safepoint.stop cheap negative (IOTask ruling R6)

Context: `Safepoint.stop(thread)` delivers preemption by locating the target thread's
slot. For a live thread that never evaluated, the probe walks the whole slot table:
65536 volatile reads on the caller's thread, and under the IOTask design the caller
is the interrupter. There is a correct early exit (entries are never written back to
null, so a null entry reached while probing proves the thread has no slot).

Status: parked with IOTask; small and standalone if wanted earlier.

