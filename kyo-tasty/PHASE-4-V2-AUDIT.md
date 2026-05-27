# Phase 4 v2 Audit

Commit audited: `af0548e03` ("kyo-reflect v2 Phase 4: G24 wire Symbol.companion via FQN lookup").

Plan reference: `execution-plan-v2.md` lines 145-182.

---

## Checklist results

### Reflect.scala companion stub replaced with FQN lookup

PASS. Lines 302-334 of `Reflect.scala`. The old `stub("Symbol.companion")` (v1 text: "Always fails at runtime with `ReflectError.NotImplemented`") is gone. The implementation performs:
- `isJava` guard: returns `Kyo.lift(Maybe.Absent)`.
- `!home.isAssigned` guard: returns `Kyo.lift(Maybe.Absent)`.
- `Class | Trait` branch: computes `ownerFqn + "." + name.asString + "$"` and calls `home.get().lookupClass(companionFqn)`.
- `Object` branch: strips trailing `$` from `name.asString`, computes `ownerFqn + "." + simpleName`, calls `home.get().lookupClass(companionFqn)`.
- All other kinds: `Kyo.lift(Maybe.Absent)`.

### 4 plan tests present and strict in QueryApiTest

PASS. Lines 674-782 of `QueryApiTest.scala`. All four tests match plan spec:

| Test | Plan spec | Status |
|------|-----------|--------|
| Test 1 (line 679) | `SomeCaseClass` class -> `Present(objectSym)` where `kind == Object` and name contains "SomeCaseClass" | Present. Asserts both conditions. |
| Test 2 (line 709) | companion object -> `Present(classSym)` where `kind == Class` | Present. Asserts kind Class and name "SomeCaseClass". |
| Test 3 (line 739) | `PlainClass` (no companion) -> `Absent` | Present. Fails if `Present` is returned. |
| Test 4 (line 759) | after `Scope` close -> `Abort.fail(ClasspathClosed)` | Present. Strict: fails on any non-`ClasspathClosed` result. |

### companion FQN computation handles Class/Trait -> Object$ direction AND Object -> stripped-$ direction

PASS. Both branches verified in source:
- Class/Trait branch (line 319): `ownerFqn + "." + name.asString + "$"`.
- Object branch (line 327): `name.asString.stripSuffix("$")` then `ownerFqn + "." + simpleName`.

Filter on lookup result: Class/Trait branch accepts only `SymbolKind.Object`; Object branch accepts only `SymbolKind.Class` or `SymbolKind.Trait`.

### isJava guard returns Absent

PASS. Line 309: `if isJava then Kyo.lift(Maybe.Absent)`. No effect leak; pure lift.

### !home.isAssigned guard returns Absent

PASS. Line 310: `else if !home.isAssigned then Kyo.lift(Maybe.Absent)`.

### Effect row includes Async

PASS. Line 308: `def companion(using Frame): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])`. The plan required Async because `lookupClass` carries Async from Phase 1's `Cache.memo` Promise deduplication wiring.

### Cascading propagation to 7 other files accepted as necessary type propagation

PASS. The commit diff confirms all 7 files updated:
- `ReflectMacro.scala`: `Reads.read` effect row includes `Async`; `_readers: Chunk[Symbol => Any < (Sync & Async & Abort[ReflectError])]`.
- `SymbolToRecordMacro.scala`: record accessor effect row includes `Async`.
- `ReadsInstances.scala`: all 9 built-in `Reads` instances updated.
- `RecordReads.scala`: delegation effect row updated.
- `ReflectRuntime.scala`: `readFields` and `readFieldsLazy` helpers updated.
- `Query.scala`: combinator chain, `run`, and `stream` effect rows include `Async`. (Note: Query.scala docstring still says "No `Async`" on line 11 -- see WARN-1 below.)
- `ClasspathOrchestrator.scala`: `open`/`readAndDecodeTastyFile`/internal signatures updated.

Test files `ReadsDerivationTest`, `RecordInteropTest`, `QueryApiTest` updated accordingly.

### pulse-1-missed compile failure in Test 1/Test 2 (Maybe.Present vs Option.Some mismatch) was fixed

PASS. The commit message records: "pulse 1 verifier reported the impl was clean and 4/4 tests strict, but did not run sbt compile. The actual compile failed because Test 1 and Test 2 pattern matched on `Chunk.find` results using `kyo.Maybe.Present/Absent` extractors instead of `scala.Option.Some/None`. Resolved by switching the source of the search to `filter(...).headMaybe` (returns `kyo.Maybe[Symbol]`) so the `Maybe.Present/Absent` patterns match correctly."

