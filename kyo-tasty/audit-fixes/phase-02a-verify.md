# Phase 02a verify report

Run-id: phase-02a-verify-1
Phase: 02a (Propagate AllowUnsafe through Symbol accessors)
Feature dir: kyo-tasty/audit-fixes
HEAD: cc3028881 (Phase 01)
Baseline: kyo-tasty/audit-fixes/phase-02a-baseline.txt

Status: PASS

## Class-A gates (mechanical, commit-blocking)

- reward-hacking grep: 70 raw hits, 0 overridden, 70 classified-as-pre-existing
  - 6 hits in audit-fixes/ docs (05-plan.md, flow-validate-report.md), all PRE-EXISTING per baseline
  - 64 hits in source/test files (Tasty.scala "placeholder symbol" domain term, ClasspathOrchestrator/TypeUnpickler/Constant Rec/cycle-break placeholder vocabulary, "Phase 4" decode-phase comments) all on lines NOT introduced by Phase 02a (grep against diff confirmed)
  - PASS (no new reward-hacking introduced by 02a)

- fp-discipline grep: 259 raw hits, 0 overridden, 259 classified-as-pre-existing
  - Categories: unsafe-site (40), private-over-annotation (37), null-literal (36), bare-var (32), left-constructor (24), some-constructor (19), right-constructor (16), none-token (16), nondeterministic-time (10), juc-tree (7), bespoke-mutable-collection (5), unsafe-method-invocation (5), extension-owned-type (3), bare-println (3), isinstanceof (2), bare-system-print (2), local-val-over-annotation (1), either-token (1)
  - Phase 02a's only line additions are `import AllowUnsafe.embrace.danger`, `// Unsafe:` comments, `end <name>` markers, and the new TastySymbolTest/TastyTest INV-001 assertions
  - All pre-existing kyo-tasty patterns, not introduced by 02a (per prompt: classify per file as PRE-EXISTING vs introduced)
  - PASS (no new fp-discipline violations introduced by 02a)

- llm-tells grep: 10 raw hits, 0 overridden, 10 classified-as-pre-existing
  - 8 hits in audit-fixes/ planning docs (PRE-EXISTING baseline)
  - 2 em-dash hits in TypeUnpicklerTest.scala:281,289 (pre-existing comment text; only line added by 02a is the `import AllowUnsafe.embrace.danger`)
  - PASS

- dev-tag grep: 62 raw hits, 3 overridden, 59 classified-as-pre-existing
  - All "Phase N" comments refer to algorithmic decode phases (Phase 1 = collect symbols, Phase 4 = type resolution, Phase C = placeholder resolution), pre-existing in source
  - SnapshotRoundTripJvmTest.scala:78,121 "Phase 16" markers not introduced in 02a diff
  - PASS

- plan-diff: AUTHORIZED=2 PRE-EXISTING=4 SUPPORTING-CASCADE=33 DRIFT-FROM-IMPL=0 MISSING=0
  - AUTHORIZED: Tasty.scala (in plan files_modified), TastyTest.scala (in plan tests.files), TastySymbolTest.scala (in plan tests.files; new file produced)
  - PRE-EXISTING (4): kyo-tasty/audit-fixes/{05-plan.md, 05-plan.yaml, flow-validate-report.md, steering.md} — all in phase-02a-baseline.txt
  - SUPPORTING-CASCADE (33): every non-plan dirty file has ONLY `import AllowUnsafe.embrace.danger` additions, optional `// Unsafe: ...§839 case 3` comments, `import kyo.AllowUnsafe` package import, and required `end <name>` markers (mechanical Scala 3 block-scope consequence). Verified per-file via diff inspection: zero substantive logic changes outside the cascade.
  - Files in cascade: 17 test files (AstUnpicklerTest, ClassfileReaderTest, ClasspathOrchestratorPipelineTest, CommentsUnpicklerTest, InternerTest, JavaSignaturesTest, JavaSymbolTest, NameUnpicklerTest, PositionsUnpicklerTest, QueryApiTest, Scala2PickleTest, SnapshotRoundTripTest, SymbolResolutionTest, TreeUnpicklerTest, TypeUnpicklerTest, UnifiedModelTest, SnapshotRoundTripJvmTest), 12 internal source files (NameUnpickler, SectionIndex, AstUnpickler, AttributeUnpickler, TypeUnpickler, TreeUnpickler, SnapshotWriter, Constant, TypeOps, Subtyping, ClasspathOrchestrator, JavaSignatures), 4 example files (CodegenExample, IdeHoverExample, JavaScalaBridgeExample, RuntimeReflectionExample)
  - PASS (zero DRIFT-FROM-IMPL after FLOW response protocol classification)

