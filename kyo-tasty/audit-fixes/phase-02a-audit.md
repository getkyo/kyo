# Phase 02a audit

Time: 2026-05-29T20:56:12Z
HEAD: d9983f6e34550bb30e2b2a789b5e58aadf3f5066
Phase commit: d9983f6e34550bb30e2b2a789b5e58aadf3f5066
Plan cites: ./05-plan.md §Phase 02a (lines 89-238)
Design cites: ./02-design.md §"Symbol accessor signatures take `(using AllowUnsafe)`" (lines 16-69)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: TastyTest "every Symbol accessor signature carries (using AllowUnsafe)" | PRESENT_STRICT | TastyTest.scala:159; regex enumerates all 10 accessors; assert count == 10 |
| 2: TastyTest "no import AllowUnsafe.embrace.danger in any migrated Symbol accessor body" | PRESENT_STRICT | TastyTest.scala:173; 3-line window scan; OK because all 10 bodies are single-expression |
| 3: TastySymbolTest "Symbol.fullName.asString returns the dotted FQN for a fixture class" | PRESENT_STRICT (substituted fixture) | TastySymbolTest.scala:90; fixture PlainClass replaces plan's scala.Predef; INV-001 still pinned via the same accessor codepath |
| 4: TastySymbolTest "Symbol.parents for PlainClass returns a non-empty Chunk" | WEAKENED | TastySymbolTest.scala:112; plan asked for `.contains("scala.AnyVal")`; impl asserts `.nonEmpty` per phase-02a-decisions.md Decision 7 (BaseClass parent unresolved as `unknown-type-tag-136` placeholder). Still exercises the accessor and INV-001 surface, but the type-resolution dimension is no longer test-pinned at this phase |
| 5: TastySymbolTest "SomeCaseClass class-Symbol companion returns Module Symbol with kind Object" | PRESENT_STRICT (substituted fixture) | TastySymbolTest.scala:144; fixture SomeCaseClass replaces plan's scala.Option; companion-name check uses `.contains("SomeCaseClass")` rather than exact equality (fixture name may carry a `$` suffix) |

## CONTRIBUTING.md violations

- None introduced by this phase. All 10 migrated accessor bodies follow §828 option 1 (propagate-the-proof). Verified at `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:69, 565, 570-571, 581-583, 592-594, 611-615, 621, 627, 633, 643-678`.

## Unsafe markers

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:643-678` `Symbol.companion`
  Justification: comment at :648 documents "Unsafe: reading immutable Ready-state fqnIndex via AllowUnsafe; populated during open, before any user access." — acceptable; the body calls `home.get()` and `home.get().pureClass(...)`, both of which internally embrace. Phase 02b/02c will refactor those callees to take `(using AllowUnsafe)`, at which point the call site here will pass the proof through without additional changes. Cross-phase reference is clean.
- 33 cascade files each add a `// Unsafe: ... §839 case 3` comment at the embrace site. Spot-checked NameUnpickler:60, CodegenExample:37/47/53, JavaSignatures:155 — all carry a justification comment in the form documented in CONTRIBUTING.md §839.

## Cross-platform consistency

- Platforms checked: jvm, js, native (per phase-02a-verify.md, all PASS at compile + targeted-test level)
- Per-platform deltas: none. All migrated accessors live in shared; cascade additions are also shared except SnapshotRoundTripJvmTest.scala (legitimate jvm-only).

## Naming convention compliance

- No deviations. Accessor names unchanged; parameter clause `(using AllowUnsafe)` matches CONTRIBUTING.md §828 verbatim shape (no default, no aliasing).

## Steering deviation

- `git diff --name-only d9983f6e3~1 d9983f6e3` matches the plan's `files_modified` set (Tasty.scala) plus the 5 phase artifacts plus 33 SUPPORTING-CASCADE files. The cascade is documented exhaustively in phase-02a-decisions.md (decisions 2, 4, 5).
- One drive-by addition outside the strict "import-only" cascade contract: `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala` gained two `end <name>` markers (`end buildFacadeMethod`, `end renderFacade`) that were absent at HEAD~1. The `end` markers are not required by Scala 3 here; they are cosmetic. Logged as NOTE.

## Anti-flakiness measures

- INV-001 source-text tests use `Files.readString` + regex, deterministic and order-independent. The 3 fixture-classpath tests bound the test via Scope.run and assert against fixture bytes that travel with the test binary (`kyo.fixtures.Embedded.*`), no I/O.

## Architecture substitution check

- Design intent: "The accessor body unwraps the underlying `OnceCell` / `SingleAssign` storage directly; no `Sync.Unsafe.defer` wrap, no `import AllowUnsafe.embrace.danger` inside the body." (02-design.md:18)
- HEAD reality: all 10 accessor bodies at Tasty.scala:69, 565, 570-571, 581-583, 592-594, 611-615, 621, 627, 633, 643-678 are bare expressions over `.get()` / `.isSet` / `flags.contains` / `match` (companion). Zero `Sync.Unsafe.defer` wrap. Zero `import AllowUnsafe.embrace.danger` inside any of the 10 bodies. Zero default parameter for the AllowUnsafe proof.
- Verdict: MATCH.

## API surface integrity

