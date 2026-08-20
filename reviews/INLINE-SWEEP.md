# The inline sweep

What P1 asked: reduce compile time without regressing runtime. This records what was measured, what changed, and
what was checked and left alone.

## The harness

Two tools, neither needing sbt, so measurement runs while a suite does.

**Compile time.** `dotty.tools.dotc.Main` off the coursier cache, compiling a fixture repeatedly in one JVM and
reporting the trimmed mean of the measured runs. The JMH harness in `kyo-compile-bench` is the board; this is the
iteration loop. One trap for anyone repeating it: at 3.8.4 `scala3-library_3` is an empty forwarder jar and the
classes live in `scala-library` 3.8.4.

**Expansion shape.** The same driver with `-Xprint:inlining`, against `fixtures-expansion`, which isolates one
variable per file: `NoMap` is the zero point, `AlreadyPending` a lambda whose body already has a pending type,
`BareValue` a lambda returning a bare value, `BareSingleton` one returning a plain object, which is the only shape
that reaches the `CanLift` splice macro.

`AlreadyPending` did not control anything as first written: its `(x + 1: Int < Any)` ascription fires the same
implicit conversion the bare value does. It now calls a helper that already returns a pending type, so the lift
happens once inside the helper rather than at the map site.

## What changed

### `Eval.apply` is no longer inline

The drive is 555 instructions. HotSpot refuses to inline it at any call site, which was already established for
`Eval$::loop` (`inlining prohibited by policy`). So the inline definition bought nothing at runtime, and it emitted
a private copy of the whole interpreter into every caller. `PendingTest.class` carried **132 copies of `loop$N`**,
each 555 instructions, roughly 73,000 instructions of duplicated interpreter in one class file.

Compile time, same-session A/B over the fixture corpus, 5 warmup and 12 measured compiles per fixture in one JVM:

| fixture | inline | plain `def` | |
|---|---:|---:|---|
| **HandleSites** | 831.7 ms | **351.5 ms** | 🟢 0.42x |
| NestedMaps | 599.1 | 570.9 | 🟢 0.95x |
| ForCompShallow | 241.9 | 231.2 | 🟢 0.96x |
| FlatMapChains | 457.5 | 444.4 | ⚪ 0.97x |
| Baseline | 210.8 | 207.0 | ⚪ 0.98x |
| MapChainDeep100 | 13445.1 | 13151.6 | ⚪ 0.98x |
| ForComprehensions | 597.4 | 587.3 | ⚪ 0.98x |
| MapChain10 | 161.5 | 160.6 | ⚪ 0.99x |
| MapChainWide100 | 578.5 | 577.1 | ⚪ 1.00x |
| EffectRowGenerics | 288.2 | 290.9 | ⚪ 1.01x |
| ForCompDeep25 | 1206.7 | 1226.0 | ⚪ 1.02x |
| SuspendSites | 251.5 | 261.4 | 🔴 1.04x |
| TagDerivation | 130.7 | 138.7 | 🔴 1.06x |

`HandleSites` is the only fixture that drives at many sites, 26 of them, and it is the one that moves. The rest
drive once or not at all, so one expansion out of a large file is invisible. The two red rows contain no `Eval` at
all, so they are run-to-run drift rather than an effect of the change.

This also answers the standing question about `HandleSites` compiling 1.49x slower than the old kernel: at 0.42x of
its own previous number it lands at roughly 0.63x of the old kernel.

**Runtime: allocation is unchanged, time is not yet settled.** Both legs of the 34-row board, same session, back to
back, `-f 1 -prof gc`. Every row's `gc.alloc.rate.norm` is **identical to the byte**, which rules allocation out and
forces any time difference into path length or code shape.

The time column at `-f 1` does not support a claim in either direction, because the two bench classes disagree
about the same paths: `suspensionFusesContinuation` reads 1.13x on `KernelBench` and 1.00x on `ProtoKernelBench`;
`trailingMapsStayLinear` reads 1.32x and 1.07x; `statefulAnswersPaySuccessor` 1.23x and 1.04x. Same code, same
session, two forks. That is fork variance rather than a mechanism, which is exactly what a single-fork screen is
for. `evalFixedOverhead` is at 0.01 us/op, below the harness's resolution, and is not readable at all.

Five rows sit outside the drift band and are being confirmed at `-f 3`: `trailingMapsStayLinear`,
`foreignCrossingsPayRotation`, `statefulAnswersPaySuccessor`, `suspensionFusesContinuation`,
`deepRecursionPaysRescuesOnly`. **Nothing is claimed about runtime until that lands.** If a delta survives, it is an
open defect against this change, not a trade to be accepted alongside the compile-time win.

**`@static` was tried and cannot be used here.** Scala.js cannot emit a static method containing a lambda:
`genSJSIR` fails with `Cannot resolve delambdafy target method $anonfun` on the eta-expansion in the `handleCont`
arm. The other `@static` methods in `kyo.kernel.internal` hold local defs and anonymous classes but never lambdas,
which is why they compile. That is a constraint on where `@static` is available, not something specific to the
drive. The module load a plain `def` costs is one `getstatic`, against a 555-instruction expansion per call site.

### `evalNow` built its receiver twice

Not a performance question in the end. `self` is `inline` on the extension, so every occurrence re-expands the
receiver expression, and `evalNow` had one occurrence per branch. `v.map(f).evalNow` therefore built the map twice
and, on the settled path, ran `f` twice. Bound once now, with a reproducing test in `PendingTest`.

### `Nested.unnest` replaces the `unsafeGet` extension

