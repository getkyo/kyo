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

## What gets inlined, exactly

The trace's per-expansion events name every inline expansion. Deep100 (100
map sites) performs 500 expansions, five per site; ForComprehensions (12
flows, 84 sites) performs 301:

| method | Deep100 | ForComp | nested in | verdict |
|---|---|---|---|---|
| `map` / `flatMap` | 100 | 72 | top level | the design: per-site fusion |
| `Kyo.unnest` | 100 | 72 | inside map | load-bearing: primitive answers skip the runtime unnest call |
| `Kyo.Defer.apply` | 100 | 72 | inside map | UNJUSTIFIED: slow-path construction; de-inlined |
| `lift` (conversion) | 100 | 13 | top level | load-bearing: the answer adaption must not be a runtime call |
| `Frame.derive` (macro) | 100 | 72 | top level | inherent: per-site position info |

Maximum inline nesting depth is 2 (map contains unnest and the Defer apply);
there is no runaway inline-in-inline. Safepoint.get/enter/exit are plain
static methods, not inline, so they cost nothing at expansion time.

Measured deltas on Deep100's inlining phase (single runs, ~3% noise):

- De-inlining the node companion applies (Defer, Handled, HandledState):
  9,736 to 9,485 ms (~2.5%). Shipped: they sat on slow paths and bought no
  runtime. mapLoop's residual shrinks 113 to 108 bytes per site.
- Supplying one `given Frame` for the file instead of deriving per site:
  9,736 to 7,828 ms (20%). Not shippable as-is (frames must be per-site for
  diagnostics), but it isolates the amplification: each derive leaves an
  Inlined node in the accumulated tree, and mechanism 2 re-collects every
  Inlined node once per ancestor, so the macro's cost on deep chains is
  quadratic amplification, not macro execution. A snippet-size theory was
  checked and refuted: the emitted classfile holds no string constant over
  236 characters.

Is the issue "map/flatMap"? The cost variable is INLINE-NESTING DEPTH PER
EXPRESSION, and both syntactic shapes are superlinear in it. Measured
inlining self time by depth, one expression per probe:

| depth | receiver chain `.map().map()` | lambda nest `.map(_ => ...)` | for comprehension |
|---|---|---|---|
| 25 | | 1,958 ms | 2,280 ms |
| 50 | 2,323 ms | 10,079 ms | 10,768 ms |
| 100 | 9,736 ms | 85,832 ms | 100,379 ms |
| exponent | 2.1 to 2.4 | 2.4 to 3.1 | 2.2 to 3.2 |

For comprehensions desugar to the lambda-nested shape, and at equal depth it
is roughly 10x MORE expensive than receiver chaining, with the exponent
heading to cubic: each level embeds the entire already-expanded rest through
the inline function parameter's substitution, under one more binder layer per
level, so the symbol-and-owner substitution re-walks the rest with an
environment that also grows with depth. (An earlier draft of this analysis
claimed the lambda shape was flat; that was an artifact of the six-deep
fixture, where the superlinear term is invisible. The ForCompDeep25 fixture
now pins the shape at an affordable 2.3 s.)

What saves real code is that comprehension depth is bounded by what a person
writes in one expression: at 6 generators the cost is invisible, at 25 it is
2.3 s for a single method, at 100 it is 100 s. Splitting an expression into
separate defs or vals resets the depth: the same 100 map sites split across
20 methods compile ~18x faster than in one method.

## Experiment: the Transform-outside-map redesign (built, measured, rejected)

The natural fix attempt: move the whole drive logic into the per-site
Transform (its apply becomes mapLoop, recursion through `this.chain(next)`,
the suspendWith `cont = this` pattern) and shrink map to inline wiring
`Transform.derive(f)(self, Arrow[B])`, so the per-level expansion is one
self-contained unit instead of machinery wrapped around the receiver. A
non-inline `map(f: Transform)` variant with an implicit conversion was ruled
out first: argument-position conversions do not propagate parameter types
into untyped lambdas, so `.map(_ + 1)` stops compiling, and `into`-style
conversions cannot carry an inline f, which fusion requires.

The wiring variant compiled and passed the full suite (587/587), and the
call-site bytecode collapsed from 140 bytes across three lifted methods to
28 bytes in one. The compile fixtures rejected it:

| fixture | mapLoop design | Transform-outside | delta |
|---|---|---|---|
| MapChainDeep100 | 9,485 ms | 8,057 ms | -15% |
| MapChainDeep200 | 52,643 ms | 43,491 ms | -17% |
| ForCompDeep25 | 2,280 ms | 2,783 ms | +22% |
| ForCompDeep50 | 10,768 ms | 16,729 ms | +55% |
| ForComp100 | 100,379 ms | 161,607 ms | +61% |
| ForComprehensions | ~1,450 ms | 1,531 ms | +6% |

The mechanism: in this design f, carrying the entire nested rest, sits
INSIDE the anonymous class definition. TreeTypeMap over a tree containing a
class def clones the class symbol and its members, so nesting the rest
class-in-class k deep makes every level's re-map clone the whole nested
class tree. The mapLoop design keeps f in a LOCAL DEF, cheap to re-map, and
its per-site class contains only a one-line call; receiver chains improved
because the prefix rides in a sibling binding outside the new class def, but
that is the rarer shape. Reverted; the rule it leaves behind: keep deeply
nested user code out of class definitions inside inline expansions, local
defs re-map an order of magnitude cheaper.

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
