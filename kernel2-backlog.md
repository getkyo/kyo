# kernel2 backlog

Improvements and themes in flight, each with its design in brief. Done work is removed
once acked; full designs live in the linked docs. Last update: 2026-08-12.

## 1. Bracket primitive

**Status: judgment pending (the next ruling).** Three candidate designs on disk, all
carrying the fixed contract (use fully interruptible; no interruption between acquire
finishing and use starting; release always executes on settle, unwind, and discard;
release runs to completion) plus IOTask's R-B1/R-B2/R-B3.

- `bracket-node-design.md`: `Kyo.Bracket` node; the node exists exactly when the
  resource exists (acquire chains into one strict transform that reads the resource and
  allocates obligation and node with no budget check between), so the acquire gap closes
  by representation, no mask. Effectful acquire gap-free. Weakness: a captured
  `Handler.Cont` continuation carrying a bracket segment is a value fork.
- `bracket-handler-design.md`: `Handler.Bracket` kind; obligations are region state via
  a register op; Scope becomes a direct instance. Needs a mask cell for the acquire gap
  (a drive-local counter is broken across parks). Weakness: taxes `find` for every open
  region's lifetime plus a registration round trip per resource.
- `bracket-effect-design.md`: the expressivity probe. Verdict: not expressible in the
  current algebra; the boundary is running releases at a truncation (knowing them is
  expressible). Minimal kernel assist: a per-cell disposal hook over truncation-discarded
  cells plus one catch site at the evaluator boundary; everything else in the sibling
  designs is convenience.

Decision axes: acquire-gap mechanism (representation vs mask machinery) and steady-state
cost (node is free on the hot walk; handler taxes it). The ruling should decide the
**shared walk**, since it has four consumers: bracket unwind, `Loop.done` truncation
(R-B3), catching-as-a-region (theme 4), and enrichment frames (theme 3). Ruled already:
bracket is NOT served via Isolate (the truncation hop is the inexpressible core).

## 2. Isolate on kernel2

**Status: design ruled (old kernel's Isolate as-is: Remove/Keep/Restore, State,
Transform, capture/isolate/restore/nest); port not started.** The Join-effect
substitution for `Transform[_]` was explored in full and set aside.

Open design item (your FB): **encoding ContextEffect isolation in kernel2 — it is
genuinely different here.** The old kernel's "simple state copying" category copied
Context-map entries generically; kernel2 has no map, context effects are regions. Two
candidate encodings:
(a) boundary inheritance: the fork transplants context cells (Noninheritable skips),
no Isolate instance involved, generic; (b) derived per-effect instances: for
`E <: ContextEffect[A]`, capture = read the value, isolate = `ContextEffect.handle(tag,
state)(v)` child-side, restore = identity — mechanical, expressible with the as-is
interface today. Interacts with theme 6 (the Maybe-shaped ContextEffect answer changes
the read capture uses) and with Noninheritable and union-on-nest semantics (Env).

Kernel prerequisite either way: the state-aware region exit (`done: (State, A) => B`),
which also blocks `Var.runTuple`/`Emit.run`/`Check.runChunk` parity independently.
Reference mechanics: `isolate-kernel2-design.md` (transplant walk, done-soundness
finding, restore-must-ship-boxed, context-read cost and its library-only mitigation).

## 3. Exception enrichment (EffectTrace successor)

**Status: designed; rulings 9.1-9.7 pending; unblocks the five ignored fiberTrace
tests.** Design: reconstruct-at-throw — the live chain is the trace (Transforms and
suspensions carry Frames, cells carry tags); a walk at the small set of kernel
boundaries an exception crosses rebuilds the effect-level context, zero cost unless a
failure happens. A suppressed carrier accumulates across nested drives; one splice at
the outermost exit (synthesized frames first, kernel plumbing filtered, which also
fixes the JS splice-position failures). Accumulate-at-catch is dominated: two catch
sites exist module-wide and restoring per-transform catches is refused. Honest loss:
pre-suspension history (the old 16-slot ring). Also found, pre-existing:
`Eval.apply`/`partial` save/restore with no try/finally leaks budget and armed bit on
escape. Full design: `exception-enrichment-design.md`; summary:
`backlog-sections/enrichment.md`.

## 4. Effect.catching becomes a region

