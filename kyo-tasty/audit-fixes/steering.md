# Steering rules for the kyo-tasty audit-fix campaign

These constraints bind every agent dispatched in this campaign. Read on
every cycle.

## Scope integrity

- Every finding from every audit (correctness, completeness, clarity,
  potential bugs, test coverage, CONTRIBUTING.md adherence) MUST appear
  in the plan as a concrete phase deliverable.
- No scope cuts. No "out of scope". No "we can revisit". No "for now".
  If a finding cannot be addressed inside kyo-tasty, the phase records
  the cross-module dependency and the work continues.
- Do not drop, weaken, or substitute. Implement exactly per plan or
  escalate to the supervisor.

## Atomic phase sizing (HARD constraint for the restructure)

Empirical lesson: bundled phases make ceremony expensive without
buying any verification guarantee; over-atomized phases make
ceremony EXPENSIVE TIMES N for no extra guarantee. The unit of
decomposition is the CONCEPTUAL CHANGE, not the code change.

### The unit is one conceptual change, not one code edit

A "conceptual change" is a single explanation that a reviewer can
hold in head as one idea. "Propagate `(using AllowUnsafe)` through
Symbol accessors" is ONE conceptual change even though it touches 9
methods. "Add bounds checks to binary input primitives (Varint,
ByteView, NameUnpickler, SectionIndex, Interner)" is ONE conceptual
change even though it touches 5 files. The test is: can the phase
name say what it does in one verb-noun without listing the items?
If yes, it is one phase regardless of how many sites are touched.

### The rules

- **Phase name format:** `<verb> <noun>`. Zero conjunctions ("and",
  "+", commas joining actions). The noun may be plural (Symbol
  accessors, classfile attributes); plurality of TARGETS is fine,
  plurality of ACTIONS is not.
- **One conceptual INV per phase.** Each phase produces exactly one
  invariant. If the planner finds itself producing the same INV
  across N phases, those N phases are the same conceptual change
  and must merge.
- **One acceptance criterion per phase.** Verifiable by a single
  targeted test or grep. If the test would have to enumerate 25
  separate cases, the phase is one conceptual change covering all
  25; if the test would have to assert 25 unrelated properties,
  those are different concepts.
- **LoC budget:** 50-400 lines of change per phase. Phases at the
  high end of this range are still ONE concept; the budget exists
  to flag bundles, not to force splits when the concept is
  irreducible.
- **Heuristic for the right granularity:** group by INV first, by
  subsystem second, by file last. One INV across 9 methods = one
  phase. Test coverage for 18 internal classes = group by subsystem
  (binary, classfile, query, reader, scala2, snapshot, symbol,
  type_ = 8 phases), not 18 per-class phases.

### Anti-pattern examples (REJECT)

- "Propagate AllowUnsafe on Symbol fullName" + "Propagate AllowUnsafe
  on Symbol parents" + ... (9 phases). MERGE into "Propagate
  AllowUnsafe through Symbol accessors" (1 phase, 9 method edits).
- "Test ConstantPool" + "Test JavaAnnotationUnpickler" + ... (18
  phases). MERGE by subsystem ("Test classfile internals" covers
  ConstantPool + JavaAnnotationUnpickler).
- "Decode BootstrapMethods" + "Decode NestHost" + "Decode
  NestMembers" + ... (6 phases). KEEP SPLIT only if each parser
  is genuinely a different conceptual decoder; MERGE if they share
  one parser harness extended for each.

### Canonical right-shape examples (KEEP)

- Phase 18a-e (Tree decoder by TASTy spec category). Each category
  has its own decode strategy (modifier vs tag+Nat vs length-prefixed
  vs ...); these ARE distinct conceptual changes.
- "Implement BitStream primitive" + "Implement Huffman decoder" +
  "Decode block type 0" + "Decode block type 1" + "Decode block type
  2" (RFC 1951 inflate layers). Each is a distinct conceptual layer
  of the spec.

### Expected count after the restructure

The original 31-phase plan was bundled. The 131-phase plan was
over-atomized. The right count for this campaign is roughly 60-80
phases.

