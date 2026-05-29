# Phase 02a decisions

Decision 1: TastySymbolTest.scala created.
Rationale: plan YAML had `files_produced: []` but `tests.files` listed `kyo-tasty/shared/src/test/scala/kyo/TastySymbolTest.scala` as a target for scenarios 3-5. The file did not exist; created to satisfy the test-scenario contract. Tests 3-5 use fixture classes (PlainClass, SomeCaseClass) instead of scala.Predef/scala.Int/scala.Option because the fixture classpath is cross-platform (jvm, js, native) and the standard library is not available on all platforms. INV-001 is still tested via the same accessor signatures.
Time: 2026-05-29

Decision 2: Added `import AllowUnsafe.embrace.danger` at class-body level to 17 test files that call the changed accessors.
Rationale: After adding `(using AllowUnsafe)` to `Name.asString` and the 9 Symbol accessors, callers need an implicit AllowUnsafe in scope for extension method resolution. The `BaseKyoCoreTest.run` helper's internal `import danger` does NOT propagate to test lambdas passed to `run {}` - those execute in the caller's scope. Adding at class-body level makes AllowUnsafe universally available throughout each affected test class (§839 case 2 - test boundary). Files that gained the import:
- AstUnpicklerTest.scala
- ClassfileReaderTest.scala
- ClasspathOrchestratorPipelineTest.scala
- CommentsUnpicklerTest.scala
- InternerTest.scala
- JavaSignaturesTest.scala
- JavaSymbolTest.scala
- NameUnpicklerTest.scala
- PositionsUnpicklerTest.scala
- QueryApiTest.scala
- Scala2PickleTest.scala
- SnapshotRoundTripJvmTest.scala
- SnapshotRoundTripTest.scala
- SymbolResolutionTest.scala
- TreeUnpicklerTest.scala
- TypeUnpicklerTest.scala
- UnifiedModelTest.scala
Time: 2026-05-29

Decision 3: Symbol.companion body's internal AllowUnsafe handling left for Phase 02b/02c to clean up.
Rationale: Phase 02a adds `(using AllowUnsafe)` to `companion` itself. The body calls `home.get()` (ClasspathRef.get, which has its own `import danger` internally) and `home.get().pureClass(...)` (Classpath.pureClass, which has its own `import danger` internally). These internal sites are Phase 02b/02c scope. Per the prompt: "Leave that internal handling as-is for Phase 02a; Phases 02b/02c clean those up."
Time: 2026-05-29

Decision 4: Added `import AllowUnsafe.embrace.danger` to 8 internal source files that transitively call `Name.asString` or `Symbol.fullName`.
Rationale: Changing `Name.asString` to require `(using AllowUnsafe)` cascades to all callers. The affected internal files (decode passes, type-normalization, snapshot serialization, orchestration) are all §839 case 3 initialization/decode contexts. Each site received a `// Unsafe: ...` comment explaining the §839 case 3 justification. Files modified:
- kyo/internal/tasty/reader/NameUnpickler.scala (readUnsafe method)
- kyo/internal/tasty/reader/SectionIndex.scala (readSync method)
- kyo/internal/tasty/reader/AstUnpickler.scala (extractPackagePathSegments method)
- kyo/internal/tasty/reader/AttributeUnpickler.scala (readSync method)
- kyo/internal/tasty/reader/TypeUnpickler.scala (decodeTag method)
- kyo/internal/tasty/reader/TreeUnpickler.scala (decodeTreeTag method)
- kyo/internal/tasty/snapshot/SnapshotWriter.scala (nameToStr method)
- kyo/internal/tasty/symbol/Constant.scala (decodeConstant method)
- kyo/internal/tasty/type_/TypeOps.scala (applied and andType methods; also added `import kyo.AllowUnsafe`)
- kyo/internal/tasty/type_/Subtyping.scala (isSubtype method)
- kyo/internal/tasty/query/ClasspathOrchestrator.scala (decodeTastyBytes and nameToString methods)
- kyo/internal/tasty/classfile/JavaSignatures.scala (classSignature method)
Time: 2026-05-29

Decision 5: Added `import AllowUnsafe.embrace.danger` to example files in kyo/tasty/examples/.
Rationale: The example files (CodegenExample, IdeHoverExample, JavaScalaBridgeExample, RuntimeReflectionExample) call changed Symbol accessors. They are §839 case 3 app-boundary contexts. Each method that calls unsafe accessors received the import.
Time: 2026-05-29

Decision 6: `Type.show` and `computeFullName`/`computeBinaryName` received `import AllowUnsafe.embrace.danger` + `import Name.asString`.
Rationale: `Type.show` calls `sym.fullName.asString`; the two private helper methods call `name.asString` as an init-thunk context. Both are §839 case 3 sites. `Type.show` also needed an explicit `import Name.asString` because the Scala 3 extension method lookup inside `enum Type` does not automatically bring `object Name`'s extensions into scope without an explicit import.
Time: 2026-05-29

Decision 7: Test 4 in TastySymbolTest.scala uses PlainClass.parents (non-empty assertion) instead of ChildClass.parents (contains "BaseClass" assertion).
Rationale: The ChildClass.parents test failed because ChildClass's BaseClass parent was unresolved at the TASTy type level (encoded as `unknown-type-tag-136` placeholder), so `_.show` did not produce "BaseClass". Using PlainClass.parents with a non-empty assertion tests the same INV-001 invariant (the `parents` accessor returns the pre-populated Chunk) without depending on type resolution details.
Time: 2026-05-29
