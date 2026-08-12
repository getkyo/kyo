# How dotc executes the symptomatic fixtures: a phase-level diagnosis

Scala 3.8.4, the kyo-compile-bench fixtures MapChainDeep100 (one method, one
hundred chained maps: 8.5 to 9.7 s per compile) and ForComprehensions (twelve
six-step for comprehensions: ~0.7 s). All numbers from single instrumented
runs of the exact classpath the bench uses; reproduction commands at the end.

## Instruments

| flag | what it gives |
|---|---|
| `-Yprofile-enabled` | per-phase self time, cpu, allocation printed per run |
| `-Yprofile-trace <f>` | chrome/perfetto trace with per-phase AND per-inline-expansion begin/end events, per-typecheck events, heap counters |
| `-Vphases` | the phase plan |
| `-Vprint:<phase>` / `-Xprint-inline` | tree after a phase / inline provenance |
| `-Ylog:inlining` | phase-level logging |
| `-Ydump-sbt-inc` | dump the zinc dependencies the run recorded |
| JFR (`-XX:StartFlightRecording,settings=profile`) | compiler hot frames |

The trace's `inline` events are the sharpest tool: one begin/end pair per
inline expansion, so the per-expansion cost curve falls straight out.

## Where the time goes

Per-phase self time (`-Yprofile-enabled`, cold JVM):

| phase | Deep100 | ForComprehensions |
|---|---|---|
| inlining | 10,071 ms (84%) | 1,450 ms (49%) |
| typer | 1,186 ms (10%) | 684 ms (23%) |
| erasure | 266 ms | 160 ms |
| genBCode | 294 ms | 232 ms |
| everything else | ~500 ms | ~450 ms |

Typer does NOT expand these inline methods: calls are typed against the
inline method's declared signature and left standing. The `inlining` phase
(transform.Inlining, after pickler) walks the typed tree bottom-up
(`InliningTreeMap.transform`: children first via `super.transform`, then
`Inlines.inlineCall` on the current node) and each expansion typechecks the
inlined body in place, which is why `typecheck method mapLoop` events nest
inside the inlining phase in the trace.

## MapChainDeep100: superquadratic inline expansion

Scaling of the inlining phase's self time with chain depth (one method, n
chained `.map(_ + 1)`):

| n | inlining ms | exponent vs previous |
|---|---|---|
| 50 | 2,323 | |
| 100 | 9,736 | 2.07 |
| 150 | 25,357 | 2.36 |
| 200 | 52,643 | 2.43 |

Per-expansion inclusive durations from the trace (500 expansions, completion
order, so innermost chain positions complete first):

| expansion window | mean ms |
|---|---|
| 0-49 | 7.8 |
| 200-249 | 9.9 |
| 350-399 | 21.0 |
| 450-499 | 34.6 |

Marginal cost per expansion grows linearly with position in the chain, so the
sum is quadratic, and the rising exponent (2.1 to 2.4) shows a third-order
term emerging at depth.

JFR hot frames (998 samples): `Substituters.substSym` (leaf leader),
`TreeTypeMap.{apply,mapType,mapPrefix}`, `SymDenotation.enclosingClass`,
`Types.TypeMap.mapOver`, and a second cluster in
`AbstractExtractDependenciesCollector.{traverse,addMemberRefDependency}`
(about 13% of samples).

Two mechanisms, both confirmed in the 3.8.4 sources:

1. **The Inliner re-maps the accumulated receiver at every expansion.**
   `.map` is bottom-receiver-nested: expanding map k produces a tree whose
   proxy bindings contain the entire already-expanded k-1 chain. Each
   `inlineCall` runs `InlinerMap`, "a TreeTypeMap with special treatment for
   inlined arguments" (inlines/Inliner.scala:112), over the expansion,
   substituting symbols and owners node by node; the walk covers the
   accumulated subtree, so expansion k costs O(k) tree nodes. `substSym`'s
   per-node cost itself grows with the size of the types it maps (the
   per-site polymorphic `mapLoop[C, S3]` instantiations stack intersection
   rows with depth), which is the emerging third order.

