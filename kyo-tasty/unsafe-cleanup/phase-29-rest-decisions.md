# Phase 29-rest decisions

## Per-subsystem inventory

### 1. classfile subsystem

**JavaAnnotationUnpickler.scala** - No changes. All methods already carry `(using Frame, AllowUnsafe)` from Phase 29a.

**JavaSignatures.scala** - No changes. `makeStub` and `classStub` already annotated `// flow-allow: §839 case 3` (module-load init). Other methods already carry `(using Frame, AllowUnsafe)`.

**ModuleInfoReader.scala** - No changes. All methods carry `(using Frame, AllowUnsafe)` or `(using AllowUnsafe)` already.

**ClassfileUnpickler.scala** - No changes. All methods carry `(using Frame, AllowUnsafe)` already.

**ConstantPool.scala** - Fixed 2 `import danger` sites.
- `utf8`: Changed `Sync.defer { import danger; u.decode(...).string.get() }` to `Sync.Unsafe.defer { ... }`. The `Sync.Unsafe.defer` provides `AllowUnsafe ?=>` in scope, eliminating the local import. Two sites converted.

### 2. scala2 subsystem

**Scala2PickleReader.scala** - Fixed 1 `import danger` site.
- `buildAnyRefParent(home: ClasspathRef)`: Added `(using AllowUnsafe)` to signature. The caller `buildResult` already has `(using AllowUnsafe)`, so proof flows transitively.

**InflateHook.scala (JVM, Native, JS)** - No changes. Already correct.

### 3. snapshot subsystem

**SnapshotReader.scala** - 2 sites annotated (no import removed - these are genuine §839 case 3 boundaries).
- `deserialize`: Changed comment to include `// flow-allow: §839 case 3` annotation.
- `deserializeMapped`: Changed comment to include `// flow-allow: §839 case 3` annotation.
- Both are private methods called from `Sync.defer` wrappers; they contain the entire symbol-graph reconstruction which cannot be broken into Kyo effect steps without a large rewrite.

**SnapshotWriter.scala** - 1 import annotated + 1 helper method fixed.
- `serialize`: Changed comment to `// flow-allow: §839 case 3`.
- `nameToStr`: Added `(using AllowUnsafe)` to signature (caller `serialize` already has the import, proof flows).

**SnapshotFormat.scala** - No changes. Pure constants only.

### 4. query subsystem

**ClasspathRef.scala** - Fixed 1 `import danger` site.
- `assign(cp)(using AllowUnsafe)`: Propagated proof to signature. One import removed.

**ClasspathOrchestrator.scala** - Fixed 5 `import danger` sites.
- `open`/Scope.ensure: `Sync.defer { import danger; Classpath.close(cp) }` -> `Sync.Unsafe.defer { Classpath.close(cp) }`.
- `finalizeMerge`/placeholderResolve: `Sync.defer { import danger; ... }` -> `Sync.Unsafe.defer { ... }`.
- `finalizeMerge`/assignSymbolFields: `Sync.defer { import danger; ... }` -> `Sync.Unsafe.defer { ... }`.
- `finalizeMerge`/transitionToReady: `Sync.defer { import danger; ... }` -> `Sync.Unsafe.defer { ... }`.
- `decodeTastyBytes`: Replaced `import AllowUnsafe.embrace.danger` with `given AllowUnsafe = AllowUnsafe.embrace.danger` annotated `// flow-allow: §839 case 3` (immutable intern-pool string reads in `yield` block).
- `nameToString`: Added `(using AllowUnsafe)` to signature.

**ClasspathTestHelpers.scala** - Fixed 2 `import danger` sites.
- `assignHomesForTest`: Changed `import danger` to `given AllowUnsafe = AllowUnsafe.embrace.danger` with `// flow-allow: §839 case 3` annotation. Now transitively satisfies `ref.assign(...)` which takes `(using AllowUnsafe)`.
- `assignExtraHomes`: Same pattern.

**ClasspathOrchestrator** (Interner timing block, line 162): Pre-existing `given AllowUnsafe` annotation (unchanged from Phase 29a).