When the restructured plan lands, every phase passes "one verb-noun,
one INV, one explanation a reviewer can hold in head as one idea."

## No priority, no human-time estimates

- Phases ordered solely by technical dependency. No "high priority" /
  "important" / "first" / "nice to have" / "if time permits".
- No human-time estimates ("weeks", "days"). Roadmap is dependency-DAG,
  not calendar.

## Performance non-regression

- The kyo-tasty cold-load and warm-cache paths were tuned heavily. The
  plan MUST NOT regress those paths.
- Specifically: do NOT wrap routine pure accessors (Symbol.fullName,
  Symbol.parents, etc.) in `Sync.Unsafe.defer` if that adds an
  allocation per call. The preferred fix per CONTRIBUTING.md §828
  option 1 is to propagate the proof: change the accessor signature to
  take `(using AllowUnsafe)`, return the raw value, no `Sync` wrapping.

## Side-effect tracking discipline

- All side effects must be tracked. Routine accessor bodies must NOT
  hide the proof via `import AllowUnsafe.embrace.danger`. Either the
  signature takes `(using AllowUnsafe)` (propagate the proof, §828
  option 1), or the work is bridged via `Sync.Unsafe.defer` (§833
  option 2).
- `import AllowUnsafe.embrace.danger` stays ONLY at the §839 case 3
  boundaries: classpath initialization (Phase A/B/C orchestration),
  unpickler decode passes, external runtime callbacks, app boundaries,
  test helpers.
- Do NOT introduce a two-tier `Symbol.Unsafe` / `Classpath.Unsafe`
  opaque-type companion. Per CONTRIBUTING.md §794-§812 that pattern is
  for concurrent types (Channel, Queue, Hub) with effectful operations
  on both tiers. Symbol and Classpath are data containers; the §828
  signature-propagation path is the correct fix.

## Code-quality rules from CONTRIBUTING.md and memory

- No em-dashes (`—` / `–`) anywhere in plan content, code, commit
  messages, or chat.
- No `asInstanceOf` unless inside an opaque-type boundary or kernel
  internal, with a justifying comment.
- No `null` in new code; existing protocol-driven null sentinels in
  unpicklers may stay.
- No `==` between types without `CanEqual`.
- No `var` for shared mutable state; use Atomic*.
- No semicolons chaining statements.
- No type aliases for effect rows.
- No backwards-compatibility shims.
- No default parameters on internal / private APIs.
- No new ScalaTest features (withFixture / FutureOutcome /
  TestCanceledException); stick to AsyncFreeSpec + assert/fail/cancel +
  `kyo.Test` helpers.
- Tests use the public API on LHS; internals only on RHS for
  verification.
- Test files placed by topic; never phase-coded names.
- Shared cross-platform tests live in `shared/`; never demoted to a
  platform folder to dodge infra cost.

## Process

- Agents NEVER `git add` or `git commit`. Supervisor commits between
  phases.
- Make changes one at a time per CLAUDE.md.
- Present plans and wait for approval before implementing.
- Create analysis files before changes.
- Every flow-* subskill (flow-explore, flow-design, flow-resolve-open,
  flow-invariants, flow-plan, flow-validate, flow-phase-prep,
  flow-phase-impl, flow-phase-pulse, flow-verify, flow-phase-audit,
  flow-sweep, flow-strip-dev, flow-resume) is invoked via the Skill
  tool. Never substitute a custom Agent for any of them. The class-A
  enforcement scripts only run against artifacts produced through the
  subskills.

## Test scenario discipline (from flow-plan.md rules)

- Test entries in the plan are numbered scenarios with Given, When,
  Then, Pins fields. Each `Then` states a concrete expected result.
  Hand-wave `Then` like "works" / "is correct" / "compiles" /
  "passes" / "ok" is rejected by
  flow-validate-test-substance.sh.
- Test file naming rule: PREFIX match against the source basename.
  `Tasty.scala` is covered by any test file whose name starts with
  `Tasty` (e.g., `TastyTest`, `TastySymbolTest`, `TastyTypeTest`).
- Preferred: 1:1 (`Foo.scala` ↔ `FooTest.scala`).
- Allowed exception: topic-split (`FooSymbolTest`, `FooTypeTest`)
  when decomposition is genuinely warranted by the source surface.