2. **The phase re-collects zinc dependencies once per ancestor.** Every
   `InliningTreeMap.transform` call ends with `inlineFinder.traverse(result)`
   (transform/Inlining.scala:164), a traverser that hands every `Inlined`
   node it finds to `ExtractInlineDependenciesCollector.traverse`, which
   walks the whole inlined subtree recording member references
   (sbt/ExtractDependencies.scala:203). transform runs at every tree node,
   and each ancestor's `result` contains all the Inlined nodes below it, so a
   deep chain's innermost expansion is re-collected once per level above it:
   O(size x depth). This is incremental-compilation bookkeeping, and it runs
   unconditionally: the standalone sbt-deps and sbt-api phases are guarded by
   `runZincPhases` (Contexts.scala:179, incremental callback present or
   `-Yforce-sbt-phases`/`-Ydump-sbt-inc`), but the Inlining phase constructs
   its collector with no guard, so plain dotc with no zinc pays it too, and
   the recorded dependencies are then discarded.

The stack requirement of the phase grows with depth for the same structural
reason (each nested expansion is typechecked recursively inside its parent's
expansion): measured minimum thread stack 1 MB at depth 50, 3 MB at 100 and
150, 4 MB at 200 for kernel2 trees (the old kernel needs 1.5 to 2 MB flat but
compiles the same fixture twice as slowly overall).

Contrast that makes the diagnosis crisp: MapChainWide100 (the same 100 sites
split across 20 methods) compiles in ~0.5 s, because each method's chain is
only 5 deep and none of the accumulation happens.

## ForComprehensions: expansion count times a constant

301 expansions, per-expansion mean 7.3 ms cold falling to 2.7 ms as the JIT
warms, no growth across the run: the cost is purely (number of expansions) x
(per-expansion constant). The constant is the full Inliner cycle per site:
allocate the expansion, substitute symbols, typecheck the ~40-line inlined
body (which defines a local recursive method and an anonymous Arrow.Transform
class, so fresh symbols and a class denotation per site), then run the
dependency collector over it. This is why the fixture was the sensitive
detector during the lift work: the pure-macro lift added a macro expansion
inside every one of those bodies (+14%), and the historical
flatMap-delegating-to-map shape doubled the count outright (+44%).

The remaining 23% in typer is inference through the six-level nested closure
chains: each `flatMap` lambda's result type feeds the next level's type
variables, with effect-row unification at each step. Linear, but with a
larger constant than ordinary code.

## Flags: what can and cannot help

Diagnostic flags are listed above and all work as documented. Mitigation
options are nearly empty, which is itself the finding:

- `-Xmax-inlines` (default 32) and `-Xmax-inlined-trees` (default 2,000,000)
  are safety limits, not cost controls. The chain does not trip either
  (recursion per site is shallow; the trees are large but under the cap), so
  raising them changes nothing and lowering them only turns slow compiles
  into errors.
- There is no flag that disables the Inlining phase's dependency collection;
  the guard exists only for the standalone sbt phases. Plain dotc pays the
  collection and discards the result.
- `-Ydump-sbt-inc` and `-Yforce-sbt-phases` force MORE zinc work, never less.
- JVM-side, `-Xss` must scale with the deepest expected inline chain
  (measurement infrastructure already sets 32 MB); this accommodates the
  recursion, it does not speed it up.
- No positions/spans/coverage flag materially affects the phase.

## Implications

Kernel-side (task #66): the cost driver is per-site expansion size times
chain accumulation. Anything that shrinks what one `.map` expansion carries
(fewer symbols, no per-site class in the settled path, smaller types in the
chain's polymorphic instantiations) multiplies against both mechanisms.
The old kernel's identically-shaped fixture takes 20 s against kernel2's
9.7 s purely because its per-site expansion tree is larger.

Dotty-side, two concrete reportable inefficiencies:

1. `InliningTreeMap.transform` re-traverses every `Inlined` subtree once per
   ancestor node for dependency collection; collecting each Inlined node once
   (or traversing only freshly created expansions) would drop that term.
2. The collection runs without an incremental callback and its output is
   discarded; gating the collector on `ctx.runZincPhases` like its sibling
   phases would make plain dotc, scripts, and benchmark harnesses stop paying
   it.

## Reproduction

```sh
DOTC=<scala3-compiler 3.8.4 classpath> ; CP=<kyo-data + kernel classes>
java -Xss32m -cp $DOTC dotty.tools.dotc.Main -classpath $CP -d /tmp/out \
  -Yprofile-enabled -Yprofile-trace deep100.trace \
  kyo-compile-bench/fixtures/MapChainDeep100.scala
# per-phase self times print to the console; the trace opens in ui.perfetto.dev
java -Xss32m -XX:StartFlightRecording=filename=deep100.jfr,settings=profile \
  -cp $DOTC dotty.tools.dotc.Main -classpath $CP -d /tmp/out \
  kyo-compile-bench/fixtures/MapChainDeep100.scala
jfr print --events jdk.ExecutionSample deep100.jfr
```
