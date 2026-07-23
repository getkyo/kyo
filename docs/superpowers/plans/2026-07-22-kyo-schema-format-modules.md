# kyo-schema Format-Module Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split `kyo-schema` into a format-agnostic core plus six per-format modules (`kyo-schema-json/-yaml/-bson/-ion/-msgpack/-protobuf`) and one unpublished cross-format test module (`kyo-schema-tests`), per `docs/superpowers/specs/2026-07-22-kyo-schema-format-modules-design.md`.

**Architecture:** Each format is already an independent `Codec` subclass discovered via `given`; extraction is mostly `git mv` plus sbt wiring. Two core refactors unblock it: hoisting shared decode-limit constants from `Json` into `Codec`, and replacing `ProtobufWriter/Reader` pattern-matches in core with an open `withFieldIdOverrides` hook on `Codec.Writer`/`Codec.Reader`. Every task leaves the build green and ends in a commit.

**Tech Stack:** Scala 3, sbt crossProject (JVM/JS/Native/Wasm via in-repo `WasmPlatform`), kyo-test, in-repo `KyoDoctestPlugin` (validates README code blocks).

**Conventions used below:**
- All paths are relative to the repo root.
- `SHARED` = `kyo-schema/shared/src/main/scala/kyo`, `STEST` = `kyo-schema/shared/src/test/scala/kyo`.
- sbt project ids follow the crossProject naming already used by this build (aggregates reference `` `kyo-schema`.jvm `` etc.). From the sbt shell, JVM projects are addressed as `kyo-schemaJVM`. Run `sbt projects` once if unsure.
- Line numbers were taken at commit `bd1e488bb`; if they've drifted, locate by the quoted code instead.
- Run sbt from the repo root. On this Windows machine prefer one long-lived `sbt` shell session over repeated cold `sbt <task>` invocations (JVM startup is expensive).

---

### Task 1: Hoist decode-limit constants into core `Codec`

Every non-Json format defaults its decode limits to `Json.DefaultMaxDepth` / `Json.DefaultMaxCollectionSize`. Move the constants to `Codec` so formats no longer reference `Json`.

**Files:**
- Modify: `kyo-schema/shared/src/main/scala/kyo/Codec.scala`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Json.scala:21-25`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Yaml.scala:37-40`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Bson.scala:45-47`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Ion.scala:59-62`
- Modify: `kyo-schema/shared/src/main/scala/kyo/MsgPack.scala:121-133`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Protobuf.scala:71-72`

- [ ] **Step 1: Add the constants to `object Codec`**

In `Codec.scala`, inside `object Codec` (the companion that already holds `abstract class Reader` at ~line 38), add near the top of the object body:

```scala
    /** Default maximum nesting depth for decoding (DoS limit), shared by every built-in codec. */
    inline val DefaultMaxDepth = 512

    /** Default maximum number of entries in any single collection or object during decoding (DoS limit), shared by every built-in codec. */
    inline val DefaultMaxCollectionSize = 100000
```

- [ ] **Step 2: Re-point the formats at the `Codec` constants**

In `Json.scala` replace the two literal definitions (lines 21-25) with aliases:

```scala
    /** Default maximum nesting depth for objects/arrays in JSON decoding (DoS limit). */
    inline val DefaultMaxDepth = Codec.DefaultMaxDepth

    /** Default maximum number of entries in any single collection or object in JSON decoding (DoS limit). */
    inline val DefaultMaxCollectionSize = Codec.DefaultMaxCollectionSize
```

In `Yaml.scala`, `Bson.scala`, `Ion.scala` replace their `= Json.DefaultMaxDepth` / `= Json.DefaultMaxCollectionSize` right-hand sides with `= Codec.DefaultMaxDepth` / `= Codec.DefaultMaxCollectionSize` (these files define their own aliases; keep the aliases, change the source).

In `MsgPack.scala` (4 sites: lines 121-122, 132-133) and `Protobuf.scala` (lines 71-72) the constants appear as parameter defaults `maxDepth: Int = Json.DefaultMaxDepth` — change each `Json.` to `Codec.`.

- [ ] **Step 3: Verify no format still references `Json` constants**

```bash
grep -rn "Json.DefaultMax" kyo-schema/shared/src/main/scala/kyo --include="*.scala" | grep -v "kyo/Json.scala"
```
Expected: no output.

- [ ] **Step 4: Compile and run the schema tests**

```
sbt kyo-schemaJVM/test
```
Expected: PASS (same suite count as before the change).

- [ ] **Step 5: Commit**

```bash
git add -A kyo-schema && git commit -m "[kyo-schema] hoist shared decode-limit defaults from Json into Codec"
```

---

### Task 2: Format-agnostic field-id override hook on `Codec.Writer`/`Codec.Reader`

Core (`SchemaSerializer`) pattern-matches on `ProtobufWriter`/`ProtobufReader` to thread field-id overrides. Replace with open methods on the core Writer/Reader API.

**Files:**
- Modify: `kyo-schema/shared/src/main/scala/kyo/Codec.scala` (Reader ~line 38, Writer ~line 202)
- Modify: `kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:79-107, 138-143, 594-618, 649-654`
- Modify: `kyo-schema/shared/src/main/scala/kyo/internal/ProtobufWriter.scala:60-82`
- Modify: `kyo-schema/shared/src/main/scala/kyo/internal/ProtobufReader.scala:63-74`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Protobuf.scala:89-100, ~175-185`
- Modify: `kyo-schema/shared/src/main/scala/kyo/Schema.scala:5` (unused import)

