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

**`@static` was tried and cannot be used here.** Scala.js cannot emit a static method containing a lambda:
`genSJSIR` fails with `Cannot resolve delambdafy target method $anonfun` on the eta-expansion in the `handleCont`
arm. The other `@static` methods in `kyo.kernel.internal` hold local defs and anonymous classes but never lambdas,
which is why they compile. That is a constraint on where `@static` is available, not something specific to the
drive. The module load a plain `def` costs is one `getstatic`, against a 555-instruction expansion per call site.

### `evalNow` built its receiver twice

Not a performance question in the end. `self` is `inline` on the extension, so every occurrence re-expands the
receiver expression, and `evalNow` had one occurrence per branch. `v.map(f).evalNow` therefore built the map twice
and, on the settled path, ran `f` twice. Bound once now, with a reproducing test in `PendingTest`.

## What was checked and left alone

**The 18 collection combinators.** Only the `Seq` façade is `inline` and each of those is a one-line delegate; the
real implementations on `Chunk`, `List`, `Set` and the generic `CC` are already plain `def`s. Nothing to win.

**`map` itself.** Its expansion is one anonymous `Transform` plus a local `run`; `Effect.defer`'s three-argument
form is already `@static` and non-inline, so a call site mints one class rather than three. The Safepoint depth
guard cannot be hoisted out because the inlined `f` sits in the middle of it.

**`CanLift`'s `derivedSingleton`.** The macro costs about 19% of a map site's tree, but it must stay inline: as a
plain given it would expand against an abstract type, both of `checkImpl`'s guards would evaluate false, and the
lint would silently become a no-op rather than failing loudly.

## Still open

`Kyo.lift` and `Kyo.unit`, and dropping `inline` from `self` on the pending extension. Both are small: after the
`evalNow` fix, `self` occurs once in every member.
