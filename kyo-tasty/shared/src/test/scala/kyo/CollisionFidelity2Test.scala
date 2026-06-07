package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Fidelity tests for same-FQN collision detection and FqnCollision diagnostics .
  *
  * Previously, loading two roots that both define the same FQN produced 0 collision diagnostics. `cp.collisionReport` returned
  * `Chunk.empty` regardless of how many duplicate symbols existed. Under `ErrorMode.FailFast`, the init succeeded silently instead of raising
  * `TastyError.InconsistentClasspath`.
  *
  * Fixes:
  *   `mergeOneInto` records each FQN where a new structural symbol overwrites a different structural symbol; the bucket is
  *     converted to `FqnCollision` diagnostics in `finalizeMerge`.
  *   OQ-001 (InconsistentClasspath wiring): under `ErrorMode.FailFast`, the first collision raises
  *     `TastyError.InconsistentClasspath(fqn, zeroUUID, zeroUUID)`.
  *   OQ-006 (collisionReport visibility): `cp.collisionReport` returns a non-empty `Chunk` when collisions occurred under SoftFail.
  *
  * Collision fixture: the same embedded TASTy bytes loaded under two separate roots ("root1/" and "root2/"). Each root decodes the same
  * tasty content and creates fresh symbol objects; the merger detects the second object as a duplicate for the same FQN.
  *
  * Cross-platform: uses MemoryFileSource and ClasspathOrchestrator.init directly. No JVM filesystem required.
  *
  * Invariant produced: -DF2.
  */
class CollisionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    /** Build a MemoryFileSource with all embedded .tasty fixtures loaded under both "root1/" and "root2/" prefixes. */
    private def collisionSource(): MemoryFileSource =
        val src = MemoryFileSource()
        val fixtures = Seq(
            "PlainClass.tasty"                     -> kyo.fixtures.Embedded.plainClassTasty,
            "SomeObject.tasty"                     -> kyo.fixtures.Embedded.someObjectTasty,
            "SomeTrait.tasty"                      -> kyo.fixtures.Embedded.someTraitTasty,
            "GenericBox.tasty"                     -> kyo.fixtures.Embedded.genericBoxTasty,
            "Outer.tasty"                          -> kyo.fixtures.Embedded.outerTasty,
            "SomeCaseClass.tasty"                  -> kyo.fixtures.Embedded.someCaseClassTasty,
            "Color.tasty"                          -> kyo.fixtures.Embedded.colorTasty,
            "FixtureClasses$package.tasty"         -> kyo.fixtures.Embedded.fixtureClassesPackageTasty,
            "BaseClass.tasty"                      -> kyo.fixtures.Embedded.baseClassTasty,
            "ChildClass.tasty"                     -> kyo.fixtures.Embedded.childClassTasty,
            "Shape.tasty"                          -> kyo.fixtures.Embedded.shapeTasty,
            "VarargFixture.tasty"                  -> kyo.fixtures.Embedded.varargFixtureTasty,
            "TypeAdtFixture$package.tasty"         -> kyo.fixtures.Embedded.typeAdtFixtureTasty,
            "AnnotatedFixture$package.tasty"       -> kyo.fixtures.Embedded.annotatedFixturePackageTasty,
            "AnnotatedFixtureDeprecated.tasty"     -> kyo.fixtures.Embedded.annotatedFixtureDeprecatedTasty,
            "AnnotatedFixtureMethods.tasty"        -> kyo.fixtures.Embedded.annotatedFixtureMethodsTasty,
            "Animal.tasty"                         -> kyo.fixtures.Embedded.animalTasty,
            "Dog.tasty"                            -> kyo.fixtures.Embedded.dogTasty,
            "Cat.tasty"                            -> kyo.fixtures.Embedded.catTasty,
            "Vehicle.tasty"                        -> kyo.fixtures.Embedded.vehicleTasty,
            "Car.tasty"                            -> kyo.fixtures.Embedded.carTasty,
            "Bike.tasty"                           -> kyo.fixtures.Embedded.bikeTasty,
            "NonSealedMarker.tasty"                -> kyo.fixtures.Embedded.nonSealedMarkerTasty,
            "OpaqueFixture$package.tasty"          -> kyo.fixtures.Embedded.opaqueFixturePackageTasty,
            "SealedBase.tasty"                     -> kyo.fixtures.Embedded.sealedBaseTasty,
            "ConcreteA.tasty"                      -> kyo.fixtures.Embedded.concreteATasty,
            "ConcreteB.tasty"                      -> kyo.fixtures.Embedded.concreteBTasty,
            "ContextFunctionFixture$package.tasty" -> kyo.fixtures.Embedded.contextFunctionFixturePackageTasty,
            "ContextFunctionFixture.tasty"         -> kyo.fixtures.Embedded.contextFunctionFixtureTasty,
            "Logger.tasty"                         -> kyo.fixtures.Embedded.loggerFixtureTasty,
            "Config.tasty"                         -> kyo.fixtures.Embedded.configFixtureTasty
        )
        fixtures.foreach: (name, bytes) =>
            src.add(s"root1/$name", bytes)
            src.add(s"root2/$name", bytes)
        src
    end collisionSource

    private def withCollisionClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root1", "root2"), Tasty.ErrorMode.SoftFail, collisionSource(), 1)

    private def withCollisionClasspathFailFast(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root1", "root2"), Tasty.ErrorMode.FailFast, collisionSource(), 1)

    private def withCleanClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end withCleanClasspath

    // same-fqn-collision-emits-diagnostic
    // Given: embedded fixtures loaded under two roots (same-FQN collision scenario)
    // When: inspecting cp.collisionReport
    // Then: returns non-empty Chunk of FqnCollision entries; each has ids.size >= 2
    "same-FQN collision produces non-empty collisionReport" in {
        withCollisionClasspath.map: cp =>
            val report = cp.collisionReport
            assert(
                report.nonEmpty,
                "Expected at least one FqnCollision in cp.collisionReport when same content is loaded twice. " +
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

    // findsymbol-collision-deterministic
    // Given: same collision setup
    // When: invoking cp.findSymbol for a colliding FQN
    // Then: returns a deterministic Present(_) -- last-write-wins per HARD RULE 4
    "findSymbol returns deterministic Present on collision FQN" in {
        withCollisionClasspath.map: cp =>
            val report = cp.collisionReport
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

    // failfast-raises-fqn-collision-error
    // Given: same collision setup but ErrorMode.FailFast
    // When: Classpath.init(collisionRoots, FailFast)
    // Then: aborts with TastyError.FqnCollisionError(fqn)
    "FailFast collision raises TastyError.FqnCollisionError" in {
        Abort.run[TastyError](withCollisionClasspathFailFast).map: result =>
            result match
                case Result.Failure(TastyError.FqnCollisionError(fqn)) =>
                    assert(
                        fqn.nonEmpty,
                        "TastyError.FqnCollisionError.fqn must be non-empty on collision abort."
                    )
                    succeed
                case Result.Success(_) =>
                    fail(
                        "Expected FailFast collision init to abort with TastyError.FqnCollisionError; " +
                            "init succeeded silently. Before fix: collision not detected."
                    )
                case Result.Failure(other) =>
                    fail(s"Expected TastyError.FqnCollisionError but got: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
    }

    // collisionreport-empty-on-clean-classpath
    // Given: standard classpath (no duplicates)
    // When: cp.collisionReport
    // Then: returns Chunk.empty
    "collisionReport is empty on a clean standard classpath" in {
        withCleanClasspath.map: cp =>
            assert(
                cp.collisionReport.isEmpty,
                s"Expected empty collisionReport on standard classpath but got ${cp.collisionReport.size} entries. " +
                    "The collision detection must not produce false positives on a clean load."
            )
            succeed
    }

    // multi-version-stdlib-collision-with-different-decode
    // Given: same-content-twice fixture (proxy for multi-version collision scenario)
    // When: counting FqnCollisions with ids.size >= 2
    // Then: count > 0
    "collisionReport contains entries with ids.size >= 2" in {
        withCollisionClasspath.map: cp =>
            val multiIdCount = cp.collisionReport.count(_.ids.size >= 2)
            assert(
                multiIdCount > 0,
                s"Expected at least one FqnCollision with ids.size >= 2; got $multiIdCount. " +
                    "Each collision bucket should carry all symbol IDs seen for that FQN across roots."
            )
            succeed
    }

    // softfail-accumulates-fqncollision-via-errors-field-bridge
    // Given: collision setup with SoftFail
    // When: counting cp.indices.diagnostics.collect{case _:FqnCollision => 1}.size
    // Then: >= 1; cp.errors does NOT contain a stringified collision message
    "SoftFail collisions appear in cp.indices.diagnostics not cp.errors" in {
        withCollisionClasspath.map: cp =>
            val collisionDiagCount = cp.indices.diagnostics.collect:
                case c: Tasty.Classpath.FqnCollision => c
            .size
            assert(
                collisionDiagCount > 0,
                s"Expected cp.indices.diagnostics to contain FqnCollision entries under SoftFail collision; got 0. " +
                    "cp.indices.diagnostics must accumulate FqnCollision items (OQ-006)."
            )
            val errorsContainCollisionString = cp.errors.exists:
                case TastyError.MalformedSection(_, reason, _) =>
                    reason.toLowerCase.contains("collision") || reason.toLowerCase.contains("fqn")
                case _ => false
            assert(
                !errorsContainCollisionString,
                "cp.errors should not contain a stringified collision message. " +
                    "Collisions belong in cp.indices.diagnostics (as FqnCollision), not in cp.errors."
            )
            succeed
    }

    // cold/warm parity for collision detection
    // Given: clean classpath (SoftFail)
    // When: round-trip via in-memory snapshot
    // Then: warm has empty collisionReport (collisions are build-time; not serialized to KRFL)
    "warm classpath has empty collisionReport (not serialized)" in {
        withCleanClasspath.flatMap: coldCp =>
            Sync.defer:
                val digest       = Array[Byte](0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
                val snapshotPath = "mem-col-parity/snapshot.krfl"
                val mem          = kyo.internal.MemoryFileSource()
                val bytes        = SnapshotWriter.serializeToBytes(coldCp, digest)
                mem.add(snapshotPath, bytes)
                (mem, snapshotPath)
            .flatMap: (mem, snapshotPath) =>
                SnapshotReader.read(snapshotPath, mem).map: warmCp =>
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

    // diagnostics type check
    // Given: clean standard classpath
    // When: checking diagnostics type
    // Then: diagnostics is Chunk[Tasty.Classpath.Diagnostic] and collisionReport is Chunk[Tasty.Classpath.FqnCollision]
    "diagnostics and collisionReport types are correct on clean classpath" in {
        withCleanClasspath.map: cp =>
            val diags: Chunk[Tasty.Classpath.Diagnostic]  = cp.indices.diagnostics
            val cols: Chunk[Tasty.Classpath.FqnCollision] = cp.collisionReport
            assert(diags.isEmpty && cols.isEmpty, "Both diagnostics and collisionReport should be empty on a clean classpath.")
            succeed
    }

end CollisionFidelity2Test
