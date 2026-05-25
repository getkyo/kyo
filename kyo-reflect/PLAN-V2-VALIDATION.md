# v2 Plan Validation

Audit run: 2026-05-25T13:22:40Z
Plan audited: kyo-reflect/execution-plan-v2.md (sha256: d8f5c5e5d32c9340d0696ead65500e0830fdef84aed6bbbcc9d700de42f26e52)

## Verdict

FAIL (re-edit required). The plan is substantially well-structured and most rules pass, but Rule 3 (concrete content per phase) and Rule 8 (open-item resolutions baked in) have demonstrable gaps where IMPROVEMENT-OPEN-ITEMS.md claims a resolution is "baked into the plan" while the plan body itself does not reflect it. These are small surgical edits, not a structural overhaul.

## Summary

| Rule | Status |
|------|--------|
| 1. Gap coverage (G1-G24) | PASS |
| 2. No priority / preference language | PASS |
| 3. Concrete content per phase | FAIL |
| 4. No vague phrasings | PASS (with one minor borderline hit) |
| 5. Test scenarios listed, not summarized | PASS |
| 6. Phase ordering dependency-justified | PASS |
| 7. Zero open items in plan summary | PASS |
| 8. IMPROVEMENT-OPEN-ITEMS resolutions baked in | FAIL |
| 9. Non-goals explicit | PASS |
| 10. Total test count concrete | PASS |
| 11. CONTRIBUTING.md alignment | PASS |
| 12. Cross-platform completeness | PASS (with one ambiguity) |
| 13. Test rigor | PASS |
| 14. Anti-flakiness measures | PASS (no Thread.sleep; one borderline concurrent test) |

## Findings

### FAILs (5)

1. **Rule 3 / Rule 8 -- Phase 8 missing `_bodyMemo` field in files-to-modify** (`execution-plan-v2.md` Phase 8, lines 332-346).
   IMPROVEMENT-OPEN-ITEMS.md OI-13 says: "Baked in: `Symbol` gains `private[kyo] val _bodyMemo: Memo[Maybe[Reflect.Tree]]` ... The `Symbol` constructor change is included in the 'Files to modify: Reflect.scala' entry for Phase 8." The plan body lists `sealed trait Tree` and `def body` on `Symbol` but does NOT list `_bodyMemo: Memo[...]` in the Reflect.scala modifications. Test 9 ("two calls to `sym.body` return the same Tree reference (Memo caching)") cannot be verified without this field.
   - **Fix**: Add `private[kyo] val _bodyMemo: Memo[Maybe[Reflect.Tree]]` to the Reflect.scala bullet in Phase 8 files-to-modify; state the caching contract explicitly.

2. **Rule 3 / Rule 8 -- Phase 9 missing concrete Rec unfolding depth limit** (Phase 9, line 382).
   IMPROVEMENT-OPEN-ITEMS.md OI-4 says: "Baked in: the depth limit is 64 unfoldings ... when the limit is exceeded, `isSubtypeOf` conservatively returns `false`." The plan body says only "Rec unfolds one level for comparison (bounded recursion depth)" -- no number, no behaviour-on-exceed contract. The "bounded recursion depth" phrasing matches Rule 4's vague-phrasings prohibition borderline.
   - **Fix**: Replace "bounded recursion depth" with "depth limit 64; on exceeding, returns false (conservative)".

3. **Rule 3 / Rule 8 -- Phase 10 `jvmOnly` tag missing from plan body** (Phase 10 tests, lines 446-452).
   IMPROVEMENT-OPEN-ITEMS.md OI-17 says: "Update to plan: Phase 10 tests 1-7 carry the `jvmOnly` tag. Added to the plan's test list." Plan body tests 1-7 do NOT show `(jvmOnly)` annotations. This contradicts the platform contract claimed in OI-17, which is needed because Phase 10 uses zlib via `InflaterInputStream` (JVM-only).
   - **Fix**: Add `(jvmOnly)` annotation to each of Phase 10 tests 1-7 in the plan body, matching the Phase 16 convention (where `(new, jvmOnly)` is shown inline).

4. **Rule 3 / Rule 8 -- Phase 11 test 6 missing `jvmOnly` tag in plan body** (Phase 11, line 498).
   IMPROVEMENT-OPEN-ITEMS.md OI-7 says: "Baked in: test 6 carries the `jvmOnly` tag." Plan body test 6 does NOT show the tag. Test 6 requires `java.base` from the JDK `jrt:/` filesystem; will not compile/run on JS/Native.
   - **Fix**: Add `(jvmOnly)` annotation to Phase 11 test 6.

