# kyo-schema format-module extraction — design

Date: 2026-07-22
Status: approved

## Goal

Split the monolithic `kyo-schema` module so that every serialization format it
supports lives in its own sbt cross-project module. `kyo-schema` becomes a pure
core: the `Schema`/`Codec` model, `Structure`, optics (`Focus`, `Modify`,
`Builder`, `Changeset`, `Compare`, `Convert`), derivation macros, and the
format-agnostic internals they share.

## Decisions (settled during brainstorming)

1. **Json is extracted too.** No format stays in core; downstream modules are
   repointed to the new json module.
2. **Naming:** `kyo-schema-<format>`.
3. **Ion grouping:** one `kyo-schema-ion` module holding the Ion text codec,
   the Ion binary codec, and the IonSchema describer.
4. **Core tests keep using Json (no rewrite):** sbt rejects project-level
   dependency cycles even when scope-differentiated, so core's test scope
   cannot depend on `kyo-schema-json`. Json-driven core suites therefore move
   (file move only, no code changes) into `kyo-schema-json`'s test scope.
5. **Protobuf decoupling:** `withFieldIdOverrides(Map[String, Int])` becomes an
   open method on core `Codec.Writer` and `Codec.Reader` with a no-op default;
   `ProtobufWriter`/`ProtobufReader` override it. All
   `case pw: ProtobufWriter =>`-style matches in core (`SchemaSerializer`,
   `Schema`) become unconditional calls.
6. **Migration:** one coherent change (single PR), not a phased extraction.

## Module layout

| Module | Main sources (under `shared/src/main/scala/kyo/`) |
|---|---|
| `kyo-schema` (core) | `Schema.scala`, `SchemaException.scala`, `Codec.scala`, `Structure.scala`, `Builder.scala`, `Changeset.scala`, `Compare.scala`, `Convert.scala`, `Focus.scala`, `Modify.scala`, `schema/SchemaAnnotation.scala`, and `internal/`: all macros (`BuilderMacro`, `CodecMacro`, `ExpandMacro`, `FocusMacro`, `NavigationMacro`, `SchemaConvertMacro`, `SchemaDerivedMacro`, `SchemaTransformMacro`, `MacroUtils`, `BuilderAt`), `SchemaBridge`, `SchemaFactory`, `SchemaOrdering`, `SchemaSerializer`, `SchemaValidation`, `StructureValueReader/Writer`, `NameCaseConversion`, `FastFloat`, `FastFloatPow10Table`, `Ryu`, `RyuTables` |
| `kyo-schema-json` | `Json.scala`, `internal/JsonReader.scala`, `internal/JsonWriter.scala`, `internal/JsonSchemaEnricher.scala`, plus the platform-split `internal/AsciiStringFactory.scala` (`jvm/`, `native/`, `js-wasm/` variants) |
| `kyo-schema-yaml` | `Yaml.scala`, `internal/yaml/*` (13 files) |
| `kyo-schema-bson` | `Bson.scala`, `internal/bson/*` (4 files) |
| `kyo-schema-ion` | `Ion.scala`, `IonBinary.scala`, `IonSchema.scala`, `internal/IonReader.scala`, `internal/IonWriter.scala`, `internal/ionbinary/*` (3 files) |
| `kyo-schema-msgpack` | `MsgPack.scala`, `internal/msgpack/*` (3 files) |
| `kyo-schema-protobuf` | `Protobuf.scala`, `internal/ProtobufReader.scala`, `internal/ProtobufWriter.scala` |

Notes:

- `FastFloat`/`Ryu` stay in core because Json, Yaml, and Ion all use them.
- `AsciiStringFactory` moves to `kyo-schema-json`: `JsonWriter` is its only
  user, and moving it leaves core with no platform-specific sources
  (`CrossType.Pure` becomes possible for core; keep `CrossType.Full` if the
  JVM-only `internal/tools/FastFloatPow10Gen.scala` generator stays — it does,
  since the tables it generates live in core).
- Its visibility is currently `private[internal]`; the split packages keep
  working because every module stays in the `kyo` / `kyo.internal` packages
  (same convention other kyo modules already use).

## Core decoupling changes

1. **`Codec.Writer`/`Codec.Reader` gain**
   `def withFieldIdOverrides(overrides: Map[String, Int]): this.type = this`
   (no-op default, documented as the hook for wire formats that address fields
   by numeric id instead of name). `ProtobufWriter`/`ProtobufReader` override
   it with their current implementation.
2. **`SchemaSerializer`** (~8 sites) and **`Protobuf.scala`** (2 sites) drop
   their `case pw: ProtobufWriter / pr: ProtobufReader` matches and call the
   hook unconditionally.
3. **`Schema.scala`** drops its unused `import kyo.internal.JsonWriter`.
4. Core scaladoc references to `[[Json]]`, `[[Yaml]]`, `[[Protobuf]]`, etc.
   become plain-code mentions (`` `Json` ``) where the target type no longer
   resolves from core, so scaladoc builds stay warning-clean. Prose examples
   in core scaladoc may keep mentioning formats.

## Build wiring

Each format module:

```scala
lazy val `kyo-schema-json` =
    crossProject(JSPlatform, JVMPlatform, NativePlatform, WasmPlatform)
        .crossType(CrossType.Full)
        .dependsOn(`kyo-schema` % "test->test;compile->compile")
        .in(file("kyo-schema-json"))
        .withKyoTest
        .settings(`kyo-settings`)
        .jvmSettings(mimaCheck(false))
        .nativeSettings(`native-settings`)
        .jsSettings(`js-settings`, Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
        .wasmSettings(`wasm-settings`)
```

