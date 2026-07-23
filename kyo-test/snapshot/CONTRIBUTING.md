# Contributing to kyo-test-snapshot

This guide complements the root [CONTRIBUTING.md](../../CONTRIBUTING.md), which covers every global Kyo convention (naming, `Maybe` / `Result` / `Chunk` / `Span`, `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, file organisation, visibility tiers, the test framework, cross-platform source placement, `AllowUnsafe`). Defer to the root guide for those; this file covers only what is specific to the snapshot module (`kyo-test/snapshot`, sbt project `kyo-test-snapshot`).

**The headline invariant:** snapshot assertions are an inheritance-based DSL, not free functions. A suite gains `assertSnapshot`, `assertSchemaSnapshot`, and `assertGoldenSnapshot` by extending `SnapshotTest[S]` (or the marker-free `SnapshotTestBase[S]`), and those DSL methods are `protected`. This is the module's single, documented exception to the root guide's no-`protected` convention: the framework contract is inheritance-based, so the methods are visible to subclass bodies and to nothing else. Both classes carry this exception verbatim in their scaladoc, "The DSL methods in this class use `protected` visibility because the framework contract is inheritance-based; this is a deliberate exception to the `No protected` convention (CONTRIBUTING P5)" (`shared/src/main/scala/kyo/test/snapshot/SnapshotTest.scala:35-36`, `:357-358`). Before adding a new assertion, keep it `protected` on the base and do not promote it to a package-level function.

## Architecture overview

Every public type lives in `shared/src/main/scala/kyo/test/snapshot/`; the file I/O leaf is split by platform under `jvm-native/` and `js-wasm/`.

| Type | File | Purpose |
|------|------|---------|
| `SnapshotTestBase[S]` | `SnapshotTest.scala:51` | Marker-free base carrying the DSL; extended by non-discoverable internal fixtures |
| `SnapshotTest[S]` | `SnapshotTest.scala:373` | Public discoverable base: `SnapshotTestBase[S] with SuiteFingerprintMarker` |
| `SnapshotConfig[A]` | `SnapshotConfig.scala:31` | The per-call customization builder for `assertSchemaSnapshot` |
| `GoldenConfig[A]` | `GoldenConfig.scala:27` | The per-call customization builder for `assertGoldenSnapshot` |
| `GoldenSamples[A]` | `GoldenSamples.scala:9` | Internal storage envelope wrapping a golden's normalized sample spread as one document |
| `SnapshotCodec` | `SnapshotCodec.scala:24` | The `Text` / `Binary` format wrapper (2-case enum, 7 presets) |
| `SnapshotSchemaEvolution` | `SnapshotSchemaEvolution.scala:24` | Distinct failure factory for a stored snapshot that no longer decodes |
| `SnapshotNotFound` | `SnapshotNotFound.scala:26` | First-run failure factory (proposed file written, then fail) |
| `SnapshotStore` | `internal/SnapshotStore.scala:11` | Shared facade over the per-platform file I/O |
| `SnapshotDiff` | `internal/SnapshotDiff.scala:8` | Unified-diff renderer, delegates to `kyo.test.internal.Diff.stringDiff` |

`SnapshotTest[S]` adds only the discovery marker; all assertion machinery lives on `SnapshotTestBase[S]` (`SnapshotTest.scala:353-355`). The base is split off precisely so the deliberately-failing fixture suites in this module's own tests can extend it and stay out of sbt/Native discovery: "The public `SnapshotTest` subclass adds the marker so real user snapshot suites are discovered; the fixtures must not be, because they fail by design" (`SnapshotTest.scala:28-29`).

---

## The three snapshot flavors

The module offers three assertions with different serialization models. Choosing between them is the first decision a contributor or user makes.

### `assertSnapshot` (Render-based)

```
protected inline def assertSnapshot[A](inline actual: A, inline name: String)
    (using inline r: Render[A], inline frame: Frame, inline as: kyo.test.AssertScope)
    : Unit < (S & Async & Abort[Throwable] & Scope)
```

(`SnapshotTest.scala:84-87`.) It renders `actual` through `Render[A]` to a single flat string, stores it at `${snapshotDir}/${this.name}/${name}.snap`, and compares after trailing-whitespace normalization: "If snapshot exists and matches (after trailing-whitespace normalization): pass" (`SnapshotTest.scala:74`, impl `stored.stripTrailing() != rendered.stripTrailing()` at `:98`). Use it for any type that has a `Render` instance when a flat textual form is all you need.

### `assertSchemaSnapshot` (schema-based)

```
protected inline def assertSchemaSnapshot[A](inline actual: A, inline name: String,
    inline config: SnapshotConfig[A] => SnapshotConfig[A] = identity[SnapshotConfig[A]])
    (using inline schema: Schema[A], inline frame: Frame, inline as: kyo.test.AssertScope)
    : Unit < (S & Async & Abort[Throwable] & Scope)
```

(`SnapshotTest.scala:137-141`.) It requires a `Schema[A]` and serializes through a `SnapshotCodec`. This buys three things the Render path cannot give: a field-named encoding (Yaml/Json/etc.), format-tolerant structural comparison, and a per-call normalization pass. The default Yaml output is field-named, not a positional `toString`: the test "default Yaml output is field-named, not a positional flat toString" asserts the stored text contains `x:` and `y:` keys and is not `Point(1,2)` (`shared/src/test/scala/kyo/test/snapshot/SnapshotSchemaTest.scala:101-116`).

### `assertGoldenSnapshot` (golden over a generated spread)

`assertGoldenSnapshot[A](name, config)` requires both a `Gen[A]` and a `Schema[A]`. It draws `config.sampleCount` values (default 20) via `gen.samples(seed, size, count)`, normalizes each through the accumulated `Modify[A]`, and stores the whole spread as ONE document wrapped in the internal `private[snapshot] GoldenSamples[A](samples: Chunk[A])` envelope (its `Schema` obtained via `Schema.derived`), at `${snapshotDir}/${this.name}/${name}.golden.${bare}` where `bare = snapshotCodec.ext.stripPrefix("snap.")`. On a later run it decodes the stored document and compares per sample through the same three-way decode branch as `assertSchemaSnapshot` (`Result.Failure` routes to `SnapshotSchemaEvolution`, `Result.Panic` is rethrown unwrapped, `Result.Success` runs a structural `Changeset` compare), reporting a field change as the index-prefixed token `sample[N].field`; a decoded spread whose length differs from the regenerated spread fails first, naming the count delta. It shares `validateSnapshotName`, the first-run `SnapshotNotFound` write-then-fail, and the `snapshotUpdateMode` bless with the two siblings, and its per-call knobs ride the `GoldenConfig[A]` builder (`.sampleCount`, `.seed`, `.size`, `.normalize`), mirroring `SnapshotConfig`. Its inline signature is `protected inline def assertGoldenSnapshot[A](inline name: String, inline config: GoldenConfig[A] => GoldenConfig[A] = identity[GoldenConfig[A]])(using inline gen: Gen[A], inline schema: Schema[A], inline frame: Frame, inline as: kyo.test.AssertScope): Unit < (S & Async & Abort[Throwable] & Scope)` (`SnapshotTest.scala`). The `kyo-test-snapshot` module gains a `dependsOn(kyo-test-prop)` build edge to bring `Gen[A]` into scope.

**When to use which.** Reach for `assertSchemaSnapshot` when the value has a `Schema`, when you need to scrub non-deterministic fields (timestamps, ids), or when you want the comparison to survive a hand-reformatted snapshot file. Reach for `assertGoldenSnapshot` when a single hand-picked value is not enough and you want to detect a wire-format change across the whole range of values a type can hold, drawn deterministically from its `Gen`. Reach for `assertSnapshot` when the value only has a `Render` instance, or when a flat rendered string is the artifact you actually want to review.

All three share the name-validation and first-run contracts exactly. `SnapshotSchemaTest` verifies that "`assertSnapshot` rejects a path separator, empty, dot, dot-dot, and an embedded space, same as `assertSchemaSnapshot`" (`SnapshotSchemaTest.scala:303-313`) and that both fail first-run with `SnapshotNotFound` (`:249-269`, `:293-301`); `assertGoldenSnapshot` shares both by construction, calling the same `validateSnapshotName` (`SnapshotTest.scala:260`) and the same first-run `SnapshotNotFound` write-then-fail path (`SnapshotTest.scala:224`, `:246`).

---

## Customization surfaces: `SnapshotConfig`, `GoldenConfig`, and `snapshotCodec`

These are complementary hooks at different scopes. Do not conflate them: `SnapshotConfig` and `GoldenConfig` are per-call builders (one per schema-backed flavor), `snapshotCodec` is the per-suite format.

### `snapshotCodec` (per-suite format)

```
protected def snapshotCodec: SnapshotCodec = SnapshotCodec.Yaml
```

(`SnapshotTest.scala:114`.) Override it in a suite to change the serialization format for every `assertSchemaSnapshot` call in that suite. The default is `SnapshotCodec.Yaml`, "the readable field-named text format" (`SnapshotTest.scala:114`, `SnapshotCodec.scala:37`).

### `SnapshotConfig` (per-call customization)

`SnapshotConfig[A]` is "The single per-call customization point for `assertSchemaSnapshot`" (`SnapshotConfig.scala:5`). It is passed as a `SnapshotConfig[A] => SnapshotConfig[A]` lambda (default `identity`). It exists so the assertion can grow new knobs "without changing its signature or breaking existing call sites: each new knob is an additive method here, never a new parameter on `assertSchemaSnapshot`" (`SnapshotConfig.scala:8-9`).

Today it carries exactly one capability, `normalize`, which "Adds a field-normalization pass, scrubbing non-deterministic fields before encode and before compare" (`SnapshotConfig.scala:33`). Normalization accumulates: `.normalize(f1).normalize(f2)` composes `f1` then `f2` (`SnapshotConfig.scala:34-35`, proven by `SnapshotConfigTest.scala:23-27`). It builds on `kyo.Modify`, the field-transform DSL, so a typical scrub reads `_.normalize(_.set(_.ts)(0L))` (`SnapshotSchemaTest.scala:123`).

**Contract for a contributor adding a capability.** Add it as a new method plus a new internal field whose default preserves current behavior, so existing `.normalize(...)` call sites keep compiling (`SnapshotConfig.scala:15-17`). The example the scaladoc names is a custom comparison strategy. Never add a parameter to `assertSchemaSnapshot` for it; that is the whole reason `SnapshotConfig` exists. The format choice stays out of this builder: "The serialization format is chosen separately, per suite, by the `snapshotCodec` override hook, not through this builder" (`SnapshotConfig.scala:10-13`).

### `GoldenConfig` (per-call customization for the golden flavor)

`GoldenConfig[A]` is the golden twin of `SnapshotConfig`, "The per-call customization point for `assertGoldenSnapshot`" (`GoldenConfig.scala:5`), passed the same way as a `GoldenConfig[A] => GoldenConfig[A]` lambda (default `identity`). It is an immutable builder started from the companion `GoldenConfig[A]` (the empty config: 20 samples, seed 0, size 10, identity normalization; `GoldenConfig.scala:57-58`) and carries four knobs: `sampleCount` (how many values the spread covers, default 20), `seed` and `size` (threaded into `gen.samples`, defaults 0 and 10), and `normalize` (the accumulating `Modify[A]` scrub, composing `f1` then `f2`; `GoldenConfig.scala:34-52`). A `sampleCount` below 1 is rejected at the assertion boundary, naming the invalid count (`SnapshotTest.scala:288-289`, test `SnapshotGoldenTest.scala:316-325`); it is checked in `goldenSamples` before any draw, so no golden file is written when the guard throws (`SnapshotGoldenTest.scala:327-339`).

The same extensibility contract as `SnapshotConfig` binds: a new capability is an additive method plus an internal field whose default preserves current behavior, never a new parameter on `assertGoldenSnapshot` (`GoldenConfig.scala:8-9`). Format selection stays out of this builder; it is the per-suite `snapshotCodec` override (`GoldenConfig.scala:11-13`).

---

## The storage contract

### Path scheme

All three assertions compute `${snapshotDir}/${this.name}/${name}.${ext}`. `snapshotDir` defaults to `"test-snapshots"` and is a `protected def` override hook (`SnapshotTest.scala:53-57`); `this.name` is the suite name, so a suite's snapshots live in a subdirectory named after the suite. The Render path fixes the extension to `snap` (`SnapshotTest.scala:91`); the schema path uses `codec.ext` (`SnapshotTest.scala:146`); the golden path uses `golden.${bare}` where `bare = codec.ext.stripPrefix("snap.")` (`SnapshotTest.scala:296-297`, `:304`).

`name` must be a bare file name. `validateSnapshotName` rejects a path separator (`/` or `\`), the empty string, `.`, `..`, and any embedded space (`SnapshotTest.scala:260-278`). Add a new restriction there, not at each call site.

### The golden storage envelope and its determinism contract

A golden stores an entire spread as ONE document. The normalized `Chunk[A]` is wrapped in the internal `private[snapshot] case class GoldenSamples[A](samples: Chunk[A])` (`GoldenSamples.scala:9`), whose `Schema` is `Schema.derived` (`GoldenSamples.scala:11-12`). The envelope exists so the whole spread round-trips as a single `samples`-keyed document through both a text and a binary codec, not as a bare sequence: the stored Yaml begins with a top-level `samples:` mapping and holds every sample in one file (`SnapshotGoldenTest.scala:389-405`). The compare unwraps `storedDoc.samples` and pairs it positionally against the regenerated spread (`SnapshotTest.scala:308-309`, `compareSamples` at `:339-344`); a length delta fails first, naming both counts before any per-index compare (`compareGolden` at `:318-327`, test `:341-354`). Keep a golden a single enveloped document: do not switch it to a bare top-level sequence, which would lose the shared text/binary round-trip.

The spread is deterministic and platform-independent, and this is a contract the golden path must preserve. `gen.samples(seed, size, count)` is pure and reproducible: the same `(seed, size, count)` triple always draws the same `Chunk[A]` (`kyo-test/prop/shared/src/main/scala/kyo/test/prop/Gen.scala:105-134`), so a blessed golden regenerates identically on every run. The stored bytes are furthermore byte-identical across JVM, JS, Native, and Wasm: a pinned Yaml encoding of a fixed spread is asserted equal on all four platforms (`SnapshotGoldenTest.scala:407-412`, pinned literal `:456-467`). A change that makes the spread seed-dependent in a new way, or that lets one platform diverge in the stored bytes, breaks the golden contract and the pinned-determinism test is the guard.

### Text codecs versus binary codecs

`SnapshotCodec` wraps a kyo-schema `Codec` "with the text-versus-binary distinction the codec itself does not carry" (`SnapshotCodec.scala:5-6`). The KIND decides two things a raw `Codec` cannot express: whether the stored file holds a UTF-8 string or raw wire bytes, and whether a mismatch report can carry a textual diff (`SnapshotCodec.scala:8-11`).

- A `Text` codec encodes to a UTF-8 string via `schema.encodeString` and stores it through the `String` store path (`SnapshotTest.scala:217-219`). `SnapshotStore.write` appends a single trailing newline if absent (`internal/SnapshotStore.scala:21-23`).
- A `Binary` codec encodes to raw wire bytes via `schema.encode` and stores them through the bytes store path (`SnapshotTest.scala:239-241`). `writeBytes` writes "content exactly as given, with no base64 encoding and no trailing-newline append, so a stored binary snapshot is the real codec wire artifact" (`internal/SnapshotStore.scala:33-39`).

The Protobuf round-trip test pins this: stored bytes are byte-identical to a fresh `schema.encode`, and the stored length is asserted not equal to the base64-encoded length (`SnapshotSchemaTest.scala:271-291`). The raw-bytes store path has its own byte-fidelity coverage including `0x00`, `0xFF`, and a newline byte (`SnapshotStoreBytesTest.scala:25-39`).

### Extensions are pairwise-distinct and distinct from `snap`

The seven presets are `Yaml` (`snap.yaml`), `Json` (`snap.json`), `Ion` (`snap.ion`), `Protobuf` (`snap.pb`), `Bson` (`snap.bson`), `MsgPack` (`snap.msgpack`), `IonBinary` (`snap.ionb`) (`SnapshotCodec.scala:36-55`). The schema default is `snap.yaml`, deliberately distinct from the Render path's plain `snap`. Extensions are "kept pairwise-distinct across presets so a text and a binary snapshot of the same name never collide, and distinct from the plain `snap` extension of the `Render`-based `assertSnapshot` so a suite mixing both assertions on the same name never cross-writes one file" (`SnapshotCodec.scala:16-18`). `SnapshotCodecTest.scala:57-70` enforces all seven distinct and none equal to `snap`. When adding a preset, choose a fresh extension and add it to that distinctness assertion.

`Text` and `Binary` stay the open extension point for any custom `Codec` (`SnapshotCodec.scala:11`, `SnapshotCodecTest.scala:73-91`).

---

## The compare contract

On a subsequent run with an existing snapshot, the stored bytes are decoded via `Schema[D]` and branched three ways through the single shared private helper `storeAndCompare[D]` (`SnapshotTest.scala:210-258`; the decode arms are `:226-236` for Text, `:248-255` for Binary). This one helper owns the codec `Text`/`Binary` dispatch, the update-mode write, the first-run absent-write-then-`SnapshotNotFound` path, and the three-way decode branch for every flavor that stores through a `Schema[D]`: `assertSchemaSnapshot` routes its value through it as `storeAndCompare[A]` (`SnapshotTest.scala:147`), and `assertGoldenSnapshot` routes the whole spread through it as `storeAndCompare[GoldenSamples[A]]` (`SnapshotTest.scala:308`). There is one decode branch in the module, not one per assertion; a new schema-backed flavor reuses `storeAndCompare` rather than re-implementing the branch. The three branches:

1. **Decode failure (`Result.Failure`)** routes to `SnapshotSchemaEvolution`, "a stored snapshot whose bytes no longer decode via the current `Schema[A]`" (`SnapshotSchemaEvolution.scala:7`). This is kept distinct from a value mismatch so "schema drift is reported with its own message and never conflated with an ordinary value mismatch" (`SnapshotSchemaEvolution.scala:9-10`). Its diagram is prefixed `SnapshotSchemaEvolution:` and is never the `Snapshot mismatch` prefix (`SnapshotSchemaEvolutionTest.scala:12-20`; distinctness proven end-to-end for both Text and Binary at `SnapshotSchemaTest.scala:206-218`, `:315-329`).

2. **Decode panic (`Result.Panic`)** propagates the underlying throwable unwrapped: `case Result.Panic(err) => throw err` (`SnapshotTest.scala:232-233`, `:251-252`). An unexpected codec defect is not rewrapped as `SnapshotSchemaEvolution`. Both the Binary and Text decode arms are pinned by `SnapshotSchemaTest.scala:347-379`.

3. **Decode success** invokes the caller's `onCompare`, which compares structurally through `Changeset[A](storedValue, norm).operations`; a mismatch fails with the changed field paths (`SnapshotTest.scala:148-157`). The gate follows schema-structural `Changeset.operations`, not value `.equals`: the `Ver` fixture whose `.equals` ignores field `b` still fails the assertion on a `b` change (`SnapshotSchemaTest.scala:331-345`).

The comparison is **format-tolerant**: a hand-reformatted stored snapshot that still decodes to an equal value passes (`SnapshotSchemaTest.scala:186-204`), because the compare is on decoded values, not on the stored text. The mismatch report shape differs by codec kind: a `Text` codec carries the changed field path plus a unified textual diff, a `Binary` codec carries the changed field path but no textual diff (`SnapshotSchemaTest.scala:142-170`). Nested field changes report the full dotted path (`b.y`), produced by the recursive `snapshotChangedPaths` helper (`SnapshotTest.scala:280-284`, test `:172-184`).

`SnapshotDiff.render(stored, actual)` fixes the diff direction convention: `stored` is the on-disk baseline, `actual` is the newly rendered value (`internal/SnapshotDiff.scala:5-6`).

---

## The update-mode workflow

```
protected def snapshotUpdateMode: Boolean =
    java.lang.System.getenv("KYO_TEST_SNAPSHOT") == "update"
```

(`SnapshotTest.scala:64-65`.) When update mode is active, all three assertions write the proposed value and pass without reading a prior file (`SnapshotTest.scala:93-94` for the Render path; the schema and golden paths share the `storeAndCompare` Text and Binary write sites at `:218-219` and `:240-241`). The default reads the `KYO_TEST_SNAPSHOT` environment variable, but the hook is a plain `protected def` so a suite can force it on or off "without mutating the process environment" (`SnapshotTest.scala:59-63`). Tests exercise this by overriding it directly and by reading a JVM system property (`SnapshotUpdateModeTest.scala:38-60`, `:98-113`).

On the first run with no stored file, all three assertions write the proposed snapshot and then fail with `SnapshotNotFound` so the run is red until the reviewer confirms the proposed file (`SnapshotTest.scala:103-105` for the Render path; the schema and golden paths share the `storeAndCompare` Text and Binary absent-write sites at `:222-224` and `:244-246`; `SnapshotNotFound.scala:7-13`).

---

## Cross-platform

This is a four-platform module (JVM, JS, Native, Wasm; `build.sbt:2556-2558`). Source is placed in three tiers:

- `shared/src/main/scala/` holds all types except the file I/O leaf.
- `jvm-native/` holds the `java.nio.file`-backed `SnapshotStorePlatform`. "Scala Native ships a compatible implementation of `java.nio.file` so the same source compiles and runs on both platforms without modification" (`jvm-native/.../SnapshotStorePlatform.scala:9-13`); the source set is wired for both JVM and Native in `build.sbt:2578-2581` and `:2593-2596`.
- `js-wasm/` holds the Node.js `fs` facade `SnapshotStorePlatform`, shared by JS and Wasm (auto-wired by the custom `CrossType.Full`; see `project/WasmPlatform.scala:18`).

`SnapshotStore` (`internal/SnapshotStore.scala`) is the shared facade; each platform provides an `object SnapshotStorePlatform` with the same four-method surface (`read`, `write`, `readBytes`, `writeBytes`).

### Plain-sync platform I/O (no Sync/AllowUnsafe ceremony)

The store methods are plain synchronous functions returning `Maybe[String]` / `Maybe[Span[Byte]]` / `Unit`, not Kyo effects. Snapshot I/O runs on the synchronous assertion path, so it does not wrap file access in `Sync` or reach for `AllowUnsafe` at the store boundary. The only `// Unsafe:` comments in the module are the two `Path.getParent` null-guards in the JVM/Native writer (`jvm-native/.../SnapshotStorePlatform.scala:25`, `:46`), each annotated with the reason. Preserve this: a new store operation stays a plain function and is added to all three source sets in lockstep, mirroring the existing four-method shape. The JS/Wasm facade re-throws a browser-environment `js.JavaScriptException` as `UnsupportedOperationException` with a clear message (`js-wasm/.../SnapshotStorePlatform.scala:39-43`); keep that translation on any new JS store method.

### Foreign-package inline access

`assertSchemaSnapshot` is `inline`, and a user extends `SnapshotTestBase` from their own package. `SnapshotConfig.modify` is `private[snapshot]` (`SnapshotConfig.scala:31`), so the inline body must resolve `normalizeWith` (`SnapshotTest.scala:199-200`) through a compiler-generated accessor rather than a direct cross-package field read. `SnapshotForeignPackageTest` is the standing proof: a `SnapshotTest[Any]` subclass in the sibling package `kyo.test.snapshotforeign` "compiles and runs `assertSchemaSnapshot` with the config lambda" where `private[snapshot]` is genuinely inaccessible (`shared/src/test/scala/kyo/test/snapshotforeign/SnapshotForeignPackageTest.scala:15-22`, `:45-60`). Any change to the inline assertion body or to `SnapshotConfig`'s visibility must keep that test compiling; do not widen `private[snapshot]` to make the inline resolve.

---

## Conventions

### The `protected` exception

The root guide bans `protected` in favour of `private[kyo]`. This module is the documented exception for its abstract DSL base classes only (`SnapshotTest.scala:35-36`, `:357-358`). The exception covers the inheritance-facing DSL surface: `snapshotDir`, `snapshotUpdateMode`, `snapshotCodec`, `assertSnapshot`, `assertSchemaSnapshot`, `assertGoldenSnapshot`. Internal helpers stay `private` (`normalizeWith`, `storeAndCompare`, `validateSnapshotName`, `snapshotChangedPaths` at `SnapshotTest.scala:199-284`) and the platform I/O objects stay `private[snapshot]`. Do not extend the `protected` exception to anything that is not a subclass override hook.

### Kyo types

Follow the root guide. In this module: `Maybe` for optional store results (`internal/SnapshotStore.scala:14`, `:30`), `Result` for decode outcomes (`SnapshotTest.scala:226-235`), `Span[Byte]` for raw bytes (`internal/SnapshotStore.scala:30`), `Chunk` for changed-path accumulation (`SnapshotTest.scala:280`).

### Test framework

Because this module IS a test framework, its own tests cannot self-host: they use ScalaTest directly. Each test file states the reason, for example "ScalaTest bootstrap: this file tests SnapshotTest DSL itself; cannot self-host using the framework-under-test" (`jvm-native/.../SnapshotSelfTest.scala:88`). This is the deliberate local exception to the root rule that tests use the module's own `Test` base. Two ScalaTest styles are in use: `AnyFunSuite` for synchronous value/IO checks and `AsyncFreeSpec` for tests that drive the runner.

The recurring pattern for exercising the DSL is a minimal `private class ... extends SnapshotTest[Any]` fixture that overrides `snapshotDir`/`snapshotUpdateMode`/`snapshotCodec` via constructor parameters and re-exposes the `protected` assertions as package-accessible methods (`SnapshotSchemaTest.scala:82-97`, `SnapshotUpdateModeTest.scala:38-47`, `SnapshotSelfTest.scala:21-28`). Fixtures that must fail by design extend the marker-free `SnapshotTestBase` so platform discovery does not pick them up (`SnapshotReparamTest.scala:25-28`, `:40-43`), and drive them via `TestRunner.runToFuture` (`SnapshotReparamTest.scala:100-117`).

### Test file naming

The 1:1 source-to-test rule holds, with aspect splits where one source needs more than one file:

| Source | Test file(s) |
|--------|--------------|
| `SnapshotTest.scala` | `SnapshotTestTest.scala`, `SnapshotSchemaTest.scala`, `SnapshotUpdateModeTest.scala`, `SnapshotReparamTest.scala`, `SnapshotSelfTest.scala`, `SnapshotGoldenTest.scala` |
| `SnapshotConfig.scala` | `SnapshotConfigTest.scala` |
| `GoldenConfig.scala` | `GoldenConfigTest.scala` |
| `SnapshotCodec.scala` | `SnapshotCodecTest.scala` |
| `SnapshotSchemaEvolution.scala` | `SnapshotSchemaEvolutionTest.scala` |
| `internal/SnapshotStore.scala` | `SnapshotStoreBytesTest.scala`, `SnapshotStoreTest` (in `SnapshotSelfTest.scala`) |
| `internal/SnapshotDiff.scala` | `SnapshotDiffTest.scala` |

`SnapshotForeignPackageTest.scala` is a deliberate cross-package proof and lives under `kyo.test.snapshotforeign`, a sibling of `kyo.test.snapshot`; it shares no source-file prefix because its subject is package accessibility itself, not a single source. Scratch and reproduction files must be folded into the matching `*Test.scala` and deleted before a change is complete.

---

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-test-snapshotJVM/test'

# A single test class
sbt 'kyo-test-snapshotJVM/testOnly kyo.test.snapshot.SnapshotSchemaTest'
```

Tests run cross-platform (JVM, JS, Native, Wasm) from the shared and partially-shared test trees; never move a shared test into a single-platform tree to dodge platform cost. Building runs scalafmt automatically, so re-read any file you edit after building.

See the root [CONTRIBUTING.md](../../CONTRIBUTING.md) for full conventions on naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

---

## Decision checklist: before adding a new X

1. **New assertion.** Is it `protected` on `SnapshotTestBase`, not a package function? Does it validate `name` through `validateSnapshotName`? Does it honour `snapshotUpdateMode` (write-and-pass) and first-run (`SnapshotNotFound`)? Does its effect row stay `S & Async & Abort[Throwable] & Scope`?

2. **New `SnapshotConfig` or `GoldenConfig` capability.** Is it an additive method plus an internal field whose default preserves current behavior, leaving `assertSchemaSnapshot`'s / `assertGoldenSnapshot`'s signature untouched? Does it stay out of format selection (that is `snapshotCodec`)?

3. **New codec preset.** Is it `Text` or `Binary`? Does its extension collide with no existing preset and differ from plain `snap`? Is it added to the distinctness assertion in `SnapshotCodecTest`?

4. **New store operation.** Is it a plain synchronous function added to all three source sets (`shared` facade, `jvm-native`, `js-wasm`) in lockstep? Does the binary path stay verbatim (no base64, no newline munge)? Is every `AllowUnsafe`/null-guard marked with `// Unsafe:`?

5. **Change to the inline body or `private[snapshot]` visibility.** Does `SnapshotForeignPackageTest` still compile from the foreign package?

6. **New test.** Does it fold into the matching `*Test.scala` by source prefix? Does it assert on a concrete value? For a fail-by-design fixture, does it extend the marker-free `SnapshotTestBase`?
