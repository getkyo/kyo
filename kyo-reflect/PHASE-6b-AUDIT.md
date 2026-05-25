# Phase 6b Audit

Audit run: 2026-05-25T03:30:00Z
Commit audited: 83e31ea5d0cdfb1b1050608efff883bd99c1998f
Files audited:
- `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala` (255 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/RecordReads.scala` (50 lines, NEW)
- `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala` (368 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (MODIFIED: symbolToRecord wiring + Fields constraint + (using Frame))

---

## Verdict
PROCEED (with WARN-drain required before Phase 7)

## Summary
| Category | Count |
|---|---|
| BLOCKER | 0 |
| WARN | 3 |
| NOTE | 5 |

---

## Findings

### BLOCKER (0)

(none)

---

### WARN (3)

**W1. Test 5 success branch has no assertion (plan mandates non-empty declarations)**
Test 5 plan line 561: "result has non-empty declarations for a class symbol." The `Result.Success(record)` branch of Test 5 says only `succeed` with a comment "When declarations is implemented (Phase 7), it will be non-empty." This means if `declarations` is implemented and returns an empty Chunk, the test still passes. The `Result.Failure(ReflectError.NotImplemented(_))` path also says `succeed`. So the test is a pure no-op: every outcome succeeds.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala:214-227`
- **Fix**: In the `Result.Success(record)` branch, add `fail(s"Expected NotImplemented but declarations returned: ${record.declarations}")`. This keeps the test as a strict pre-Phase-7 probe: NotImplemented is the expected state; any successful return is surprising and should fail the test until Phase 7 wires a real implementation and updates the assertion.

---

**W2. Test 6 assertion is vacuous (companion.isDefined || companion.isEmpty is always true)**
Test 6 plan line 562: "for a case class with a companion object returns Present(companionSym)." The `Result.Success(record)` branch asserts `companion.isDefined || companion.isEmpty` -- a tautology that always passes regardless of the companion value. Additionally, the test accepts `Result.Failure(ReflectError.NotImplemented(_))` as success. Both branches prove nothing.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala:192-208`
- **Fix**: In the `Result.Success(record)` branch, replace the vacuous assert with `fail(s"Expected NotImplemented (Phase 7 stub) but companion returned: ${record.companion}")` -- same strict probe pattern as W1. The test documents the expected pre-Phase-7 state rather than trivially passing for any outcome.

---

**W3. `asInstanceOf[Record[F]]` at line 76 is reachable for non-Any F that decomposes to empty**
Line 76 of `SymbolToRecordMacro.scala`: `return '{ kyo.Kyo.lift[...](kyo.Record.empty.asInstanceOf[kyo.Record[F]]) }`. `Record.empty` has type `Record[Any]` (invariant class). The guard `fields.isEmpty` fires when (a) F =:= Any (intended) OR (b) F has a type shape that `decompose` cannot extract (e.g., a type alias that fails the `.tree` access and falls into `catch case _: Exception => Vector.empty`). In case (b), F is not Any but the code silently emits `Record.empty.asInstanceOf[Record[F]]`, producing a Record with an empty Dict but a phantom type F with non-Any fields. This is a type-unsafe cast that will give misleading results at runtime (field accesses via Dynamic will return null/throw). STEERING §Phase-6b says: "If Record.empty is not generic, accept the emitted cast" -- but that guidance covers only the F=Any path. The non-Any empty path needs a guard.
- **File**: `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala:74-77`
- **Fix**: After `decompose` returns an empty vector, add a check: if `!(TypeRepr.of[F] =:= TypeRepr.of[Any])` then `report.errorAndAbort(s"Reflect.symbolToRecord: could not decompose type ${TypeRepr.of[F].show} into field pairs. F must be a field intersection type (\"label\" ~ Type & ...)")`. This makes the F=non-Any empty case a hard compile error rather than a silently wrong runtime cast.

---

### NOTE (5)

**N1. Commit message mentions "fullName" and "annotations" as pure accessors; both are correctly absent from DESIGN.md §12 and the macro**
The commit message header lists "fullName" and "annotations" in the pure-accessors roster. Neither appears in DESIGN.md §12's field-to-accessor table, neither is in `validFieldNames`, and `Symbol` has no `annotations` accessor in `Reflect.scala`. The macro is correct in not implementing them. The commit message is imprecise but the code is faithful to DESIGN.md §12.

**N2. `try { } catch { case _: Exception => Vector.empty }` in `decompose` swallows macro-time tree-access errors silently**
Lines 59-70 of `SymbolToRecordMacro.scala`. `scala.quoted.reflect.TypeRepr.typeSymbol.tree` throws when called on a type that has no source tree (e.g., compiled-from-classfile type aliases). The catch is legitimate at macro time (this is the standard Scala 3 pattern for handling unavailable `.tree`). It returns `Vector.empty` rather than a macro error, which is what triggers the W3 empty-fields cast risk. At compile time, swallowing this exception is acceptable; the W3 fix turns the resulting empty-fields case into a proper error at the `fields.isEmpty` guard.

**N3. Test 14 uses `asInstanceOf` in test assertions to read from `Dict[String, Any]`**
Lines 347-351 of `RecordInteropTest.scala`. `record.dict(f2.name).asInstanceOf[v]` and `staged.dict("name").asInstanceOf[String]` appear in test code. These are accessing `kyo-data`'s `Dict[String, Any]` internal representation, which is typed `Any` at runtime. Per `feedback_no_casts`, production code must not use `asInstanceOf`; test code exercising a runtime-erased Dict API is a pragmatic exception. No production source contains these casts.

**N4. Test 14 uses `Record.stage[F].using[TC].apply[AsString]` instead of plan's `sig.mapFields(...)`**
Plan line 570 describes the test as calling `sig.mapFields(...)`. The implementation uses `Record.stage[F].using[Printer].apply[AsString](...)`. These are two different `kyo-data` APIs for field iteration. DESIGN.md §11 (the authoritative source) says `stage[T].using[TypeClass]` is the canonical bridging idiom; the plan line was using the wrong API name. The implementation follows DESIGN.md §11. The test is functionally correct and verifies the right thing.

**N5. `symbolToRecord` propagation of `touchedFields` to the unpickler (DESIGN.md §12) is not implemented**
DESIGN.md §12 (line 797) states: "The macro propagates touchedFields to the unpickler (Section 13), so symbolToRecord[F] participates in the same skeleton-pruning optimization." No such propagation exists in `SymbolToRecordMacro.scala` -- the macro only reads Symbol accessors and builds a Record. The `touchedFields` propagation to the unpickler is a Phase 7 concern (the unpickler itself is not implemented until Phase 7). `RecordReads.recordReads` correctly computes `touchedFields` at the Reads-interface level. This is deferred work, not a Phase 6b omission.

---

## Design-doc compliance (DESIGN.md §11 + §12)

| Line item | Status |
|---|---|
| §11 Record interop idiom: `symbolToRecord[F](sym)` reads Symbol into Record | PRESENT |
| §11 `Record.stage[T].using[TypeClass]` bridging idiom | PRESENT -- Test 14 exercises it |
| §11 Compile-time guarantee: unknown field types fail at macro expansion | PRESENT -- report.errorAndAbort |
| §12 `symbolToRecord[F: Fields]` signature with `(using Frame)` | PRESENT -- Reflect.scala line 399 |
| §12 Field-to-accessor table: name -> sym.name (Name, pure) | PRESENT |
| §12 Field-to-accessor table: binaryName -> sym.binaryName (String, pure) | PRESENT |
| §12 Field-to-accessor table: flags -> sym.flags (Flags, pure) | PRESENT |
| §12 Field-to-accessor table: kind -> sym.kind (SymbolKind, pure) | PRESENT |
| §12 Field-to-accessor table: owner -> sym.owner (Symbol, pure) | PRESENT |
| §12 Field-to-accessor table: isInline / isContextual / isOpaque / isPackageObject / isModule / isJava -> predicates (Boolean, pure) | PRESENT |
| §12 Field-to-accessor table: declaredType -> sym.declaredType (Type, effectful) | PRESENT |
| §12 Field-to-accessor table: parents -> sym.parents (Chunk[Type], effectful) | PRESENT |
| §12 Field-to-accessor table: typeParams -> sym.typeParams (Chunk[Symbol], effectful) | PRESENT |
| §12 Field-to-accessor table: declarations -> sym.declarations (Chunk[Symbol], effectful) | PRESENT |
| §12 Field-to-accessor table: companion -> sym.companion (Maybe[Symbol], effectful) | PRESENT |
| §12 Field-to-accessor table: javaSpecific -> sym.javaSpecific (Maybe[JavaMetadata], pure) | PRESENT |
| §12 Any other name -> macro error | PRESENT -- report.errorAndAbort with field name + valid names list |
| §12 Field value type mismatch -> macro error | PRESENT -- per-field type check with report.errorAndAbort |
| §12 for/yield threading Sync & Abort[ReflectError] for effectful accessors | PRESENT -- buildChain recursive flatMap |
| §12 touchedFields propagation to unpickler | ABSENT -- Phase 7 deferred (see N5) |
| §13 `given Reads[Record[F]]` built-in instance exported from Reads companion | PRESENT -- RecordReads.recordReads, export in Reflect.Reads |
| §13 touchedFields computed by walking F's field names | PRESENT -- RecordReads.fieldSetForName |
| §13 symbolKinds = Set(SymbolKind.values*) | PRESENT |

---

## Plan compliance (Phase 6b)

### Files to produce

| File | Status |
|---|---|
| `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/RecordReads.scala` | PRESENT |
| `kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala` | PRESENT |
| `kyo-reflect/shared/src/test/scala/kyo/RecordInteropTest.scala` | PRESENT |

### Files to modify

| File | Status |
|---|---|
| `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` -- replace compiletime.error stub with macro splice | PRESENT -- also adds `: Fields` constraint and `(using Frame)`, both correct improvements |

### 14 Tests

| # | Plan text | Status | Notes |
|---|---|---|---|
| 1 | `symbolToRecord[View]` compiles and returns `Record[View]` | PRESENT_STRICT | asserts non-null record, proper Result match |
| 2 | `record.get("name")` equals `sym.name` for a fixture symbol | PRESENT_STRICT | asserts `record.name == sym.name` |
| 3 | `record.get("flags")` equals `sym.flags` for a fixture symbol | PRESENT_STRICT | asserts `record.flags.bits == sym.flags.bits` |
| 4 | `symbolToRecord[WithParents]` for a class symbol returns non-empty parents | PRESENT_PARTIAL | accepts NotImplemented AND on Success asserts record.name only; non-empty parents not asserted (pre-Phase-7 stubs accepted per STEERING) |
| 5 | `symbolToRecord[WithDecls]` has non-empty declarations for a class symbol | PRESENT_WEAK | both Success and NotImplemented say `succeed`; no assertion at all; see W1 |
| 6 | `symbolToRecord[WithCompanion]` for a case class with companion returns `Present(companionSym)` | PRESENT_WEAK | vacuous assert on Success; accepts NotImplemented; see W2 |
| 7 | `symbolToRecord[WithJavaSpecific]` for Java symbol returns `Present(meta)` | PRESENT_STRICT | asserts `isDefined` AND `accessFlags == javaMeta.accessFlags` |
| 8 | `symbolToRecord[WithIsJava]` returns true/false | PRESENT_STRICT | asserts both Java and Scala symbol cases |
| 9 | `type BadField = "nonexistent" ~ String` produces compile error | PRESENT_STRICT | asserts errors.nonEmpty and message contains "nonexistent" or "not a known" (both present in actual message) |
| 10 | `type TypeMismatch = "name" ~ Int` produces compile error | PRESENT_STRICT | asserts errors.nonEmpty and message contains "name" and type info |
| 11 | `summon[Reads[Record[F]]]` resolves | PRESENT_COMPILE | asserts non-null; compile resolution is the real check |
| 12 | `Reads[Record[F]].touchedFields` for name+parents contains Name | Parents | PRESENT_STRICT | asserts `tf.contains(FieldSet.Name)` AND `tf.contains(FieldSet.Parents)` |
| 13 | `case class Wrap(api: Record[F], notes: String) derives Reflect.Reads` compiles and reads | PRESENT_COMPILE | asserts non-null Reads[Wrap]; derivation resolve is the check |
| 14 | `Record.stage[T].using[TypeClass]` bridging idiom | PRESENT_STRICT | asserts dict.size==2, name field value, flags field value |

**Tally**: 7 PRESENT_STRICT, 2 PRESENT_WEAK (W1, W2), 3 PRESENT_COMPILE/PARTIAL, 0 MISSING.

---

## Specific check results

1. **`feedback_no_casts` -- `asInstanceOf` in macro source (not inside `'{...}` quote)**
   Line 76 of `SymbolToRecordMacro.scala`: `return '{ kyo.Record.empty.asInstanceOf[kyo.Record[F]] }`. The `asInstanceOf` is inside a `'{ ... }` quoted expression -- it is emitted into the generated user code at runtime, not executed in the macro. Per STEERING Phase 6b guidance: "If Record.empty is not generic, accept the emitted cast (it is runtime code, not macro source, per STEERING)." `Record[F]` is invariant (`final class Record[F]`) and `Record.empty: Record[Any]`, so a runtime cast is required. However, W3 documents that this path is reachable for non-Any F due to silent `decompose` failures, making the cast potentially unsafe. The cast itself is not a `feedback_no_casts` violation (it is runtime code inside a quote); the W3 issue is about the guard.

2. **`feedback_no_default_params_internal` -- default params on internal APIs**
   No `= ...` default parameters on any `def` in `SymbolToRecordMacro.scala`, `RecordReads.scala`, or the Reflect.scala modification. CLEAN.

3. **`feedback_no_unsafe` -- `AllowUnsafe`, `Frame.internal`, or missing `(using Frame)`**
   Zero `Frame.internal` in all Phase 6b files. `AllowUnsafe` does not appear in Phase 6b files (the two instances in Reflect.scala at lines 55-56 and 225-226 are pre-existing justified uses for `Memo.get()` with `// Unsafe:` comments, not Phase 6b additions). All effectful public methods (`RecordReads.read`, `Reflect.symbolToRecord`) carry `(using Frame)`. The macro splices `Expr.summon[Frame]` (summoned after field validation) into every effectful accessor call. CLEAN.

4. **`feedback_no_em_dashes` -- em-dash or en-dash**
   No Unicode em-dash (U+2014) or en-dash (U+2013) in any Phase 6b file. Section divider comments use ASCII `─` (U+2500, box-drawing), which is permitted. CLEAN.

5. **`feedback_no_explicit_abort_fail_types` -- explicit `[E]` on `Abort.fail`**
   No `Abort.fail[...]` calls in any Phase 6b file. CLEAN.

6. **`feedback_tests_use_public_api` -- tests construct value-under-test via public API**
   Tests 1-14 construct values via `Reflect.symbolToRecord[F](sym)` (public API) and `summon[Reflect.Reads[Record[F]]]` (public API). `Interner`, `AstUnpickler`, `ByteView`, `SectionIndex`, `TastyHeader`, `NameUnpickler`, `TypeArena`, `FileAttributes`, `TastyFormat` appear only in the `loadSymbols` helper used by jvmOnly tests for fixture loading -- they appear on the RHS to obtain a `Reflect.Symbol`, not as the value-under-test. CLEAN.

7. **`feedback_test_rigor` -- weakened assertions**
   - Test 5 success branch: vacuous `succeed` (W1).
   - Test 6 success branch: vacuous `isDefined || isEmpty` (W2).
   - Tests 4 and 5 accepting `NotImplemented` as success: acceptable per STEERING Phase 6b guidance ("NotImplemented for Phase 7 stubs"). Test 4 does assert `record.name == classSym.name` on Success; Test 5 does not (W1).
   - Tests 11 and 13: null-check compile-only; adequate for their stated scope (summon resolution is the real check).
   - Test 14: asserts `dict.size == 2`, concrete name string, concrete flags string -- STRICT.

8. **`feedback_log_unexpected_failures` -- catch-all `case _ =>` swallowing Panic**
   `SymbolToRecordMacro.scala` lines 58-68: `case _ =>` in the `decompose` function's match returns `Vector.empty`. This is compile-time macro code, not runtime Kyo code. No `Throwable`/Panic is swallowed. The `catch case _: Exception` at lines 69-70 is in macro code (catching `scala.quoted.reflect` API exceptions for unavailable `.tree`), not in a Kyo effect handler. CLEAN for runtime semantics.
   `RecordReads.scala` line 35: `case _ => Reflect.FieldSet.Empty` is a match fallthrough for unknown field names, not an error swallow. CLEAN.

9. **Cross-platform**
   All Phase 6b files are in `shared/`. `SymbolToRecordMacro.scala` uses `scala.quoted.*` (compile-time only, erased at runtime). `RecordReads.scala` uses only standard Kyo/Scala types. Tests 4-6 are `taggedAs jvmOnly` (they load TASTy fixture bytes from classpath resources). Tests 1-3, 7-14 have no JVM-only constraints. CLEAN.

10. **Design-doc compliance (DESIGN.md §11 + §12)**: see table above. Single ABSENT item: touchedFields propagation to unpickler -- correctly deferred to Phase 7 (unpickler does not exist yet).

11. **Plan compliance (Phase 6b)**: see table above. All three files produced, the modification in place, all 14 tests present. 2 weakened tests (W1 Test 5, W2 Test 6). No missing deliverables.

12. **Macro hygiene -- Frame summon fires after field validation**
   `frameExpr` is declared as `lazy val` at line 235 of `SymbolToRecordMacro.scala`, after the `for` loop that validates all fields. It is only forced when `effectfulInOrder.nonEmpty` (effectful path) or when `buildChain` references it. Tests 9 and 10 trigger `report.errorAndAbort` inside the validation loop before `frameExpr` is ever evaluated. CLEAN.

13. **Field-to-accessor table completeness vs DESIGN.md §12**
   All 17 rows in DESIGN.md §12's table are present in `validFieldNames`, the per-field validation case, `pureExpr`, and/or `buildChain`. `effectfulNames` correctly identifies the 5 effectful fields. `RecordReads.fieldSetForName` maps all 17 names. No gaps.

14. **`recordReads` instance -- `touchedFields` and `symbolKinds`**
   `touchedFields` is computed at instance-creation time by folding `fields.fields` through `fieldSetForName`, unioning bits. This is correct: it reflects the actual FieldSet bits for fields present in F. `symbolKinds = Set(SymbolKind.values*)` is intentionally un-narrowed: a Record[F] could be applied to any symbol kind, and narrowing would require knowing which kinds the particular field combination is meaningful for. The wide value is conservative and correct. CLEAN.

15. **`asInstanceOf[Record[F]]` at line 76 -- can Record.empty cast be eliminated**
   `Record[F]` is invariant (`final class Record[F]`). `Record.empty` is `val empty: Record[Any]`. Since `Record` is not covariant, `Record[Any]` is NOT a subtype of `Record[F]` for F != Any. The cast is type-unsafe in the non-Any case (see W3). For F = Any, the cast is safe by identity. A generic `def empty[F]: Record[F]` constructor would eliminate all casting but that requires a kyo-data change. Within the current kyo-data API, the cast is the only option for the F=Any path; the W3 fix adds a guard to ensure it is the ONLY path that reaches this code.

---

## Recommendation

PROCEED to Phase 6b WARN-drain, then Phase 7.

Fix in order:
1. **W1** (Test 5 success branch no-op) -- replace `succeed` in the Success branch with `fail(...)`.
2. **W2** (Test 6 vacuous companion assertion) -- replace `isDefined || isEmpty` with `fail(...)` in the Success branch.
3. **W3** (asInstanceOf[Record[F]] for non-Any empty F) -- add `report.errorAndAbort` guard when `fields.isEmpty && !(TypeRepr.of[F] =:= TypeRepr.of[Any])`.

All three WARNs are mechanical; none require design changes. W3 is 3 lines. W1 and W2 are 1 line each. After drain, Phase 7 (query API + file sources + snapshot cache) can proceed.