- test-count: expected=5 actual=5
  - Plan tests.total=5 (5 leaves pinning INV-001)
  - New tests added by 02a: TastyTest gains 2 (signature regex + body-absence regex), TastySymbolTest is new with 3 (fullName.asString FQN, parents non-empty, companion Module Symbol)
  - PASS

- stowaway-commit: NONE
  - HEAD unchanged at cc3028881 (Phase 01 commit). Phase 02a dirty tree contains no new commits.
  - Manual verification (no impl-stdout log available; supervisor-identity match implicit)

- cross-platform:
  - JVM: PASS (sbt 'project kyo-tasty; Test/compile' green, 3 s warm)
  - JS: PASS (sbt 'project kyo-tastyJS; Test/compile' green, 31 s; one pre-existing E029 warning at QueryApiTest.scala:924 unrelated to 02a)
  - Native: PASS (sbt 'project kyo-tastyNative; Test/compile' green, 34 s; same pre-existing E029 warning)
  - Targeted test run: TastyTest + TastySymbolTest = 9 tests, all passing (4 Phase 01 + 2 Phase 02a in TastyTest, 3 in TastySymbolTest)

- invariants gate: PASS for INV-001
  - INV-001 status: OK (producer Phase 2 [equals 02a/02b/02c split], pre-consumer)
  - INV-001 satisfied by: (a) Tasty.scala signatures `def fullName(using AllowUnsafe)`, `def isPackageObject(using AllowUnsafe)`, `def scaladoc(using AllowUnsafe)`, `def position(using AllowUnsafe)`, `def declaredType(using AllowUnsafe)`, `def parents(using AllowUnsafe)`, `def typeParams(using AllowUnsafe)`, `def declarations(using AllowUnsafe)`, `def companion(using AllowUnsafe)`, `def asString(using AllowUnsafe)`; (b) no `import danger` inside the 10 migrated accessor bodies; (c) TastyTest source-text regex tests verify both invariants
  - Unrelated ledger ORDER-VIOLATIONs flagged (INV-003, INV-027) are pre-existing ledger-vs-plan numbering issues outside 02a scope

- Rule 8 organization: skipped per prompt direction (35 pre-existing layout violations, none introduced by 02a)

## Class-B findings (opus judgment)

None surfaced by the class-A regex catalogs that warrant escalation. The new test code in TastyTest reads source text via Files.readString and pattern-matches via regex; the pattern enumerates the 10 accessor names explicitly so the test fails if a new accessor is added without the `(using AllowUnsafe)` modifier. This is the intended INV-001 source-text pin per the plan.

## Overrides

No `// flow-allow:` annotations introduced in the Phase 02a diff. Three pre-existing dev-tag overrides remain in source comments outside 02a scope.

## Decision-log cross-check

phase-02a-decisions.md decisions reviewed against the diff:

- Decision 1 (TastySymbolTest.scala created): plan tests.files listed it; new file is AUTHORIZED.
- Decision 2 (17 test files gain class-body `import AllowUnsafe.embrace.danger`): SUPPORTING-CASCADE; per-file verified ONLY-import.
- Decision 3 (Symbol.companion body's internal AllowUnsafe deferred to 02b/02c): aligns with prompt scope note; the `(using AllowUnsafe)` on companion signature itself IS in this phase.
- Decision 4 (8 internal source files gain `import danger` + `// Unsafe:` comment): SUPPORTING-CASCADE.
- Decision 5 (4 example files gain `import danger` + `// Unsafe:` comment): SUPPORTING-CASCADE.
- Decision 6 (Type.show and computeFullName/computeBinaryName helpers): in-file changes to Tasty.scala (AUTHORIZED).
- Decision 7 (PlainClass.parents non-empty test substituted for ChildClass.parents BaseClass test): scope-preserved (still pins INV-001 via the `parents` accessor); acceptable test-design choice, not a weakening.

## Exit code: 0

All class-A gates PASS after FLOW response protocol classification. Substantive changes match plan files (Tasty.scala, TastyTest.scala, TastySymbolTest.scala). Non-plan dirty files are all SUPPORTING-CASCADE (33 files with only `import AllowUnsafe.embrace.danger` and supporting comments). Zero DRIFT-FROM-IMPL. INV-001 produced and source-text-pinned. Cross-platform JVM/JS/Native green. Targeted tests 9/9 green. Ready for supervisor commit.