- [ ] **Step 1: Add the hook to both `Codec.Reader` and `Codec.Writer`**

Add to the body of `abstract class Reader` AND `abstract class Writer` in `Codec.scala` (identical text in both):

```scala
        /** Whether this codec addresses record fields by numeric id instead of (or in addition to)
          * name, as Protobuf does. `SchemaSerializer` gates on this before computing a schema's
          * field-id override map, which would otherwise pay rename-resolution cost at every nesting
          * depth of every encode/decode on codecs that cannot use the result.
          */
        def supportsFieldIdOverrides: Boolean = false

        /** Installs field-name-to-numeric-id overrides for interoperability with wire formats that
          * carry numeric field ids (e.g. existing `.proto` definitions). No-op by default; a codec
          * that returns `true` from [[supportsFieldIdOverrides]] must override this mutably and
          * return `this` for chaining.
          */
        def withFieldIdOverrides(overrides: Map[String, Int]): this.type = this

        /** The currently installed field-id override map, read by a caller that is about to replace
          * it with a nested schema's own overrides so the prior value can be restored afterwards.
          */
        private[kyo] def fieldIdOverridesSnapshot: Map[String, Int] = Map.empty
```

- [ ] **Step 2: Make `ProtobufWriter`/`ProtobufReader` override the hook**

In `ProtobufWriter.scala` (~lines 74, 82) and `ProtobufReader.scala` (~lines 66, 74): add `override` to the existing `def withFieldIdOverrides` and `private[kyo] def fieldIdOverridesSnapshot`, and add in each class body:

```scala
    override def supportsFieldIdOverrides: Boolean = true
```

- [ ] **Step 3: Rewrite the four `SchemaSerializer` helpers**

Replace the bodies of `threadFieldIdOverridesForWrite` (line 79) and `restoreFieldIdOverridesForWrite` (line 100):

```scala
    private[kyo] def threadFieldIdOverridesForWrite(schema: Schema[?], writer: Writer): Maybe[Map[String, Int]] =
        if writer.supportsFieldIdOverrides then
            val overrides = schema.fieldIdNameOverrides
            if overrides.nonEmpty then
                val prior = writer.fieldIdOverridesSnapshot
                // Installs this schema's pins on the writer: withFieldIdOverrides mutates the
                // writer's active override map (the side effect is the purpose) and returns the
                // writer for chaining, discarded here because `prior` captured above is what the
                // caller restores once this schema's write completes.
                val _ = writer.withFieldIdOverrides(overrides)
                Maybe.Present(prior)
            else Maybe.Absent
            end if
        else Maybe.Absent
    end threadFieldIdOverridesForWrite
```

```scala
    private[kyo] def restoreFieldIdOverridesForWrite(writer: Writer, prior: Maybe[Map[String, Int]]): Unit =
        prior match
            case Maybe.Present(overrides) => val _ = writer.withFieldIdOverrides(overrides)
            case Maybe.Absent             => ()
    end restoreFieldIdOverridesForWrite
```

Mirror the same shape for `threadFieldIdOverridesForRead` (line 594) and `restoreFieldIdOverridesForRead` (line 611), substituting `reader: Reader` for `writer: Writer`.

In `writeWithTransforms` (lines 138-143) replace:

```scala
        val fieldIdOverrides = schema.fieldIdNameOverrides
        if fieldIdOverrides.nonEmpty then
            writer match
                case pw: ProtobufWriter => val _ = pw.withFieldIdOverrides(fieldIdOverrides)
                case _                  => ()
        end if
```

with:

```scala
        if writer.supportsFieldIdOverrides then
            val fieldIdOverrides = schema.fieldIdNameOverrides
            if fieldIdOverrides.nonEmpty then
                val _ = writer.withFieldIdOverrides(fieldIdOverrides)
        end if
```

and the analogous reader block in `readWithTransforms` (lines 649-654) the same way.

Update the two doc comments (lines ~66-70 and ~581-585) that describe "a single failed `isInstanceOf` check" to describe the virtual-call gate instead, e.g. "Gating on `supportsFieldIdOverrides` first makes the call a single virtual call for every codec without field ids". Remove any now-unused `ProtobufWriter`/`ProtobufReader` mentions that describe the *mechanism* (mentions that describe Protobuf as the motivating format may stay).

- [ ] **Step 4: Simplify `Protobuf.scala`'s own call sites**

In `Protobuf.encode` (lines 92-97) replace:

```scala
        val overrides = schema.fieldIdNameOverrides
        if overrides.nonEmpty then
            w match
                case pw: kyo.internal.ProtobufWriter => val _ = pw.withFieldIdOverrides(overrides)
                case _                               => ()
        end if
```

with:

```scala
        val overrides = schema.fieldIdNameOverrides
        if overrides.nonEmpty then
            val _ = w.withFieldIdOverrides(overrides)
```

Do the same transformation at the reader-side site (~line 179, `case pr: kyo.internal.ProtobufReader`).

- [ ] **Step 5: Remove the unused import**

In `Schema.scala` delete line 5: `import kyo.internal.JsonWriter`.

- [ ] **Step 6: Verify core no longer names Protobuf internals in code**

