# Lift machinery: inventory, history, and a single-macro redesign

## What exists today (kernel2)

| piece | where | purpose |
|---|---|---|
| `implicit inline def lift[A, S](v: A)(using CanLift[A])` | Implicits.scala | main conversion; erasedValue dispatch: primitives and String cast, everything else `Nested.lift` (runtime Boxed test) |
| `liftAnyVal`, `liftUnit` | Implicits.scala | macro-free fast paths (bare cast) |
| `abortCastUnit[S1, S2]` | Implicits.scala + LiftMacro | always-error trap: `Unit < S1` where `Unit < S2` expected, the issue-903 guided message |
| `liftPureFunction1..6(using CanLift[B])` | Implicits.scala | pure function to effectful function ergonomics |
| `Render` given for `A < S` | Implicits.scala | diagnostics rendering (not lift, cohabits) |
| `CanLift[A]` opaque Null | internal/CanLift.scala | erased "may not contain a nested computation" evidence |
| `derived` (2 x NotGiven), `derivedCaseObject`, `derivedSingleton` (macro), `CanLift[Nothing]`, `unsafe.bypass` | internal/CanLift.scala | the given zoo routing around the macro for common cases |
| `CanLiftMacro.checkSingleton` | internal/CanLift.scala | rejects kyo module singletons and known-pending singletons |

Three files, nine implicits, two macros, five givens.

## The history that shaped it (old kernel lineage)

- **Flat era** (#828, #840, #886, #1127): strict evidence, integration pain
  (MonadLawsTest had to bypass it), verbose signatures.
- **#1148 first-class nesting**: `Flat` dropped; nesting became a runtime
  `Nested` wrapper minted by lift when the value is a computation. The
  constraint survived as WeakFlat for one documented reason: without it, an
  effect-row mismatch silently nests instead of erroring. Passing
  `Int < Memo` where `Int < Async` is expected would auto-lift to
  `Int < Memo < Any`. The pending-rejection is mis-nesting prevention, not
  garnish.
- **#888**: evidence made allocation-free (no AnyVal wrapper).
- **#1202**: the discarded-defer trap (`Unit < S1` to `Unit < S2` always-error
  conversion), pinned by kyo-direct's HygieneTest; later linked to issue 903.
- **#1291**: kyo module singletons must not lift (`Abort.type < Any`);
  WeakFlat became CanLift with the module-rejection macro. Pinned by
  CanLiftTest (renamed from WeakFlatTest) and AbortCombinatorsTest.
- **#1314 lift upgrade**: measured ~6,500 lift sites in the codebase, ~1,105
  AnyVal and ~400 String of them paying runtime dispatch; the old kernel's
  lift became a macro emitting three shapes: bare cast (Nothing, concrete
  classes, opaques), `Nested(v)` (statically known pending), and
  `defaultLift` (runtime Boxed test) only for genuinely abstract types.
  Accepted +1-2% compile time; BytecodeTest pins the emission.
- **kernel2 port regression** (62429e9fa6): the full-macro lift crashed the
  3.8.4 inliner with StaleSymbolException; a unit whose compilation suspends
  on a same-module macro, then inlines bodies referencing first-run classes,
  is the trigger (diagnosed with -Xprint-suspension). The port retreated to
  the erasedValue dispatch and the given zoo so that no unit of the module
  expands a same-module macro. A second quirk: with `opaque type CanLift =
  Null`, sibling givens in the defining file leak into every reference-typed
  implicit search, so the macro object lives outside `object CanLift`.

## Behaviors that must be preserved (the test map)

1. Pure values lift transparently: kernel2 PendingTest, old PendingTest
   "lift" section, KyoTest.
2. Known-pending types rejected with the flatten guidance (the #1148
   mis-nesting prevention): PendingTest `assertTypeError`, CanLiftTest.
3. Soft in generic contexts: abstract `A` gets evidence and a runtime Boxed
   check (CanLiftTest `genericContext[Int < Any]` compiles).
4. kyo module singletons rejected: PendingTest, CanLiftTest,
   AbortCombinatorsTest.
5. Case objects and ordinary singletons lift (Absent, user case objects).
6. The discarded-defer Unit trap errors with the 903 guidance: kyo-direct
   HygieneTest (downstream, mid-migration; needs a kernel2-local pin).
7. Pure function lifts 1-6 with evidence on the result type.
8. Aliases dealias; unions and intersections stay soft unless a branch is
   provably pending (CanLiftTest).
