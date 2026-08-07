# Effect kinds and handler kinds: the kernel2 exploration

Recovered record of the design session on effect and handler taxonomy for the
new kernel (Codex desktop, July 15, 2026). The session's working file
(`kernel2.scala`) lived in a projectless scratch directory that no longer
exists; the full conversation survives at
`~/.codex/sessions/2026/07/15/rollout-2026-07-15T06-01-32-019f6502-89a8-7392-956d-13396ac93f87.jsonl`.
This document distills the decisions so the design work can continue against
the proto line. Items marked "ruled" were settled by the designer; items
marked "proposed" were assistant proposals not explicitly ratified.

## Where it fits

The current kernel has two effect kinds: `ArrowEffect` (handlers take a
multi-shot continuation) and `ContextEffect` (injects values). The session
explored refining the handler side into distinct kinds, using Koka's handler
taxonomy as the reference, and ended at the representation question that the
proto1/proto2/proto3 line answered: handlers must not own an evolving `prev`;
`AndThen` owns the composition, steps are stable values, and the step
representation is the first-class public `Arrow[A, B, S]`. The proto line
built and validated exactly that substrate (Arrow, AndThen, Transform, Offset,
typed Step decomposition, minted per-site bytecode). The handler-kind
taxonomy on top of that substrate is the unrealized remainder.

## Koka reference taxonomy

Operation kinds: `val` (dynamically scoped value), `fun` (tail-resumptive,
resumes exactly once), `ctl` (general control, resumption is first-class,
resume zero, one, or many times), `final ctl` (never resumes), `raw ctl`
(no automatic finalization), `return` (transforms normal completion),
`initially`/`finally` (lifecycle around resumptions). Handler scoping kinds:
ordinary, `override`, named handlers, named scoped handlers.

## The kernel taxonomy (ruled)

| Node | Koka analogue | Shape | Continuation cost |
|---|---|---|---|
| `HandleCont` | `ctl` | `[C] => (I[C], O[C] => A < S) => A < S` | materialized, first-class, multi-shot; abort = not calling cont |
| `HandleResume` | `fun` | `[C] => I[C] => O[C]` (effectful variant `O[C] < S`) | none: kernel resumes exactly once, no first-class cont |
| `HandleStop` | `final ctl` | `[C] => I[C] => A < S` | none: continuation never built |
| `HandleLoop` | stateful `ctl` | `[C] => (I[C], State, O[C] => A < S) => Loop.Outcome2[State, A < S, B] < S2` plus `done(State, A)` | materialized plus an Outcome2 per handled op |
| `HandleContext` | `val` | per tag: `Maybe[V] => V` (current to scoped) | none |

Rulings along the way:

- The handler kinds exist as optimizations. A resume or stop handler must not
  require building the continuation; that is their entire point.
- `HandleStop` over `HandleFinal` as the name: stop describes the
  continuation behavior; final collides with finalizers and final classes.
- Abort is `HandleCont` territory (the zero-shot case) or `HandleStop`.
  `HandleResume` must not get an abort escape hatch: not via `Maybe` (loses
  the abort value, conflates routing with termination), not via `Result`
  (resume/abort is not success/failure). If routing is ever needed,
  `Maybe[X]` where `Absent` strictly means "keep searching", never abort.
- Effect matching is by `Tag` with subtype checking, never by inspecting the
  input value. Input predicates (`accept`) are selective handling inside an
  already matched effect family, not matching.
- Semantically `HandleLoop` subsumes cont, resume, and stop (state `Unit`,
  identity `done`). The specialized nodes stay because each removes a real
  allocation: `HandleCont` avoids the per-op `Outcome2`, `HandleResume` the
  continuation, `HandleStop` both.
- `HandleContState` dropped: `Loop.Outcome2` already carries next state plus
  next computation. Note for multi-shot: `Outcome2` gives one next state per
  outcome; a handler resuming one continuation along several branches with
  different states would need a state-indexed cont `(State, O[C]) => ...`.
  Mutable captured state is not an encoding: branches would interfere.
- The effect-kind split stays: `ArrowEffect[I, O]` takes `HandleCont`,
  `HandleResume`, `HandleStop`, `HandleLoop`; `ContextEffect[V]` takes only
  `HandleContext`. A middle exploration dropped `ContextEffect` in favor of
  the value type as its own row entry (`Tag[V]` as key, one slot per type);
  the split was restored to keep the `E` to `V` association that justifies
  context-storage casts.
