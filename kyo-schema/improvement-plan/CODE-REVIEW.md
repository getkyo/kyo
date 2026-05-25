# Code review — kyo-schema improvement branch

## Method

Walked the 22 commits chronologically (`a91161eb4` first, `9f4992da4` last). For each, ran `git show <sha>` against the actual diff; cross-referenced against the per-phase specification in `kyo-schema/improvement-plan/execution-plan.md` and the bug catalogue in `analysis.md`. Spot-checked file state in `kyo-schema/shared/src/main/scala/kyo/{Codec,Schema,Structure,Json,Protobuf}.scala` and the internal macros (`SerializationMacro`, `SchemaSerializer`, `ProtobufReader`, `UnionMacro`, `FocusMacro`, `ExpandMacro`, `MacroUtils`) to verify that referenced symbols (`Reader.frame`, `Json`-as-class, `ParseException` constructor) actually exist with the shapes the diffs depend on. Convention checks ran against `STEERING.md` (the project's own steering doc), not `/Users/fwbrasil/CLAUDE.md`, per the instructions. Reviewed in scope-first order: scope creep, correctness, conventions, test quality, style, cross-commit consistency, performance, completeness.

## Per-commit findings

### a91161eb4 — failing NestedTransformTest reproducing Bug D
- **Scope**: Clean. Single new test file, no other edits.
- **Correctness**: PASS. Test fixtures live at package level per STEERING. Repro is faithful to analysis.md Bug D.
- **Conventions**: PASS. Uses `Test`, top-level fixtures, no semicolons, no AllowUnsafe.
- **Test quality**: PASS. Tests are RED on purpose (the pre-fix state) and the assertions are tight (`js == exact-string`, not just `contains`).
- **Findings**: None.

### db1bcc601 — Phase 4: route nested field codecs through transform-aware dispatch
- **Scope**: Clean. Touches only `SerializationMacro.scala` at the three sites the spec names (L72/80 maybe/option write, L114 generic write, L837-862 read).
- **Correctness**: PASS. Substitutions are 1:1 swaps from `serializeWrite`/`serializeRead` to `SchemaSerializer.writeTo`/`readFrom`. Frame strategy (writes use `Frame.internal`, reads use `$reader.frame`) is correct and matches STEERING precedent.
- **Conventions**: ACCEPTABLE. `Frame.internal` in macro-emitted lambdas is permitted by STEERING; not yet annotated with `// Unsafe:` markers (deferred to commit `9f4992da4`).
- **Test quality**: N/A (no test changes; preceding test commit covers it).
- **Findings**: None.

### ec01fad89 — Phase 5: Protobuf discriminator decode (out of dependency order)
- **Scope**: BLOATED. Beyond the spec's "path (a)" (publish field-name map), the commit ALSO adds path (b) — a `CodecMacro.fieldId(name).toString` hash-equivalence fallback in two places: `DiscriminatorReader.objectStart` (L325-336) AND `matchField` (L443-459). The plan's "Recommendation: path (a)" was explicit; the commit implements both belt-and-suspenders. The commit body's justification ("variant case-class field names aren't statically known at the discriminator level") is plausible but the spec said path (a) alone would close the bug. The two-path implementation muddies the design.
- **Correctness**: PASS for the happy path. The merge semantics (`fieldNames = fieldNames ++ names`) silently changes `ProtobufReader.withFieldNames` from set-semantics to merge-semantics. Pre-existing test sites (`ProtobufTest.scala:139`, `SchemaTest.scala:2957, 2972`) treat it as set-semantics; on a freshly-allocated reader the difference is invisible, but any future caller assuming "overwrite" gets merge instead.
- **Conventions**: PASS. `Codec.Reader.withFieldNames` default is a no-op; ProtobufReader overrides explicitly with `override`. No global state.
- **Test quality**: PASS. The negative test asserts `e.isInstanceOf[MissingFieldException]` AND `e.getMessage.contains("type")`. The other four leaves are real round-trips, not type-check tautologies.
- **Performance regression**: MAJOR. The macro now emits, on every case-class decode for every wire format (not just Protobuf), an `Array[Int]` allocation, a loop, and a `Map[Int, String]` allocation, then calls `reader.withFieldNames(map)`. The default no-op `withFieldNames` discards it. This is two heap allocations + an O(n) loop per case-class decode on JSON and StructureValue paths, where the work is wasted. Spec didn't address this; the commit body claims it is "one allocation per case-class read, only relevant for formats that consume it" but materialising the map is unconditional.
- **Findings**:
  - MAJOR: per-decode allocation overhead on JSON and StructureValueReader where the map is never consumed.
  - MINOR: the dual-path fix (publish + hash-equivalence fallback in DiscriminatorReader) diverges from the plan's explicit single-path recommendation.
  - MINOR: silent change of `withFieldNames` semantics from set to merge.
  - MINOR: out-of-dependency-order commit — Phase 5 (depends on Phase 4) shipped before Phase 1, even though Phase 5 had no Phase 1 dependency this opportunistic ordering doesn't follow the plan's stated layering.

### cdf05c809 — Phase 1: isSerializableType enumeration drift
- **Scope**: Clean. Adds 8 entries to `primitiveSymbols`, Seq+Span to `containerSymbols`, new `tupleSymbols`, Either branch, tuple branch. Test fixtures and drift-guard macro added.
- **Correctness**: PASS. The drift macro (`SerializationMacroDriftMacro`) substitutes free type-parameter args with `String`; this is sound because `String` is itself serializable. Only catches one direction of drift (gate rejects a given) — does not catch the opposite (gate accepts a non-given), but that direction is non-bug.
- **Conventions**: PASS.
- **Test quality**: PASS. The pending `LocalDateTime` leaf is well-justified with a clear escalation note and is unblocked in the next commit. Negative drift cells use distinct types (Either, Tuple3, Span).
- **Findings**: None.

### cc62abd3e — TagMacro: treat Java wildcard type-args as their upper bound
- **Scope**: Minimal kyo-data change. Removes the Phase 1 pending tag and unpends `LocalDateTime`. Correctly scoped escalation.
- **Correctness**: PASS. Fix is one short branch in `visit` plus an explanatory comment; mirrors compiler subtype semantics.
- **Conventions**: PASS.
- **Test quality**: PASS — re-enables a real round-trip that was previously skipped.
- **Findings**: None.

### d34fe0660 — Phase 2: PrimitiveKind handles Instant/Duration/Frame/Text
- **Scope**: Expanded beyond spec but justified. Spec mentioned only Structure / StructureMacro / StructureTest; commit also edits `Json.scala`, `Protobuf.scala`, `MacroUtils.scala`. These are required for soundness (Json/Protobuf exhaustive match must cover new enum cases; `extendedPrimitiveSymbols` must include them so `isPrimitive` accepts them). Correct scope expansion.
- **Correctness**: PASS. Mappings are correct (kyo.Duration -> Long-of-nanos but JSON surface is Str, documented inline).
- **Conventions**: PASS.
- **Test quality**: PASS. Negative test pending; justification is solid (after Phase 2 every `extendedPrimitiveSymbols` entry has a `primitiveKindExpr` branch, so no natural synthetic example). The comment names where to land it when the situation changes.
- **Findings**: None.

### a03102bfb — Phase 3: MacroUtils symbol sets become single source of truth
- **Scope**: Clean. Plan said tests with `???` placeholders; agent actually implemented real macros.
- **Correctness**: PASS for the additions to `MacroUtils.{collectionSymbols, mapSymbols}` and the delegation in `SerializationMacro`. The drift macro is well-engineered (correctly excludes `Tuple2..5`, `Tag`, `Either`).
- **Conventions**: PASS.
- **Test quality**: ACCEPTABLE. Leaf "SerializationMacro.containerSymbols equals MacroUtils collection ++ optional" does NOT actually compare for equality; it walks every MacroUtils symbol and asserts `isSerializableType[F[String]]` accepts it. The reverse direction (gate has extras MacroUtils doesn't) is not caught. Sufficient for forward-drift but not bidirectional.
- **Findings**:
  - MINOR: drift-test leaf label ("equals") oversells the assertion ("each MacroUtils tycon is accepted by gate").

### 74b7000bd — Phase 6: composition matrix exposes collection-given bug
- **Scope**: Clean. Single new test file.
- **Correctness**: PASS. 79 leaves matches the plan's 48+28+5 ≈ 81 budget (49+28+5 = 82 — one C cell short of the planned 5; let me recount: Sweep A is 8 categories × 6 positions = 48; commit says "49 leaves" because the Maybe Absent leaf for C1 is added). Tally is within tolerance.
- **Conventions**: PASS. Top-level fixtures, no AllowUnsafe.
- **Test quality**: PASS. Tests are MEANT to fail (19 reds expose a real bug — the collection-given bypass). The agent correctly identified the diagnosis and named the next-commit fix in the body.
- **Findings**: None.

### ff3669abc — Phase 4b: route collection-given codecs through transform-aware dispatch
- **Scope**: Clean. Mechanical 1:1 replacement of `inner.serializeWrite`/`serializeRead` with `SchemaSerializer.writeTo`/`readFrom` across 13 collection/sum givens.
- **Correctness**: PASS. Frame strategy consistent with Phase 4 (writes use `Frame.internal`, reads use `reader.frame`).
- **Conventions**: BORDERLINE. STEERING permits `Frame.internal` only in macro-emitted code; these givens are hand-written in public `Schema.scala`. However, the writeFn lambdas truly have no Frame in scope (no `using Frame` available without changing Schema.init's signature), so the use is structurally forced. Final-audit commit annotates each region with explanation.
- **Test quality**: N/A (Phase 6 tests now go green).
- **Performance**: Each collection element write/read now adds one `hasTransforms` field-read and a single conditional branch. For the no-transform default case (overwhelming majority) the cost is one CPU branch per element. Acceptable.
- **Findings**: None blocking. The convention exception is documented in the audit commit.

### f127c1028 — Phase 7: KeyCodec[K] typeclass
- **Scope**: Clean. New file + test file.
- **Correctness**: PASS. ParseException constructor signature is correctly invoked (`Json()`, input, target-type-name).
- **Conventions**: PASS. Public API uses `Result[DecodeException, K]` (kyo type), passes `Frame` explicitly.
- **Test quality**: ACCEPTABLE. Tests have superfluous trailing `succeed` calls after `assert(...)` — verbose but harmless. Negative test asserts the message contains the input — sufficient.
- **Findings**:
  - MINOR: `ParseException(Json(), ...)` allocates a `Json` instance per failed decode just to label the format. Optimisable to a `lazy val` if it shows up in profiling.
  - MINOR: `KeyCodec` is exposed at top-level `kyo` package; per the export-only-when-warranted convention, niche typeclasses should stay nested. Defensible because it's user-facing.

### 60042adf0 — Phase 8: generic Map[K, V] via KeyCodec + array-of-pairs fallback
- **Scope**: Clean.
- **Correctness**: PASS. The `NotGiven[KeyCodec[K]]` constraint disambiguates the two givens. Commit body documents why the spec's LowPriorityMapGivens trait approach failed (Schema.derived outranks trait-inherited givens).
- **Conventions**: PASS.
- **Test quality**: PASS — 5 leaves covering Int / Long / UUID / case-class key (fallback) / tuple key (fallback).
- **Findings**:
  - MINOR: `mapPairsSchema.readFn` has two suspicious `discard(reader.hasNextElement())` calls just before reading k and v. If `hasNextElement` is non-side-effecting on every Reader implementation, these are dead calls; if any reader uses them to advance state, the code is reader-implementation-coupled in a way the rest of the codebase doesn't replicate. Worth a comment or removal. (Phase 13 sortedMapSchema repeats this pattern.)

### 2ba83a153 — Phase 4c: route sealed-trait variant codecs through transform-aware dispatch
- **Scope**: Clean. Two sites in `SerializationMacro`. Updates `NestedTransformTest` to unpend the previously-pending leaf.
- **Correctness**: PASS. Same pattern as Phase 4 / 4b.
- **Conventions**: PASS.
- **Test quality**: MINOR. The unpended leaf asserts the dropped `metadata` field is absent from JSON and that round-trip succeeds, but the comment says "the field is missing from wire and the generated decoder leaves the case-class default — empty string here since no explicit default was set". The case classes (`NestedDiscDropRO.\`string\``, `NestedDiscDropRO.\`number\``) have NO default for `metadata` — so where does the default come from? Either the schema-derived decoder synthesises an empty-string default, or there's a hidden mechanism. The test never asserts `decoded.metadata == ""`, so the behaviour is silent. Add an explicit assertion to lock the contract.
- **Findings**:
  - MINOR: test under-specifies the read behavior for dropped fields without defaults.

### bf3274df9 — Phase 9: shared string-transform givens
- **Scope**: Clean. Six new givens in `Schema.scala`, gate updates in `SerializationMacro` + `MacroUtils`. Matches spec.
- **Correctness**: PASS. Throwable round-trip documentation is accurate (always lowered to RuntimeException).
- **Conventions**: PASS.
- **Test quality**: ACCEPTABLE. "Throwable round-trip via getMessage" asserts `got.isInstanceOf[RuntimeException]` — but since the decode side ALWAYS produces RuntimeException, this is tautological. The load-bearing check is `getMessage == "boom"`, which IS present. Net: passes for the right reason.
- **Findings**:
  - POLISH: `regexSchema`'s decode-side `_.r` throws `PatternSyntaxException` on invalid wire input; not wrapped in `DecodeException`. Not in plan to validate, but a real edge case.

### 14c70b53a — Phase 10: JVM-only string-transform givens
- **Scope**: Clean. Cross-platform shadow files in `jvm/`, `js/`, `native/`. Spec said to mirror `AsciiStringFactory`; commit does that.
- **Correctness**: PASS. URL constructor switched to `URI(s).toURL` (deprecated `URL(String)` in JDK 25) — correct adaptation.
- **Conventions**: PASS. JVM-only types are isolated behind `PlatformSchemas`; user must import to enable.
- **Test quality**: PASS. Edge cases (URI query+fragment, BCP-47 with script and region, multi-segment Path, EUR currency) are real.
- **Findings**:
  - POLISH: `CodecJvmTest` uses `JsonWriter()` / `JsonReader()` directly while shared `CodecTest` uses the `Json.encode/decode` API. Style inconsistency.

### 9e52f0012 — Phase 11: java.time gap closure
- **Scope**: Clean. Eight givens + gate registration. Initial commit placed all tests in `shared/`.
- **Correctness**: PASS at JVM. Cross-platform regression on JS/Native (no tzdb for IANA zones / DST) caught and fixed in `9f4992da4`. Better caught pre-merge but the audit cleanup commit closes the gap.
- **Conventions**: PASS.
- **Test quality**: PASS — covers documented edge cases (DST spring-forward, DST fall-back, leap year, leap February, leap day).
- **Findings**:
  - MINOR (now resolved): JS/Native tzdb assumption shipped without verification, then caught by audit.

### eafa22904 — Phase 12: tuple ladder Tuple1, Tuple6..Tuple22
- **Scope**: Clean. 18 mechanical givens + 17 tuple symbols + drift-macro exclusion update.
- **Correctness**: PASS. Round-trip leaves for Tuple1/6/12/22.
- **Conventions**: PASS.
- **Test quality**: ACCEPTABLE. Only 4 representative arities tested; the gate-recognition test from Phase 1 catches the rest.
- **Findings**:
  - POLISH: `tupleSymbols` consistently uses `Tuple1[?]` / `Tuple6[?,?,?,?,?,?]` etc., but `MacroUtilsDriftMacro.excludedTycons` mixes `Tuple2` (no params) with `Tuple6[?,?,?,?,?,?]`. Equivalent at runtime but inconsistent style within one Set literal.

### 598ba3d37 — Phase 13: Array + ArraySeq + Queue + SortedSet + SortedMap
- **Scope**: Clean. Five givens + symbol-set updates.
- **Correctness**: ATTENTION. The plan's `sortedMapSchema` was specified as `mapSchemaWithKeyCodec[K,V].transform(...)` (reuses Phase 8 object encoding when a KeyCodec is available). Commit implements its OWN array-of-pairs encoder for SortedMap unconditionally. This is a behavioural departure: a `SortedMap[Int, V]` will encode as `[[1,...],[2,...]]` even though `Map[Int, V]` encodes as `{"1":...,"2":...}`. The commit body acknowledges this consciously. Asymmetry between Map and SortedMap is real-world surprising; spec was explicit; agent unilaterally departed.
- **Conventions**: PASS.
- **Test quality**: PASS for the 5 round-trips.
- **Findings**:
  - MAJOR: SortedMap encoding diverges from spec without supervisor sign-off. Spec said object form with KeyCodec, fallback to array-of-pairs; commit hard-codes array-of-pairs.
  - MINOR: same `discard(reader.hasNextElement())` redundancy as Phase 8.

### 8766af3be — Phase 14: Java enum derivation
- **Scope**: Clean. FocusMacro + ExpandMacro + SerializationMacro branches added; new JavaEnumTest.
- **Correctness**: PASS. Spec's `java.lang.Enum.valueOf(cls, name)` approach replaced with `Class.getMethod("valueOf", classOf[String]).invoke(null, name)` reflection — commit body explains the polymorphic-type bound issue. Adds `!Flags.JavaStatic` guard that the spec missed (would have fired on each enum constant).
- **Conventions**: PASS. Wraps `IllegalArgumentException` into `UnknownVariantException` so `Json.decode` yields `Result.Failure` not `Result.Panic`.
- **Test quality**: PASS. Negative test asserts class+message+variantName, no tautologies.
- **Findings**: None.

### bb3bdb7fd — Phase 15: Scala 3 union type derivation A | B
- **Scope**: BLOATED beyond spec, but legitimately. Spec's `derive` sketch used "try-each read with reader.replay"; commit implements wrapper-format (`{"L_i": <legValue>}`) with field-name dispatch. Wrapper format is genuinely better than spec — composes with `.discriminator(...)` via the existing flatten path. New `UnionMacroProxy` trampoline added inside `FocusMacro.scala` to defer `UnionMacro` loading until the call fires (avoids NoClassDefFoundError during Schema.scala's own compilation). Reasonable expansion.
- **Correctness**: ATTENTION.
  - The read catch is `case t: Throwable if !dispatched`, which silently swallows `Throwable` (including control-flow types like `InterruptedException`, `ControlThrowable`) and rewraps as `TypeMismatchException`. Kyo convention typically lets fatal/control types through. Minor.
  - The `dispatched = true; resultRef = readFrom(...)` ordering correctly distinguishes "dispatch failed" (rewrap) from "leg decode failed" (rethrow verbatim).
  - The `String | Nothing` reduction path correctly delegates to the single remaining leg's Schema.
- **Conventions**: PASS. `Frame.internal` in macro-emitted lambdas matches Phase 4 precedent; annotated by audit commit.
- **Test quality**: ATTENTION.
  - "Schema.derived[Foo | Foo] reduces (or fails) cleanly" — spec required a compile error naming "duplicate" or "degenerate"; the test accepts EITHER a compile error OR a clean compile. The agent justified empirically (Scala 3 dedups at the type level), but this WEAKENS the spec's negative contract. If a future change causes the gate to silently accept other degenerate shapes, this test passes anyway.
  - 12 leaves matches the spec's 12 (counting `Schema[Foo | Bar]` discriminator as one rather than spec's "tagged" leaf — close enough).
- **Findings**:
  - MAJOR: "degenerate union" test accepts both outcomes; weakens the spec's compile-error contract.
  - MINOR: `catch case t: Throwable if !dispatched` swallows fatals.
  - MINOR: `UnionMacroProxy` lives in the same file as `FocusMacro` (correctly justified by sbt batch ordering, but the file mixes the proxy with FocusMacro's other content).

### f2873dfc6 — Phase 16: intersection type rejection
- **Scope**: Clean. One branch in `FocusMacro.derivedImpl`, two negative tests.
- **Correctness**: PASS.
- **Conventions**: PASS.
- **Test quality**: PASS — two separate `typeCheckFailure` leaves (rather than the spec's combined one) is a mild strengthening.
- **Findings**: None.

### 9626b3484 — Phase 17: scaladoc
- **Scope**: Clean. Pure doc.
- **Correctness**: PASS.
- **Test quality**: N/A.
- **Findings**:
  - POLISH: opaque-type example `Schema.stringSchema.transform[Email](identity)(identity)` is only valid because the example happens to be inside `object Email`; external readers may not realise the scoping is load-bearing. Could be clearer.

### 9f4992da4 — Audit cleanup
- **Scope**: Clean. Tzdb fix (Phase 11) + 32 `Frame.internal` site annotations + JVM-only test moves.
- **Correctness**: PASS. New JVM-only tests preserve coverage that the shared tests had to drop.
- **Conventions**: PASS. Each `Frame.internal` site now carries the `// Unsafe:` block comment per STEERING.
- **Test quality**: PASS.
- **Findings**: None.

## Cross-commit consistency

- **Phase 4 / 4b / 4c are consistent**: same `SchemaSerializer.writeTo`/`readFrom` substitution pattern, same Frame strategy (writes `Frame.internal`, reads `reader.frame`). Good.
- **Phase 5 was committed BEFORE Phase 1** in chronological order, although Phase 5 depends on Phase 4 (which is before both). This is technically allowed by the dependency DAG but breaks the plan's ascending-phase intent. No code consequence; ordering hygiene only.
- **Phase 7 was committed BEFORE Phase 4c** in chronological order. Phase 7 doesn't depend on Phase 4c, so OK.
- **Phase 11 missed cross-platform validation** at commit time and was fixed by the audit cleanup. The Phase-11-as-shipped commit body claimed JS+Native compile clean but the runtime tzdb-zone tests would have failed on those platforms.
- **`Frame.internal` annotations** added retroactively (audit commit) rather than as each phase landed; consistent post-hoc.
- **`discard(reader.hasNextElement())` redundancy** appears in Phases 8 and 13 (both `mapPairsSchema` and `sortedMapSchema`). Consistent only in being equally unexplained.
- **Drift-test scaffolding** is consistent across Phase 1 (`SerializationMacroDriftMacro`) and Phase 3 (`MacroUtilsDriftMacro`); each tracks its own concern.
- **Tuple symbol-set style inconsistency** (Phase 12): `Tuple2`/`Tuple3` (no params) mixed with `Tuple6[?,?,?,?,?,?]` (explicit) in `MacroUtilsDriftMacro.excludedTycons` — works at runtime, looks accidental.

## Performance assessment

- **Phase 5 macro change**: every case-class decode on EVERY wire format now allocates an `Array[Int]` and a `Map[Int, String]` and runs an O(n) zip-loop, even when the resulting map is discarded by the default `withFieldNames` no-op (JSON, StructureValue). This is the largest performance regression in the branch; spec didn't address it. Could be lazy-allocated or pushed into a Protobuf-only emission path.
- **Phase 4 / 4b / 4c**: each non-primitive field / collection element gains one `hasTransforms` field-read and one branch. The `hasTransforms` short-circuit at `SchemaSerializer.scala:21` makes the no-transform default path essentially free. Acceptable.
- **Phase 7 / 8**: KeyCodec's failed `decode` allocates `Json()` each call. Minor unless key decode is on a hot path.
- **Phase 14**: Java enum derivation reflects `Class.forName` + `getMethod("valueOf", ...)` once per derived `Schema.init[A]` call (captured at schema-creation time), then closes over `valueOfMethod` for reads. No per-call reflection. Good.
- **Phase 15**: union write is an `isInstanceOf` chain (O(n) legs); read is field-name match (O(n)). Both linear in number of legs; acceptable for typical 2-3 leg unions.

## Overall verdict

- BLOCKER findings: 0
- MAJOR findings: 3 (Phase 5 per-decode allocation regression; Phase 13 SortedMap encoding diverges from spec; Phase 15 degenerate-union test weakened to accept either outcome)
- MINOR findings: ~12 (test under-specification on Phase 4c dropped-field reads; ParseException allocates Json instance per call; `withFieldNames` set-vs-merge semantics change; `discard(reader.hasNextElement())` redundancy in Phase 8/13; Phase 15 fatal-swallowing catch; tuple-symbol-set style inconsistency; Phase 3 drift label oversold; Phase 1 commit ordering outside plan layering; Phase 11 missed JS/Native validation pre-commit; CodecJvmTest style inconsistency; KeyCodec public-package exposure; UnionMacroProxy file co-location)
- POLISH findings: ~3 (regexSchema invalid-input not wrapped; opaque-type scaladoc example clarity; minor stylistic mixes)

Bottom line: the branch is shippable with two follow-up clarifications: (1) confirm the Phase 5 allocation cost is acceptable or push the map materialisation behind a wire-format hint; (2) decide whether SortedMap should match Map's encoding (spec) or stay array-of-pairs (commit) — and update either the plan or the implementation. Phase 15's degenerate-union test should either compile-error or accept-and-assert, not both. All other findings are minor polish or future-work tickets that don't block the deliverable. Phases 1-4-4b-4c successfully close Bug D end-to-end (case-class field, collection-given, and sealed-trait-variant dispatch sites all routed through transform-aware paths); Phase 5 closes Bug E; Phases 9-14 fill the long tail without surprises; Phase 15 introduces real new derivation surface with sound macro engineering despite some test-rigor slippage.
