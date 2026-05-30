# Phase 04a verify report

Run-id: phase-04a-verify-1
HEAD: a373a7fa6 (Phase 03b shipped; phase 04a dirty against HEAD)
Phase id: 04a
Phase name: Widen JAR offsets to 64-bit
INV produced: INV-012
Authorized files (per plan + prompt):
- kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarCentralDirectory.scala (source)
- kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JarMappedReader.scala (source)
- kyo-tasty/jvm/src/test/scala/kyo/JarCentralDirectoryTest.scala (tests)
- kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala (tests)

Status: PASS

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green
  - runs/phase-04a-flow-verify-testOnly-jvm-3.log: `Tests: succeeded 23, failed 0, canceled 0, ignored 0, pending 0`
  - JarCentralDirectoryTest 13/13 (incl. P04a-T1, P04a-T2), JvmFileSourceTest 10/10 (incl. P04a-T3)
- reward-hacking grep: 3 hits, 0 overridden, 0 NEW
  - PRE-EXISTING `swallow-throwable` at JvmFileSourceTest.scala:161 (in HEAD; Phase 04a did not introduce; new Phase 04a try/catch at lines 343-345 uses multi-line form not matched by the catalog regex; no new instance)
  - PRE-EXISTING `deferral-for-now` at JarCentralDirectory.scala:541 (scaladoc word "placeholder" present verbatim in HEAD's JarCentralDirectory.scala:517 of the prior file shape; not introduced by Phase 04a)
  - PRE-EXISTING `dismissed-as-flake` at audit-fixes/phase-03b-audit.md:22 (artifact from Phase 03b audit; not in Phase 04a authorized set)
- fp-discipline grep: 38 hits, 0 overridden, 0 NEW
  - All `bare-var` (22), `null-literal` (6), `private-over-annotation` (7), `bespoke-mutable-collection` (1), `local-val-over-annotation` (1) are PRE-EXISTING idiomatic ZIP/binary-parsing code in HEAD (verified `git show HEAD:JarCentralDirectory.scala | grep -cE '^\s*var ' == 22` == current count; no new vars in diff)
  - `local-val-over-annotation` at JarMappedReader.scala:76 is the new explicit `: Long` ascription on `dataOffset`, which is part of the INV-012 fix (intentional type annotation to make the Long widening explicit and auditable; class-B candidate per skill spec)
- llm-tells grep: 6 hits, 0 overridden, 0 NEW in source
  - All 6 hits are in PRE-EXISTING audit-fixes/phase-03b-audit.md (5x em-dash, 1x hedge-robust); not part of Phase 04a authorized scope; no source-code or new-test hits
- dev-tag grep: 0 hits, 0 overridden
- plan-diff: AUTHORIZED=4 PRE-EXISTING=0 DRIFT-FROM-IMPL=0 MISSING=0
  - Diff matches AUTHORIZED set exactly (2 source + 2 test files; matches prompt's AUTHORIZED list and plan's files_modified + tests.files for phase "04a")
  - Note: flow-verify-plan-diff.sh shell-script raw-emits DRIFT for all four because it only reads `.files_modified[]` as scalar and does not aggregate `tests.files[]`; manual classification by reading 05-plan.yaml phase "04a" entry confirms PASS
- test-count: expected=3 actual=3
  - 3 P04a tests grepped via `grep -nE '"P04a-T'`: P04a-T1 (Test 1 CD>2GB), P04a-T2 (Test 2 Zip64 locator), P04a-T3 (Test 3 LFH offset round-trip); matches plan's `test_count: 3`
- stowaway-commit: SKIPPED (no impl-stdout log captured; HEAD unchanged at a373a7fa6 since baseline so no in-dispatch commit possible)
- cross-platform compile: JVM compile + tests green; JS clean compile green; Native clean compile green (Phase 04a plan declares `platforms: [jvm]`; prompt requires JS+Native compile must still succeed; verified)
  - runs/phase-04a-flow-verify-testOnly-jvm-3.log: success Total time 1s after compile
  - runs/phase-04a-flow-verify-compile-js-3.log: success Total time 34s (1 pre-existing E029 warn in QueryApiTest.scala:924, unrelated)
  - runs/phase-04a-flow-verify-compile-native-1.log: success Total time 32s (same pre-existing E029 warn)

## INV-012 verdict

PASS. The plan's 14-site contract is fully implemented:

JarCentralDirectory.scala, 9 sites guarded with `> Int.MaxValue` or widened to Long:
- lines 141, 148, 186, 364: cenSizeLong / cenOffset guards in 4 EOCD/CEN entry-points
- line 549, 552: EOCD scan with `EOCD_MAX_SCAN.toLong.min(fileLen).toInt` (Decision 3) + scanStartOffset guard
- line 591, 606: Zip64 locator and Zip64 EOCD offset guards in readCenLocationBuf

JarMappedReader.scala, 5 sites (Decision 2 expanded plan-cited 3 to 5):
- line 51: entry.lfhOffset > Int.MaxValue
- line 67: lfhBase26 (entry.lfhOffset + 26L) > Int.MaxValue
- line 77: dataOffset (lfhOffset + 30L + nameLen + extraLen) Long with explicit `< 0L || > buf.limit().toLong` check
- line 82: entry.compSize > Int.MaxValue (B2 sub-case)
- line 87: entry.uncompSize > Int.MaxValue (B2 sub-case)

dataOffset promoted to Long: confirmed (JarMappedReader.scala:76, explicit `val dataOffset: Long = entry.lfhOffset + 30L + nameLen.toLong + extraLen.toLong`).

P04a-T1, P04a-T2, P04a-T3 all green; each pins a distinct INV-012 path (C1 oversized cenOffset, B3 Zip64 locator detection, B2 oversized lfhOffset).

## Class-B findings (opus judgment) — 0

No class-B findings introduced. The `local-val-over-annotation` on JarMappedReader.scala:76 (`val dataOffset: Long = ...`) is the explicit-type-annotation pattern documented in Decision 4; the annotation is load-bearing for the Long arithmetic guard and is the intentional auditable form. Not flagged.

## Overrides

None. No `// flow-allow:` annotations introduced.

## Cross-platform JVM/JS/Native summary

- JVM: 23/23 tests pass (JarCentralDirectoryTest + JvmFileSourceTest; includes the 3 P04a tests)
- JS: clean compile PASS (test/compile after kyo-tastyJS/clean)
- Native: clean compile PASS (test/compile after kyo-tastyNative/clean)

## Class-A NEW hits

None. All catalog hits are PRE-EXISTING in HEAD or in non-Phase-04a artifacts (phase-03b-audit.md). The diff introduces zero new lines matching the reward-hacking, fp-discipline (bare-var/null-literal), llm-tells, or dev-tag patterns.

## Recommendation

Ready for commit. INV-012 is satisfied; 14 .toInt sites guarded (9 + 5); dataOffset widened to Long; 3 plan tests green; cross-platform compile clean on all 3 platforms.

## Exit code: 0