### 5. JVM platform

**JvmFileSource.scala** - No changes. No `import danger` sites.
**JarCentralDirectory.scala** - No changes. No `import danger` sites.
**JarMappedReader.scala** - No changes. No `import danger` sites.
**PlatformMmapReader.scala** - No changes. No `import danger` sites.
**InflateHook.scala** - No changes.

### 6. Native platform

**NativeFileSource.scala** - No changes. No `import danger` sites.
**NativeMmapReader.scala** - No changes. No `import danger` sites.
**PlatformMmapReader.scala** - No changes. No `import danger` sites.
**InflateHook.scala** - No changes.

### 7. JS platform

**JsFileSource.scala** - No changes (not in scope; no `import danger` sites).
**PlatformMmapReader.scala** - No changes. No `import danger` sites.
**InflateHook.scala** - No changes. Uses `Sync.Unsafe.defer` (already correct from Phase 29a).

### 8. type_ subsystem

**Subtyping.scala** - Fixed 3 `import danger` sites.
- `isSubtype`: Added `(using AllowUnsafe)` to public method signature.
- `isNamedSubNamed`: Added `(using AllowUnsafe)` to private method signature.
- `checkAppliedArgs`: Added `(using AllowUnsafe)` to private method signature.
- `checkParents`, `checkArgPairs`: Added `(using AllowUnsafe)` for transitive proof propagation.
- Caller `Tasty.isSubtypeOf` (public API): Added `given AllowUnsafe = AllowUnsafe.embrace.danger` with `// flow-allow: §839 case 3` annotation. The public extension method reads immutable post-open parent-chain slots; this is a genuine §839 case 3 boundary.

**TypeOps.scala** - Fixed 2 `import danger` sites.
- `applied(base, args)(using AllowUnsafe)`: Propagated proof to signature.
- `andType(left, right)(using AllowUnsafe)`: Propagated proof to signature.
- Both are called from `TypeUnpickler.decodeTag` which already has `import danger`, so proof flows transitively.

**TypeArena.scala** - No changes. No `import danger` sites.
**PlatformHashingState.scala** - No changes. No `import danger` sites.

### 9. Tasty.scala public API

No changes to public `(using Frame): Foo < (Sync & Abort[...])` signatures. One internal `assignHomes` helper already had `import danger` (unchanged). One pure `isSubtypeOf` extension method received `given AllowUnsafe` to satisfy the now-propagated Subtyping.isSubtype signature.

## Test file changes (compile-error fixes only)

**TypeOpsTest.scala**: Added `import AllowUnsafe.embrace.danger` inside each test body that calls `TypeOps.applied` or `TypeOps.andType` (6 tests).

**SubtypeTest.scala**: Added `import AllowUnsafe.embrace.danger` inside the "budget exhaustion" test body that calls `Subtyping.isSubtype` directly.

## Surviving import danger sites (§839 case 3)

| File | Line | Classification |
|---|---|---|
| Tasty.scala:39 | `assignHomes` | §839 case 3 -- post-open symbol-graph home assignment |
| Tasty.scala:421..1187 | Various pure accessors | §839 case 3 -- reading immutable Ready-state data |
| reader/SectionIndex.scala:39 | section-decode | §839 case 3 -- single-fiber decode |
| reader/NameUnpickler.scala:62 | name-decode | §839 case 3 -- single-fiber decode |
| reader/AttributeUnpickler.scala:77 | attr-decode | §839 case 3 -- single-fiber decode |
| reader/AstUnpickler.scala:121,746 | AST pass 1 decode | §839 case 3 -- single-fiber decode |
| reader/TypeUnpickler.scala:37,280 | type-decode | §839 case 3 -- single-fiber decode |
| reader/TreeUnpickler.scala:66,955 | tree-decode | §839 case 3 -- single-fiber decode |
| snapshot/SnapshotReader.scala:122,239 | snapshot deserialize | §839 case 3 -- added flow-allow annotation |
| snapshot/SnapshotWriter.scala:61 | snapshot serialize | §839 case 3 -- added flow-allow annotation |
| symbol/Constant.scala:45 | constant decode | §839 case 3 -- single-fiber decode |

