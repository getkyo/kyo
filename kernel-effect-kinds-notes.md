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
