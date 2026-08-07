# kernel2 source conformance with the current kernel

Goal: kernel2 offers the current kernel's APIs at source level (no binary
compatibility), file by file, mirroring the source file and folder
structure, so downstream modules can eventually swap kernels with at most
a package-name change. New kernel2-only surface (Arrow as a user-facing
type, the preemption Safepoint) is additive and allowed; conformance
means the old surface exists and type-checks for callers, not that the
new surface disappears.

## Conformance boundary

Two tiers, because downstream splits the same way:

- Tier 1, the public user surface (`kyo.kernel.*`): what user code and
  the effect modules call. Full source conformance is the goal.
- Tier 2, the internals (`kyo.kernel.internal.*`): consumed only by
  kyo-core's scheduler machinery (IOTask, IOPromise: KyoSuspend,
  Safepoint.Interceptor, Context, Trace). These are exactly what kernel2
  redesigned (delimiter dispatch replaces Context threading, the parked
  state machine replaces the interceptor). Conformance here is neither
  possible nor desirable; the swap adapts those call sites instead. File
  placement still mirrors the old tree where it makes sense.

## File mapping

| current kernel | kernel2 today | action |
|---|---|---|
| Pending.scala | Pending.scala | conform surface (largest gap) |
| Effect.scala | Effect.scala | conform, reconcile defer and catching |
| ArrowEffect.scala | ControlEffect.scala | conform; naming decision below |
| ContextEffect.scala | ContextEffect.scala | conform (small gap) |
| Loop.scala | Loop.scala | conform (near-conformant) |
| Isolate.scala | missing | the environment-threading round; out of this pass |
| internal/Safepoint.scala | Safepoint.scala | move to internal/; API intentionally different (tier 2) |
| internal/package.scala (Kyo node hierarchy) | Kyo.scala | keep kernel2 shape; placement decision below |
| internal/Context.scala | none (Handler.Context) | tier 2, replaced by design |
| internal/Trace.scala | KyoException.scala | tier 2; trace enrichment is a later round |
| internal/CanLift.scala, LiftMacro.scala | lift/liftSlow in Pending | tier 2; conform the user-visible auto-lift behavior only |
| internal/KyoInternal.scala | none | tier 2 |
| (none) | Arrow.scala, Handler.scala | kernel2-only, stays (Arrow user-facing by ruling) |

## Per-file deltas (tier 1)

### Pending.scala (`<`)

Current kernel surface: `map`, `flatMap`, `andThen`, `unit`, `handle`
(9 pipe-style overloads), `eval`, private[kyo] `evalNow`, private[kyo]
`unsafeGet`. kernel2 today: `map`, `discard`, `observe`, `eval`,
`eval(preempt, period)`.

Work items: add `flatMap` (alias of map for for-comprehensions),
`andThen`, `unit` (kernel2's `discard` is the same operation under a
non-conformant name; conform to `unit`), the `handle` overload family,
`evalNow` (kyo-core uses it), `unsafeGet`. Signature note: the current
kernel's function parameters are context functions (`Safepoint ?=> A =>
B < S2`); kernel2 takes plain functions. Callers pass plain lambdas
either way, so this is source-compatible for call sites and kept as is.

### Effect.scala

Current kernel: `abstract class Effect`, public `Effect.catching`
(exception interception across suspensions), private[kyo] `defer` and
`deferInline` (suspend-once). kernel2: `Effect`, public `defer` (lazy
per drive), public `bracket`, `Const`.

Work items: add `catching` (needs a kernel2 mechanism: catch around
frame execution and re-drive, the KyoException machinery is adjacent);
reconcile `defer` naming and semantics (the current kernel's defer
suspends once; kernel2's re-evaluates per drive, which is the honest
name for Sync.defer's contract to build on; decide whether the old
private[kyo] semantics is needed by any downstream path). `bracket` and
`Const` stay as kernel2 additions.

### ArrowEffect.scala / ControlEffect.scala

Current kernel surface: `suspend`, `suspendWith`, `handle` (1, 2, 3,
and 4 effect tags in one call), `handleFirst`, `handleLoop` (stateless,
stateful, stateful-with-done), private[kyo] `handlePartial` (two tags
plus context, the IOTask driver), private[kyo] `handleCatching`.
kernel2 today: `suspend`, `suspendWith`, `handle`, `handleResume`,
`handleStop`, `handleFirst`, `handleLoop`, `handlePartial` (single tag,
Maybe protocol).

Work items: the multi-tag `handle` arities (2, 3, 4 effects handled in
one pass; kernel2 can install several delimiters in one call), aligning
`handleLoop` variants, keeping `handleResume`/`handleStop` as kernel2
additions. `handlePartial`'s divergence is tier 2 in practice (only
IOTask uses it) and the IOTask port adapts. Naming decision below.

### ContextEffect.scala

Current kernel: `suspend(tag)`, `suspendWith(tag)(f)`, defaulted
variants, `handle(tag, value)(v)`, `handle(tag, ifUndefined,
ifDefined)(v)`, `trait Noninheritable`. kernel2 has all but
`Noninheritable`, which belongs to the fork-inheritance model and lands
with the Isolate round.

### Loop.scala

Both carry Continue/Outcome families, `continue`/`done` constructors,
`apply` 1-4, plus in the current kernel `indexed`, `foreach`, `repeat`,
`forever`, `whileTrue`; kernel2 ported the full set. Work item: diff the
exact signatures and close small gaps. Expected to be the quickest file.

### Isolate.scala

Not in this pass: kernel2's fork-time environment snapshot is the
recorded environment-threading round, and Isolate's API depends on it.
The conformance pass leaves a placeholder note, not a file.

## Decisions needed

1. ArrowEffect vs ControlEffect: conformance implies the name
   `ArrowEffect` (downstream sources reference it), but this campaign
   deliberately renamed to ControlEffect because Arrow is now
   user-facing. Options: rename back to ArrowEffect; or keep
   ControlEffect and provide `ArrowEffect` as a source-level alias
   (type alias plus an `object ArrowEffect` with exports). The alias
   keeps both truths but two names for one concept is its own cost.
2. Package strategy: conform shapes under `kyo.kernel2` now, and let
   the eventual swap round decide how `kyo.kernel` gets provided (a
   scalafix/import swap, or compiling these sources under the old
   package in a swap build). Proposed: yes, defer packaging.
3. File placement of the internals: move Safepoint.scala under
   `internal/` to mirror the old tree, and optionally Kyo.scala's node
   hierarchy toward an `internal/` home, with Arrow.scala and
   Handler.scala placed per their user-facing status (Arrow top-level,
   Handler internal). Proposed: yes, mirror.
4. Order of work. Proposed: Pending, Effect, ArrowEffect naming plus
   surface, ContextEffect, Loop, then the internal/ file moves; Isolate
   and Noninheritable explicitly parked for the environment round.

## Method

Per file: extract the current kernel's public signatures, diff against
kernel2, implement the missing surface in kernel2's semantics (never
port old internals), add tests for each added operation in the matching
kernel2 test file, run the kernel2 JVM suite, run the JMH sensitive rows
when the change touches the hot path, commit per file.