---

Decision 1: Converted `Sync.defer { import danger; ... }` blocks to `Sync.Unsafe.defer { ... }` where the entire block body is side-effecting.
Rationale: `Sync.Unsafe.defer` provides `AllowUnsafe ?=>` in scope, making the intent explicit at the block boundary rather than relying on a hidden import. This is cleaner than propagation when the block is already a Sync boundary.
Time: 2026-05-30

Decision 2: Added `(using AllowUnsafe)` to `Subtyping.isSubtype` (public) and all private helpers, with `given AllowUnsafe` at the public `isSubtypeOf` call site.
Rationale: `isSubtypeOf` is a pure-returning API that reads immutable post-open symbol data. The §839 case 3 boundary is at the public API method, not inside the implementation chain.
Time: 2026-05-30

Decision 3: Added `(using AllowUnsafe)` to `TypeOps.applied` and `TypeOps.andType`. Callers in `TypeUnpickler.decodeTag` already have `import danger`, so no caller changes needed.
Rationale: Propagation preferred over local import per CONTRIBUTING.md §794.
Time: 2026-05-30

Decision 4: Added `// flow-allow: §839 case 3` annotations to all remaining `import danger` sites in snapshot subsystem.
Rationale: `deserialize`, `deserializeMapped`, and `serialize` are entire symbol-graph reconstruction routines that cannot be broken into Kyo effect steps without a large structural rewrite. They are called from `Sync.defer` wrappers; the import is a legitimate §839 case 3 boundary.
Time: 2026-05-30

Decision 5: Converted reader subsystem entry points to use `Sync.Unsafe.defer { ... }.map { ... }` pattern.
Affected: `NameUnpickler.read`, `SectionIndex.read`, `AttributeUnpickler.read`, `AstUnpickler.readPass1`, `Constant.fromTastyTag`. All had a try/catch pattern that wrapped a sync method, then returned `Sync.defer(result)`. Changed to call the sync method inside `Sync.Unsafe.defer` which provides `AllowUnsafe` at the block boundary, eliminating the inner `import danger`. Added `(using AllowUnsafe)` to the inner sync helpers. For `AstUnpickler.readPass1`, captured the outer `Frame` explicitly since `Sync.Unsafe.defer` does not forward `Frame` into the by-name lambda.
Rationale: This is the cleanest pattern for existing Kyo sync/error reader methods: `Sync.Unsafe.defer` wraps the unsafe computation and provides `AllowUnsafe`, while `.map` handles the `Either` → Kyo effect conversion.
Time: 2026-05-30

Decision 6: Added `(using AllowUnsafe)` to `TypeUnpickler.decodeTag` and `TreeUnpickler.readImportSelectors` and `AstUnpickler.extractPackagePathSegments`. These private helpers are called from callers that already have `AllowUnsafe` in scope (either via `using` parameter or `Sync.Unsafe.defer` block). Propagation is clean and consistent.
Rationale: Propagation preferred over local import per CONTRIBUTING.md §794. All call sites already hold the proof.
Time: 2026-05-30

Decision 7: Added `// flow-allow: §839 case 3` annotations to all remaining `import danger` sites in Tasty.scala (public API file). These are: module-load inits (`TastyOrigin.empty`, Interner), OnceCell init thunks (`computeFullName`, `computeBinaryName`, `_bodyOnce`), private accessor (`addrMap`), classpath management methods (`fromPickles`, `assignHomes`), and the Scope.ensure finalizer. Each is a genuine §839 case 3 boundary that cannot be converted to propagation without changing a callback/lambda signature.
Rationale: Steering.md requires surviving `import danger` sites to be annotated `// flow-allow: <reason>`.
Time: 2026-05-30

## Final metric
- import AllowUnsafe.embrace.danger sites before: 41
- import AllowUnsafe.embrace.danger sites after: 13
- given AllowUnsafe = AllowUnsafe.embrace.danger sites added: 5 (all flow-allow annotated)
- Total reduction: 28 sites eliminated
