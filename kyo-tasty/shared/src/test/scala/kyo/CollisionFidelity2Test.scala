package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Fidelity tests for same-fully-qualified name collision detection and FullNameCollision diagnostics.
  *
  * Collision fixture: the same embedded TASTy bytes loaded twice (two Pickle entries with the
  * same bytes but distinct UUIDs), producing duplicate fully-qualified name entries. Tests verify that mergeOneInto
  * records collisions as FullNameCollision diagnostics, FailFast mode raises TastyError.FullNameCollisionError,
  * and collisionReport is non-empty under SoftFail.
  */
class CollisionFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    private val fixtureBytes: Seq[(String, Array[Byte])] = Seq(
        "PlainClass"                 -> kyo.fixtures.Embedded.plainClassTasty,
        "SomeObject"                 -> kyo.fixtures.Embedded.someObjectTasty,
        "SomeTrait"                  -> kyo.fixtures.Embedded.someTraitTasty,
        "GenericBox"                 -> kyo.fixtures.Embedded.genericBoxTasty,
        "Outer"                      -> kyo.fixtures.Embedded.outerTasty,
        "SomeCaseClass"              -> kyo.fixtures.Embedded.someCaseClassTasty,
        "Color"                      -> kyo.fixtures.Embedded.colorTasty,
        "FixtureClasses-pkg"         -> kyo.fixtures.Embedded.fixtureClassesPackageTasty,
        "BaseClass"                  -> kyo.fixtures.Embedded.baseClassTasty,
        "ChildClass"                 -> kyo.fixtures.Embedded.childClassTasty,
        "Shape"                      -> kyo.fixtures.Embedded.shapeTasty,
        "VarargFixture"              -> kyo.fixtures.Embedded.varargFixtureTasty,
        "TypeAdtFixture-pkg"         -> kyo.fixtures.Embedded.typeAdtFixtureTasty,
        "AnnotatedFixture-pkg"       -> kyo.fixtures.Embedded.annotatedFixturePackageTasty,
        "AnnotatedFixtureDeprecated" -> kyo.fixtures.Embedded.annotatedFixtureDeprecatedTasty,
        "AnnotatedFixtureMethods"    -> kyo.fixtures.Embedded.annotatedFixtureMethodsTasty,
        "Animal"                     -> kyo.fixtures.Embedded.animalTasty,
        "Dog"                        -> kyo.fixtures.Embedded.dogTasty,
        "Cat"                        -> kyo.fixtures.Embedded.catTasty,
        "Vehicle"                    -> kyo.fixtures.Embedded.vehicleTasty,
        "Car"                        -> kyo.fixtures.Embedded.carTasty,
        "Bike"                       -> kyo.fixtures.Embedded.bikeTasty,
        "NonSealedMarker"            -> kyo.fixtures.Embedded.nonSealedMarkerTasty,
        "OpaqueFixture-pkg"          -> kyo.fixtures.Embedded.opaqueFixturePackageTasty,
        "SealedBase"                 -> kyo.fixtures.Embedded.sealedBaseTasty,
        "ConcreteA"                  -> kyo.fixtures.Embedded.concreteATasty,
        "ConcreteB"                  -> kyo.fixtures.Embedded.concreteBTasty,
        "ContextFunctionFixture-pkg" -> kyo.fixtures.Embedded.contextFunctionFixturePackageTasty,
        "ContextFunctionFixture"     -> kyo.fixtures.Embedded.contextFunctionFixtureTasty,
        "Logger"                     -> kyo.fixtures.Embedded.loggerFixtureTasty,
        "Config"                     -> kyo.fixtures.Embedded.configFixtureTasty
    )

    // Load each fixture twice with root1 and root2 prefixes to create collisions.
    private val collisionPickles: Chunk[Tasty.Pickle] =
        Chunk.from(fixtureBytes.flatMap { case (name, bytes) =>
            Seq(
                Tasty.Pickle(s"root1-$name", Tasty.Version(28, 3, 0), Span.from(bytes)),
                Tasty.Pickle(s"root2-$name", Tasty.Version(28, 3, 0), Span.from(bytes))
            )
        })

    private val cleanPickles: Chunk[Tasty.Pickle] = Chunk(
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty)),
        Tasty.Pickle("some-case-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someCaseClassTasty))
    )

    "same-fully-qualified name collision produces non-empty collisionReport" in {
        Abort.run[TastyError](
            Tasty.withPickles(collisionPickles) {
                Tasty.classpath.map { classpath =>
                    val report = classpath.collisionReport
                    assert(
                        report.nonEmpty,
                        "Expected at least one FullNameCollision in classpath.collisionReport when same content is loaded twice."
                    )
                    val firstCollision = report(0)
                    assert(
                        firstCollision.ids.size >= 2,
                        s"Expected FullNameCollision.ids.size >= 2 for '${firstCollision.fullName}'; got ${firstCollision.ids.size}."
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // findSymbol uses last-write-wins for determinism under collision.
    "findSymbol returns deterministic Present on collision fully-qualified name" in {
        Abort.run[TastyError](
            Tasty.withPickles(collisionPickles) {
                Tasty.classpath.map { classpath =>
                    val report = classpath.collisionReport
                    assume(report.nonEmpty, "Collision fixture produced no collisions; skip leaf.")
                    val collisionFullName = report(0).fullName
                    val sym1              = classpath.findSymbol(collisionFullName)
                    val sym2              = classpath.findSymbol(collisionFullName)
                    assert(
                        sym1 == sym2,
                        s"findSymbol('$collisionFullName') returned different results on consecutive calls: $sym1 vs $sym2."
                    )
                    assert(
                        sym1.isDefined,
                        s"findSymbol('$collisionFullName') returned Absent on a colliding fully-qualified name; expected Present (last-write-wins)."
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "FailFast collision raises TastyError.FullNameCollisionError" in {
        // Write collision bytes to temp dirs: root1 and root2 each contain the same fixtures.
        Path.tempDir("kyo-col-root1").map { root1 =>
            Path.tempDir("kyo-col-root2").map { root2 =>
                Kyo.foreach(Chunk.from(fixtureBytes)) { (name, bytes) =>
                    (root1 / s"$name.tasty").writeBytes(Span.from(bytes)).map { _ =>
                        (root2 / s"$name.tasty").writeBytes(Span.from(bytes))
                    }
                }
                    .map { _ =>
                        Scope.run {
                            Abort.run[TastyError](
                                ClasspathOrchestrator.init(Seq(root1.toString, root2.toString), Tasty.ErrorMode.FailFast, 1)
                            ).map { result =>
                                result match
                                    case Result.Failure(TastyError.FullNameCollisionError(fullName)) =>
                                        assert(
                                            fullName.nonEmpty,
                                            "TastyError.FullNameCollisionError.fullName must be non-empty on collision abort."
                                        )
                                        succeed
                                    case Result.Success(_) =>
                                        fail(
                                            "Expected FailFast collision init to abort with TastyError.FullNameCollisionError; " +
                                                "init succeeded silently."
                                        )
                                    case Result.Failure(other) =>
                                        fail(s"Expected TastyError.FullNameCollisionError but got: $other")
                                    case Result.Panic(t) =>
                                        fail(s"Unexpected panic: ${t.getMessage}")
                            }
                        }
                    }
            }
        }
    }

    "collisionReport is empty on a clean standard classpath" in {
        Abort.run[TastyError](
            Tasty.withPickles(cleanPickles) {
                Tasty.classpath.map { classpath =>
                    assert(
                        classpath.collisionReport.isEmpty,
                        s"Expected empty collisionReport on standard classpath but got ${classpath.collisionReport.size} entries."
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "collisionReport contains entries with ids.size >= 2" in {
        Abort.run[TastyError](
            Tasty.withPickles(collisionPickles) {
                Tasty.classpath.map { classpath =>
                    val multiIdCount = classpath.collisionReport.count(_.ids.size >= 2)
                    assert(
                        multiIdCount > 0,
                        s"Expected at least one FullNameCollision with ids.size >= 2; got $multiIdCount."
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "SoftFail collisions appear in classpath.indices.diagnostics not classpath.errors" in {
        Abort.run[TastyError](
            Tasty.withPickles(collisionPickles) {
                Tasty.classpath.map { classpath =>
                    val collisionDiagCount = classpath.indices.diagnostics.collect {
                        case c: Tasty.Classpath.FullNameCollision => c
                    }.size
                    assert(
                        collisionDiagCount > 0,
                        s"Expected classpath.indices.diagnostics to contain FullNameCollision entries under SoftFail collision; got 0."
                    )
                    val errorsContainCollisionString = classpath.errors.exists {
                        case TastyError.MalformedSection(_, reason, _) =>
                            reason.toLowerCase.contains("collision") || reason.toLowerCase.contains("fullName")
                        case _ => false
                    }
                    assert(
                        !errorsContainCollisionString,
                        "classpath.errors should not contain a stringified collision message."
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Collision diagnostics are build-time observations and are not serialized to KRFL.
    "warm classpath has empty collisionReport (not serialized)" in {
        Abort.run[TastyError](
            Tasty.withPickles(cleanPickles) {
                Tasty.classpath.map { coldCp =>
                    val digest       = Array[Byte](0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47)
                    val snapshotPath = "mem-col-parity/snapshot.krfl"
                    val bytes        = SnapshotWriter.serializeToBytes(coldCp, digest)
                    SnapshotReader.readFromBytes(bytes, snapshotPath).map { warmCp =>
                        assert(
                            warmCp.collisionReport.isEmpty,
                            s"Expected empty collisionReport on warm (snapshot-loaded) classpath; got ${warmCp.collisionReport.size}."
                        )
                        assert(
                            coldCp.collisionReport.isEmpty,
                            "Standard cold classpath should also have empty collisionReport (no collisions on clean load)."
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "diagnostics and collisionReport types are correct on clean classpath" in {
        Abort.run[TastyError](
            Tasty.withPickles(cleanPickles) {
                Tasty.classpath.map { classpath =>
                    val diags: Chunk[Tasty.Classpath.Diagnostic]       = classpath.indices.diagnostics
                    val cols: Chunk[Tasty.Classpath.FullNameCollision] = classpath.collisionReport
                    assert(diags.isEmpty && cols.isEmpty, "Both diagnostics and collisionReport should be empty on a clean classpath.")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end CollisionFidelity2Test