**Status: designed; depends on the walk ruling (theme 1).** The guarded arrow-rewrite
pays per resumed step (guard Transform + node re-allocation + step flatten) and cannot
cover nested region interiors (pinned by test). A plain evaluator try cannot replace
it (a guarded residual recovered in a second drive is pinned), so the scope must be a
value: `Kyo.Caught` region node + a passive catch cell; catching becomes one
allocation; `guarded` and its per-node-kind arms are deleted; the nested-interior hole
closes. The failure path is the shared walk. Full design: `kernel2-todos-design.md`
section 1.

## 5. Handlers cell layer

**Status: designed; ready after theme 1 (Passive waits for the bracket ruling);
JMH-gated.** Cell kinds are enumerated in seven live places (the FirstNode addition
touched all seven). Design: `Cell(exit, prev)` base with `withPrev`/`rebuilt`;
`Answering(tag, ...)` vs `Passive` under it; generic walks read the base; only
operation dispatch stays per-kind. The hoisted tag turns `find`'s per-cell megamorphic
`handler.tag` call into a field read — encapsulation and a hot-path win in one change
(watch `sharedHandlerPaysDispatch`). Full design: `kernel2-todos-design.md` section 2.

## 6. Defaulted redesign (optional context)

**Status: designed; ruling needed on the ContextEffect row change (parked task #31's
discussion input).** `default: Any` is a scar from deleting the Context map. Design:
`ContextEffect[+A] extends ArrowEffect[Const[Unit], Const[Maybe[A]]]` so answers carry
their own definedness, plus a typed `Unhandled` marker (`unhandled: O[X]`) replacing
`Defaulted` in the find-miss arm. Interacts with theme 2's ContextEffect encoding.
Full design: `kernel2-todos-design.md` section 3.

## 7. Loop constructors return bare Outcome

**Status: ready to implement; first in dependency order; compile-gated.** The
`Outcome[..] < S` return type was an inference workaround from the lower-bound era; it
broke signature parity and forced the explicit `Nested.lift`. Design: bare returns as
origin/main; the lift moves to use sites where the conversion already fires; ten
runner sites inside Loop.scala adjust. Origin/main's 1,750 call sites over the same
currency with a stricter lift are the evidence; one compile settles it. Full design:
`kernel2-todos-design.md` section 4.

## 8. dispatchFirst

**Status: specified; lands beside handleFirst.** Region-peeling `private[kyo]` entry:
peels `Handled`/`HandledState` values, tests the standing suspension's tag, runs
nothing, stops at a `Defer` or a settled value. Needed for IOTask's
interrupt-before-join cascade repair. Spec: `iotask-kernel2-integration-r2.md` 5.5
(ruling R5).

## 9. IOTask integration

**Status: designed (r2, self-contained, verified against current kernel); rulings
R1-R6 pending; consumes themes 1, 3, 7, 8.** Core: the boundary layer installed
outermost (prev is Empty) so a parked re-raise leaves a bare residual that
`handlePartial` answers without nesting a new layer; the park must be written as a
pending outcome (one character from the settled form, which spins); field layout at
baseline-parity 32 bytes. Rulings: R1 Eval.partial deadline (JS/Wasm slicing), R2
charge the settled-answer arm one budget step (JMH-gated), R3 abandoned releases
synchronous Unit (recommended), R4 context as conditional-subclass def, R5
dispatchFirst, R6 Safepoint.stop cheap negative. Full design:
`iotask-kernel2-integration-r2.md`; summary: `backlog-sections/iotask.md`. Then tasks
#9 (Sync.ensure/Scope tests) and #11 (green kyo-core).

## 10. Small kernel items

- Eval.partial deadline parameter (R1): one Long compare per 512 steps.
- Settled-answer budget charge (R2): hot arm, JMH A/B gate.
- Safepoint.stop early exit on a null probe (R6).
- save/restore try/finally in Eval.apply/partial (enrichment finding 8).

## 11. Standing / parked

| item | pointer |
|---|---|
| kyo-bench arena rows old vs new (task #8) | KernelBench boards exist for both kernels |
| Safepoint overflow one-shot report decision (task #57) | internal/Safepoint.scala |
| Fold Implicits back into Pending.scala (layout parity) | kernel-parity-gaps.md section 2 |
| Stray empty dir kyo-kernel2/kyo-kernel2/ | hygiene |
| JS/Native/Wasm compile check of kernel2 | kernel-parity-gaps.md section 4 |
| deepRecursion 1.05x time (0.43x alloc) | rescue-path target if the perf campaign reopens |