- `HandleContext` is strict per tag: it receives `Maybe[V]` (outer binding)
  and returns the scoped `V`. Returning `V` and not `Maybe[V]` is deliberate:
  handling the effect must establish a binding. It is not encodable as
  `HandleResume`: context is scoped environment installation, not per-op
  interpretation. The kernel owns the storage (`Dict`-backed opaque
  `Context`); handlers never see the dictionary.
- Multi-effect handler overloads (2 to 4 effects) removed. Stateless
  `handleLoop` collapses into `handle`. `handleFirst` stays as a shallow
  wrapper. `handleCatching` is an exception boundary, not a mode.
- Koka features triaged out of the kernel: `return` clause is a tail `map`;
  `initially`/`finally` belong to a Bracket/Ensure frame (the proto line has
  Bracket); `override`/`mask` are interpreter routing policy; named and
  scoped handlers are a capability layer above the kernel; linear effects are
  metadata that select the fast path; `raw ctl` is not exposed.

## Interpretation principles (ruled, late in the session)

- Zero mutability in anything returned or captured: computations and
  continuations are immutable values. Cached mutable buffers are legitimate
  only as unpublished scratch while building a flat immutable representation.
- Flatten continuation spines only, never handler stacks and never whole
  computations. Handler nodes are delimiters inside the continuation; a
  captured continuation crossing nonmatching inner handlers retains them.
- No parallel runtime hierarchy mirroring the node types, no frame wrapper
  objects, no `AnyRef` erasure where the structure can stay typed, no
  mutation of handler nodes (loop state is a recursion argument, frozen into
  snapshots only at escape boundaries).
- Dispatch walk: at a suspension, the accumulated elements between the
  suspension and the first tag-matching handler are the captured
  continuation; the matching handler stays active around its own clause
  (deep handler semantics); everything above stays outside.

## The representation breakthrough (end of session)

Handlers owning `prev` is broken: as evaluation advances, the previous step
changes, so an immutable handler node pointing at the handled computation
either mutates, reallocates per step, or forces the banned mirror hierarchy.
The fix, and the session's last ratified direction:

- `AndThen` is the single node that owns the evolving computation
  (`prev: Pending`, `next: step`).
- `Continue` (map) and every handler mode become stable, prev-less steps.
- The step type is public and first-class: `Arrow[-A, +B, -S]` with a single
  contravariant effect parameter (both sides widen into the total effect
  set), and a `Handled` type member (`Any` for `Continue`, `E` for handler
  arrows) marking what the arrow discharges.

The session ended before this was implemented to satisfaction. The proto
line subsequently realized the substrate: first-class `Arrow[A, B, S]`,
`AndThen` composition, `Transform` as the stable step, `Offset` chains, the
typed `Step` decomposition for caller-site dispatch, preserved minted
bytecode, measured against proto2 and the kernel.

## Open remainder for the proto line

What the taxonomy adds on top of the current proto3 surface, which today has
a single handler protocol (`eval`/`evalPartial` with a continuation-taking
handler, tag matched, plus `Maybe` routing in `evalPartial`):

1. `HandleResume` and `HandleStop` fast paths: handler modes that skip
   continuation decomposition entirely (no step protocol, no Offset walk for
   the captured segment on stop).
2. `HandleLoop`: handler state threaded through the drive without mutation,
   `done` on normal completion.
3. Context effects: the `ContextEffect[V]`/`HandleContext` kind, kernel-owned
   context storage, and how context threading interacts with the drive loop
   and parked continuations.
4. The `Handled` type member (or equivalent) if handler arrows become
   ordinary `Arrow` values in the public composition algebra.
5. Routing policy (mask/override) and the linear-effect fast-path metadata,
   both explicitly parked as above-kernel or later concerns.

## Rulings from the current design round (August 2026)

Settled in discussion on top of the recovered record, in order:

1. The sync path stays. Handling is a drive that executes now, clause as a
   loop argument in per-site inline bytecode, clause-now on a match, zero
   nodes per handled step (the current kernel's property, on the proto3
   substrate).
2. Handle* become Arrow types, but only as the park-time reification. When
   the drive must suspend, the handler appends its delimiter node to the
   parked continuation (one AndThen at first park); thereafter the existing
   chain-prepend machinery carries the handler with the chain for free, so
   retention costs nothing per hop (better than the current kernel's re-wrap
   per hop, and no always-suspend cost like the kernel2 draft). Delimiters
   are pass-through Transforms (segmentBoundary precedent): identity on
   values, dispatch targets for suspensions. Dispatch after a park becomes
   chain search for the first matching delimiter; HandleResume needs no
   chain surgery at all, HandleStop jumps to the delimiter's next,
   HandleCont pays the O(prefix) capture re-link, the only mode that must.
