# Cross-library benchmark requirements

Goal: measure how the kernel2 evaluator fares against the ecosystem on the same workload shapes, with
the old kyo-kernel included. Plain JMH benchmarks; their long-term home is decided later.

## Targets and references (verified 2026-08-21)

| library | artifact | version | run entry | notes |
|---|---|---|---|---|
| kyo-kernel2 | in-repo | HEAD | `.eval` | existing `KernelBench` rows, reused as-is |
| kyo-kernel (old) | in-repo | HEAD | `.eval` | existing `kyo-kernel-bench` mirror, reused as-is |
| ZIO | `dev.zio %% zio` | 2.1.26 (2026-05) | shared `Runtime.default`, `unsafe.run` | |
| cats-effect | `org.typelevel %% cats-effect` | latest stable 3.6.x/3.7.x (plan pins) | `unsafeRunSync()` on the global runtime | |
| zio-blocks Async | `dev.zio %% zio-blocks-async` | 0.0.51 | `.block` | claims: completed `Async[A]` IS the `A`; map/flatMap over ready values allocate nothing; no runtime |
| Turbolift | `io.github.marcinzh %% turbolift-core` | 0.114.0 | `.handleWith(...).run` | algebraic effects: Reader/State map directly onto Ask/stateful rows |

## Module layout requirement

A dedicated, unpublished sbt project (precedent: `kyo-kernel-bench`), e.g. `kyo-kernel2-bench-cross`:
JmhPlugin, `publish / skip := true`, depends on `kyo-kernel2.jvm`, carries the external dependencies.
The external libraries must NEVER reach a published kyo artifact's pom; that is the hard constraint the
layout exists to satisfy. One benchmark class per library (`ZioBench`, `CatsEffectBench`,
`ZioBlocksBench`, `TurboliftBench`), row names identical to `KernelBench`'s so tables join by name.

## Row mapping, by fidelity tier

Tier A: exact semantic match, all libraries.

| row | shape | mapping |
|---|---|---|
| evalFixedOverheadBatch | settled value + 1 map, x1000 | pure/succeed + map + run per library |
| fusionAllocatesNothing | depth-32 recursion, 11 maps/level | identical code shape everywhere |
| fusionPastBudgetPaysRescuesOnly | same at depth 1000 | identical; measures each library's trampoline/stack machinery |
| uncachedValuesPayBoxingOnly | Int boxing outside cache through the chain | identical |
| userTypesSkipKernelWrapping | user case class through the chain | identical |
| deepRecursionNoRescue / OneRescue / PaysRescuesOnly | deep pure bind recursion | classic deep-bind; identical |
| trailingMapsStayLinear | recursion with a trailing map per level (issue 531 shape) | flatMap recursion + trailing map; the row where old kyo is quadratic |

Tier B: mapped with a documented substitution (the suspension/handler rows; only Turbolift has true
algebraic effects, ZIO/CE substitute their idiomatic ambient-value mechanism).

| row | kernel2 meaning | ZIO | cats-effect | Turbolift | zio-blocks |
|---|---|---|---|---|---|
| suspensionBaseline / suspensionFusesContinuation / continuationBodiesFuse / sharedHandlerPaysDispatch | Ask suspension answered by a cont handler | environment read (`ZIO.service[Int]`) under `provideEnvironment`, or `FiberRef.get` (plan picks one, justified) | `IOLocal.get` | `Reader.ask` under its handler (exact) | no effect system: nearest is a pre-completed `Async` read; fidelity documented as LOW |
| statefulAnswersPaySuccessor / handleLoopAnswersInPlace | stateful/stateless loop handler | `FiberRef.updateAndGet` or `Ref` (plan picks, justified) | `Ref[IO]` | `State` effect (exact) | local var loop; fidelity LOW |
| foreignCrossingsPayRotation | two effects alternating, foreign handler crossings | two services / two FiberRefs | two IOLocals | two Reader effects (exact) | skip |
| fusionAfterSuspension / RunOnly | chain built once over an unanswered suspension, answered per run | prebuilt `val` of the effect, run per op | same | same (exact) | prebuilt Async chain |

Tier C: skipped as kernel-specific, with the reason recorded: `inlineLimitKeepsZeroAllocation`,
`inlineLimitCostsTimeNotAllocation` (they pin kyo inline mechanics, not evaluator work);
`emittingClausesPayRegionRebuild` and the Proto-only rows (region rebuild has no portable analogue).

## Measurement protocol

Same as the campaign's: `-f 2 -wi 5 -i 5 -prof gc` for claims (`-f 3` on anything contested), all
libraries same session on the quiet machine, bench mutex respected, `gc.alloc.rate.norm` reported
beside time in every table. Batch rows use `@OperationsPerInvocation` where the single op is below
harness resolution. Run-entry overhead (runtime startup vs `.eval`) is part of what each library's
sync execution costs and is measured, not factored out; each bench class documents its entry choice.

## Fairness rules

1. Same shapes, same constants (Depth/NarrowDepth/FusedDepth), same seeds, same accumulation trick
   preventing hoisting.
2. Idiomatic code per library, not deliberately pessimized or micro-tuned beyond what kyo's rows get;
   where a library offers two natural spellings the plan picks the faster common one and records the
   alternative.
3. Every Tier B substitution stated in the bench file's scaladoc, so no table can be read as claiming
   an exact-match comparison where there is none.
4. No library gets a warm-up advantage: identical JMH settings, one class per library, no shared
   mutable state across rows.

## Deliverables

1. The bench project with the four classes compiling and running.
2. A results document with the joined tables: kernel2, old kernel, ZIO, CE, zio-blocks, Turbolift —
   time and B/op per row, missing cells marked with the tier/skip reason.
3. The raw JMH JSONs committed under reviews/bench/.

## Open questions for the plan (Opus agent) to resolve and the user to validate

- Exact pins: cats-effect 3.6.x vs 3.7.0-RC1; whether zio-blocks-async 0.0.51 API (`.block`) is
  stable enough to bench; turbolift's recommended handler composition for lowest overhead.
- ZIO ambient choice: environment vs FiberRef for the Ask rows (measure both once, pick, record).
- Whether Turbolift needs its interpreter's stack-safety settings adjusted for depth-10000 rows.
- Scala version compatibility: repo is on Scala 3.8.4; verify each library resolves under it (or
  needs CrossVersion.for3Use2_13 anywhere).