(Only `kyo-schema-json` needs `CrossType.Full` for its platform sources; the
other five are `shared/`-only and use `CrossType.Pure`. `CrossType.Full` is
what auto-wires the `js-wasm/` partially-shared directory — required for
`kyo-schema-json`.)

- `kyo-schema` keeps its current `dependsOn(kyo-data, kyo-core % test)`
  wiring. It cannot gain a test-scope dependency on `kyo-schema-json` (sbt
  rejects project-level `dependsOn` cycles even across scopes), so Json-driven
  core suites move into `kyo-schema-json` instead — see "Tests".
- All four platform aggregates (`kyoJVM`, `kyoJS`, `kyoNative`, `kyoWasm`,
  build.sbt lines ~286/360/413/457) add the six new modules.

### Downstream repointing

Modules with a direct `dependsOn(`kyo-schema`)` today: `kyo-tasty`,
`kyo-http`, `kyo-ai`, `kyo-jsonrpc`, `kyo-slack`, `kyo-examples`,
`kyo-bench`, `kyo-doctest`. Rule:

- Every module whose *code* uses `Json.` (kyo-ai, kyo-browser, kyo-doctest,
  kyo-flow, kyo-http, kyo-jsonrpc, kyo-jsonrpc-http, kyo-lsp, kyo-mcp,
  kyo-pod, kyo-slack, kyo-tasty, kyo-ui) must see `kyo-schema-json` on its
  classpath. Direct users of `kyo-schema` switch their dep to
  `kyo-schema-json` (core arrives transitively); transitive users (kyo-mcp,
  kyo-lsp, etc. via kyo-jsonrpc/kyo-http) inherit it the same way they inherit
  kyo-schema today.
- `kyo-bench` additionally depends on all six format modules (it benchmarks
  every format).
- Modules that use only `Schema`/`Codec` core with no format keep a plain
  `kyo-schema` dependency (audit during implementation; current evidence says
  every direct dependent uses Json).

## Tests

- Format-specific suites move to their module's `shared/src/test`:
  - json: `JsonTest`, `JsonDocTest`
  - yaml: `YamlTest`, `YamlWriterTest`, `YamlParserTest`, `YamlPipelineTest`,
    `YamlCstTest`, `YamlEventsTest`, `internal/yaml/*Test` (6 files)
  - bson: `BsonTest`, `BsonConformanceTest`
  - ion: `IonTest`, `IonCorpusTest`, `IonBinaryTest`,
    `IonBinaryConformanceTest`, `IonSchemaTest`
  - msgpack: `MsgPackTest`
  - protobuf: `ProtobufTest`, `ProtobufConformanceTest`
- Cross-format helpers `CodecTestSupport.scala` and `SchemaTestData.scala`:
  stay in `kyo-schema`'s test scope, exported to format modules via the
  existing `"test->test"` dependency each format module declares on core.
  They must therefore not reference any format themselves (verify; strip Json
  usage into a json-local helper if found).
- Core suites that exercise Schema through Json (`SchemaTest`, `CodecTest`,
  `SchemaCompositionTest`, doc tests, etc.) move unchanged to
  `kyo-schema-json/shared/src/test` (decision 4).
- Pure-core suites with no format dependency (`FocusTest`, `BuilderTest`,
  `CompareTest`, `ConvertTest`, macro tests, `internal/FastFloat*Test`,
  `NameCaseConversionTest`, etc.) stay in `kyo-schema`.
- `kyo-schema/jvm/src/test/scala/kyo/CodecInitTest.scala`: audit; goes
  wherever its subject lands.

## Docs and repo hygiene

- `kyo-schema/CONTRIBUTING.md` (module map, platform-split notes) is updated
  for the new layout; add a short CONTRIBUTING/README to each format module or
  extend the core one with a module table — implementation picks the lighter
  option consistent with other kyo modules.
- `kyo-schema/README.md` updated; root `README.md` module list updated if it
  enumerates modules.
- Maven artifacts: new artifact ids `kyo-schema-json` etc.; `kyo-schema`
  artifact shrinks. Pre-1.0, mima is already `mimaCheck(false)` for schema —
  no compat shims. Release notes must call out the artifact split and that
  users of Json must add `kyo-schema-json`.

## Error handling / risks

- **Split-package visibility:** moved files keep packages `kyo` /
  `kyo.internal` / `kyo.internal.<format>`. `private[kyo]` /
  `private[internal]` members used across the new module boundary compile per
  package, not per module, so this works — but JPMS/OSGi users get split
  packages across jars (already the kyo status quo; acceptable).
- **Doctest:** scaladoc examples in core files that call `Json.encode` compile
  in the owning module's test scope. After the move, any such example living in
  a *core* file would fail to compile. Audit core scaladoc examples; rewrite
  the ones that exercise formats to be illustrative-only (non-compiled) or
  move the claim into the json module's docs.
- **CI/native linking:** six new modules increase link targets; the recent CI
  parallelism cap (#1755) already governs this. Watch CI duration.
- **Benchmarks:** kyo-bench must still compile against all formats; it is the
  only consumer of non-Json formats and serves as the integration check.

## Success criteria

- `sbt +Test/compile && sbt test` green on JVM; JS/Native/Wasm compile green
  in CI.
- `kyo-schema` main sources contain no reference to any format reader/writer
  or format object (verified by grep).
- Downstream modules compile without source changes (imports stay `kyo.*`).