9. Emission quality: statically-safe types compile to a bare cast
   (old BytecodeTest, #1314).
10. Nesting round-trips: a computation lifted as a value gets the Nested box
    and unnests on the way out (PendingTest boxes, deep nesting rows).
11. Evidence is erased, never allocated (#888).
12. Zero same-module macro expansion in kyo-kernel2's own units (the 3.8.4
    StaleSymbolException constraint).

## Gaps in kernel2 today

- Concrete user types pay the runtime Boxed test on every lift (old kernel
  cast them statically). The userTypesSkipKernelWrapping bench row lifts a
  case class per level and measures this.
- Every lift site pays two NotGiven implicit searches for CanLift.
- kernel2 has no CanLiftTest; the evidence spec is only pinned in the old
  kernel.

## Proposed design: one conversion, one macro

```scala
implicit inline def lift[A, S](inline v: A): A < S = ${ LiftMacro.lift[A, S]('v) }
```

The macro owns the whole decision tree over dealiased A:

1. kyo module singleton (Module flag, not a case) -> abort with the module
   message (#1291).
2. Statically pending (A <:< Any < Nothing) -> abort with the flatten
   guidance (#1148 mis-nesting prevention; the text moves from CanLift's
   implicitNotFound into the macro).
3. Nothing, primitives, AnyVal, String, concrete classes, opaques,
   non-module singletons -> `v.asInstanceOf[A < S]` (bare cast: Kyo is
   sealed, so a value of a concrete non-pending class can never be a
   computation).
4. Everything else (type params, abstract types, unions with a pending
   branch) -> `defaultLift(v)` (the runtime Boxed test, today's
   Nested.lift).

Consequences:

- `liftAnyVal`, `liftUnit`, and the CanLift dependence of the conversion are
  subsumed; the given zoo shrinks to one macro-backed `derived` (no NotGiven,
  the macro checks directly and stays soft for abstract types),
  `unsafe.bypass`, and nothing else. CanLift remains as public evidence for
  APIs and the function lifts.
- `abortCastUnit` stays: different source shape, pure error trap.
- `liftPureFunction1..6` stay and call the same macro path.

## The suspension hazard plan

The one hard constraint is StaleSymbolException on 3.8.4. The rule: no unit
inside kyo-kernel2 may expand the module's own lift macro.

- Internal code keeps a private macro-free helper (today's erasedValue body,
  e.g. `private[kyo] inline def liftDirect`) and uses it at every internal
  site; the implicit conversion is a user-boundary feature.
- Enumerate internal sites mechanically: make the conversion non-implicit in
  a scratch build and let the compiler list every expansion point inside the
  module; convert each to the helper.
- The module's own Test configuration compiles in a separate run against the
  built main classes, so tests expand the macro safely; same for Jmh and
  every downstream module.
- Gate: a clean build with -Xprint-suspension asserting zero suspended units,
  the same diagnosis that caught the original crash.

## Expected wins

- Compile time: one conversion candidate instead of four, zero NotGiven
  searches. Measure on the fixture suite (ForComprehensions,
  EffectRowGenerics, Baseline delta) plus a new LiftHeavy fixture with many
  pure-value positions. #1314 measured +1-2% compile time for macro-lift on
  the old kernel against the pre-macro state; here the macro replaces implicit
  search machinery rather than adding to it, so the sign is expected to flip,
  and the fixtures decide.
- Runtime: concrete-type lifts lose the per-value branch;
  userTypesSkipKernelWrapping (176,720 B/op today, currently at 0.86x old
  kernel time) is the direct gauge.
- Surface: three files of routing collapse into a conversion, a macro, and a
  slim evidence object; the case-object and singleton specials disappear into
  macro arms.

## Open questions

1. Hybrid fast paths: keep `liftUnit`/`liftAnyVal` as non-macro implicits to
   spare macro expansion at the ~1,100 AnyVal sites, or let the single macro
   carry everything and accept the expansion cost? Proposal: build the pure
   single-macro shape first and let the compile fixtures arbitrate; add the
   fast paths back only if the numbers demand it.
2. Where the guided nesting error appears: the macro aborts whenever the
   conversion is attempted on a pending type, matching today's behavior
   (CanLift's implicitNotFound fires in the same positions). Positions where
   the conversion is never attempted keep the plain type error, unchanged.
3. Non-module singletons whose underlying type is pending (`val x: (Int <
   Any) = ...; x.type`): macro widens the singleton before classification, so
   they fall into the pending-rejection arm like today's derivedSingleton.

## Validation plan

- Port CanLiftTest into kernel2 (kernel-only assertions, no kyo-test
  dependency) and add a kernel2-local pin for the discarded-defer trap.
- All PendingTest pins unchanged; add a bytecode-shape probe asserting the
  bare-cast emission for a concrete class (the MapProbe/javap technique).
- Full kernel2 suite on JVM plus JS/Native compile.
- Compile bench before/after on the fixture suite plus LiftHeavy.
- Runtime board guards: userTypes, suspension family, fusionAllocatesNothing,
  trailingMaps.

## Implementation outcome (phase 1, commit afada84394)

Shipped: the single macro conversion with the CanLift gate untouched, the
liftInternal + fromKyo lexical escape in six kernel files (zero suspended
units under -Xprint-suspension), liftAnyVal and liftUnit subsumed, all 600
pins green.

Two corrections the pins forced on the old kernel's emission rule:

1. The old rule (cast for all classDefs and opaques) is unsound in principle
   and kernel2's inventory trips it: Loop.Outcome's underlying admits
   computations, Nested implements Product so trait-typed values can be
   boxes, and Arrow-typed values can be fused suspensions. The shipped cast
   arm is the provable subset: Nothing, AnyVal, String, final classes not
   Boxed. Everything else keeps the runtime test, which is the pre-change
   behavior, so nothing regressed.
2. Importing liftInternal alone shadowed the companion's fromKyo node
   conversion (lexical scope outranks implicit scope regardless of
   specificity), wrapping kernel-internal Kyo values as nested values;
   importing fromKyo alongside restores specificity-based selection.

Emission deltas pinned in PendingBytecodeTest: String 10 to 2 bytes, final
concrete class 17 to 2 (the issue-1314 gap closed), generic 17 to 8, mapLoop
109 to 113 (the pure-arm answer lift's macro cast shape).

## Phase 2 outcome: the macro off the trivial shapes (commit 2350fb2bb0)

The pure-macro lift regressed the primitive-dense fixtures (ForComprehensions
1.14x, MapChainWide100 1.09x at eight warmups): a macro expansion per lift
site costs more in the typer than an erasedValue match, and primitive answers
inside map-heavy code are the most common lift by far. The conversion stays
single: its body is the erasedValue prefilter, primitives and Unit and String
reduce to a bare cast in the inliner, and only non-trivial types reach the
macro. The macro entry lives on the LiftMacro object rather than in the
Implicits trait: a trait-member call in an inline body binds this-proxies at
every expansion, eight dead bytes per lift site even on branches that never
reach it. The hybrid restored the regressed fixtures to pre-macro time
(ForComprehensions 689 vs 707 pre-macro, Wide100 541 vs 527) with emission
byte-identical to the pure macro.

## Phase 3 outcome: CanLift removed (commit b6f61f7e80)

The evidence added nothing the macro cannot check itself. The conversion
dropped its gate, the macro took over both rejections (pending types with the
flatten guidance, kyo modules), the function lifts route through the macro,
liftInternal lost its parameter, and CanLift.scala went away entirely with
its 13-test spec. Every rejection pin passed unchanged: a macro abort during
implicit conversion search surfaces as search failure, so the same plain
mismatch is reported. The final machinery is one conversion, one macro, one
internal escape, against the original three files, nine implicits, two
macros, and five givens.

Measured effect of the removal: parity on the compile fixtures
(ForComprehensions 727 +-43 vs the hybrid's 689 +-77, Wide100 552 +-38 vs
541 +-47, SuspendSites 250 vs 250, EffectRowGenerics 257 vs 254 pre-macro;
the CanLift given search was cheap relative to macro expansion). The removal
is justified by the surface reduction alone, at no compile-time price.

Runtime guards after the full campaign, against the standing board:
userTypesSkipKernelWrapping 34.40 us/op vs 37.89 on the board (the
concrete-class lift dropped its runtime Boxed dispatch for a bare cast; the
last kernel2 row losing to the old kernel on time by more than noise outside
the structural map-on-suspension family improved 9 percent), allocation
byte-identical 176,720 B/op; fusionAllocatesNothing 0.584/0.004,
suspensionBaseline 84.5/640,120, trailingMaps 325.1/2,001,146, uncachedValues
38.6/155,248, all at board values.

If the stack migration surfaces an API that genuinely needs a propagating
no-nesting constraint (the old kernel's [A: CanLift] combinator signatures),
the evidence can return as a standalone type scoped to that surface; the
kernel itself no longer needs it.
