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
| Isolate.scala | Isolate.scala | ported: snapshot-based runDetached, derive macro, oracle green |
| internal/Safepoint.scala | Safepoint.scala | move to internal/; API intentionally different (tier 2) |
| internal/package.scala (Kyo node hierarchy) | Kyo.scala | keep kernel2 shape; placement decision below |
| internal/Context.scala | internal/Context.scala | ported as the fork-time snapshot carrier |
| internal/Trace.scala | internal/Trace.scala (stub) + KyoException.scala | stub keeps boundary signatures; trace round fills it |
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

Ported. The one new kernel capability is the fork-time environment
snapshot: a ContextSnapshot suspension resolved at boundary drives by
walking the chain and folding every visible context delimiter per tag
into a Context (built through set so the Noninheritable flag entry is
maintained), with `inherit` filtering applied in runDetached before the
fork sees it. The snapshot gets the same late resolution as a context
read: bindings installed between construction and the boundary are
visible. Isolate itself is a library port: the three-phase class,
andThen with the Identity fast path, the derive macro with its
pedagogical error, and runDetached over the snapshot. Trace is a stub
type keeping runDetached's two-parameter shape until the trace round;
internal.restoring (interceptor-based) is not ported, since kernel2's
preemption replaces the interceptor and the IOTask adaptation uses it
directly.

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
port old internals), run the kernel2 JVM suite, run the JMH sensitive
rows when the change touches the hot path, commit per file.

Tests conform too, and serve as the oracle: each conformance step ports
the current kernel's matching test file (`kyo/kernel/<Name>Test.scala`,
package `kyo.kernel`) to `kyo/kernel2/<Name>Test.scala` (package
`kyo.kernel2`), changing only the imports and whatever the port reveals.
A ported test that fails to compile or pass is a conformance gap: fix
kernel2, or adapt the test minimally and record the divergence here
when it touches tier-2 internals by design. kernel2's own suites in
`kernel2test` stay, covering kernel2-specific behavior.

Recorded tier-1 divergences (each ruled by the user):
- Function parameters are plain functions, not `Safepoint ?=>` context
  functions (no reason for the given-threading in kernel2).
- ArrowEffect handler clauses must receive plain function continuations
  like the current kernel's; the Arrow-typed continuation stays only in
  `handlePartial`, whose consumer (IOTask) gets adapted at swap time.
- `Effect.catching` is a by-name method rather than inline, keeping its
  interception transform private.
- `discard` is private[kyo] runtime machinery (abandon trigger for
  parked computations); `unit` is the public conformant operation.
- Combinators avoid nested inlining (each mints its own transform):
  forced by opaque-alias transparency across the defining file, and
  preferred anyway for compile-time cost.
- The multi-tag handle overloads (2, 3, 4 effects in one call) are not
  provided: handlePartial covers the runtime boundary need, and
  downstream multi-tag call sites get adapted at swap time.
- handleResume and handleStop stay as kernel2-only handler formats:
  effects will migrate to them where applicable since they are cheaper
  to execute (no continuation capture).
- Kyo.lift, Effect.defer, Effect.bracket, and observe are private[kyo]:
  kernel machinery and effect-module surface, not user API.
- ArrowEffect handler clauses take plain function continuations, per
  the current kernel's signatures; the Arrow continuation remains only
  in handlePartial. The wrapper costs 16 B per dispatched operation.
- handleCatching is implemented as catching over handle; the current
  kernel's accept input filter lands test-first with the effect that
  needs it (Abort).
- The ported ArrowEffectTest oracle runs with multi-tag scenarios as
  nested handles, handlePartial scenarios on the single-tag Maybe
  protocol, interceptor-dependent tests dropped (no interceptor in
  kernel2), and the tail-recursion depth bound at 20 (kernel2's dispatch
  cycle is a few frames deeper, still constant). It caught one real
  kernel bug: bare suspensions (no chain yet) skipped boundary dispatch,
  so handlePartial clauses and context defaults never applied to them;
  fixed in driveLoop.
- The ported LoopTest oracle surfaced two more gaps, both fixed: the
  missing indexed 4-input arity, and a livelock where a drive nested
  inside eager recursion that exhausted the depth budget re-rescued the
  same deferred step forever; drives now open a fresh depth budget
  (openDrive/closeDrive on Safepoint), since a drive's real stack
  restarts at its own frame.
- The ported ContextEffectTest oracle passed unchanged.
- The kyo.Kyo utilities object (the current kernel module's package-kyo
  surface: when, unless, zip 2-10, fill, and the collection combinator
  families) lives on kernel2's Kyo companion, exported as kyo.Kyo at
  swap time. kernel2 implements the generic Iterable variant of each
  combinator once; the current kernel's per-collection specializations
  (List, Seq, Chunk, Set) are performance work for the optimization
  round, since overload resolution binds the generic variant
  source-compatibly. The Map-keyed family is ported with kernel2-specific tests (the current kernel ships it without kernel-level coverage). Node
  toString renders shape, tag, and position rather than the current
  kernel's frame snippet until the trace round (KyoTest adaptation).

## Structure ruling

The package stays `kyo.kernel2`: an attempt to compile the module as
`package kyo.kernel` collides with the current kernel's classes, which
reach the test classpath through the kyo-test runner, and dropping
kyo-test for the module was judged not worth it. The file layout still
mirrors the current kernel within the kernel2 package: user-facing
sources at `kyo/kernel2/` (Pending, Effect, ArrowEffect, ContextEffect,
Loop, plus the kernel2-only Arrow and Kyo), machinery under
`kyo/kernel2/internal/` (Safepoint, Handler, KyoException, CanLift,
LiftMacro). Tests are consolidated in package `kyo.kernel2` at
`kyo/kernel2/`: the former `kernel2test` package is gone, its suites
merged into the ported oracle files (PendingTest, EffectTest) or moved
alongside them, and internal tests sit at `kyo/kernel2/internal/`.