- Return types verified at HEAD:
  - `def fullName(using AllowUnsafe): Name` (Tasty.scala:565) — Name, no leaked internal type. MATCH.
  - `def parents(using AllowUnsafe): Chunk[Type]` (:621) — Chunk[Type]. MATCH.
  - `def declarations(using AllowUnsafe): Chunk[Symbol]` (:633) — Chunk[Symbol]. MATCH.
  - `def declaredType(using AllowUnsafe): Type` (:611) — Type. MATCH.
  - `def scaladoc(using AllowUnsafe): Maybe[String]` (:581) — Maybe[String]. MATCH.
  - `def position(using AllowUnsafe): Maybe[Position]` (:592) — Maybe[Position]. MATCH.
  - `def companion(using AllowUnsafe): Maybe[Symbol]` (:643) — Maybe[Symbol]. MATCH.
  - `def typeParams(using AllowUnsafe): Chunk[Symbol]` (:627) — Chunk[Symbol]. MATCH.
  - `def isPackageObject(using AllowUnsafe): Boolean` (:570) — Boolean. MATCH.
  - `def asString(using AllowUnsafe): String` (:69, extension on Name) — String. MATCH.
- No `kyo.internal.tasty.*` types appear in any of the 10 public signatures.
- Verdict: PASS.

## Documentation drift

- Scaladoc additions in this phase: comment at `Tasty.scala:66-68` for `Name.asString` documents the new `(using AllowUnsafe)` requirement ("Requires (using AllowUnsafe) because the underlying OnceCell.get() is unsafe-tier."). Scaladocs on Symbol accessors (`scaladoc`, `position`, `parents`, `typeParams`, `declarations`, `companion`) are pre-existing and unchanged in structure; companion's scaladoc has expanded prose about the lookup mechanism, in line with §828 propagation semantics.
- README references migrated accessors: `kyo-tasty/README.md:17` (`_.fullName.asString`), :102 (`decls.find(_.name.asString == member)`), :104 (`s.declaredType.map`), :136 (`f.declaredType.map`, `f.name.asString`), :168 (`cls.fullName.asString`). None of the 5 fenced `scala` doctest blocks (lines 10-11, 46-47, 91-92, 123-124, 152-153) introduces an `AllowUnsafe` proof in scope. The blocks are no longer compilable in isolation after 02a's signature change. The existing "README doctest extraction" test (TastyTest:130) verifies structural presence of blocks but does NOT actually compile them, so this drift is invisible to the test suite. WARN — route to next phase's prep for either (a) updating the doctest snippets to bring an `AllowUnsafe` proof into scope or (b) materializing a sbt doctest harness that would catch the compile failure.
- Beyond-plan intent: no scaladoc grew beyond a one-sentence justification of the new proof requirement on `Name.asString`. Acceptable.

## Findings (categorized)

- BLOCKER: none.
- WARN:
  - README doctest snippets at kyo-tasty/README.md:10-25, 46-60, 91-105, 123-140, 152-170 reference migrated accessors (`fullName.asString`, `declaredType`, `declarations`, `name.asString`) without an `AllowUnsafe` proof in scope. After Phase 02a's signature change, these snippets no longer compile in isolation. Suggested fix in Phase 02b prep: add an `import AllowUnsafe.embrace.danger` line to each fenced scala block (the README is an end-user / app-boundary surface, §839 case 3) OR add a top-level "These snippets assume `given AllowUnsafe = AllowUnsafe.embrace.danger`" call-out near the first code block.
  - phase-02a-decisions.md Decision 7 documents the test-4 weakening; the weakening is justified (TASTy placeholder `unknown-type-tag-136`), but the un-resolvable parent type is itself a substantive gap that should appear on the next phase's prep input. Suggested fix: file an issue under the type-resolution audit-findings group (if not already present) so the BaseClass-parent resolution is tracked.
- NOTE:
  - CodegenExample.scala gained two `end <name>` markers (`end buildFacadeMethod` at :49, `end renderFacade` at :58) that were absent at HEAD~1. The cascade contract was "single import addition with no other change"; the `end` markers slip past that. Cosmetic only (Scala 3 accepts both forms), but worth a one-liner in end-of-project cleanup to either keep the `end` markers consistently across the file or drop the new ones.
  - kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/JavaSignatures.scala is detected by `file(1)` as binary `data` (likely a NULL byte or unusual high-bit code unit). This is PRE-EXISTING (parent state has the same property), not introduced by 02a, but worth a cleanup pass at end of project so future diffs render as text and grep tooling stays consistent.

## Routing

- BLOCKER findings: none. SLOT-A launch of Phase 02c may proceed without halt.
- WARN findings: TaskCreate for Phase 02b's prep input.
  - WARN-1: README doctest AllowUnsafe drift — investigate doctest-block fix vs. harness work; surface in Phase 02b prep so the doctest infra question is on the table when Classpath accessors land.
  - WARN-2: BaseClass parent type-resolution gap (uncovered by Decision 7) — confirm tracked under an existing audit finding; if not, file under the type-resolution group.
- NOTE findings: TaskCreate for end-of-project cleanup.
  - NOTE-1: CodegenExample `end` marker consistency.
  - NOTE-2: JavaSignatures.scala binary-detect by `file(1)`; pin source as UTF-8 text without high-bit anomalies.

Overall: PASS, ready for Phase 02b dispatch.
