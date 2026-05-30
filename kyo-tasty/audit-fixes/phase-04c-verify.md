# Phase 04c verify report

Status: FAIL (class-A)

Run-id: phase-04c-verify-1
HEAD: 879b88897 (unchanged; Phase 04c is dirty-tree only, would commit on PASS)
Baseline: phase-04c-baseline.txt (empty modulo itself; HEAD is post-04b commit)
Plan platforms: [jvm]
Verification command: `sbt 'project kyo-tasty' 'Test/compile' 'testOnly kyo.JarCentralDirectoryTest'`

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green
  - runs/phase-04c-flow-verify-testOnly-jvm-1.log: 14/14 PASS
    (Test/compile and testOnly chained in one sbt invocation; both report
    `[success] Total time`. P04c-T1 listed and passing.)
- reward-hacking grep: 0 hits on Phase 04c added lines, 0 overridden
  - whole-tree grep surfaced 2 hits but both are pre-existing:
    JarCentralDirectory.scala:543 is a `placeholder` token in an unchanged
    comment (line 543, not in the 04c diff); phase-04b-audit.md:49 is a
    prior phase artifact. Diff-scoped grep against `^\+` introduced lines:
    zero hits.
- fp-discipline grep: 2 hits on Phase 04c added lines, 0 overridden (CLASS-A)
  - option-token at kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala:607
    `                    None`
  - some-constructor at kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala:609
    `                    case ex: java.io.IOException => Some(ex.getMessage)`
  - whole-tree grep also surfaced 34 hits in JarCentralDirectory.scala
    (bare-var, bespoke-mutable-collection, null-literal, private-over-annotation);
    all are pre-existing imperative ZIP decoder code in unchanged lines.
- llm-tells grep: 0 hits on Phase 04c added lines, 0 overridden
  - whole-tree grep surfaced 10 em-dash hits in phase-04b-audit.md (prior
    phase artifact, not touched by 04c).
- dev-tag grep: 0 hits, 0 overridden
  - Note: test file line 580 has `// Phase 04c - B11:`. Catalog regex
    `Phase\s+\d+\b` requires word-boundary after digits; `04c` (digits+letter)
    does not match. Catalog gap, not a 04c issue. Recommendation: keep the
    comment, or add `// DEV: Phase 04c` tag prefix for clarity.
- semicolons hit (CLASS-A per user feedback `feedback_no_semicolons`):
  - kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala:593
    `cenBuf(0) = 0x50; cenBuf(1) = 0x4b; cenBuf(2) = 0x01; cenBuf(3) = 0x02`
  - kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala:595
    `cenBuf(28) = 0xe8.toByte; cenBuf(29) = 0x03`
  - Note: not in the shipped fp-discipline catalog; surfaced by manual
    diff inspection against the user-feedback rule.
- plan-diff (three-bucket, with baseline):
  - AUTHORIZED (in plan files_modified for 04c): 1 file
    - kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala
  - AUTHORIZED (in plan tests.files for 04c): 1 file
    - kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala
  - PRE-EXISTING (in baseline): 0 files (baseline only contains itself)
  - DRIFT-FROM-IMPL: 0 files
  - MISSING: 0 files
  - Tool note: `flow-verify-plan-diff.sh` reports both files as
    DRIFT-FROM-IMPL because the YAML `files_modified` entries are objects
    `{path, before, after}` and the script extracts the whole object, not
    `.path`. Manual classification follows the plan correctly. Same issue
    affected 04a/04b verifiers (resolved manually in both prior reports).
- test-count: expected=1 actual=1
  - HEAD's JarCentralDirectoryTest.scala has 13 leaves; current has 14;
    delta = 1 = P04c-T1, matches plan `test_count: 1` and `tests.total: 1`.
- stowaway-commit: NONE
  - HEAD unchanged at 879b88897; no commits since 04b.
- cross-platform: SKIPPED-LEGITIMATE
  - Plan declares `platforms: [jvm]` for Phase 04c (single platform);
    JarCentralDirectory.scala lives only under `kyo-tasty/jvm/src/main/`.
    No JS/Native cascade (JAR central directory is a JVM-only concern).
- organization gate (8a/8b/8c):
  - Phase 04c modifies an existing JVM source and an existing JVM test
    file. No new files, no package-kyo additions. Pre-existing 8c
    `orphan-test` findings (5) are inherited from HEAD; sources live
    under `kyo/internal/tasty/query/` so they cannot prefix-match
    test files under `kyo/`. Not 04c-introduced.

## Held-out acceptance check (class-B, opus-derived from 04-invariants.md)

