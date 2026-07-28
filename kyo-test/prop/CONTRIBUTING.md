# Contributing to kyo-test-prop

This guide complements the root [CONTRIBUTING.md](../../CONTRIBUTING.md), which covers every global Kyo convention (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, cross-platform source placement, `AllowUnsafe`). Defer to the root guide for those; this file covers only what is specific to the property-testing module (`kyo-test/prop`, sbt project `kyo-test-prop`).

**The headline invariant:** every generator is a pure function of a splittable seed, and shrinking is integrated, not bolted on. A `Gen[A]` samples a rose `Tree[A]` (the value plus the full lazy tree of its shrink candidates) from a pure `Seed` and a size hint, so `map` / `flatMap` / `filter` propagate shrinking structurally through the `Tree` combinators instead of dropping it (`shared/src/main/scala/kyo/test/prop/Gen.scala:9-38`, `internal/Tree.scala:3-16`). Determinism follows from purity: a fixed `(seed, size)` pair always produces the same `Tree` (`PropertyTest.scala:137`). Keep both properties intact: a generator that reaches for a mutable RNG, or a combinator that discards the source tree's shrink structure, breaks the model.

Like [kyo-test-snapshot](../snapshot/CONTRIBUTING.md), this module is an inheritance-based DSL. A suite gains `forAll` by extending `PropertyTest[S]` (or the marker-free `PropertyTestBase[S]`), and the DSL methods are `protected`: "The DSL methods in this class use `protected` visibility because the framework contract is inheritance-based; this is a deliberate exception to the `No protected` convention (CONTRIBUTING P5)" (`PropertyTest.scala:37-38`). Keep a new DSL method `protected` on the base, never a package function.

## Architecture overview

Every public type lives in `shared/src/main/scala/kyo/test/prop/`; the shrinking primitives are internal, under `internal/`.

| Type | File | Purpose |
|------|------|---------|
| `Gen[A]` | `Gen.scala:26` | The generator: `sample(seed, size): Tree[A]`, plus `map`/`flatMap`/`filter` and the companion's built-ins and combinators |
| `PropertyTestBase[S]` | `PropertyTest.scala:51` | Marker-free base carrying the `forAll` DSL; extended by non-discoverable internal fixtures |
| `PropertyTest[S]` | `PropertyTest.scala:320` | Public discoverable base: `PropertyTestBase[S] with SuiteFingerprintMarker` |
| `Shrink` | `Shrink.scala:17` | Primitive shrink algorithms exposed for custom generators (`int`/`long`/`double`/`string`/`list`) |
| `PropertyFailedException` | `PropertyFailedException.scala:30` | Carries the original and the shrunk counterexample plus the run seed for replay |
| `Seed` | `internal/Seed.scala:15` | Pure splittable SplitMix64 seed (opaque over `Long`) |
| `Tree[A]` | `internal/Tree.scala:16` | Rose tree of a value and its lazy shrink candidates; the integrated-shrinking core |
| `GenDerive` | `internal/GenDerive.scala:16` | Macro deriving `Gen[A]` from a `scala.deriving.Mirror` |

`PropertyTest[S]` adds only the discovery marker; all `forAll` machinery lives on `PropertyTestBase[S]` (`PropertyTest.scala:309-311`). The base is split off precisely so the deliberately-failing fixture suites in this module's own tests can extend it and stay out of test discovery on every platform, including Scala Native's reflective discovery, which "matches the marker on the actual class hierarchy via `@EnableReflectiveInstantiation`" and previously surfaced a `PropertyTest[Any]` fixture as a standalone suite (`PropertyTest.scala:19-27`).

## Purity and the SplitMix64 seed

`Seed` is an `opaque type Seed = Long` implementing SplitMix64 (`internal/Seed.scala:15`). Two operations carry the contract: `next` advances the stream deterministically, and `split` derives two statistically independent streams so an outer and an inner generator never share draws (`internal/Seed.scala:42-56`).