3. Isolate stays, as a library protocol above the kernel, not kernel nodes.
4. The ArrowEffect/ContextEffect split stays. The load-bearing relationship:
   the kinds are distinguished by boundary semantics, and Isolate is the
   boundary protocol. Context state is copyable (no write-back, nothing to
   reconcile, forks by environment copy with Noninheritable filtering, no
   instances needed, which is what makes forks over Env/Local zero-burden).
   Arrow state is interpretive (lives in the handler, forks by
   handle-into-value plus an explicit, semantically meaningful join policy,
   which is why instances and strategies exist, and why Abort and Choice
   have none).
5. Consequently the kernel owes Isolate exactly three capabilities: a
   snapshotable drive-threaded environment at fork points (settles the
   HandleContext question toward threaded environment, not per-read chain
   search), HandleLoop with done as the handler-into-value primitive, and
   parked chains as shippable immutable data.

6. The new kernel keeps the current model, not the current machinery. Kept
   essentially verbatim: the two-kind split, Isolate's full user surface
   (rows, stages, composition, derivation, exclusions), Isolate as a library
   protocol. Same model on new mechanisms: arrow handling becomes the
   handler-kind taxonomy on the sync drive with delimiter reification at
   park; context keeps its kind and API but the environment moves from a
   parameter of every continuation application into drive state plus parked
   delimiters; the sync path sheds Safepoint per-step enter/exit (depth
   slots, structural cadence), the fully replicated inline handle loops, and
   the context-threading calling convention. Not ported: per-hop KyoContinue
   re-wrap, context in every apply signature, always-suspend Handle* nodes.
   Open detail: how a resumed chain re-establishes its environment (park
   snapshot vs collect during the resume walk), constrained by the fork
   snapshot requirement toward an explicit drive environment view. Optional
   polish on Isolate: state the Transform laws, revisit capture's CPS shape.

7. The handler formats are effect kinds. Each Handle* delimiter pairs a
   clause signature with a dispatch behavior, which is exactly what
   distinguishes ContextEffect from ArrowEffect; with delimiters, the kinds
   form one uniform table (suspension format, delimiter format, dispatch
   behavior) of which the current kernel has two rows. Direction under
   discussion, leaning hybrid: the effect declares its control upper bound
   (context, resume, stop, cont) and the delimiter class carries the actual
   per-site mode under the subsumption resume and stop are restricted ctl.
   What declared kinds make principled: Abort as a stop-kind effect loses
   the impossible continuation (no output type at all); the Isolate
   exclusion becomes expressible as a kind bound instead of a convention
   (with judgment remaining, Emit has instances despite abortive stream
   handling); resume-kind suspensions get a static one-shot tail-resumptive
   guarantee. The cost case for strict declaration is Emit: resume-shaped
   almost everywhere, abortively handled in Stream.take, so strict kinds
   would force ctl everywhere or an Ack-style output protocol; the hybrid
   keeps per-site fast paths off the delimiter class while restrictive
   declarations add suspension-side guarantees.

8. The kind family for the new kernel: three declared kinds, defined by
   what the handler provides. ContextEffect[V] provides a value once per
   scope (reads, continuation untouched, copy at boundaries).
   ResumeEffect[I, O] provides a function run per op (answered in place,
   continuation never materialized, exactly-once by declaration, the
   natural Isolate bound). ArrowEffect[I, O] provides a control operator
   (continuation captured, zero to many resumes). Koka's val/fun/ctl triad
   as declarations. Stop is not a fourth kind: StopEffect[I] =
   ArrowEffect[I, Const[Nothing]] plus sugar, sound by typing (a ctl clause
   for O = Nothing provably cannot resume), which fixes Abort's impossible
   continuation. Loop/state is a handler format over Resume and Arrow, not
   a kind. One-shot vs multi-shot within Arrow is linearity metadata
   (Async: arrow one-shot because it must capture to park; Choice: arrow
   multi-shot). Placements: Env and Local context; Var, Memo, Sync-style
   defer resume; Abort stop corner; Choice arrow; Emit the declared
   judgment case (arrow with per-site resume-format delimiters, or Ack
   output to stay resume).