5. **Rule 3 -- Phase 17 verification command and test count contract divergence** (Phase 17, lines 736-740, 778).
   Phase 17 declares 0 tests; the summary table also says 0. Acceptable in itself. BUT the verification command lists `kyo-reflect-bench/compile` while the file-to-produce is at `kyo-reflect-bench/jvm/src/main/scala/...`, and the `build.sbt` modification declares `crossProject(JVMPlatform)` -- the sbt selector should be `kyo-reflect-benchJVM/compile` to match the cross-project convention used in Phase 16 (`kyo-reflectJVM/testOnly ...`, `kyo-reflectNative/Test/compile`). The selectors are inconsistent across phases.
   - **Fix**: Standardize to `kyo-reflect-benchJVM/compile` and `kyo-reflect-benchJVM/run`.

### Concerns / NOTEs

1. **Rule 4 borderline phrasing (Phase 9, line 382)**: "`Rec` unfolds one level for comparison (bounded recursion depth)" -- the parenthetical is vague. Folded into FAIL #2 above.

2. **Rule 12 ambiguity -- verification commands**: Phases 1-15 use `sbt 'project kyo-reflect; testOnly ...'` without a platform suffix. On a cross-platform sbt build the `project kyo-reflect` selector is the JVM cross-project (or aggregate, depending on sbt-crossproject version). All these tests live in `shared/src/test/scala`, so they should run on all three platforms by default. The plan does not state the intended cross-platform contract (e.g., "run on JVM only; JS/Native runs via aggregate"). Recommend explicit platform per phase as Phase 16 does (`kyo-reflectJVM/testOnly`). Not a hard FAIL because the existing v1 plan uses the same convention.

3. **Phase 8 Tree ADT enumeration completeness disclaimer**: Phase 8 inline enumerates TASTy term tags but omits several (SELECTin, SHAREDterm, REPEATED). OI-3 acknowledges this and pushes the impl agent to use `TastyFormat.scala` as source-of-truth. This is acceptable per the OI-3 resolution but the plan body should reference TastyFormat as the authoritative tag list rather than enumerating a partial list inline. NOTE only.

4. **Phase 6 `_scaladoc: Memo[Maybe[String]]` initialization**: The plan says "initialized to `Absent` by default", but `Memo` is a thunk-based memoizer; defaulting to `Absent` means the memo is never queried by the unpickler. The wording would be clearer as `private[kyo] val _scaladoc: SingleAssign[Maybe[String]]` (matching the `_parents`/`_typeParams`/`_declarations` pattern in Phase 3) or as `Memo` populated by the unpickler before publish. Same for Phase 7 `_position`. Mechanism is decidable but the type choice (Memo vs SingleAssign) is not consistent across phases. NOTE only.

5. **Phase 1 concurrency test (test 2)** asserts blocking semantics ("both block until the classpath transitions to `Ready`"). The test plan does not specify the synchronization primitive used to release the block (e.g., `Async.parallel(2)` + `Latch` + `Async.timeout(5.seconds)`). This is borderline for Rule 14 but the underlying API (Promise via Cache.memo) provides the synchronization, so an explicit `Async.timeout` bound around the assertion would still be needed in the test implementation. NOTE: recommend the plan state "wraps assertions in `Async.timeout(5.seconds)` to prevent test hangs".

6. **Phase 16 mmap memory-safety test (test 2)** is sound (asserts `ClasspathClosed` rather than relying on segfault detection), but the test description includes "use a deliberate post-close `sym.parents` call" which mixes platform tests with a closed-classpath check. This is fine as written.

## Gap-to-phase map

| Gap | Phase | One-line check |
|-----|-------|----------------|
| G1  | 8     | PRESENT (Tree body decode + Tree ADT) |
| G2  | 7     | PRESENT (Position section reader) |
| G3  | 6     | PRESENT (Comments section reader) |
| G4  | 10    | PRESENT (Scala 2 pickle reader) |
| G5  | 9     | PRESENT (isSubtypeOf extension) |
| G6  | 11    | PRESENT (ModuleInfoReader + findModule) |
| G7  | Non-Goals | PRESENT (TASTy writing) |
| G8  | Non-Goals | PRESENT (multi-Scala-version) |
| G9  | Non-Goals | PRESENT (incremental refresh) |
| G10 | Non-Goals | PRESENT (Phase C sharding) |
| G11 | 12    | PRESENT (TouchedFields.declare) |
| G12 | Non-Goals | PRESENT (C/C++ header parsing) |
| G13 | 2     | PRESENT (Phase C UnresolvedRef resolution) |
| G14 | 15    | PRESENT (BODY_BYTES section) |
| G15 | 14    | PRESENT (inputDigest fix) |
| G16 | 16    | PRESENT (JVM MemorySegment mmap) |
| G17 | 16    | PRESENT (Native POSIX mmap) |
| G18 | 17    | PRESENT (benchmark harness) |
| G19 | 13    | PRESENT (UUID type) |
| G20 | 5     | PRESENT (declaredType from Pass 1) |
| G21 | 3     | PRESENT (parents wiring) |
| G22 | 3     | PRESENT (typeParams wiring) |
| G23 | 3     | PRESENT (declarations wiring) |
| G24 | 4     | PRESENT (companion FQN lookup) |