```bash
grep -rnE "ProtobufWriter|ProtobufReader" kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala kyo-schema/shared/src/main/scala/kyo/Schema.scala kyo-schema/shared/src/main/scala/kyo/Codec.scala | grep -vE "^\s*\*|//"
```
Expected: no output (doc-comment prose lines are fine if any remain; code lines must be gone).

- [ ] **Step 7: Run the override-sensitive suites, then the full module**

```
sbt "kyo-schemaJVM/testOnly kyo.ProtobufTest kyo.ProtobufConformanceTest kyo.SchemaAnnotationTest kyo.NestedTransformTest"
sbt kyo-schemaJVM/test
```
Expected: PASS both times.

- [ ] **Step 8: Commit**

```bash
git add -A kyo-schema && git commit -m "[kyo-schema] replace Protobuf writer/reader instanceof checks with a Codec-level field-id override hook"
```

---

### Task 3: Create `kyo-schema-json` and `kyo-schema-tests`; move Json + cross-format suites; repoint downstream

This is the pivot commit: after it, core no longer contains Json, and every suite that mixes format families lives in `kyo-schema-tests`.

**Files:**
- Modify: `build.sbt` (new module defs after the `kyo-schema` def ending at line 680; aggregates at ~286/360/413/457; dependents at lines 1015, 1647, 1687, 1722, 2270, 2417, 2443, 2513)
- Modify: `.github/workflows/build.yml:82`
- Move (git mv): sources and tests listed in steps 2-4
- Test: existing suites, relocated

- [ ] **Step 1: Add the two module definitions to `build.sbt`**

Insert directly after the `kyo-schema` definition (after line 680):

```scala
lazy val `kyo-schema-json` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-json"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)

// Unpublished home for suites that exercise multiple serialization formats at once
// (sbt cannot express mutual test-scope dependencies between sibling format modules).
// Also validates kyo-schema/README.md doctest blocks, which span every format (wired in a later change).
lazy val `kyo-schema-tests` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-schema-json`)
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-tests"))
        .withKyoTest
        .settings(`kyo-settings`, publish / skip := true)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

Note: at this point the still-in-core formats (Yaml, Protobuf, ...) reach `kyo-schema-tests` transitively through `kyo-schema`; later tasks add explicit `dependsOn` lines as each format moves out.

In each of the four platform aggregates, add next to the existing `` `kyo-schema`.jvm `` (and `.js`/`.native`/`.wasm`) entries:

```scala
        `kyo-schema-json`.jvm,
        `kyo-schema-tests`.jvm,
```
(and the matching `.js`, `.native`, `.wasm` entries in the other three lists).

- [ ] **Step 2: Move the Json main sources**

```bash
mkdir -p kyo-schema-json/shared/src/main/scala/kyo/internal kyo-schema-json/jvm/src/main/scala/kyo/internal kyo-schema-json/native/src/main/scala/kyo/internal kyo-schema-json/js-wasm/src/main/scala/kyo/internal
git mv kyo-schema/shared/src/main/scala/kyo/Json.scala                        kyo-schema-json/shared/src/main/scala/kyo/Json.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/JsonReader.scala         kyo-schema-json/shared/src/main/scala/kyo/internal/JsonReader.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/JsonWriter.scala         kyo-schema-json/shared/src/main/scala/kyo/internal/JsonWriter.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/JsonSchemaEnricher.scala kyo-schema-json/shared/src/main/scala/kyo/internal/JsonSchemaEnricher.scala
git mv kyo-schema/jvm/src/main/scala/kyo/internal/AsciiStringFactory.scala     kyo-schema-json/jvm/src/main/scala/kyo/internal/AsciiStringFactory.scala
git mv kyo-schema/native/src/main/scala/kyo/internal/AsciiStringFactory.scala  kyo-schema-json/native/src/main/scala/kyo/internal/AsciiStringFactory.scala
git mv kyo-schema/js-wasm/src/main/scala/kyo/internal/AsciiStringFactory.scala kyo-schema-json/js-wasm/src/main/scala/kyo/internal/AsciiStringFactory.scala
```

No package or import changes are needed: everything stays in `kyo` / `kyo.internal`, and cross-module `private[kyo]` / `private[internal]` access works because Scala checks package membership, not jar membership.

- [ ] **Step 3: Move the Json-only test suites**

```bash
mkdir -p kyo-schema-json/shared/src/test/scala/kyo kyo-schema-json/shared/src/test/scala/externalschema
git mv kyo-schema/shared/src/test/scala/kyo/JsonTest.scala                          kyo-schema-json/shared/src/test/scala/kyo/JsonTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/JsonDocTest.scala                       kyo-schema-json/shared/src/test/scala/kyo/JsonDocTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/ChangesetTest.scala                     kyo-schema-json/shared/src/test/scala/kyo/ChangesetTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/FocusMacroDocTest.scala                 kyo-schema-json/shared/src/test/scala/kyo/FocusMacroDocTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala   kyo-schema-json/shared/src/test/scala/kyo/SchemaCustomContainerNestedTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaStructureTest.scala               kyo-schema-json/shared/src/test/scala/kyo/SchemaStructureTest.scala
git mv kyo-schema/shared/src/test/scala/externalschema/SchemaExternalPackageDerivationTest.scala kyo-schema-json/shared/src/test/scala/externalschema/SchemaExternalPackageDerivationTest.scala
```

- [ ] **Step 4: Move the cross-format suites to `kyo-schema-tests`**

