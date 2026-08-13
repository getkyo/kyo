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

## The local-run shape: user redesign, validated (commits 4b2eab8441, 9e9f725727, 1982c8c4cc)

The shipped resolution of the self-contained-candidate violations. The
per-site expansion defines a local `run` def carrying the whole evaluation
step (kyo attach, budget check, `step.head(f(res), step.tail)`); the
anonymous Transform's apply is a one-line delegation to `run`, and the
entry path calls `run` directly. Consequences, each verified:

- The user's `f` no longer nests inside a class definition, so the
  TreeTypeMap class-def cloning that made lambda-nested shapes ~10x worse
  no longer multiplies per level.
- The eager path never touches `def arrow`, so it allocates no Transform
  by construction again (not escape-analysis-dependent), which closes both
  candidate violations.
- The suspend and defer arms mint the Transform lazily inside `run`
  (`arrow.chain(next)`), preserving the candidate's node-count.

Alongside it, `Safepoint.enter()` fused slot resolution and budget entry
into one call returning the slot, with a negative sentinel when denied.
That fusion was later found to carry a large regression on the eager
path and was reverted (see "The fused enter() regression" below); the
two-step get/enter is the shipped shape at the computation sites and in
the evaluator loop.

### Compile fixtures, in-session A/B (JMH, 8 warmup, 5 measure)

| fixture | old kernel | new kernel | ratio |
|---|---|---|---|
| ForCompDeep25 | 1,849 ms | 1,577 ms | 0.85x |
| ForComprehensions | 735 ms | 630 ms | 0.86x |
| MapChainDeep100 | 20,032 ms | 7,791 ms | 0.39x |

The for-comp family flips from the fatal shape to the biggest winner
(mapLoop design measured 2,280 ms on ForCompDeep25; the rejected
Transform-outside 2,783 ms).

### Runtime guard rows, in-session A/B (-f 1 -wi 8 -i 5 -prof gc)

| row | old kernel | new kernel | time | alloc |
|---|---|---|---|---|
| uncachedValuesPayBoxingOnly | 74.2 us / 141,777 B | 51.7 us / 155,160 B | 0.70x | 1.09x |
| inlineLimitKeepsZeroAllocation | 2.17 us / 0 B (prior board) | 1.45 us / 0.010 B | 0.67x | zero holds |
| inlineLimitCostsTimeNotAllocation | 344.3 us / 724,386 B | 266.8 us / 735,642 B | 0.78x | 1.02x |
| suspensionBaseline | 129.8 us / 560,089 B | 81.9 us / 640,121 B | 0.63x | 1.14x |
| suspensionFusesContinuation | 78.8 us / 240,041 B | 25.3 us / 240,080 B | 0.32x | 1.00x |
| fusionPastBudgetPaysRescuesOnly | 48.2 us / 1,128 B | 47.9 us / 448 B | 0.99x | 0.40x |
| deepRecursionPaysRescuesOnly | 54.9 us / 2,128 B | 57.7 us / 912 B | 1.05x | 0.43x |
| fusionAfterSuspension | 85.8 us / 408,431 B | 157.5 us / 472,505 B | 1.84x | 1.16x |
| fusionAllocatesNothing | zero row | 0.588 us / 0.004 B | | holds |

Both candidate violations closed: uncachedValuesPayBoxingOnly allocation
back from 331K to 155K B/op (1.09x old, the pre-candidate status quo) and
inlineLimitKeepsZeroAllocation back from 4,968 to ~0 B/op.

Open items after this board:

1. deepRecursionPaysRescuesOnly 1.05x time (57.7 vs 54.9 us, error bars
   disjoint) against a 0.43x allocation win on the same row. The rescue
   family's time moved with the candidate design (fusionPastBudget 32.8
   to 47.9 us vs our own mapLoop-era board while staying at old-kernel
   parity), so the rescue/resume path is the next optimization target.
