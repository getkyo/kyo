# Phase 04a audit

Time: 2026-05-29T00:00:00Z
HEAD: d1cd18e12
Phase commit: d1cd18e12 (kyo-tasty Phase 04a: widen JAR offsets to 64-bit)
Plan cites: ./05-plan.yaml phase "04a", ./05-plan.md Phase 04a (lines 981-1071)
Design cites: ./02-design.md §"JAR 64-bit offset handling (C1, B2, B3, B11)" lines 298-306; INV-012 at 606
Invariants ledger: ./04-invariants.md line 60 (INV-012)

## Test count

| Leaf | Status | Notes |
|---|---|---|
| 1: CD > 2GB without Zip64 rejected (P04a-T1) | PRESENT_STRICT | JarCentralDirectoryTest.scala +416-477: patches a real JAR's EOCD cenOffset to 0xFFFFFFFF, asserts `Result.Failure(TastyError.MalformedSection(...))` whose reason contains "out of range". Pins C1+INV-012. Real out-of-range input. |
| 2: Zip64 EOCD locator detected (P04a-T2) | PRESENT_STRICT | JarCentralDirectoryTest.scala +479-565: synthesizes a real Zip64 EOCD record + Zip64 locator and prepends to standard EOCD; asserts list() succeeds and returns the entry. Exercises the SIG_ZIP64_LOC path including the new `locOffset > Int.MaxValue` and `zip64EocdOffset > Int.MaxValue` guard sites (lines 591, 606). Pins B3+INV-012. |
| 3: 64-bit LFH offset round-trip (P04a-T3) | PRESENT_STRICT | JvmFileSourceTest.scala +311-354: opens a real JAR, reflection-injects `entry.copy(lfhOffset = Int.MaxValue + 1L)` into the entries map, calls `readEntry`, asserts IOException containing "exceeds 2GB". Real out-of-range input drives the new guard at line 51 of JarMappedReader. Pins B2+INV-012. |

All three tests exercise oversized offsets directly. No disguised happy-path assertions detected.

## CONTRIBUTING.md violations

None.

## Unsafe markers

None added. Phase scope is JVM IO/binary parsing; no `AllowUnsafe` introduced.

## Cross-platform consistency

- Platforms checked: jvm (phase scope per plan `platforms: [jvm]`)
- Per-platform deltas: source under `kyo-tasty/jvm/`; JS/Native untouched and compile clean (per verify report runs/phase-04a-flow-verify-compile-{js,native}). No drift.

## Naming convention compliance

- `cenSizeLong`, `cenSizeLong0`, `cenSizeLong1` used as scratch names at three sibling EOCD entry points (JarCentralDirectory.scala:140, 185, 363). Numeric-suffixed locals are within the existing JarCentralDirectory naming style and shadow concerns are avoided by suffix.
- `dataOffsetInt` at JarMappedReader.scala:91 is a deliberate, auditable Long→Int conversion local; matches Decision 4. No naming issue.
- `lfhBase26` at JarMappedReader.scala:66 inlines the meaning of the 26-byte offset. Reasonable.

## Steering deviation

- `git diff --name-only HEAD~1 HEAD` (source-only): exactly the 4 authorized files (2 source + 2 test). Matches plan files_modified + tests.files for phase 04a. No drift.
- Artifact files added (phase-04a-{prep,decisions,verify,baseline}, phase-03b-audit) are flow tooling output, not steering deviation.

## Anti-flakiness measures

- All three tests use `makeTempDir()` + write+read flow; no shared mutable state.
- P04a-T3 uses reflection on a private `entries` field; this is JVM-only and deterministic, but see NOTE below on reflection appropriateness.
- No sleeps, no timeouts, no networking. No flake surface introduced.

## Architecture substitution check

- Design intent (02-design.md §JAR 64-bit offset handling): "Long arithmetic on offset sites; Zip64 EOCD locator detection; bounds guard at .toInt cast"; INV-012 = "no Int truncation past 2GB".
- HEAD reality: every cited `.toInt` site is preceded by either (a) an `> Int.MaxValue` guard that throws a structured error, or (b) widened to Long arithmetic with the Int-bounded result re-verified (EOCD scan: `EOCD_MAX_SCAN.toLong.min(fileLen).toInt`, safe because constant 65557 dominates). `dataOffset` is now `val dataOffset: Long = lfhOffset + 30L + nameLen.toLong + extraLen.toLong` and passes Long up to a single auditable `dataOffsetInt` conversion guarded by `dataOffset > buf.limit().toLong`.
- Verdict: MATCH. No simpler-equivalent substitution. The plan-cited 3 LFH sites grew to 5 (compSize/uncompSize as B2 sub-cases), which is in-scope expansion per Decision 2, not substitution.

## Documentation drift

- Scaladoc additions: an inline comment at JarCentralDirectory.scala:203 ("EOCD_MAX_SCAN is 65557, always < Int.MaxValue...") and at JarCentralDirectory.scala:548 (same rationale at mmap site); an inline comment at JarMappedReader.scala:89 ("dataOffset is Long; ByteBuffer.position(int) only accepts Int..."). All three explain why a `.toInt` is safe after the widening. Within plan intent: the prep section 3 explicitly calls this out as the resolution pattern. Not beyond intent.
- No README/DESIGN edits in this phase. Aligned with plan.