The purity is not a stylistic choice, it is required by integrated shrinking: "`flatMap` expansion re-runs the bind function and re-samples the inner generator while the lazy tree is traversed, so a mutable RNG (whose state would be consumed unpredictably during traversal) cannot be used. A pure splittable seed solves this" (`internal/Seed.scala:5-8`). `kyo.Random` exposes only the stateful `Sync` effect and no pure splittable PRNG, so SplitMix64 lives here as the right pure primitive; the top-level seed is drawn once at the boundary from the configured run seed and threaded purely through sampling (`internal/Seed.scala:10-11`). The property RNG is therefore unrelated to `kyo.Random`; a scenario that needs `kyo.Random` discharges it through the suite's effect row `S` via `.handle(Random.withSeed(...))`, a scenario concern the framework has zero awareness of (`PropertyTest.scala:137-139`).

Threading rule for a new combinator: to draw two independent values, `split` the seed (never reuse one stream for both); to draw a sequence, thread the advanced seed forward. The built-ins are the reference: `flatMap` splits into outer/inner streams (`Gen.scala:56-60`), `samples` splits per draw so successive draws are independent (`Gen.scala:120-133`), and the derivation macro splits per field and per subtype (`internal/GenDerive.scala:72-80`, `:118-137`).

## Integrated shrinking

`Tree[A]` is a rose tree carrying a value together with a lazily-computed sequence of strictly-smaller subtrees, "the core of integrated (Hedgehog-style) shrinking: a generated value and the full lazy tree of its shrink candidates travel together" (`internal/Tree.scala:3-16`). The combinators preserve the shrink structure: `map` transforms values while keeping the tree shape, `flatMap` is the monadic rose-tree bind, `filter` prunes candidates while splicing in satisfying descendants so no valid shrink path is lost, and `zipWith` is the applicative product that interleaves two subtrees so BOTH components minimize independently (`internal/Tree.scala:21-59`).

The consequence a contributor must respect: applicative composition minimizes better than monadic. `flatMap` shrinking is NOT guaranteed minimal, because the inner generator is re-sampled per outer shrink and the dependency blocks component-wise minimization; when the components are independent, prefer `zipWith` / `zip`, whose applicative shrinking minimizes each component independently, and reach for `flatMap` only when the inner generator genuinely depends on the outer value (`Gen.scala:51-54`).

`Shrink` exposes the canonical primitive shrink algorithms as static methods (`int`/`long`/`double`/`string`/`list`), used by the built-in `Gen` instances and available directly for custom generators (`Shrink.scala:5-46`). Each documents its exact strategy (halving toward zero for `int`/`long`, toward `0.0` and integral values for `double`, character removal for `string`, drop-then-shrink-elements for `list`); a new primitive generator's shrink belongs here so it is reusable and covered by `ShrinkTest`.

## Derivation: Mirror-based, with boundaries

`Gen.derive[A]` is an inline macro delegating to `GenDerive.deriveImpl` (`Gen.scala:460`, `internal/GenDerive.scala:16-40`). It derives from a `scala.deriving.Mirror`:

- **Case classes** (`Mirror.ProductOf[A]`): summon `Gen[FieldType]` for each field (recursively deriving when no given is in scope), sample each field tree from an independent seed split, then assemble via `Mirror.fromProduct` and zip the field trees into a product tree (`internal/GenDerive.scala:44-90`).
- **Sealed traits** (`Mirror.SumOf[A]`): pick a subtype uniformly at random and delegate to that subtype's `Gen`; earlier (lower-index) subtypes are prepended as additional shrink candidates so the shrink walk tends toward the simplest, index-0 subtype (`internal/GenDerive.scala:94-141`).

Boundaries, all enforced at macro time:

- Only case classes and sealed traits are supported. A type with no `Mirror` aborts with "no Mirror found for .... Only case classes and sealed traits are supported" (`internal/GenDerive.scala:35-38`); an unsupported `Mirror` shape aborts too (`:31-34`). Function-typed generators (e.g. `Gen[Int => Int]`) are not supported (`Gen.scala:15`).
- Recursion is size-bounded: the size parameter is decremented by 1 at each recursive level, and at `size == 0` a Sum mirror always chooses the index-0 subtype to produce a base case and avoid infinite recursion on recursive ADTs (`internal/GenDerive.scala:13-15`, `:117-123`).