```bash
mkdir -p kyo-schema-tests/shared/src/test/scala/kyo kyo-schema-tests/jvm/src/test/scala/kyo
git mv kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala            kyo-schema-tests/shared/src/test/scala/kyo/SchemaTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaCodecTest.scala       kyo-schema-tests/shared/src/test/scala/kyo/SchemaCodecTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaAnnotationTest.scala  kyo-schema-tests/shared/src/test/scala/kyo/SchemaAnnotationTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaCompositionTest.scala kyo-schema-tests/shared/src/test/scala/kyo/SchemaCompositionTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala   kyo-schema-tests/shared/src/test/scala/kyo/NestedTransformTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/StructureTest.scala         kyo-schema-tests/shared/src/test/scala/kyo/StructureTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/ProtobufTest.scala          kyo-schema-tests/shared/src/test/scala/kyo/ProtobufTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/YamlTest.scala              kyo-schema-tests/shared/src/test/scala/kyo/YamlTest.scala
git mv kyo-schema/jvm/src/test/scala/kyo/CodecInitTest.scala            kyo-schema-tests/jvm/src/test/scala/kyo/CodecInitTest.scala
```

(`CodecTestSupport.scala` and `SchemaTestData.scala` stay in `kyo-schema`'s test tree — they reference no format and are shared via `test->test`.)

- [ ] **Step 5: Repoint direct downstream dependents**

In `build.sbt`, replace the `` `kyo-schema` `` token with `` `kyo-schema-json` `` inside the `dependsOn(...)` of these eight modules (core arrives transitively through the json module):

- `kyo-tasty` (line 1015), `kyo-http` (1647), `kyo-ai` (1687), `kyo-jsonrpc` (1722), `kyo-slack` (2270), `kyo-examples` (2417), `kyo-bench` (2443), `kyo-doctest` (2513)

- [ ] **Step 6: Update the CI heavy-native-link hint**

`.github/workflows/build.yml:82` reads:

```yaml
      NATIVE_HEAVY: "${{ matrix.target == 'Native' && 'kyo-schema' || '' }}"
```

Change `'kyo-schema'` to `'kyo-schema-tests'` (the heavy suites — SchemaTest and friends — now live there). Read the surrounding lines 75-90 first and update any other use of the value consistently.

- [ ] **Step 7: Compile everything, run the three affected modules' tests**

```
sbt kyoJVM/Test/compile
sbt kyo-schemaJVM/test kyo-schema-jsonJVM/test kyo-schema-testsJVM/test
```
Expected: compile clean; all three test runs PASS. If `kyo-schemaJVM/test` fails compiling remaining tests, a moved suite was still referenced — re-check steps 3-4 lists.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-json and add cross-format kyo-schema-tests module"
```

---

### Task 4: Extract `kyo-schema-protobuf`

**Files:**
- Modify: `build.sbt`
- Move: Protobuf sources/tests (below)

- [ ] **Step 1: Add the module definition** (after `kyo-schema-tests` in `build.sbt`)

```scala
lazy val `kyo-schema-protobuf` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-protobuf"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

Add `.dependsOn(`kyo-schema-protobuf`)` to `kyo-schema-tests`. Add the four platform entries to the aggregates.

- [ ] **Step 2: Move sources and tests**

```bash
mkdir -p kyo-schema-protobuf/shared/src/main/scala/kyo/internal kyo-schema-protobuf/shared/src/test/scala/kyo
git mv kyo-schema/shared/src/main/scala/kyo/Protobuf.scala                 kyo-schema-protobuf/shared/src/main/scala/kyo/Protobuf.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/ProtobufReader.scala  kyo-schema-protobuf/shared/src/main/scala/kyo/internal/ProtobufReader.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/ProtobufWriter.scala  kyo-schema-protobuf/shared/src/main/scala/kyo/internal/ProtobufWriter.scala
git mv kyo-schema/shared/src/test/scala/kyo/ProtobufConformanceTest.scala  kyo-schema-protobuf/shared/src/test/scala/kyo/ProtobufConformanceTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/CodecTest.scala                kyo-schema-protobuf/shared/src/test/scala/kyo/CodecTest.scala
```

- [ ] **Step 3: Compile and test**

```
sbt kyoJVM/Test/compile
sbt kyo-schema-protobufJVM/test kyo-schema-testsJVM/test
```
Expected: PASS. (A failure in `kyo-schemaJVM/Test/compile` here means a remaining core test references Protobuf — check the Task 3/4 move lists against the spec's classification.)

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-protobuf"
```

---

### Task 5: Extract `kyo-schema-msgpack`

- [ ] **Step 1: Add build wiring**

Add after the previous format module in `build.sbt`; add the four platform entries to the aggregates; add `.dependsOn(`kyo-schema-msgpack`)` to `kyo-schema-tests`:

```scala
lazy val `kyo-schema-msgpack` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-msgpack"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

- [ ] **Step 2: Move sources and tests**

```bash
mkdir -p kyo-schema-msgpack/shared/src/main/scala/kyo/internal/msgpack kyo-schema-msgpack/shared/src/test/scala/kyo
git mv kyo-schema/shared/src/main/scala/kyo/MsgPack.scala kyo-schema-msgpack/shared/src/main/scala/kyo/MsgPack.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/msgpack kyo-schema-msgpack/shared/src/main/scala/kyo/internal/
git mv kyo-schema/shared/src/test/scala/kyo/MsgPackTest.scala kyo-schema-msgpack/shared/src/test/scala/kyo/MsgPackTest.scala
```

- [ ] **Step 3: Compile and test**

```
sbt kyoJVM/Test/compile
sbt kyo-schema-msgpackJVM/test kyo-schema-testsJVM/test
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-msgpack"
```

---

### Task 6: Extract `kyo-schema-bson`

- [ ] **Step 1: Add build wiring**

Add after `kyo-schema-msgpack` in `build.sbt`; add the four platform entries to the aggregates; add `.dependsOn(`kyo-schema-bson`)` to `kyo-schema-tests`:

```scala
lazy val `kyo-schema-bson` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-bson"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

- [ ] **Step 2: Move sources and tests**

```bash
mkdir -p kyo-schema-bson/shared/src/main/scala/kyo/internal/bson kyo-schema-bson/shared/src/test/scala/kyo
git mv kyo-schema/shared/src/main/scala/kyo/Bson.scala kyo-schema-bson/shared/src/main/scala/kyo/Bson.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/bson kyo-schema-bson/shared/src/main/scala/kyo/internal/
git mv kyo-schema/shared/src/test/scala/kyo/BsonTest.scala            kyo-schema-bson/shared/src/test/scala/kyo/BsonTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/BsonConformanceTest.scala kyo-schema-bson/shared/src/test/scala/kyo/BsonConformanceTest.scala
```

- [ ] **Step 3: Compile and test**

```
sbt kyoJVM/Test/compile
sbt kyo-schema-bsonJVM/test kyo-schema-testsJVM/test
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-bson"
```

---

### Task 7: Extract `kyo-schema-ion` (Ion text + IonBinary + IonSchema)

- [ ] **Step 1: Add build wiring**

Add after `kyo-schema-bson` in `build.sbt`; add the four platform entries to the aggregates; add `.dependsOn(`kyo-schema-ion`)` to `kyo-schema-tests`:

```scala
lazy val `kyo-schema-ion` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-ion"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

- [ ] **Step 2: Move sources and tests**

```bash
mkdir -p kyo-schema-ion/shared/src/main/scala/kyo/internal/ionbinary kyo-schema-ion/shared/src/test/scala/kyo
git mv kyo-schema/shared/src/main/scala/kyo/Ion.scala        kyo-schema-ion/shared/src/main/scala/kyo/Ion.scala
git mv kyo-schema/shared/src/main/scala/kyo/IonBinary.scala  kyo-schema-ion/shared/src/main/scala/kyo/IonBinary.scala
git mv kyo-schema/shared/src/main/scala/kyo/IonSchema.scala  kyo-schema-ion/shared/src/main/scala/kyo/IonSchema.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/IonReader.scala kyo-schema-ion/shared/src/main/scala/kyo/internal/IonReader.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/IonWriter.scala kyo-schema-ion/shared/src/main/scala/kyo/internal/IonWriter.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/ionbinary kyo-schema-ion/shared/src/main/scala/kyo/internal/
git mv kyo-schema/shared/src/test/scala/kyo/IonTest.scala                     kyo-schema-ion/shared/src/test/scala/kyo/IonTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/IonCorpusTest.scala               kyo-schema-ion/shared/src/test/scala/kyo/IonCorpusTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/IonBinaryTest.scala               kyo-schema-ion/shared/src/test/scala/kyo/IonBinaryTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/IonBinaryConformanceTest.scala    kyo-schema-ion/shared/src/test/scala/kyo/IonBinaryConformanceTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/IonSchemaTest.scala               kyo-schema-ion/shared/src/test/scala/kyo/IonSchemaTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/SchemaUnionRepresentationTest.scala kyo-schema-ion/shared/src/test/scala/kyo/SchemaUnionRepresentationTest.scala
```

- [ ] **Step 3: Compile and test**

```
sbt kyoJVM/Test/compile
sbt kyo-schema-ionJVM/test kyo-schema-testsJVM/test
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-ion"
```

---

### Task 8: Extract `kyo-schema-yaml`

- [ ] **Step 1: Add build wiring**

Add after `kyo-schema-ion` in `build.sbt`; add the four platform entries to the aggregates; add `.dependsOn(`kyo-schema-yaml`)` to `kyo-schema-tests`; and add to `kyo-bench` (line ~2443, its `yamlbench` package uses Yaml):

```scala
        .dependsOn(`kyo-schema-yaml`)
```

```scala
lazy val `kyo-schema-yaml` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .dependsOn(`kyo-core` % "test->compile")
        .in(file("kyo-schema-yaml"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

- [ ] **Step 2: Move sources and tests**

```bash
mkdir -p kyo-schema-yaml/shared/src/main/scala/kyo kyo-schema-yaml/shared/src/test/scala/kyo/internal
git mv kyo-schema/shared/src/main/scala/kyo/Yaml.scala      kyo-schema-yaml/shared/src/main/scala/kyo/Yaml.scala
git mv kyo-schema/shared/src/main/scala/kyo/internal/yaml   kyo-schema-yaml/shared/src/main/scala/kyo/internal/
git mv kyo-schema/shared/src/test/scala/kyo/YamlWriterTest.scala   kyo-schema-yaml/shared/src/test/scala/kyo/YamlWriterTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/YamlParserTest.scala   kyo-schema-yaml/shared/src/test/scala/kyo/YamlParserTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/YamlPipelineTest.scala kyo-schema-yaml/shared/src/test/scala/kyo/YamlPipelineTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/YamlCstTest.scala      kyo-schema-yaml/shared/src/test/scala/kyo/YamlCstTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/YamlEventsTest.scala   kyo-schema-yaml/shared/src/test/scala/kyo/YamlEventsTest.scala
git mv kyo-schema/shared/src/test/scala/kyo/internal/yaml          kyo-schema-yaml/shared/src/test/scala/kyo/internal/
```

- [ ] **Step 3: Compile and test — including bench**

```
sbt kyoJVM/Test/compile
sbt kyo-schema-yamlJVM/test kyo-schema-testsJVM/test
sbt kyo-benchJVM/Test/compile
```
Expected: PASS / compile clean.

- [ ] **Step 4: Verify core is now format-free**

```bash
ls kyo-schema/shared/src/main/scala/kyo
grep -rnE "\b(Json|Yaml|Bson|MsgPack|Protobuf|IonBinary|IonSchema|Ion)\b" kyo-schema/shared/src/main/scala --include="*.scala" | grep -vE ":\s*(\*|//)" | grep -vE "\".*\"" | head
```
Expected: file listing shows only core files (per the spec's module table); the grep shows at most doc-comment/string mentions, no code references.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "[kyo-schema] extract kyo-schema-yaml; core is now format-free"
```

---

### Task 9: Scaladoc reference hygiene in core

Core scaladoc uses `[[Json]]`, `[[Ion]]`, `[[Yaml]]`, `[[Protobuf]]` link syntax (e.g. `Codec.scala:12`, `Schema.scala:27`) whose targets no longer resolve from core.

**Files:**
- Modify: `kyo-schema/shared/src/main/scala/kyo/Codec.scala`, `Schema.scala`, and any other core file the grep below finds.

- [ ] **Step 1: Find and rewrite dangling links**

```bash
grep -rnE "\[\[(Json|Yaml|Bson|MsgPack|Protobuf|IonBinary|IonSchema|Ion)[\].]" kyo-schema/shared/src/main/scala --include="*.scala"
```

For each hit, replace the `[[X]]` link with inline code `` `X` `` (keep the prose). Example: `[[Json]] (JSON)` becomes `` `Json` (JSON) ``.

- [ ] **Step 2: Verify scaladoc builds clean**

```
sbt kyo-schemaJVM/doc
```
Expected: success, no broken-link warnings for the rewritten names.

- [ ] **Step 3: Commit**

```bash
git add -A kyo-schema && git commit -m "[kyo-schema] rewrite core scaladoc links to extracted format entry points"
```

---

### Task 10: Documentation and doctest wiring

**Files:**
- Modify: `build.sbt` (doctest settings on `kyo-schema` jvm and `kyo-schema-tests` jvm)
- Create: `kyo-schema-json/README.md`, `kyo-schema-yaml/README.md`, `kyo-schema-bson/README.md`, `kyo-schema-ion/README.md`, `kyo-schema-msgpack/README.md`, `kyo-schema-protobuf/README.md`
- Modify: `kyo-schema/README.md` (intro only), `kyo-schema/CONTRIBUTING.md`, root `README.md` if it enumerates modules

- [ ] **Step 1: Re-home README doctest validation**

`KyoDoctestPlugin` auto-enables on JVM projects and validates `README.md` from the project base dir (or one level up). `kyo-schema/README.md`'s code blocks exercise every format, which core's classpath no longer has. First read `kyo-doctest/plugin/src/main/scala/kyo/doctest/sbt/KyoDoctestPlugin.scala` to confirm the exact keys (`doctestSources`) and which classpath blocks compile against. Then in `build.sbt`:

- On `kyo-schema`'s jvm settings add: `doctestSources := Seq.empty` (core stops validating the shared README).
- On `kyo-schema-tests`'s jvm settings add:

```scala
        .jvmConfigure(_.settings(
            doctestSources := Seq((LocalRootProject / baseDirectory).value / "kyo-schema" / "README.md")
        ))
```

Verify with:

```
sbt kyo-schema-testsJVM/doctest
```
Expected: the README blocks compile and pass. If the plugin task name differs, check `KyoDoctestPlugin.autoImport` for the actual task key and use that.

- [ ] **Step 2: Add per-module READMEs**

Each of the six format modules gets a `README.md` following this template (adjust name, formats list, and section anchor; the anchors exist in `kyo-schema/README.md` — JSON `#json`, Ion `#ion`, BSON `#bson`, YAML `#yaml`, Protobuf `#protobuf`, MsgPack `#msgpack`):

```markdown
# kyo-schema-json

JSON codec for [kyo-schema](../kyo-schema). Provides the `Json` entry point
(`Json.encode` / `Json.decode` / `Json.JsonSchema`) for any type with a
`Schema` instance.

## Installation

​```scala
libraryDependencies += "io.getkyo" %% "kyo-schema-json" % "<version>"
​```

Pulls in `kyo-schema` (the Schema/Codec core) transitively.

## Documentation

See the [JSON section of the kyo-schema README](../kyo-schema/README.md#json)
for the full guide; everything there applies unchanged — only the artifact
name is new.
```

(Strip the zero-width characters around the inner code fence when writing the real files. New READMEs are auto-validated by the doctest plugin; the installation snippet is not a compilable Scala block, which the plugin skips or trivially accepts — if `sbt kyo-schema-jsonJVM/doctest` complains, mark the block as `sbt` instead of `scala`.)

- [ ] **Step 3: Update `kyo-schema/README.md` intro and `kyo-schema/CONTRIBUTING.md`**

- README: in the intro (lines 1-98), add a short "Module layout" table listing the seven artifacts and what each contains (core + six formats, `kyo-schema-tests` unpublished), and note that format sections below require the matching artifact.
- CONTRIBUTING: `grep -n "AsciiStringFactory\|shared/src\|jvm/src" kyo-schema/CONTRIBUTING.md` and update every moved path (the platform-split row now points at `kyo-schema-json/...`); update the module-inventory prose to describe the new layout; fix stale statements like "the module has exactly one platform-specific surface" if they now belong to kyo-schema-json.

- [ ] **Step 4: Root README check**

```bash
grep -n "kyo-schema" README.md
```
If a module list/table exists, add the six new artifacts (and not `kyo-schema-tests`, which is unpublished).

- [ ] **Step 5: Validate and commit**

```
sbt kyo-schema-testsJVM/doctest kyo-schema-jsonJVM/doctest
```
Expected: PASS.

```bash
git add -A && git commit -m "[kyo-schema] split docs across format modules and re-home README doctest"
```

---

### Task 11: Final verification sweep

- [ ] **Step 1: Full JVM compile + test for the schema family and direct dependents**

```
sbt kyoJVM/Test/compile
sbt kyo-schemaJVM/test kyo-schema-jsonJVM/test kyo-schema-yamlJVM/test kyo-schema-bsonJVM/test kyo-schema-ionJVM/test kyo-schema-msgpackJVM/test kyo-schema-protobufJVM/test kyo-schema-testsJVM/test
sbt kyo-jsonrpcJVM/test kyo-httpJVM/Test/compile kyo-mcpJVM/Test/compile kyo-benchJVM/Test/compile
```
Expected: all PASS / compile clean.

- [ ] **Step 2: JS and Native spot-compile** (catches platform-source wiring mistakes, e.g. the js-wasm AsciiStringFactory move)

```
sbt kyo-schema-jsonJS/Test/compile kyo-schema-testsJS/Test/compile
sbt kyo-schema-jsonNative/Test/compile
```
Expected: compile clean. (Full JS/Native/Wasm test runs are CI's job.)

- [ ] **Step 3: Structural grep gates**

```bash
# Core main sources: no format code references
grep -rnE "\b(Json|Yaml|Bson|MsgPack|Protobuf|IonBinary|IonSchema|Ion)\." kyo-schema/shared/src/main/scala --include="*.scala" | grep -vE "^\S+:[0-9]+:\s*(\*|//)"
# No format module references another format module's internals
grep -rn "internal.yaml\|internal.bson\|internal.msgpack\|internal.ionbinary" kyo-schema-json kyo-schema-protobuf --include="*.scala"
```
Expected: no output from either (string literals mentioning format names in error messages are acceptable — eyeball any hits).

- [ ] **Step 4: Verify the worktree is fully committed and summarize**

---

## Execution notes (added during execution — read before dispatching the named tasks)

- **Task 7 (ion):** `IonTest.scala:313` contains `assert(!Json().newWriter().canWriteAnnotations)` — a cross-format assertion. Before moving `IonTest.scala` into `kyo-schema-ion`, relocate that single assertion into a suite in `kyo-schema-tests` (e.g. a new small `CodecCapabilityTest` or an existing cross-format suite); otherwise `kyo-schema-ion` fails to compile (no Json on its classpath).
- **Task 9 (scaladoc):** widen the grep to the format modules too — format files carry dangling links after the split, e.g. `Bson.scala:35` (`[[Json]]`, plus stale prose "shared with Json" — reword to reference `Codec`), `Json.scala:18` (`[[kyo.Protobuf]]`), Bson's `@see [[kyo.Json]]`. Rewrite cross-module links as inline code.
- **Task 10 (docs):** `kyo-schema/README.md:333` documents defaults as `Json.DefaultMaxDepth` — reword to `Codec.DefaultMaxDepth` (constants were hoisted in Task 1).
- (Non-blocking) `ProtobufTest.scala` uses `Json.DefaultMax*` at 7 sites; it moves to `kyo-schema-tests` which sees Json, so it compiles — optional cleanup to `Codec.DefaultMax*` if touched anyway.
- **Task 3 outcome — classification deviations (fixture coupling):** four single-format suites reference fixture types defined inside cross-format suites (`ProtobufTest`, `YamlTest`, `StructureTest`) and therefore moved to `kyo-schema-tests` instead of their format modules: `ProtobufConformanceTest`, `YamlWriterTest`, `YamlPipelineTest`, `kyo/internal/yaml/YamlEventsTest`. They stay in `kyo-schema-tests` permanently (it depends on every format module). Consequences: **Task 4** moves only `Protobuf.scala`, `ProtobufReader/Writer.scala`, and `CodecTest.scala` (ProtobufConformanceTest is already gone from core). **Task 8** moves only `Yaml.scala`, `internal/yaml/*` main sources, and the remaining yaml suites still in core: `YamlParserTest`, `YamlCstTest`, top-level `kyo/YamlEventsTest`, and the remaining `internal/yaml/*Test` files (all except the already-moved `internal/yaml/YamlEventsTest`).
- **Task 4 rider fixes (from Task 3 quality review):** (a) `kyo-tasty` uses Json only in tests — change its dep to `.dependsOn(`kyo-core`, `kyo-schema`)` plus `.dependsOn(`kyo-schema-json` % "test->compile")` so the published POM doesn't carry the Json codec; (b) refresh the stale NATIVE_HEAVY doc comment in `scripts/ci-test.sh:258-261` (still cites the pre-split "kyo-schema ~7.7G" measurement) and ideally the fake-sbt fixtures at lines 165-188 to reference `kyo-schema-tests`.
- **Task 10 reminder:** drop the "(wired in a later change)" clause from the kyo-schema-tests comment in build.sbt when wiring doctests.
- **CI watch item:** first Native CI run decides whether NATIVE_HEAVY needs to become the list `'kyo-schema kyo-schema-tests'`; link pressure grows as tasks 4-8 add native Test binaries.
- **Task 4 outcome (larger than planned — Task 3's core-test verification claim was wrong):** six core suites still referenced Json after Task 3 and had to relocate: `SchemaNamingTest` → kyo-schema-json (Json-only); `BuilderTest`, `SchemaFieldTransformTest`, `SchemaUnionRepresentationTest` → kyo-schema-tests (cross-format; SchemaUnionRepresentationTest therefore does NOT move in Task 7). `CodecTest` is a cross-format matrix (Json/MsgPack/Bson/Yaml/Ion/IonBinary readers+writers) and went to kyo-schema-tests, with its format-agnostic fixture block extracted to core's `kyo-schema/shared/src/test/scala/kyo/CodecTestFixtures.scala` (shared via test->test; `Frame.internal` adaptations required by kyo-data's Frame macro in non-Test files). `IonTest` stays in core for now (fixtures used by IonBinaryTest/IonSchemaTest); its lone Json assertion moved to kyo-schema-json's JsonTest ("writer does not claim annotation support") — Task 7's execution note about IonTest:313 is RESOLVED. `SchemaStructureTest`'s TruncatedInputException fixture now uses `Json()` (was Protobuf, briefly MsgPack). **kyo-schema-protobuf ships zero test suites** — its coverage lives in kyo-schema-tests (ProtobufTest, ProtobufConformanceTest, CodecTest); Tasks 5-8 movers: re-verify each candidate test file's actual imports/usages before moving, and expect kyo-schemaJVM/test suite counts to have shifted from the Task 3 report.
- **Tasks 5-8 mover heads-up (verified by Task 4 quality review):**
  - Task 5 (msgpack): move only `MsgPackTest.scala` (fixtures all in core test scope via test->test). Rider: add one sentence to `kyo-schema/shared/src/test/scala/kyo/CodecTestFixtures.scala`'s header explaining the Frame constraint (file suffix outside Frame's allowlist ⇒ top-level givens pass `Frame.internal`; helpers propagate the caller's Frame).
  - Task 6 (bson): move `BsonTest.scala` + `BsonConformanceTest.scala` (need only CodecTestSupport/SchemaTestData).
  - Task 7 (ion): move IonTest, IonBinaryTest, IonBinaryConformanceTest, IonCorpusTest, IonSchemaTest PLUS the test resources `kyo-schema/shared/src/test/resources/iontestdata/**` (used by IonCorpusTest and IonTest). SchemaUnionRepresentationTest already relocated (Task 4) — skip. IonTest is Json-free now.
  - Task 8 (yaml): move `kyo/YamlCstTest`, `kyo/YamlEventsTest` (top-level — distinct from the `kyo.internal.yaml.YamlEventsTest` already in kyo-schema-tests; keep it OUT of the omnibus to avoid name confusion), `kyo/YamlParserTest`, and `kyo/internal/yaml/{YamlCstBuilderTest, YamlCstParserTest, YamlDocumentsTest, YamlEventReaderTest}`. kyo-bench uses Yaml in MAIN scope (yamlbench/*) and currently depends only on kyo-schema-json — add `dependsOn(kyo-schema-yaml)` or its compile breaks.
  - Each of tasks 5-8: add `dependsOn(kyo-schema-<fmt>)` to kyo-schema-tests (its suites reference every format). Verification per task: `sbt "kyo-schemaJVM/test" "kyo-schema-<fmt>JVM/test" "kyo-schema-testsJVM/test"` (plus `kyo-benchJVM/Test/compile` for yaml); no need to re-run kyoJVM/Test/compile until Task 11.
  - Release-notes item (Task 10/11): kyo-tasty's published POM no longer carries kyo-schema-json (moved to test scope) — downstream users relying on that transitive dep must add it.
- **Environment note (Tasks 9-11):** sbt runs that pull kyo-net (e.g. via kyo-bench/kyo-http) need `CC=gcc` in the environment on this machine — the FFI plugin defaults to `cc`, which doesn't exist in Git Bash here.
- **Task 11 note:** `kyoJVM/Test/compile` on this Windows machine shows pre-existing/environmental failures unrelated to the split: kyo-kernel/kyo-direct testVariants macro `StringIndexOutOfBoundsException`, and `ffiCompile` "Cannot run program cc" (no C compiler). Scope the final sweep's expectations accordingly; CI is the cross-platform arbiter.

```bash
git status --short && git log --oneline main..HEAD
```
Expected: clean tree; one commit per task. Then hand off per the finishing-a-development-branch skill (PR against `main`).