An inline body that selects a member through the opaque type's owner makes the expansion carry a proxy chain for
that owner with the refinement written out longhand. `unsafeGet` is an extension on `A < S`, so every inline
combinator that read a settled value paid for it. `Nested` gained a sibling to its lift, and the two are now named
for each other: `nest` and `unnest`. The extension is removed rather than kept as a delegate, so the trap is not
available; nothing outside kernel2 used it.

The decompiled `map` body for `ask.map(_ + 1)` shows what it bought. Before:

```java
public final Object run$1(Pending$package$ $proxy8$1, Object v, Arrow next) {
    ...
    Pending$package$ Pending$package$_this = pending$package$ = $proxy8$1;
    if (object2 instanceof Nested) n = unboxToInt(((Nested)object2).value());
    else                           n = unboxToInt(object2);
```

After:

```java
public final Object run$1(Object v, Arrow next) {
    ...
    int n = unboxToInt(Nested.unnest(v));
```

`run$1` loses a parameter, and the anonymous `Transform` loses a captured `Pending$package$` field it stored in its
constructor. That second part is a per-allocation runtime saving on the hottest allocation in the kernel, not only a
compile-time one.

Per map call site, against a map-free control: 7053 characters of tree to 4164, `$proxy` bindings 14 to 12,
refinement restatements 10 to 4.

Compile time, `kyo-compile-bench` on the fixtures that exercise `map`, 8 warmup and 5 measured as the harness
defines, on an otherwise quiet machine:

| fixture | before | after | |
|---|---:|---:|---|
| **MapChainDeep100** | 13,981 ± 2,250 ms | **11,090 ± 345 ms** | 🟢 0.79x |
| MapChain10 | 187.1 ± 84.4 | 153.3 ± 18.9 | 🟢 0.82x |
| MapChainWide100 | 617.5 ± 68.6 | 529.6 ± 36.0 | 🟢 0.86x |
| NestedMaps | 589.3 ± 48.3 | 547.3 ± 71.2 | 🟢 0.93x |

`MapChainDeep100` is where the inliner is superlinear and it is the row that moves most, 2.9 seconds. It is also the
only row whose intervals essentially separate, and the after leg is far more stable, ±345 against ±2250.

Runtime: every lift pin in `PendingBytecodeTest` is unchanged, so the primitive lift is still a bare cast and the
concrete-class lift is still 5 bytes. `ArrowEffectBytecodeTest`'s `handleCont` pin moved 87 to 44, which also closes
the standing question about that call site.

### Function1's specialization forwarders are dead weight, and not yet addressed

`Arrow` extends `(A => B < S)`. `Function1` is `@specialized` on both parameters, so the library declares the
`apply$mcXY$sp` grid, and Scala 3 emits a concrete mixin forwarder per trait member into every implementing class.
Since `Arrow` and `Transform` are both traits, each anonymous `new Transform[...]` is the first class in its chain
and emits all 26.

Measured across kernel2's 1165 classes: **19,776 forwarder definitions and zero genuine call sites.** Every
occurrence is a forwarder invoking its own trait default. That matches the decompiled body, where the value goes
through `boxToInteger` into the generic two-argument `apply`; the grid targets the one-argument `Function1.apply`,
which the drive barely uses. One `Transform` class is 6195 bytes, of which 26 of its 34 methods are forwarders.

An isolated probe confirms the fix without touching kyo: an anonymous class mixing the trait directly is 5459 bytes
with 24 forwarders, and one extending an abstract class that mixes the trait once is 1014 bytes with none. The
abstract class pays them once.

`Transform` has to stay a trait, since six sites mix it into a `Kyo` node class. So the shape is abstract classes
alongside it, one per instantiated combination: a plain `TransformBase` for the seven standalone-arrow sites, and
fused ones for `suspendWith` and the three `*With` handlers. Not implemented.

## What was checked and left alone

**The 18 collection combinators.** Only the `Seq` façade is `inline` and each of those is a one-line delegate; the
real implementations on `Chunk`, `List`, `Set` and the generic `CC` are already plain `def`s. Nothing to win.

**`map` itself.** Its expansion is one anonymous `Transform` plus a local `run`; `Effect.defer`'s three-argument
form is already `@static` and non-inline, so a call site mints one class rather than three. The Safepoint depth
guard cannot be hoisted out because the inlined `f` sits in the middle of it.

This also answers the standing question in `Pending.scala` about whether the expanded code reaches other `inline`
methods. Reading the `-Xprint:inlining` tree for `BareValue`, the map expansion contains **calls**, not expansions,
to `Effect.defer` (twice), `Arrow.id` (twice), `next.head`, `next.tail` and the three `Safepoint` entry points, and
it constructs one `Arrow.Transform`. It carries exactly two inlined bodies, visible as the two `v$proxy` bindings:
the user's `f`, and `unsafeGet`. So `map` nests two inline methods and nothing else, which is why there is no
flattening left to do there. The dump this is read from predates the visibility fix, so the Safepoint calls appear
there through accessors; they are direct calls now, and the count of nested inline bodies is unaffected.

**`CanLift`'s `derivedSingleton`.** The macro costs about 19% of a map site's tree, but it must stay inline: as a
plain given it would expand against an abstract type, both of `checkImpl`'s guards would evaluate false, and the
lint would silently become a no-op rather than failing loudly.

## Still open

`Kyo.lift` and `Kyo.unit`, and dropping `inline` from `self` on the pending extension. Both are small: after the
`evalNow` fix, `self` occurs once in every member.