### The primitive-givens block convention

For `Gen.derive[A]` to resolve a field, a `Gen[FieldType]` must be summonable. The companion therefore carries a givens block for the built-in field types, each with a rationale comment naming why it exists:

```scala
// Given instances for the primitive built-ins, so `Gen.derive[A]` can resolve a field of these types ...
given Gen[Int]     = int
given Gen[Long]    = long
given Gen[Double]  = double
given Gen[String]  = string
given Gen[Boolean] = boolean
```

(`Gen.scala:269-276`.) The same convention extends to commonly-derived container fields: `optionGen` ("So `Gen.derive[A]` resolves a field of type `Option[B]`": draws `None` 1:4 against `Some`, covering both branches) and `chunkGen` ("So `Gen.derive[A]` resolves a field of type `Chunk[B]`") are givens carrying the identical comment form (`Gen.scala:278-283`). `byteGen` extends the primitive set itself: `Gen[Byte]` narrows `Gen.int` via `toByte` so `Gen.derive[A]` resolves a `Byte` field (and, through `chunkGen`, a `Chunk[Byte]` field) without a hand-supplied given (`Gen.scala:285-288`). When you add a generator for a type that commonly appears as a case-class field, add it here as a `given` with the `so Gen.derive can resolve` comment, so derivation keeps working without a user hand-writing the instance.

## The `forAll` DSL

`forAll` (arities 1 to 4) and `forAllSeeded` are `protected inline` methods on `PropertyTestBase` (`PropertyTest.scala:177-301`). A call runs `numSamples` iterations (default 100) over a size schedule that grows from 1 to 100 as `size = 1 + i * 100 / numSamples` (`PropertyTest.scala:54`, `:148-152`); on the first failure the shrink loop minimizes the counterexample, up to `maxShrinks` iterations (default 100), before reporting (`PropertyTest.scala:57`, `:159-161`).

The run seed is `rngSeed`: `randomSeed` (inherited from `TestBase`) when `randomize` is true, else `nonRandomSeed` (default 42L, a `protected def` override hook) (`PropertyTest.scala:59-64`). On failure, `PropertyFailedException` carries the original sample, the shrunk counterexample, the cause, and the seed; copy that seed into `forAllSeeded(seed, gen) { ... }` to replay a failure deterministically (`PropertyFailedException.scala:16-18`, `:30-44`). The leaf body registered by `forAll` has type `Unit < (S & Async & Abort[Throwable] & Scope)`, matching the `TestBase[S]` baseline; a thrown `AssertionFailed` is caught at the `Abort.run[Throwable]` boundary and surfaced as a `Result.Failure` (`PropertyTest.scala:33-34`, `:68-83`).

## The samples API (consumed by kyo-test-snapshot)

`Gen` exposes two pure introspection helpers that run a generator WITHOUT a property test:

- `samples(seed: Long, size: Int, count: Int): Chunk[A]` draws `count` root values, each from an independently split seed; "the same `(seed, size, count)` triple always produces the same `Chunk`", and `count <= 0` returns `Chunk.empty` (`Gen.scala:105-134`).
- `classify(seed, size, count)(label): Map[String, Int]` tallies draws into labelled buckets whose counts sum to `count` (`Gen.scala:136-156`).

Both take a plain `Long` seed; the internal `Seed` type never appears in a public signature. `samples` is the contract the `kyo-test-snapshot` golden flavor depends on (`assertGoldenSnapshot` calls `gen.samples(seed, size, count)` to draw its deterministic spread), which is why this module carries a `dependsOn(kyo-test-prop)` build edge from snapshot (`build.sbt:2896`). Preserve `samples`'s determinism and its seed-split topology: the golden's cross-platform byte-identity rests on it.