2. fusionAfterSuspension 1.84x time / 1.16x alloc: the standing
   two-objects-vs-one map-on-suspension structural fork, unchanged by
   this work, still awaiting a ruling.

## The fused enter() regression: found by the final sweep, reverted (fcd19e40fc)

The end-of-campaign sweep flagged userTypesSkipKernelWrapping at 1.21x
the old kernel (53.1 vs 43.8 us/op), the only unexplained loss on the
board. A two-stage bisect over throwaway detached worktrees attributed
it:

| point | userTypes | uncached |
|---|---|---|
| 61f80d45d3 (candidate design) | 44.1 | 50.2 |
| 4b2eab8441 (local-run map) | 33.8 | 37.5 |
| 9e9f725727 (flatMap mirror) | 34.0 | 37.6 |
| 1982c8c4cc (fused enter()) | 52.5 | 51.8 |
| session tip pre-fix | 53.1 | 51.5 |

The local-run map shape was a large win on the eager reference path, and
the fused `Safepoint.enter()` in the very next commit wiped it. The
original validation missed this because it bundled both changes and
compared the pair against the candidate: the net looked flat while
hiding a -13 us gain and a +14 us loss on the same rows.

Attribution experiments on the tip: restoring the five Pending sites to
the two-step get-then-enter measured 33.8/38.0; an inline-def variant
keeping the single-call API (splicing get plus enter at each site)
measured 37.6/38.8. So the un-inlined fused method carried most of the
cost, and the sentinel merge kept an 11 percent residual even when
spliced. The two-step shape returned in fcd19e40fc and the sentinel
machinery (Denied, Slot.entered, the zero-arg enter) was deleted. The
nested-defer pins from 1982c8c4cc stay.

### Final board, old vs new kernel (post-fix tip, -f 1 -wi 8 -i 5 -prof gc)

The fix lifted six rows, not two: everything that pays budget entry on
the eager path.

| row | old kernel | new kernel | time | alloc |
|---|---|---|---|---|
| trailingMapsStayLinear | 642,517 us / 1.60 GB | 354.8 us / 2,161,402 B | 0.0006x | 0.0013x |
| suspensionFusesContinuation | 78.6 us / 240,041 B | 26.4 us / 240,088 B | 0.34x | 1.00x |
| uncachedValuesPayBoxingOnly | 74.2 us / 141,777 B | 37.6 us / 155,160 B | 0.51x | 1.09x |
| handleLoopAnswersInPlace | 137.2 us / 960,144 B | 80.0 us / 640,129 B | 0.58x | 0.67x |
| suspensionBaseline | 131.7 us / 560,089 B | 81.8 us / 640,129 B | 0.62x | 1.14x |
| inlineLimitCostsTimeNotAllocation | 347.7 us / 724,338 B | 228.2 us / 735,642 B | 0.66x | 1.02x |
| idleHandlerAddsNothing | 48.5 us / 1,224 B | 33.0 us / 496 B | 0.68x | 0.41x |
| fusionPastBudgetPaysRescuesOnly | 48.9 us / 1,128 B | 33.6 us / 448 B | 0.69x | 0.40x |
| fusionAllocatesNothing | 0.830 us | 0.580 us | 0.70x | zero holds |
| inlineLimitKeepsZeroAllocation | 2.19 us | 1.64 us | 0.75x | zero holds |
| userTypesSkipKernelWrapping | 43.8 us / 177,056 B | 34.0 us / 176,632 B | 0.78x | 1.00x |
| statefulAnswersPaySuccessor | 144.4 us / 1,040,148 B | 118.9 us / 1,118,153 B | 0.82x | 1.08x |
| evalFixedOverhead | 0.009 us | 0.002 us | 0.22x | zero |
| deepRecursionPaysRescuesOnly | 54.3 us / 2,128 B | 53.9 us / 912 B | 0.99x | 0.43x |
| foreignCrossingsPayRotation | 320.7 us / 1,680,202 B | 345.7 us / 1,520,266 B | 1.08x | 0.90x |
| sharedHandlerPaysDispatch | 135.2 us / 240,415 B | 147.3 us / 240,449 B | 1.09x | 1.00x |
| continuationBodiesFuse | 24.7 us / 56,072 B | 27.1 us / 64,128 B | 1.10x | 1.14x |
| fusionAfterSuspension | 87.0 us / 408,431 B | 155.8 us / 472,513 B | 1.79x | 1.16x |
| fusionAfterSuspensionRunOnly | 0.270 us | 0.521 us | 1.93x | 48 B |
| partialSuspensionBaseline | no old row | 81.4 us / 640,129 B | | |