Derived independently from 04-invariants.md INV-012 ("no Int truncation
past 2GB") and the 02-design intent that JarCentralDirectory must surface
malformed CEN structure rather than silently dropping entries:

- Acceptance #1: a CEN record whose declared `recordSize` exceeds the
  remaining buffer bytes MUST cause the parser to RAISE rather than
  silently advance `pos` to `cenSize`. Without raising, downstream callers
  see a short result with no error signal, violating INV-012's promise of
  Zip64/large-archive correctness. The new test P04c-T1 exercises this
  with `nameLen=1000` in a 100-byte buffer; the parser throws
  `java.io.IOException` whose message contains `"truncated CEN record"`.
  PASS on JVM.
- Acceptance #2: the raised error MUST be `IOException` (or a subtype),
  consistent with `JarMappedReader.open`'s declared failure mode. The
  diff at JarCentralDirectory.scala:489 throws `new java.io.IOException`
  with positional context (`$pos`, `$recordSize`, `$jarPath`).
  PASS by inspection.

Note on INV-012 scope: the same silent-skip pattern exists in
`parseCenRecords` and `parseCenRecordsFull` (the public `list`/`listFull`
paths). Phase 04c addresses ONLY `parseCenRecordsAll` per the plan slice
("B11", files_modified single path). The decisions doc acknowledges this:
"The other two parsing methods share the same silent-skip pattern but
are called from the list/listFull public paths, which are outside the
scope of B11. Those are addressed if a follow-on finding targets them."
This is a class-B JUDGMENT-NEEDED note: INV-012 in 04-invariants.md is
phrased broadly ("JarCentralDirectory handles 64-bit offsets correctly")
and the plan slice scopes it tighter (B11 = parseCenRecordsAll only).
Phase 04c satisfies the plan slice; whether INV-012 as written is
fully discharged is a supervisor call (likely deferred to whichever
follow-on phase addresses the two remaining methods, or noted as a
scope refinement of INV-012).

## Class-B findings (opus judgment)

1. **Option-vs-Maybe in test code** (rule 4 type-safety, fp-discipline):
   JarCentralDirectoryTest.scala:600-609. The catch-pattern uses
   `Option[String]` (`val caught = try {...; None} catch {... => Some(...)}`).
   Project convention (`feedback_kyo_patterns`) prefers ScalaTest's
   `intercept[java.io.IOException]` for exception assertions; alternatively,
   `Maybe[String]`/`Present`/`Absent` matches the kyo idiom. The current
   shape is the most invasive form (introducing `Option` in a test file
   that otherwise has none). Recommend: rewrite as
   `val ex = intercept[java.io.IOException] { JarCentralDirectory.parseCenRecordsAll(...) }`
   then `assert(ex.getMessage.contains("truncated CEN record"))`.
   This collapses lines 600-617 to ~5 lines and removes the Option-token
   class-A hit above. CLASS-A blocker by the fp-discipline catalog;
   surfacing here as the judgment rationale.

2. **Multi-statement lines via `;`** (user feedback `feedback_no_semicolons`):
   JarCentralDirectoryTest.scala:593 and :595. Test buffer setup chains
   4 / 2 assignments on one line via `;`. Per the rule "never use `;` to
   chain statements; separate lines". CLASS-A by the user-feedback rule
   (catalog has no semicolon entry; this is a feedback-blocked pattern).
   Recommend: split each `cenBuf(i) = ...` onto its own line, or use a
   `java.nio.ByteBuffer.wrap(cenBuf).order(LITTLE_ENDIAN).putInt(0, 0x02014b50)`
   one-liner for the signature and `.putShort(28, 1000.toShort)` for nameLen.

3. **Test bypasses the API under test** (class-B catch-list item 13):
   The test calls `JarCentralDirectory.parseCenRecordsAll` directly rather
   than going through the public `list`/`listAll` entry points. The
   method is `private[kyo]` so accessible from package `kyo`, but the user
   path to truncated-record detection is presumably `list`/`listAll`. This
   is a JUDGMENT call: the plan explicitly names `parseCenRecordsAll` and
   B11 targets that exact symbol, so direct invocation matches the plan
   slice. Note that this means a truncated record reaching `list` (the
   user-facing path that calls `parseCenRecords`, NOT `parseCenRecordsAll`)
   would still silently skip; the test does not cover that and the impl
   does not fix it. Cross-references decisions-doc acknowledgement of
   the same gap. NOT a 04c blocker; flag for follow-on planning.

4. **No INV-012 smoke test touched**: 04-invariants.md cites
   `kyo-tasty/jvm/src/test/scala/kyo/InvariantsSpec.scala::INV-012` as
   INV-012's smoke test path. Phase 04c adds P04c-T1 to
   JarCentralDirectoryTest, not to InvariantsSpec. JUDGMENT: P04c-T1
   pins INV-012 by its `Pins:` comment and the plan's
   `consumed_invariants: [INV-012]`, but the InvariantsSpec smoke test
   is the canonical pinning site. May or may not exist yet; if it does,
   add a cross-reference assertion there. NOT a 04c blocker.

## Overrides

None. No `// flow-allow:` annotations introduced in the diff.

## INV verdict

- INV-012 (consumed_by Phase 04c per plan): PARTIALLY SATISFIED.
  - `parseCenRecordsAll` truncated-record path now raises (B11 closed).
  - `parseCenRecords` and `parseCenRecordsFull` (called from the
    `list`/`listFull` public paths) retain the silent-skip behavior;
    the plan slice does NOT include them, but the invariant text as
    written would not be fully discharged until those are also fixed.
    Treat as scope refinement of INV-012 to be addressed by follow-on
    finding or in the final benchmark-regression sweep (Phase 26)
    where INV-012 reappears in `consumed_invariants`.

## Exit code: 1

Class-A failures require remediation:
- B-1: Replace `Option`/`None`/`Some` in test with
  `intercept[java.io.IOException]` (fixes fp-discipline option-token,
  some-constructor; tightens to ScalaTest idiom; matches project
  convention).
- B-2: Split the two `;`-chained assignment lines (593, 595) onto
  separate lines (or replace with `ByteBuffer.putInt`/`putShort` calls).

Re-run `flow-verify` after remediation. Phase 04c is NOT ready for
commit until B-1 and B-2 are resolved. The held-out acceptance checks
and the test outcome itself are PASS; the blockers are pure
convention/discipline. The supervisor may also choose to add a
`// flow-allow: <reason>` override to either, but the patterns are
straightforwardly fixable.