## Guard pattern consistency (focus item 1)

Two idioms in use:

1. Plain `if x > Int.MaxValue then throw new java.io.IOException(...)` (10 sites): JarCentralDirectory.scala:148, 552, 591, 606; JarMappedReader.scala:51, 67, 82, 87 (and the dataOffset upper-bound check at 78 that uses `buf.limit().toLong` instead of `Int.MaxValue`).
2. `throw new TastyErrorWrapper(TastyError.MalformedSection("jar", ...))` (3 sites): JarCentralDirectory.scala:141, 186, 364.

The choice between IOException and TastyErrorWrapper at each call site is consistent with the existing throw style at that entry point in HEAD (e.g., `parseAllEntries` already used `TastyErrorWrapper`, `findEocd` already used `IOException`). No inconsistency with surrounding code; this is the correct mirror.

The `dataOffset` guard at JarMappedReader.scala:78 uses `< 0L || > buf.limit().toLong` rather than `> Int.MaxValue` because the actual semantic bound is the mmap region size, which is tighter than Int.MaxValue. This is the correct upper bound for the LFH data position, not a deviation.

Pattern consistency verdict: CONSISTENT.

## dataOffset Long propagation (focus item 2)

Verified. JarMappedReader.scala lines 66-93:
- entry.lfhOffset (Long) flows into `lfhBase26: Long` (guarded then `.toInt` for `buf.position`).
- `dataOffset: Long = entry.lfhOffset + 30L + nameLen.toLong + extraLen.toLong` (explicit Long arithmetic; no intermediate Int).
- One auditable conversion `val dataOffsetInt = dataOffset.toInt` at line 91, gated by the `dataOffset > buf.limit().toLong` check at line 78.
- Both downstream consumers (`buf.position(dataOffsetInt)` at line 99, `buf.slice(dataOffsetInt, compSize)` at line 107) use the converted Int. No second `.toInt` cast.

Long stays Long until the final consumed-as-Int boundary. PASS.

## INV-012 surface closure (focus item 4)

INV-012 covers all three JAR 64-bit surfaces (JarCentralDirectory offsets, JarMappedReader LFH offsets, MappedByteView cursor). Phase 04a addresses the first two (JarCentralDirectory + JarMappedReader). Phase 04b addresses MappedByteView (per plan id 04b "Widen MappedByteView cursor to 64-bit"). Phase 04c addresses truncated CEN records via INV-012 consumption (per plan id 04c `consumed_invariants: [INV-012]`).

Phase 04a does NOT touch MappedByteView (verified: no `MappedByteView` file in diff). Phase 04a does NOT introduce a truncated-CEN-records check. Surface boundaries respected.

INV-012 partial-production for Phase 04a is correct: the invariant ledger names it as a single invariant produced by 04a, with 04b widening the cursor and 04c adding the truncated-record detector under its consumption. No encroachment.

## Findings (categorized)

- BLOCKER: none.

- WARN: none.

- NOTE 1 (for Phase 04b prep): The `EOCD_MAX_SCAN.toLong.min(fileLen).toInt` widening idiom (used at JarCentralDirectory.scala:204 and 549) is the recommended pattern when a constant Int bound dominates a Long input. Phase 04b will face similar choices in `MappedByteView` cursor APIs (position/cursor/goto/subView/remaining); 04b prep should apply the same "widen-min-then-cast" idiom rather than re-deriving a guard each time.

- NOTE 2 (cross-campaign reflection appropriateness, focus item 5): P04a-T3 uses reflection to mutate a private `entries: java.util.HashMap` field on the live `JarMappedReader` instance to inject `lfhOffset = Int.MaxValue + 1L`. This is the most direct way to exercise the LFH-offset guard without building a 2GB+ JAR fixture, and the existing test infrastructure does not expose a JarEntry constructor for tests. Reflection here is appropriate given the unit-test constraint. However, the pattern leaks an implementation detail (the entries map field name) into the test, which will need updating if `JarMappedReader` ever changes its entry storage. For future campaigns: consider a test-only constructor or a package-private `withInjectedEntry` helper if more B2-style oversized-input tests are needed. Not a Phase 04a defect; queue for end-of-project cleanup.

- NOTE 3 (Phase 04b prep input): the Decision 2 expansion (LFH compSize/uncompSize as B2 sub-cases) is precedent for Phase 04b's prep to enumerate ALL `.toInt` sites in MappedByteView rather than trusting the plan's cited count. The Phase 04a prep self-check (lines 152-156) caught the count delta; Phase 04b prep should repeat the `rg -n '\.toInt\b'` verification before impl.

## Routing

- BLOCKER findings: none; do NOT halt SLOT-A launch of phase 04c.
- WARN findings: none.
- NOTE findings: route NOTE 1 and NOTE 3 to Phase 04b prep input. Route NOTE 2 to end-of-project cleanup.

## Overall verdict

PASS. Phase 04a fully satisfies INV-012 on the JAR offset surface (14 sites guarded, dataOffset propagated as Long, 3 plan tests with real out-of-range inputs green, cross-platform compile clean). No architecture substitution, no documentation drift, no class-C judgment failure. Ready.
