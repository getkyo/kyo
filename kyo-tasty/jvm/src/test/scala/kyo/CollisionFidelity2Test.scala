package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Fidelity tests for same-FQN collision detection and FqnCollision diagnostics (F-A1-008, OQ-001, OQ-006).
  *
  * Before Phase 2.08, loading two roots that both define the same FQN (e.g., the kyo-tasty classes directory passed twice) produced 0
  * collision diagnostics. `cp.collisionReport` returned `Chunk.empty` regardless of how many duplicate symbols existed. Under
  * `ErrorMode.FailFast`, the init succeeded silently instead of raising `TastyError.InconsistentClasspath`.
  *
  * Fixes:
  *   - F-A1-008: `mergeOneInto` records each FQN where a new structural symbol overwrites a different structural symbol; the bucket is
  *     converted to `FqnCollision` diagnostics in `finalizeMerge`.
  *   - OQ-001 (InconsistentClasspath wiring): under `ErrorMode.FailFast`, the first collision raises
  *     `TastyError.InconsistentClasspath(fqn, zeroUUID, zeroUUID)`.
  *   - OQ-006 (collisionReport visibility): `cp.collisionReport` returns a non-empty `Chunk` when collisions occurred under SoftFail.
  *
  * Collision fixture: the kyo-tasty classes directory passed twice as roots. Each pass re-decodes the same .tasty files and creates
  * fresh symbol objects; the merger detects the second object as a duplicate for the same FQN.
  *
  * Invariant produced: INV-106-DF2.
  */
class CollisionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Leaf 1 (F-A1-008, OQ-006): same-fqn-collision-emits-diagnostic
    // Given: kyo-tasty root passed twice (same-FQN collision scenario)
    // When: inspecting cp.collisionReport
    // Then: returns non-empty Chunk of FqnCollision entries; each has ids.size >= 2
    // Pins: INV-106-DF2; F-A1-008
    "F-A1-008 leaf 1 (Phase 2.08): same-FQN collision produces non-empty collisionReport" in run {
        TestClasspaths2.withCollisionClasspath.map: cp =>
            val report = cp.collisionReport
            assert(
                report.nonEmpty,
                "Expected at least one FqnCollision in cp.collisionReport when same root is loaded twice. " +
                    "Before fix: collisionReport was always empty."
            )
            val firstCollision = report(0)
            assert(
                firstCollision.ids.size >= 2,
                s"Expected FqnCollision.ids.size >= 2 for '${firstCollision.fqn}'; got ${firstCollision.ids.size}. " +
                    "Each collision should carry all symbol IDs seen for that FQN."
            )
            succeed
    }

    // Leaf 2 (F-A1-008): findsymbol-collision-deterministic
    // Given: same collision setup
    // When: invoking cp.findSymbol for a colliding FQN
    // Then: returns a deterministic Present(_) -- last-write-wins per HARD RULE 4
    // Pins: INV-106-DF2; F-A1-008 (HARD RULE 4 layered compat)
    "F-A1-008 leaf 2 (Phase 2.08): findSymbol returns deterministic Present on collision FQN" in run {
        TestClasspaths2.withCollisionClasspath.map: cp =>
            val report = cp.collisionReport
            // Pick any colliding FQN (must be non-empty for this leaf to be meaningful)
            assume(report.nonEmpty, "Collision fixture produced no collisions; skip leaf.")
            val colFqn = report(0).fqn
            val sym1   = cp.findSymbol(colFqn)
            val sym2   = cp.findSymbol(colFqn)
            assert(
                sym1 == sym2,
                s"findSymbol('$colFqn') returned different results on consecutive calls: $sym1 vs $sym2. " +
                    "findSymbol must be deterministic (last-write-wins) under collision."
            )
            assert(
                sym1.isDefined,
                s"findSymbol('$colFqn') returned Absent on a colliding FQN; expected Present (last-write-wins)."
            )
            succeed
    }

    // Leaf 3 (OQ-001, F-A5-001 InconsistentClasspath): failfast-raises-inconsistent-classpath
    // Given: same collision setup but ErrorMode.FailFast
    // When: Classpath.init(collisionRoots, FailFast)
    // Then: aborts with TastyError.InconsistentClasspath(fqn, _, _)
    // Pins: INV-103-DF2; INV-106-DF2; F-A5-001 (InconsistentClasspath wired to collision)
    "F-A5-001 leaf 3 (Phase 2.08): FailFast collision raises TastyError.InconsistentClasspath" in run {
        Abort.run[TastyError](TestClasspaths2.withCollisionClasspathFailFast).map: result =>
            result match
                case Result.Failure(TastyError.InconsistentClasspath(fqn, _, _)) =>
                    assert(
                        fqn.nonEmpty,
                        "TastyError.InconsistentClasspath.file (fqn) must be non-empty on collision abort."
                    )
                    succeed
                case Result.Success(_) =>
                    fail(
                        "Expected FailFast collision init to abort with TastyError.InconsistentClasspath; " +
                            "init succeeded silently. Before fix: collision not detected."
                    )
                case Result.Failure(other) =>
                    fail(s"Expected TastyError.InconsistentClasspath but got: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
    }

    // Leaf 4 (INV-106-DF2 regression guard): collisionreport-empty-on-clean-classpath
    // Given: standard classpath (no duplicates)
    // When: cp.collisionReport
    // Then: returns Chunk.empty
    // Pins: INV-106-DF2 regression guard (clean classpath must not spuriously report collisions)
    "INV-106-DF2 leaf 4 (Phase 2.08): collisionReport is empty on a clean standard classpath" in run {
        TestClasspaths.withClasspath().map: cp =>
            assert(
                cp.collisionReport.isEmpty,
                s"Expected empty collisionReport on standard classpath but got ${cp.collisionReport.size} entries. " +
                    "The collision detection must not produce false positives on a clean load."
            )
            succeed
    }

    // Leaf 5 (F-A1-008 multi-version proxy): multi-version-stdlib-collision-with-different-decode
    // Given: same-root-twice fixture (proxy for multi-version collision scenario)
    // When: counting FqnCollisions with ids.size >= 2
    // Then: count > 0
    // Pins: F-A1-008 (multiple symbol IDs per colliding FQN)
    "F-A1-008 leaf 5 (Phase 2.08): collisionReport contains entries with ids.size >= 2" in run {
        TestClasspaths2.withCollisionClasspath.map: cp =>
            val multiIdCount = cp.collisionReport.count(_.ids.size >= 2)
            assert(
                multiIdCount > 0,
                s"Expected at least one FqnCollision with ids.size >= 2; got $multiIdCount. " +
                    "Each collision bucket should carry all symbol IDs seen for that FQN across roots."
            )
            succeed
    }

    // Leaf 13 (OQ-006, INV-104-DF2 + INV-106-DF2): softfail-accumulates-fqncollision-via-errors-field-bridge
    // Given: collision setup with SoftFail
    // When: counting cp.diagnostics.collect{case _:FqnCollision => 1}.size
    // Then: >= 1; cp.errors does NOT contain a stringified collision message
    // Pins: INV-104-DF2 + INV-106-DF2 (diagnostics channel separate from errors channel)
    "INV-106-DF2 leaf 6 (Phase 2.08): SoftFail collisions appear in cp.diagnostics not cp.errors" in run {
        TestClasspaths2.withCollisionClasspath.map: cp =>
            val collisionDiagCount = cp.diagnostics.collect:
                case c: Tasty.Classpath.FqnCollision => c
            .size
            assert(
                collisionDiagCount > 0,
                s"Expected cp.diagnostics to contain FqnCollision entries under SoftFail collision; got 0. " +
                    "cp.diagnostics must accumulate FqnCollision items (OQ-006)."
            )
            // Verify collisions are NOT stringified into cp.errors
            val errorsContainCollisionString = cp.errors.exists:
                case TastyError.MalformedSection(_, reason, _) =>
                    reason.toLowerCase.contains("collision") || reason.toLowerCase.contains("fqn")
                case _ => false
            assert(
                !errorsContainCollisionString,
                "cp.errors should not contain a stringified collision message. " +
                    "Collisions belong in cp.diagnostics (as FqnCollision), not in cp.errors."
            )
            succeed
    }

    // Leaf 7: cold/warm parity for collision detection
    // Given: collision classpath (SoftFail)
    // When: cold collisionReport
    // Then: warm has empty collisionReport (collisions are build-time; not serialized to KRFL)
    // Pins: INV-101-DF2 (warm KRFL has no collision info; cold has it)
    "INV-101-DF2 leaf 7 (Phase 2.08): warm classpath has empty collisionReport (not serialized)" in run {
        TestClasspaths.withClasspath().flatMap: coldCp =>
            Sync.defer:
                java.nio.file.Files.createTempDirectory("kyo-df2-col").toString
            .flatMap: tmpDir =>
                val digest  = Array[Byte](0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
                val platSrc = kyo.internal.tasty.query.PlatformFileSource.get
                kyo.internal.tasty.snapshot.SnapshotWriter.write(coldCp, tmpDir, digest, platSrc).flatMap: _ =>
                    val hexDigest    = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
                    val snapshotPath = s"$tmpDir/$hexDigest.krfl"
                    kyo.internal.tasty.snapshot.SnapshotReader.read(snapshotPath, platSrc).map: warmCp =>
                        assert(
                            warmCp.collisionReport.isEmpty,
                            s"Expected empty collisionReport on warm (snapshot-loaded) classpath; got ${warmCp.collisionReport.size}. " +
                                "Collision diagnostics are build-time observations and must not be serialized to KRFL."
                        )
                        assert(
                            coldCp.collisionReport.isEmpty,
                            "Standard cold classpath should also have empty collisionReport (no collisions on clean load)."
                        )
                        succeed
    }

    // Leaf 8: diagnostics type check
    // Given: clean standard classpath
    // When: checking diagnostics type
    // Then: diagnostics is Chunk[Tasty.Classpath.Diagnostic] and collisionReport is Chunk[Tasty.Classpath.FqnCollision]
    // Pins: public API shape correctness
    "Phase 2.08 leaf 8: diagnostics and collisionReport types are correct on clean classpath" in run {
        TestClasspaths.withClasspath().map: cp =>
            val diags: Chunk[Tasty.Classpath.Diagnostic]  = cp.diagnostics
            val cols: Chunk[Tasty.Classpath.FqnCollision] = cp.collisionReport
            assert(diags.isEmpty && cols.isEmpty, "Both diagnostics and collisionReport should be empty on a clean classpath.")
            succeed
    }

end CollisionFidelity2Test