## Cross-platform

This is a four-platform module (JVM, JS, Native, Wasm), a `crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)` with `CrossType.Full` (`build.sbt:2834-2836`). All source is cross-platform under `shared/`; only the test `TestExecutionContext` is platform-split. The module depends on `kyo-test-api` and `kyo-data`, with `kyo-test-runner` and ScalaTest as `Test`-only dependencies (`build.sbt:2837-2844`).

## Test conventions

### The self-hosting boundary: ScalaTest, not kyo-test

This module's own tests use ScalaTest directly (`AsyncFreeSpec` / `AnyFunSuite` with `NonImplicitAssertions`), never the `PropertyTest` DSL under test. The reason is stated in each test file: "kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here" (`shared/src/test/scala/kyo/test/prop/GenTest.scala:11`) and "this file tests PropertyTest DSL itself; cannot self-host using the framework-under-test" (`PropertyTestSelfTest.scala:65-68`). This is the deliberate local exception to the root rule that tests use the module's own `Test` base.

The recurring pattern for exercising the DSL is a fixture suite extending the marker-free `PropertyTestBase[Any]` (not `PropertyTest[Any]`), driven through the real runner via `TestRunner.runToFuture`. Fixtures that fail by design MUST extend the marker-free base so platform discovery does not pick them up (`PropertyTestSelfTest.scala:18-49`, `PropTest.scala:27-56`); this matters on Scala Native, whose reflective discovery would otherwise surface them as standalone suites (`PropertyTest.scala:24-27`).

### Test file naming

The 1:1 source-to-test rule holds, with aspect splits where one source needs more than one file:

| Source | Test file(s) |
|--------|--------------|
| `Gen.scala` | `GenTest.scala`, `GenEdgeBiasTest.scala`, `GenFilterBudgetTest.scala`, `GenIntrospectionTest.scala`, `GenSeedIndependenceTest.scala`, `GenZipTest.scala`, `GenChoiceShrinkTest.scala`, `GenShrinkChunkTest.scala`, `IntegratedShrinkTest.scala` |
| `PropertyTest.scala` | `PropTest.scala`, `PropertyTestSelfTest.scala`, `PropertyMaybeTest.scala`, `ForAllSeededTest.scala` |
| `Shrink.scala` | `ShrinkTest.scala` |
| `internal/Tree.scala` | `internal/TreeZipWithTest.scala` |

Scratch and reproduction files must be folded into the matching `*Test.scala` by source prefix and deleted before a change is complete.

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-test-propJVM/test'

# A single test class
sbt 'kyo-test-propJVM/testOnly kyo.test.prop.GenTest'
```

Tests run cross-platform (JVM, JS, Native, Wasm) from the shared test tree; never move a shared test into a single-platform tree to dodge platform cost. Building runs scalafmt automatically, so re-read any file you edit after building.

See the root [CONTRIBUTING.md](../../CONTRIBUTING.md) for full conventions on naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

## Decision checklist: before adding a new X

1. **New generator or combinator.** Is it a pure function of the `Seed`? Does it `split` for independent draws and thread the advanced seed for sequences? Does it preserve the source `Tree`'s shrink structure (via the `Tree` combinators), rather than dropping shrinking?

2. **New primitive shrink.** Is the algorithm added to `Shrink` (and delegated to from the built-in `Gen`), so it is reusable and covered by `ShrinkTest`?

3. **New commonly-derived field type.** Is its `Gen` added as a `given` in the companion's givens block with the `so Gen.derive can resolve` rationale comment, so derivation resolves the field without a hand-written instance?

4. **New DSL method.** Is it `protected inline` on `PropertyTestBase`, not a package function? Does its leaf body stay `Unit < (S & Async & Abort[Throwable] & Scope)`?

5. **New test.** Does it use ScalaTest directly (the self-hosting boundary), fold into the matching `*Test.scala` by source prefix, and assert on a concrete value? For a fail-by-design or runner-driven fixture, does it extend the marker-free `PropertyTestBase`?