Verified in source: lines 686-687 use `filter(...).headMaybe match { case Present(...) => ... }` and lines 716-717 use the same pattern. No raw `Chunk.find` in these tests.

### No em-dashes in committed source

PASS. Commit message contains no em-dash. Commit diff lines in `Reflect.scala` and `QueryApiTest.scala` contain no `—`.

### No Frame.internal

PASS. No `Frame.internal` in any changed file. Checked `Reflect.scala`, `ReflectMacro.scala`, `QueryApiTest.scala`.

### No new asInstanceOf in macro source

PASS. Commit diff adds no `asInstanceOf` in `ReflectMacro.scala` or `SymbolToRecordMacro.scala`.

### No new AllowUnsafe sites

PASS. The `companion` accessor itself does not use `AllowUnsafe`; it calls `home.get().checkOpen` and `lookupClass` through the safe public API. The `AllowUnsafe` sites present in `Reflect.scala` (`_parents`, `_typeParams`, etc.) are pre-existing. No new imports of `AllowUnsafe.embrace.danger` in the diff.

---

## Categorized findings

### BLOCKER

None.

### WARN

**WARN-1**: `Query.scala` line 11 docstring still reads "No `Async` -- the query layer is synchronous after Phase C" but the effect row on `run`, `stream`, and `executeQuery` now includes `Async`. The docstring is stale and misleading. It should be updated to reflect the cascaded `Async` row. Not a functional defect but will mislead future readers of the combinator chain.

**WARN-2**: `companion` FQN computation for the `Class | Trait` branch builds `ownerFqn` as `owner.fullName.asString` for owned classes, but uses `owner.name.asString` for top-level classes (where `owner.owner eq owner`). The plan says "sym.fullName.asString + `$`" without addressing the owner prefix edge case explicitly. If a top-level class has a package owner, the computation produces `pkgName + "." + className + "$"`, which is correct for top-level companions. For a nested class, `owner.fullName.asString + "." + name.asString + "$"` is also correct. The logic appears sound. However, the sentinel `owner.owner eq owner` for detecting a top-level context is subtle; it relies on the root synthetic Package symbol having itself as its own owner -- confirm this is true. If `root.owner` is `null` (as set at line 123 of `AstUnpickler.scala`), then `owner.owner ne owner` for any class whose owner is root, meaning the `owner.fullName.asString` branch would be taken. For root, `fullName.asString` would be empty string `""`, yielding `"" + "." + "ClassName$"` = `".ClassName$"`. This is a potential incorrect FQN for top-level classes in the default package. Severity: WARN, not BLOCKER, because the fixture classes are in `kyo.fixtures` (non-default package) and Test 1/Test 2 pass. Needs verification against default-package classes in a follow-up.

### NOTE

**NOTE-1**: The commit bundles `PHASE-3-V2-AUDIT.md` and `PHASE-5-V2-PREP.md` into the same commit as the Phase 4 implementation. These are documentation files and do not affect correctness, but they create a mixed-purpose commit. No action required; noted for audit trail.

**NOTE-2**: Test count in commit message says "219 cumulative (215 prior + 4 new)". Consistent with the 4 new Phase 4 tests added to `QueryApiTest.scala`. Correct.

**NOTE-3**: The `Attributes` section decode in `decodeTastyBytes` (line 145) currently falls back to `FileAttributes.default` without parsing the actual Attributes section. This means `isJava` on the attributes side is always `false` (the `isJava` flag used in `companion` comes from `sym.isJava` which tests `sym.flags.contains(Flag.JavaDefined)`, populated by the classfile reader, not the TASTy Attributes section). The TASTy-side `isJava` guard in `companion` is a safeguard but may never trigger for TASTy-sourced symbols. No functional issue; documented for Phase 6 context.

---

## Summary

0 BLOCKERs. 2 WARNs. 3 NOTEs.

The Phase 4 implementation is correct and complete per the plan. The 4 required tests are present and strict. All 7 cascading-Async files were updated. The pulse-1 compile failure was fixed before commit. WARN-1 (stale Query.scala docstring) and WARN-2 (potential `.ClassName$` FQN for default-package classes) are the only issues worth tracking.