Thirteen wins, one parity (deepRecursion, the mandate row), three modest
gaps under 1.10x (foreignCrossings trades 8 percent time for a 10
percent allocation win; sharedHandler is the megamorphic dispatch row,
already improved 4 percent by the Handlers encapsulation; the
continuation-body row pays one extra word per fused body), and the two
fusion-after-suspension rows carrying the standing structural fork
(map-on-suspension re-suspends rather than fusing), previously ruled
acceptable at the stop-optimization decision.

Note on the earlier "Runtime guard rows" table above: its new-kernel
numbers were measured with the fused enter() in place and read
uniformly worse than the shipped kernel; this final board supersedes it.

## The lift's final shape: lint as a given, emission as the macro (4648ce9868)

The rule the measurements forced: the lint may not re-expand, the emission
must. The lint is a macro-free given whose `NotGiven` parameter resolves
where the conversion is written and is baked, which waives an inline method
with an abstract type parameter (the root cause of the computation-as-data
rejections) and keeps it sound through the box. The emission is the one
macro, re-expanded per site, so a type instantiated later still gets its own
strategy: a bare cast where a value of the type can never be a computation
(Nothing, value types, String, final non-Boxed classes), the monomorphic
box bridge everywhere else.

Two intermediate designs were measured and rejected. A macro-free emission
(an erasedValue match in the lift body) passed the suite but lost the
per-type analysis and cost userTypesSkipKernelWrapping 10.4 percent (34.0 to
37.5, allocation byte-identical, so pure CPU; the bytecode pin showed final
classes going from a 2-byte cast to an 8-byte call doing a runtime Boxed
test). Making the evidence itself the lifter recovered the cast in principle
but inflated every lift site to 23 bytes through the virtual call.

### Runtime, 3 forks (-f 3 -wi 8 -i 5 -prof gc)

| row | baseline | final | note |
|---|---|---|---|
| userTypesSkipKernelWrapping | 34.0 | 33.70 +/- 0.20 | regression recovered |
| uncachedValuesPayBoxingOnly | 37.6 | 37.31 +/- 0.34 | flat |
| statefulAnswersPaySuccessor | 118.9 | 119.78 +/- 1.69 | bars overlap |
| inlineLimitCostsTimeNotAllocation | 228.2 | 226.71 +/- 2.59 | flat |
| inlineLimitKeepsZeroAllocation | 1.644 | 1.647 +/- 0.014 | flat |

Allocation is byte-identical on every row. statefulAnswersPaySuccessor read
+4 percent on a single fork earlier; across three forks the bars overlap the
baseline, so that reading was a JIT draw, not a regression.

### Compile fixtures with liftAnyVal deleted

The conversion was kept on the premise that a per-site implicit search
costs compile time. It does not, because the search resolves a macro-free
given: MapChainDeep100 8429.7 to 8077.5 ms, NestedMaps 691.5 to 635.8,
FlatMapChains 395.3 to 378.0, ForComprehensions 651.9 to 637.1,
ForCompDeep25 1471.4 to 1451.7, TagDerivation 118.1 to 114.8, with every
row that moved the other way inside its error bars. The emission's isValue
arm covers primitives, pinned at 2 bytes, so the conversion is deleted.