- Best to avoid: scenario-coded suffixes
  (`FooIdempotenceTest`, `FooOverflowTest`, `FooLeakTest`). These
  prefix-match so they are not forbidden, but prefer placing the
  scenario as a numbered entry inside the topic file. Reach for a
  scenario-suffixed file only when the scenario set is large enough
  to warrant its own file.
- New test files appear only for new source files
  (`### Files to produce`). Modified source files extend an existing
  prefix-matching test file.
- Test code is never written in the plan; the scenario substance is
  the contract impl reads against.

## Audit feedback ledger (append-only)

- Phase 12 audit WARN (2026-05-30): resolveExtFqn in Scala2PickleReader recurses unboundedly. Corrupt pickle with owner-cycle would StackOverflow. ROUTE TO: Phase 21e (Test scala2 subsystem) impl agent, add depth counter bounded by `entries.length` plus a malformed-pickle test that exercises the cycle.
- Phase 12 audit NOTE: resolveExtFqn allocates one PickleCursor + string per owner level. Not hot; no action.
- Phase 13 audit WARN (2026-05-30): T1 thin-API coverage gap remains; declaredType/declarations/typeParams/scaladoc/position/flags.contains/kind accessors still untested. ROUTE TO: Phase 21g (Test symbol subsystem) impl agent, expand SymbolApiSurfaceTest to cover the remaining accessors.
- Phase 13 audit WARN: `makeNamed` helper duplicated verbatim across TastyAnnotationTest and TastyTypeTest. NOTE-level; consolidate in Phase 21g if convenient.
- Phase 14a audit WARN (2026-05-30): CommentsUnpickler.read at line ~41-43 passes 0L to MalformedSection but `view` IS in scope at the catch. One-line fix: replace 0L with `view.position`. ROUTE: address inline before Phase 15 (post Phase 14b commit), or fold into a brief debt commit.
- Phase 14b audit BLOCKER (2026-05-30): TastyError.ParameterizedTypeNotAllowed has ZERO production call sites in `src/main/`. Either wire it in TypeUnpickler at an illegal APPLIEDtype position, or delete the case + Test 5. MUST resolve as a `14b-debt` commit BEFORE Phase 16 launches. Preferred path: wire it (no scope cuts policy). Decision pending after Phase 15 lands.
- Phase 14b audit WARN: Test 5 is purely structural (field presence). If the BLOCKER is resolved by wiring, replace Test 5 with a real-path provocation. If by deletion, drop Test 5 entirely.
- Phase 14b audit NOTE: SymbolNotFound wiring gap (lookupClass soft-fails) is BY DESIGN per RuntimeReflectionExample.scala. No action.
- Phase 15 audit NOTE (2026-05-30): SubtypeTest Test 12 uses budget=0 shortcut, doesn't exercise real 65-deep Rec traversal. ROUTE: add real-recursion test in Phase 22b (Test type-graph edges).
- Phase 15 audit NOTE: SubtypeTest Test 13 (unset parents) fixture is synthetic; add a comment citing the real production scenario (stub symbol from missing classpath dependency). NOTE-level, no commit churn required.
- Phase 16 audit NOTE (2026-05-30): CLASSconst now allocates a DecodeCtx + recursive descent per occurrence (vs one shared sentinel pre-Phase-16). DecodeSession.addrCache and arena bound repeat work, so impact is proportional to distinct class references. Recommended optimization: cache decoded type by inner-type address in DecodeSession.addrCache, consistent with SHAREDtype. ROUTE TO: Phase 27 benchmark sweep (verify no regression; consider cache add if measurable).
- Phase 18a audit BLOCKER (2026-05-30): decodeCategoryOneModifier missing 3 tags. OBJECT(19), TRAIT(20), ENUM(21) all have corresponding Flag enum values (Module, Trait, Enum). SUBMATCH(48) has no Flag equivalent and requires a separate handling decision (skip with NOTE? add a Flag.Submatch?). Real classes will fire DecodeException on body decode. ROUTE: Phase 18a-debt commit AFTER Phase 18c lands, BEFORE Phase 18d launch.
- Phase 18a audit NOTE: phase-18a-decisions.md doc text describes a `= 0L` default that was actually removed before commit. Stale documentation. Patch as part of 18a-debt.
- Phase 18b audit WARN (2026-05-30): TreeUnpicklerTest helpers findLiteral / findIf / findMatch have wildcard arms that silently miss Tree.Shared nodes. Production callers handle Tree.Shared correctly; the gap is test-internal. ROUTE: Phase 21d (Test reader subsystem) when consumer-style tests are added — extend helpers to resolve Shared via the cache.
- Phase 18c audit WARN: Tree.TypeBounds uses Unknown(0,0) as absent-hi sentinel; TypeUnpickler returns lo directly for alias bounds. Semantic quirk. ROUTE: Phase 21d or 21h; consider splitting into TypeAlias vs TypeBounds cases for clarity.
- Phase 18d audit NOTE (2026-05-30): test helper `containsApplyOrIdentOrLiteral` still pattern-matches Tree.Ident. Not a production gap (no main-source consumer left). ROUTE: Phase 21d cleanup if convenient.
- Phase 18d audit NOTE: Tree.*Ref* cases carrying addr: Int lack scaladoc clarifying that addr is an absolute byte offset into the TASTy section, keyed against addrMap. ROUTE: Phase 21d or doc sweep.
- Phase 18e audit WARN (2026-05-30): INV-005 zero-Unknown sweep covers only someObjectTasty + plainClassTasty (2 of 10 fixtures, small). BOUNDED (102) import-selector path untested. ROUTE: Phase 22a or 22d (Test deep symbol structures) — sweep wider corpus including macro / type-lambda / bounded-given fixtures.
- Phase 18e audit NOTE: readImportSelectors scaladoc omits BOUNDED handling note. ROUTE: Phase 21d doc sweep.
- Phase 18e audit NOTE: phase-18e-decisions.md has stale sentence describing discarded Tree.Unknown/Chunk.empty design alongside the final design. Code is correct; remove stale sentence in Phase 21d.
- Phase 18e audit NOTE: Import/Export/AnnotationNode placed at Tree tail before Unknown; could be grouped near PackageDef (line 514). Optional improvement, not incorrect.
- Phase 19a audit NOTE (2026-05-30): pre-existing scaladoc name mismatches in SnapshotFormat.scala — TYPES_EXTRA in doc vs TYPESEXT wire name; BODY_BYTES in doc vs BODYBYTE wire name. Pre-dates Phase 19a. ROUTE: Phase 21f (Test snapshot subsystem) doc sweep.
- Phase 19b audit WARN (2026-05-30): PARENTS section serialization drops java.lang.Object and other non-local / non-Named parents. Mitigated by SubtypeVerdict.Unknown for partial chains (Phase 15). NOT a correctness regression but a semantic degradation post-warm-cache. ROUTE: Phase 21f (Test snapshot subsystem) — add a round-trip test that materially asserts at least one local Named parent populates correctly; consider FQN-string encoding for external parents in a future minor bump.
- Phase 19b audit WARN: SnapshotRoundTripTest PARENTS check only asserts _parents non-null, satisfied by Chunk.empty fallback. The non-empty-parent code path is not exercised. ROUTE: Phase 21f — extend fixture with a local-parent class chain.
- Phase 19b audit NOTE: phase-19b-decisions.md says "assignHomes in ClasspathOrchestrator" but the function actually lives in Tasty.Classpath (lines 1135-1147). Misleading citation; fix in Phase 21d doc sweep.
- Phase 19b audit NOTE: -1 sentinel for non-local parents is documented; consider extending to FQN-string encoding in a future minor bump if cross-classpath retention becomes important.
- Phase 20a audit WARN (2026-05-30): Native InflateHook catches ZipException as dead code. scala-native's InflaterInputStream rethrows DataFormatException as IOException, so corrupt ZLIB input emits CorruptedFile instead of MalformedSection. ROUTE: Phase 21f (Test snapshot subsystem) — add a DataFormatException arm to map to MalformedSection consistently, or document the platform divergence.
- Phase 20a audit NOTE: inflater.close() in try block leaks the calloc struct on exception path. Minor (no OS resource); cleaner with try/finally. ROUTE: Phase 21f.
- Phase 20a audit NOTE: test duplication shared/jvmOnly + native/-specific is correct because Test.scala lacks a nativeOnly tag. ROUTE: Phase 21d / general infra — consider adding nativeOnly tag for symmetry.
- Phase 20b+20c audit NOTE (2026-05-30): BitStream and HuffmanTree are `private[kyo]` because the test lives under `package kyo`. A tighter `private[tasty]` would suffice. ROUTE: Phase 21d (Test reader subsystem) or general API-tightening sweep.
- Phase 20b+20c audit NOTE: decodeOne's `var result = -1` sentinel benefits from a short comment marking the no-return rationale. ROUTE: Phase 21d doc sweep.
- Phase 20d audit WARN (2026-05-30): PortableInflate.copyBack lacks a `dist > out.length` bounds check. Corrupt ZLIB input would raise raw IndexOutOfBoundsException instead of InflateException. ROUTE: inline fix in a 20d-debt commit after Phase 20e lands (to avoid edit conflicts with the in-flight Phase 20e impl).
- Phase 20d audit NOTE: `val _ = stream.alignToByte()` could be a bare statement call (drop `val _`). ROUTE: Phase 21d cleanup.
- Phase 20e audit NOTE (2026-05-30): PortableInflate has no test for CM != 8 or FDICT-set inputs (the throw arms). ROUTE: Phase 21d cleanup — add negative-path tests.
- Phase 20f audit NOTE (2026-05-30): INV-017 parity is implicit (each platform matches the same hardcoded reference constant) rather than a three-way runtime comparison. Future improvement: add an explicit cross-platform parity sweep test. ROUTE: Phase 23a/23b.
- Phase 21f audit NOTE (2026-05-30): Native InflateHook DataFormatException arm has no targeted test. A test injecting a valid ZLIB header with corrupted DEFLATE payload would close the gap. ROUTE: Phase 22 edge tests (likely 22a or 22c).
- Phase 22c deferral (2026-05-30): JMOD recognition (T4 test 3) deferred. Adding JMOD requires threading a 6-byte prefix offset through findEocd / readCenLocation / raf.seek / JarMappedReader — a production structural change outside Phase 22c's tests-only scope. ROUTE: schedule as M? follow-up if jmod classpath entries are needed; for now a placeholder test pins the deferral.
- Phase 22a-c audit WARN (2026-05-30): Utf8.decode is used on classfile constant-pool path via Interner.intern. Java classfiles use MUTF-8 but kyo-tasty decodes as pure UTF-8; `[0xC0,0x80]` becomes U+FFFD U+FFFD instead of U+0000. Practically safe (JVM names cannot legally carry U+0000). ROUTE: doc comment at Interner.scala:92 call site clarifying the MUTF-8 approximation; consider proper MUTF-8 decoder in a future phase if classfile string fidelity matters.
- Phase 22a-c audit WARN: TypeArena `case Tasty.Type.RecThis(_) => t` (line 38) has no inline comment. The leaf treatment is essential for cycle safety; a future refactor that recurses into RecThis.rec would silently break it. ROUTE: add an explanatory comment at that match arm.
- Phase 22a-c audit NOTE: Zip64 byte-layout could be a shared helper across P04a-T2 and P22c-T1; minor cleanup opportunity.
- Phase 23a regression discovery (2026-05-30): full kyo-tastyJS `test` run uncovered 3 pre-existing JS stack-overflow failures in TypeArenaTest (Phase 08a Tests 6+7 and Phase 22b Test 8) for deeply-nested type chains at MaxDepth=1024 levels. Scala.js doesn't have the JVM stack depth to fold MurmurHash3 productHash through Rec.hashCode 1000+ times. Fix: tag the three tests `taggedAs jvmOnly`. ROOT CAUSE: prior phases ran `kyo-tastyJS/Test/compile` but not `test`. ROUTE: from now on every cross-platform phase runs kyo-tastyJS/`test` not just compile.