No duplicates. No omissions. Each addressable gap maps to exactly one phase; non-goals are documented.

## Per-phase concrete-content table

| Phase | Files-produce | Files-modify | Files-delete | Tests# | API delta | Verify cmd | Dep justified |
|-------|---------------|--------------|--------------|--------|-----------|------------|---------------|
| 1     | OK (none)     | OK (5 sites) | OK (none)    | 1      | none      | OK         | OK ("None" with rationale) |
| 2     | OK (none)     | OK           | OK           | 3      | none      | OK         | OK (Phase 1) |
| 3     | OK (none)     | OK           | OK           | 7      | 3 stubs->real | OK     | OK (Phase 2) |
| 4     | OK (none)     | OK           | OK           | 4      | 1 stub->real  | OK     | OK (Phase 3) |
| 5     | OK (none)     | OK           | OK           | 7      | 1 stub->real  | OK     | OK (Phase 3) |
| 6     | OK            | OK           | OK           | 6      | 1 addition    | OK     | OK (Phase 5) |
| 7     | OK            | OK           | OK           | 5      | 2 additions   | OK     | OK (Phase 6) |
| 8     | OK            | **MISSING** `_bodyMemo` field | OK | 9 | Tree + body | OK | OK (Phase 5) |
| 9     | OK            | OK; depth limit underspecified | OK | 9 | 1 extension | OK | OK (Phase 8) |
| 10    | OK            | OK           | OK           | 7      | Flag.Scala2   | OK     | OK (Phase 9; jvmOnly not stated on tests) |
| 11    | OK            | OK           | OK           | 6      | ModuleDescriptor + findModule | OK | OK (Phase 10; test 6 jvmOnly not stated) |
| 12    | OK (none)     | OK           | OK           | 2      | TouchedFields.declare | OK | OK (Phase 11; +Phase 6 of v1) |
| 13    | OK (none)     | OK           | OK           | 1      | UUID type   | OK     | OK (Phase 12) |
| 14    | OK (none)     | OK           | OK           | 1      | minorVersion 0->1 | OK | OK (Phase 13) |
| 15    | OK (none)     | OK           | OK           | 2      | minorVersion 1->2 | OK | OK (Phase 14 + Phase 8) |
| 16    | OK            | OK           | OK           | 2      | none (internal) | OK   | OK (Phase 15) |
| 17    | OK            | OK           | OK           | 0      | none        | OK; sbt selector inconsistent | OK (Phase 16) |

## Detailed rule-by-rule notes

**Rule 1 (Gap coverage)**: 24 gaps enumerated in IMPROVEMENT-OPEN-ITEMS.md gap-to-phase cross-check; all 24 are addressed in v2 (either in a phase or in the explicit Non-Goals section). No gap is duplicated; no gap is omitted.

**Rule 2 (No priority language)**: Plan grepped for "priority|important|first most|if time permits|tier 1/2|critical path|nice-to-have|polish|would be nice|bandwidth". Only false-positive hit is the word "important" never appearing -- CLEAN.

