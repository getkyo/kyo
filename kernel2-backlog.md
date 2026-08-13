# kernel2 backlog

Queues: implementing, designing, awaiting ruling, next up, parked. Each item carries
its own context so it reads without the linked docs; the docs carry the full designs.
Done work is removed once acked. Last update: 2026-08-12.

## Implementing now (kernel2-impl agent)

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

## Awaiting your ruling

### Bracket primitive

Context: acquire/use/release with the fixed contract: use fully interruptible; no
interruption between acquire finishing and use starting; release always executes
(normal completion, exception unwind, and discard of an interrupted fiber's remainder)
and runs to completion once started. Ruled already: not served via Isolate (release on
a short-circuit that truncates the owning region is the inexpressible core). IOTask
adds R-B1 (discard entry synchronous, Unit), R-B2 (a throw escaping a partial drive
already ran what it unwound), R-B3 (a done-truncation runs the discarded regions'
releases).

Three designs on disk. `bracket-node-design.md`: a `Kyo.Bracket` node that exists
exactly when the resource exists; acquire chains into one strict transform that reads
the resource and allocates obligation and node with no budget check between, so the
gap closes by representation, no mask, effectful acquire included; weakness: a
captured continuation carrying a bracket segment is a value fork.
`bracket-handler-design.md`: a handler kind whose region state accumulates
obligations via a register op; Scope becomes a direct instance; needs a mask cell for
the gap; weakness: taxes the handler-stack walk for every open region's lifetime plus
a round trip per resource. `bracket-effect-design.md` (the probe): the contract is not
expressible in the current algebra; the minimal kernel assist is a per-cell disposal
hook over truncation-discarded regions plus one catch at the evaluator boundary;
everything else in the siblings is convenience.

The ruling should decide that shared disposal walk explicitly: its consumers are
bracket unwind, R-B3 truncation, and enrichment frames (and the parked
catching-as-region if ever revived). Decision axes: gap mechanism (representation vs
mask) and steady-state cost (node free on the hot walk; handler taxes it).

### Exception enrichment (EffectTrace successor)

Context: kernel2 dropped the old kernel's always-on trace ring (16 frames recorded per
step through Safepoint, per-platform pools). Failures currently carry only the physical
stack. The design restores effect-level frames by reconstruction: at the few boundaries
an exception crosses, walk the live continuation chain and handler stack (Transforms
and suspensions already carry Frames; cells carry tags) and splice synthesized frames
into the exception, with a suppressed carrier for accumulation and idempotence. Also
unblocks the five ignored fiberTrace tests (same walker over a fiber's residual).
Review status: the executive summary, attach-point inventory, carrier and splice
sections, and rulings were reviewed against the sources; the full 885 lines were not
adversarially re-verified. **Implementation-as-validation is now running**: an opus
agent in an isolated worktree is building the design end to end, taking the doc's own
recommendations as provisional defaults for the unruled 9.1-9.7 (each application
recorded), with the full suite as the gate and the JMH A/B run only if the machine is
uncontended. Performance is the design's organizing constraint (reconstruct-at-throw
chosen so the non-throwing path pays nothing; per-arm try regions shaped to preserve
tailrec); the try-region neutrality claim is exactly what the A/B validates. The
design's finding 8 (Eval save/restore without try/finally leaks budget state on an
escaping throw) is included in that implementation as a standalone fix. Rulings
9.1-9.7 in `exception-enrichment-design.md`; 9.6 (the Debug combinator) is explicitly
out of the implementation's scope.

## Next up (designed, blocked on the bracket ruling)

### Handlers encapsulation (your TODO at Handlers.scala:8)

Provenance: this is the designed answer to your own review TODO, "can we encapsulate
so the internal representation is easier to evolve later?", sized by what has happened
since: adding FirstNode for handleFirst had to touch every place that enumerates the
node classes.

Context: Handlers is the evaluator's stack of installed handler regions, a linked
list of Node (stateless handler), StateNode (stateful), and FirstNode (one-shot).
Five operations walk it: find (locate the handler for a suspension's tag; the hot
one, once per answered operation), the settled-value pop, rebuild (turn a stack
prefix back into a value when parking), replace (functional state update), and the
isolate design adds a transplant. Every one of them pattern-matches all three node
classes: seven enumeration sites today. Separately, find reads the tag through
`handler.tag`, and since every handle call site expands its own anonymous handler
class, that call is megamorphic on the hot path.

Design, in existing vocabulary: give Node, StateNode, and FirstNode a shared abstract
parent inside Handlers that carries what every walk already uses on all of them: the
exit arrow, prev, the withPrev copy, the rebuilt-node constructor, and the handler's
tag hoisted to a field. The generic walks then read the parent and stop enumerating
the concrete classes; only the operation-dispatch arm in Eval still distinguishes
them, because that is where they genuinely differ. The tag-as-field turns find's
megamorphic call into a field read: the encapsulation and a hot-path improvement in
the same change. JMH-gated (sharedHandlerPaysDispatch is the row). The design doc's
names for the parent and its subdivisions are the agent's proposals, subject to your
naming. (`kernel2-todos-design.md` section 2.)

### Small kernel items

FB fix
- save/restore try/finally in `Eval.apply`/`partial`: found by the enrichment
  analysis, pre-existing: an escaping throw leaves the thread's budget and armed bit
  as the aborted drive left them. Small, standalone fix.

## Parked (your call to revive)
FB I told you no compact representation of any items in the fuckign backlog! all proper sections. I fucking fon't want to repeat this.
| item | one-line context | design |
|---|---|---|
| Effect.catching as a region | the guarded arrow-rewrite pays per resumed step and cannot cover nested region interiors; the redesign makes catching a region node with a passive cell | `kernel2-todos-design.md` section 1 |
| Defaulted redesign | `default: Any` untyped probe replacing the old Context-map definedness check; redesign: Maybe-shaped ContextEffect answers plus a typed Unhandled marker (task #31) | `kernel2-todos-design.md` section 3 |
| IOTask integration | the full scheduler port: boundary layer, park protocol, preemption wiring, field layout; rulings R1-R6 | `iotask-kernel2-integration-r2.md`; summary `backlog-sections/iotask.md` |
| IOTask's small kernel asks | Eval.partial deadline (R1), settled-answer budget charge (R2, JMH-gated), Safepoint.stop cheap negative (R6) | same doc, section 9 |

## Standing

| item | pointer |
|---|---|
| kyo-bench arena rows old vs new (task #8) | KernelBench boards exist for both kernels |
| Safepoint overflow one-shot report decision (task #57) | internal/Safepoint.scala |
| Fold Implicits back into Pending.scala (layout parity) | kernel-parity-gaps.md section 2 |
| Stray empty dir kyo-kernel2/kyo-kernel2/ | hygiene |
| JS/Native/Wasm compile check of kernel2 | kernel-parity-gaps.md section 4 |
| deepRecursion 1.05x time (0.43x alloc) | rescue-path target if the perf campaign reopens |
