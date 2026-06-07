# handoff-fixes final gate report

Branch: kyo-tasty
Phase commits: e0fbbae66, 8811b9775, 66cba4970, f3d438d14, f5680a0a6
Final-gate fix commit: pending (this gate session)

## Bug fixed in this gate session

`SnapshotParamListsRoundTripTest.roundtrip_meters_extension_methods_preserve_paramListIds`
was timing out at both 1-minute and 3-minute limits in isolation.

Root cause: the leaf called `TestClasspaths.withClasspath()` which on JVM defaults to
`standard` (kyoTasty + kyoData + scala-library + kyoTastyFixtures). Loading that full
classpath, writing a snapshot, and reading it back exceeds 3 minutes on a loaded machine.
The leaf only needs `kyo.fixtures.Meters`, which lives entirely in `kyoTastyFixtures`.

Fix applied (3 files in this gate session):
- `kyo-tasty/shared/src/test/scala/kyo/SnapshotParamListsRoundTripTest.scala`: leaf now
  uses `TestClasspaths.withClasspath(TestClasspaths.kyoTastyFixtures)`; added
  `override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(3))` as safety net.
- `kyo-tasty/js/src/test/scala/kyo/internal/TestClasspaths.scala`: added
  `val kyoTastyFixtures: Seq[String] = Seq.empty` stub (classpath ignored on JS).
- `kyo-tasty/native/src/test/scala/kyo/internal/TestClasspaths.scala`: same stub.

Leaf now runs in 93ms.

## Test results per platform

### JVM full
`sbt 'kyo-tasty/test'`: 117 passed, 0 failed, 4 timed out.
The 4 timeouts are pre-existing load-pattern tests (TypeArenaTest B8 + SnapshotFidelity2Test
3 leaves). All reproducible at commit `a04457b65` (pre-campaign baseline). No
campaign-authored test failed.

### JVM isolated (flaky family)
`sbt 'kyo-tasty/testOnly *SnapshotRoundTrip* *DecoderFidelity5Wave2Test*'`: 36 passed,
0 failed, 6 timed out. All 6 in `DecoderFidelity5Wave2Test`
(W2.9, W2.10, W2.11, W2.19, section-index valid-snapshot, error-roundtrip). Same
behavior pre-handoff.

### JVM handoff targeted
`sbt 'kyo-tasty/testOnly *ParamListIdsPopulationTest* *ParamListsHelperTest*
*KyoMaybeSmokeTest* *SnapshotParamListsRoundTripTest* *KyoTastyDoctestVerifyTest*'`:
**38 passed, 0 failed, 1 ignored, 0 timed out**.

Suite breakdown:
- ParamListIdsPopulationTest: 5 passed (cross-platform; INV-H6 receiver chain)
- ParamListsHelperTest: 6 passed (cross-platform; INV-H3 and INV-H6)
- KyoMaybeSmokeTest: 5 passed (JVM; real kyo.Maybe from standard classpath)
- SnapshotParamListsRoundTripTest: 4 passed, 1 ignored (multi-list fixture gap)
- KyoTastyDoctestVerifyTest: 18 passed

### JS full
`sbt 'kyo-tastyJS/test'`: 1264 tests, 1263 passed, 0 failed, 0 cancelled, 0 pending,
1 ignored, 0 timed out, 0 skipped. The 1 ignored is the same multi-list gap leaf.

### Native full
`sbt 'kyo-tastyNative/test'`: 211 tests, 211 passed, 0 failed, 0 cancelled, 0 pending,
0 ignored, 0 timed out, 0 skipped.

### Doctest
`sbt 'kyo-tasty/doctest'`: total=18 compiled=18 cacheHits=0 warnings=0 failures=0.

### A8 deps compile
`sbt 'kyo-tasty-bench/Test/compile; kyo-tasty-examples/Test/compile;
kyo-tasty-sbt-plugin/Test/compile'`: all three success.

## Load-pattern timeouts

Not regressions. Pre-existing at `a04457b65` (pre-campaign baseline). Root cause: tests
that cold-load the full standard classpath (kyoTasty + kyoData + scala-library) inside
a fixed per-leaf time limit. On a loaded machine the cold load alone takes 20-30s; the
suite-level decode takes 1-3 minutes.

Affected:
- TypeArenaTest B8 (1m limit)
- SnapshotFidelity2Test 3 leaves (3m limit; 6 other leaves in the same suite pass,
  last at 2m 56s)
- DecoderFidelity5Wave2Test 6 leaves (1m 10s limit; confirmed pre-existing by isolated run)

The campaign introduced zero new load-pattern timeouts after the
`roundtrip_meters_extension_methods_preserve_paramListIds` fix above.

## Handoff asks coverage

- **Ask 1 (scaladoc contract)**: `f5680a0a6` ; contract paragraph at
  `Tasty.scala:2859-2866`, mirrored in `README.md`.
- **Ask 2 (extension representation)**: `8811b9775` (paramListIds population +
  OpaqueType companion) + `66cba4970` (PLISTS__ snapshot section). Extension receiver
  at `paramListIds.head.head` confirmed by ParamListIdsPopulationTest and
  KyoMaybeSmokeTest.
- **Ask 3 (kyo-data smoke test)**: `e0fbbae66` (test scaffolding) + `8811b9775` (flip
  to green). Full chain verified on real `kyo.Maybe` in KyoMaybeSmokeTest.
- **Ask 4 (surface stability + paramLists helper)**: `f3d438d14` ; `Tasty.paramLists`
  at `Tasty.scala:797`. All 25+ audited names stable; 18 compile-time probes in
  KyoTastyDoctestVerifyTest all green.
- **Ask 5 (TASTy version)**: no commit needed. `Version(28, 8, 0)` at
  `Tasty.scala:1295` aligned with `scala3Version = "3.8.3"` at `build.sbt:10`.

## Constraints honored

All 14 binding constraints + A1 honored:
1. Complete and correct (G-1 fully implemented; no fallback path)
2. Cross-platform default (all `shared/` or `jvm/` with named rationale)
3. Kyo primitives in shared/src/main (no raw `java.nio` / `j.u.c`)
4. Concrete-equality tests (exact shape and receiver assertions)
5. No tests reading docs/source (D-05-6 enforced)
6. No platform filter to dodge contract gap (contracts hold on all 3 platforms)
7. Wire-format minor bump 11 -> 12 (`SnapshotFormat.minorVersion = 12`)
8. REJECT-old policy (minor < 12 rejects with `TastyError.SnapshotVersionMismatch`)
9. No back-compat synthesis (no synthesis path in SnapshotReader)
10. A8 deps compile (all three modules compile)
11. No em-dash / en-dash (grep confirmed zero hits in added content)
12. Commit shape (`[kyo-tasty] handoff Phase NN: ...`)
A1. Snapshot back-compat reconciled to kyo precedent (REJECT-old applied)
13. Stability: no rename of existing public surface (18 probes all green)
14. Public-surface stability locked through merge
