# Phase 13 Audit

## Summary

Phase 13 is structurally sound. The six new tests compile cleanly against the correct public API, use the established synthetic-symbol pattern, stay in `shared/`, require no jvm-only tags, and correctly bridge `Annotation.unapply` (returns `Some`) via `Maybe.fromOption`. Two findings are worth routing forward: the T1 coverage remains thin because `declaredType`, `declarations`, `parents`, `typeParams`, `annotations`, and all `flags.contains` queries are untested in isolation, and the `makeNamed` helper is duplicated verbatim across `TastyTypeTest` and `TastyAnnotationTest`. Neither is a blocker for the commit.

## Findings

### 1. Public-API coverage for T1 - WARN

T1 states "thin Symbol API surface untested." The public Symbol surface from `Tasty.scala` is: `name`, `fullName`, `binaryName`, `kind`, `flags`, `parents`, `isPackageObject`, `declaredType`, `declarations`, `typeParams`, `body`, `annotations` (on `Annotation`), `scaladoc`, `position`, `companion`. Phase 13 adds direct tests for `binaryName` (2 cases) and `isPackageObject` (2 cases). Pre-existing tests cover `fullName`, `parents`, and `companion`. Still untested in any test file: `declaredType`, `declarations`, `typeParams`, `scaladoc`, `position`, `flags.contains(Flag.*)`, and `kind` as a direct assertion (kind appears only in fixture infrastructure). These are all documented in the T1 invariant and are left open. Phase 13 makes progress but does not close T1.

### 2. Test fixture realism - OK

`Tasty.Symbol.make` produces the same `Symbol` class instance as the production unpickler path. `binaryName` calls `Symbol.computeBinaryName(this)`, which only reads `name.asString` and `kind` from the owner chain. A hand-built symbol with the correct `name`, `kind`, and owner chain produces exactly the same binary name as a classpath-loaded symbol. The null-owner termination condition (`cur.owner != null`) is the same sentinel used by `computeFullName` and is documented in `phase-13-decisions.md`. No gaming.

### 3. Type.show test substance - OK

`Applied(Named(listSym), Chunk(Named(intSym))).show` executes the `Applied` branch: `s"${base.show}[${args.map(_.show).mkString(", ")}]"`, where each `Named(sym).show` delegates to `sym.fullName.asString`. `computeFullName` walks the chain `Class("List") -> Class("scala") -> Package("", null)`, filters empty, joins with `.` to produce `"scala.List"`. The test is not self-circular: the `show` method is in `Tasty.scala` production code, not the test; the construction is correct. Note: `makeNamed` assigns `SymbolKind.Class` to intermediate segments (e.g. `"scala"`) that would be `Package` in production. This does not affect `fullName` (which ignores kind) or `show`, so the test is correct for its stated purpose. However, it means the fixture cannot be reused to test `binaryName` for a package-terminated chain without modification.

### 4. Annotation extractor test - OK

`Annotation.unapply` is declared `def unapply(a: Annotation): Some[(Type, Chunk[Byte])]` (line 232 of `Tasty.scala`). The return type is `Some`, a stdlib type. `Maybe.fromOption(Some(...))` correctly produces `Present(...)`. The `Maybe.fromOption` wrapper is the right bridge for the Maybe-over-Option convention. The extractor is called directly (`Tasty.Annotation.unapply(a)`) rather than via pattern match syntax, which is intentional to make the extractor-call explicit for test readability.

### 5. Cross-platform discipline - OK

No `jvmOnly` tags in any of the three test files (confirmed by grep). Tests 1-4 use `Tasty.Symbol.make` with in-memory owner chains. Test 5 uses `Tasty.Type.Applied`/`Named` constructors. Test 6 uses `Tasty.Annotation.apply` with `Chunk.empty`. None touches `ClasspathOrchestrator`, JAR reading, or any JVM-specific I/O. Placement in `shared/` is correct.

### 6. Code quality - WARN (minor)

`makeNamed` is duplicated verbatim in `TastyAnnotationTest` and `TastyTypeTest` (22 lines each, byte-for-byte identical). This is a NOTE-level duplication that could be extracted to a shared test utility in a future phase. No em-dashes found in the new test files. No semicolons at end of lines. No direct `Option`/`Some`/`None` usage in the Phase 13 additions themselves (the `Some`/`None` matches in lines 27-39 of `TastySymbolTest.scala` are pre-existing `MemoryFileSource` infrastructure from Phase 02a, confirmed by `git show d9983f6e3`). `AllowUnsafe.embrace.danger` is imported at class level in all three files; for `TastyAnnotationTest` and `TastyTypeTest` it is not strictly required by the test bodies (both `show` and symbol construction embrace internally), but it is harmless and consistent with the class-level pattern used in `TastySymbolTest`.

### 7. Test count claim - OK

Counting `"<test name>" in` strings across the three files: `TastySymbolTest.scala` has 7 total (3 pre-existing from Phase 02a, 4 new from Phase 13). `TastyTypeTest.scala` has 1. `TastyAnnotationTest.scala` has 1. New Phase 13 additions total 6. Claim verified. Note: the inline test labels in the new additions reuse numbers `Test 1` through `Test 4` that conflict with pre-existing `Test 3` through `Test 5` labels in the same `TastySymbolTest.scala`. This is a cosmetic inconsistency in the comment labels only; it does not affect test execution.

## Recommendations

- NOTE: Route to a later phase (T1 completion pass): add tests for `declaredType`, `declarations`, `typeParams`, and at least one `flags.contains(Flag.*)` assertion using synthetic symbols. These are the remaining untested axes of T1.
- NOTE: Extract the identical `makeNamed` helper from `TastyAnnotationTest` and `TastyTypeTest` into a shared test utility (e.g. `TastyTestHelpers` in `shared/src/test/`) to avoid drift when the helper needs updating.
- NOTE: Reconcile the `// Test N` comment numbering in `TastySymbolTest.scala` so Phase 13 additions use labels `Test 6` through `Test 9` (or simply reference the INV tag without a numeric label) to avoid confusion with the pre-existing Phase 02a tests in the same file.