**Rule 4 (No vague phrasings)**: Grepped for "TBD|to be decided|investigate further|reconsider after|tighten as we go|edge case|probably not needed|revisit". Zero hits for the strong markers. One borderline: "bounded recursion depth" in Phase 9 (folded into FAIL #2). One borderline: Phase 17 "non-assertion benchmarks are not 'tests' in the JUnit sense" -- this is descriptive scoping, not vagueness. Plan otherwise CLEAN.

**Rule 5 (Test scenarios listed, not summarized)**: Spot-checked all 72 test leaves. Every leaf is a single specific scenario (input fixture, action, expected outcome). No "concurrency tests" / "edge case tests" / category-summary leaves. CLEAN.

**Rule 6 (Phase ordering dependency-justified)**: Each Phase N>1 includes a **Dependencies** section naming the specific prior phase and the specific API/type/state it provides (e.g., Phase 3 -> Phase 2 because "Phase C placeholder resolution complete; cross-file parents are resolved before being stored on Symbols"). No hand-waving. CLEAN.

**Rule 7 (Zero open items in summary)**: Plan summary table at lines 758-781 contains zero "TBD" or "open item" markers. The IMPROVEMENT-OPEN-ITEMS document exists separately and is the audit of plan completeness, not a list of unresolved questions. CLEAN.

**Rule 8 (IMPROVEMENT-OPEN-ITEMS resolutions baked in)**: Spot-checked 6 items:
  - OI-1 (Phase 3 Pass1Result derivation): PARTIAL -- plan body says "extend Pass1Result to carry... pre-indexed maps" but does not state O(n) single-scan derivation strategy stated in OI-1.
  - OI-4 (Phase 9 depth limit 64): NOT BAKED IN -- plan body says only "bounded recursion depth".
  - OI-7 (Phase 11 test 6 jvmOnly): NOT BAKED IN -- plan body does not annotate test 6.
  - OI-8 (Phase 12 same-compilation-unit hint): PARTIAL -- test 2 implicitly covers it but plan body does not state the same-CU limitation explicitly.
  - OI-13 (Phase 8 `_bodyMemo`): NOT BAKED IN -- plan body lacks the field.
  - OI-17 (Phase 10 tests 1-7 jvmOnly): NOT BAKED IN -- plan body lacks tags.
  4 of 6 spot-checked items are NOT properly baked in. FAIL.

**Rule 9 (Non-goals explicit)**: Non-Goals section at lines 7-13 covers G7, G8, G9, G10, G12 with one-line rationale each. PASS.

**Rule 10 (Total test count concrete)**: Summary table line 780: "Total new v2 tests: 72". Sum of per-phase counts (1+3+7+4+7+6+5+9+9+7+6+2+1+1+2+2+0) = 72. Matches. PASS.

**Rule 11 (CONTRIBUTING.md alignment)**:
  - AllowUnsafe: Phase 1 adds `// Unsafe:` comments at existing sites; no new AllowUnsafe sites proposed. PASS.
  - Frame.internal: no occurrences. PASS.
  - asInstanceOf in macro source: not proposed. PASS.
  - default params on internal APIs: Phase 14 adds a `digest: Array[Byte]` param to `SnapshotWriter.serialize` without a default; threading is explicit. PASS.
  - em-dashes: zero occurrences in plan. PASS.
  - Maybe/Chunk/Span/Result: plan uses `Maybe[String]`, `Chunk[Reflect.Type]`, `Result.Panic` references. Phase 16 mmap reader returns `ByteView`, not `Array[Byte]`. PASS.
  - `kyo` public + `kyo.internal` internal: Phase 6 puts `CommentsUnpickler` under `kyo.internal.reflect.tasty`, public surface only `Symbol.scaladoc`. PASS.
  - `(using Frame)`: Phase 9 extension uses `(using cp: Classpath, Frame)`; Phase 8 body uses `(using Frame)`. PASS.

**Rule 12 (Cross-platform completeness)**:
  - Phase 16 names `kyo-reflectJVM/testOnly`, `kyo-reflectNative/Test/compile` explicitly. PASS for G16/G17.
  - Phase 10 (G4 Scala 2 pickle reader) -- relies on zlib; OI-17 says tests 1-7 are `jvmOnly` but the plan body omits the tag. Cross-platform implementation strategy (FFI to `zlib`, `inflateRawSync` for JS) is documented in OI-17 but not in the plan body. RECOMMEND adding to plan.
  - Phase 17 (G18) lists `crossProject(JVMPlatform)`, JVM-only is appropriate. PASS.

**Rule 13 (Test rigor)**: Spot-checked all 72 leaves. Every leaf asserts a concrete property: returned value equals X, returned reference equal-eq to Y, error case Z, etc. No `assert(true)`, no compile-only checks (except Phase 17 which is explicitly a bench, not a test). CLEAN.

**Rule 14 (Anti-flakiness measures)**: No `Thread.sleep` in plan. Phase 1 test 2 (concurrent block-until-Ready) is the only concurrency-sensitive test; the plan does not explicitly state `Async.timeout(bound)` around the assertions but the underlying `Cache.memo` Promise-based sync makes this deterministic. RECOMMEND adding explicit timeout bounds to Phase 1 test 2 and the supervisor checks.

## Recommendation

**RE-EDIT plan to address FAILs 1-5** before kicking off implementation. The five FAILs are surgical edits in the plan body (adding the `_bodyMemo` field, stating the depth limit 64, adding `jvmOnly` annotations to Phase 10 tests 1-7 and Phase 11 test 6, standardizing the Phase 17 sbt selector). Expected re-edit time: under 10 minutes. After the re-edit, the plan satisfies all 14 rules and is ready for user review.
