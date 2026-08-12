# Compile-time benchmarking: old kernel vs kernel2

Goal: a defensible, repeatable measurement of the compilation-time cost each
kernel imposes on user code. The kernel controls this cost through the inline
expansion of map/suspend/handle (typer: inlining, implicit search, macro
execution) and through the volume of code it makes the backend emit.

## The instrument: kyo-compile-bench

A warmed in-process dotc compiles fixture files against each kernel's classes;
each fixture is its own benchmark row isolating one cost driver. The project
depends only on kyo-data and scala3-compiler; the kernels enter as -classpath
entries, so the harness stays green regardless of the stack migration state.
An earlier draft proposed a second instrument on kyo-compiler (presentation
compiler latency); dropped as unnecessary complexity, and the batch numbers
carry the story.

Methodology (JMH, one forked JVM per fixture-and-kernel combination):

- Each benchmark invocation is a fresh `dotc.Main.process` (fresh ContextBase
  and symbol table), so no compiler state crosses invocations, and the JMH
  fork isolates all shared-JVM state (JIT warmth, GC, path caches) between
  combinations. Warmup iterations bring the compiler's own code to steady
  state inside each fork; per-iteration output makes progress observable.
- The Baseline fixture (plain Scala, no kyo) pins the measurement floor at
  0.98-1.02 and would drift if the harness favored either side.
- Fixtures live outside the kyo package so Frame derivation is the real
  per-site macro cost, and are byte-identical against both kernels (handleLoop
  is excluded: its handler shape diverges between kernels).
- The first iteration of the harness was a hand-rolled in-process loop with
  old/new interleaving; its ratios matched across differently composed runs
  within ~2%, and JMH replaced it for fork isolation and standard reporting.

## Results (6 warmup / 12 measure rounds, ratio = new/old)

| fixture | old ms | new ms | ratio |
|---|---|---|---|
| MapChainDeep100 | 20,314.7 | 9,595.1 | 0.47 |
| HandleSites | 648.7 | 370.7 | 0.57 |
| SuspendSites | 394.4 | 243.3 | 0.62 |
| TagDerivation | 139.0 | 109.3 | 0.79 |
| MapChain10 | 170.2 | 145.9 | 0.86 |
| MapChainWide100 | 558.7 | 504.1 | 0.90 |
| ForCompShallow | 255.5 | 235.4 | 0.92 |
| FlatMapChains | 436.4 | 406.6 | 0.93 |
| ForComprehensions | 710.2 | 679.4 | 0.96 |
| NestedMaps | 702.3 | 680.3 | 0.97 |
| EffectRowGenerics | 251.1 | 256.3 | 1.02 (floor) |
| Baseline (no kyo) | 169.9 | 173.4 | 1.02 (floor) |
| TOTAL | 24,751.1 | 13,399.7 | 0.54 |

The corpus compiles in 54% of the old kernel's time. Notable shape: compile
cost is roughly quadratic in map-chain length within a single method on BOTH
kernels (10 sites ~170 ms, 100 sites ~20 s old / ~9.6 s new; the same 100
sites across 20 methods cost ~0.5 s); the new kernel halves the constant.

## The regression the harness caught and its fix

The first run measured ForComprehensions at 1.46x the old kernel (1.23x at
half the nesting depth) while identical nesting through direct map calls sat
at 0.97x and flat flatMap chains at 1.16x, isolating the cause: flatMap,
andThen, unit, and flatten delegated to the inline map, so every call site
expanded twice (the wrapper, then the inliner re-running over the spliced map
call), compounding with closure-nesting depth in every for comprehension.
The fix gives each extension its own full inline body, the old kernel's
shape. After it, every fixture is at or below the old kernel; the diagnosis
fixtures (NestedMaps, FlatMapChains, ForCompShallow) stay in the suite as
regression pins.

## Running it

```sh
sbt ';project kyo-compile-bench ;Jmh/run'          # full board, forked
sbt ';project kyo-compile-bench ;Jmh/run -f 0 -p fixture=ForComprehensions'  # quick in-process loop
```

Open follow-ups: per-kernel shim fixtures to price the handleLoop family; a
-Yprofile-trace mode for per-phase attribution.
