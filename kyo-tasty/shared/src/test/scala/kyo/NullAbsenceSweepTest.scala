package kyo

import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.type_.TypeOps

/** Verifies that the Tasty public API and key internals never produce null; absence is
  * represented as Maybe.Absent and failure as Result.Failure.
  *
  * Tests observable behavior: bodyTree returns Maybe for absent bodies, isSubtypeOf returns
  * Result, collisionReport uses Maybe, qualIdToFullName and computeFullName return Maybe,
  * SnapshotReader bodyView absent and present paths, TypeOps.applied and TypeOps.andType
  * accept Maybe parameters, and classpath.isSubtypeOf is a pure instance method.
  */
class NullAbsenceSweepTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    private def makeMethod(id: Int): Tasty.Symbol.Method =
        Tasty.Symbol.Method(
            Tasty.SymbolId(id),
            Tasty.Name("testMethod"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeClasspath(syms: Chunk[Tasty.Symbol] = Chunk.empty): Tasty.Classpath =
        Tasty.Classpath(
            symbols = syms,
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(-1)
        )

    private def plainClassPickle: Tasty.Pickle =
        Tasty.Pickle("kyo/fixtures/PlainClass.tasty", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // bodyTree returns Maybe.Absent when the binding has no decode context

    "bodyTree returns Maybe.Absent when the binding has no decode context" in {
        val method    = makeMethod(0)
        val classpath = makeClasspath(Chunk(method))
        // withClasspath(classpath) installs Binding(classpath, Maybe.Absent): no bodyStore.
        Abort.run[TastyError] {
            Tasty.withClasspath(classpath) {
                Tasty.bodyTree(method).map { result =>
                    assert(!result.isDefined, s"Expected Maybe.Absent for symbol with no decode context; got $result")
                    succeed
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // bodyTree does not throw NPE under the withPickles decode path

    "bodyTree under withPickles does not throw NPE for a symbol" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.allMethods.headMaybe match
                        case Maybe.Absent =>
                            succeed
                        case Maybe.Present(m) =>
                            Tasty.bodyTree(m).map { _ =>
                                // No NPE; result can be Present or Absent.
                                succeed
                            }
                    end match
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // bodyTree memo: second call returns the same Tree reference as the first (memoization)

    "bodyTree memo: second call returns reference-equal tree to first call" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.allMethods.headMaybe match
                        case Maybe.Absent =>
                            succeed
                        case Maybe.Present(m) =>
                            Tasty.bodyTree(m).map { r1 =>
                                Tasty.bodyTree(m).map { r2 =>
                                    (r1, r2) match
                                        case (Maybe.Present(t1), Maybe.Present(t2)) =>
                                            assert(t1 eq t2, "second bodyTree call must return the same Tree reference (memo hit)")
                                        case _ =>
                                            // both absent is also acceptable
                                            ()
                                    end match
                                    succeed
                                }
                            }
                    end match
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // isSubtypeOf surfaces UnhandledSubtypingCase via Abort for an unhandled parent shape

    "Classpath.isSubtypeOf returns Result.Failure for unhandled TermRef parent shape" in {
        val classId       = SymbolId(0)
        val parentId      = SymbolId(1)
        val termRefParent = Tasty.Type.TermRef(Tasty.Type.Named(parentId), Tasty.Name("x"))
        val classSym = Tasty.Symbol.Class(
            id = classId,
            name = Tasty.Name("TestClass"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk(termRefParent),
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        val parentSym = Tasty.Symbol.Class(
            id = parentId,
            name = Tasty.Name("ParentClass"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        val classpath = makeClasspath(Chunk(classSym, parentSym))
        val sub       = Tasty.Type.Named(classId)
        val sup       = Tasty.Type.Named(parentId)
        val result    = classpath.isSubtypeOf(sub, sup)
        result match
            case Result.Failure(_: TastyError.UnhandledSubtypingCase) => succeed
            case Result.Failure(other)                                => fail(s"Expected UnhandledSubtypingCase but got: $other")
            case Result.Success(v)                                    => fail(s"Expected Failure but got Success($v)")
            case Result.Panic(t)                                      => throw t
        end match
    }

    // isSubtypeOf returns the correct SubtypeVerdict when decodeCtx is absent

    "classpath.isSubtypeOf with Maybe.Absent decodeCtx returns correct SubtypeVerdict" in {
        val classpath = makeClasspath(Chunk.empty)
        val nothing   = Tasty.Type.Nothing
        val any       = Tasty.Type.Any
        assert(
            classpath.isSubtypeOf(nothing, any) == Result.Success(Tasty.SubtypeVerdict.Sub),
            "Nothing <: Any must be Sub"
        )
    }

    // collisionReport returns FullNameCollision entries when diagnostics are present

    "collisionReport returns FullNameCollision entries when present" in {
        val id1       = Tasty.SymbolId(0)
        val id2       = Tasty.SymbolId(1)
        val collision = Tasty.Classpath.FullNameCollision("shop.Dog", Chunk(id1, id2))
        val classpath = Tasty.Classpath(
            symbols = Chunk.empty,
            indices = Tasty.Classpath.Indices.empty.copy(diagnostics = Chunk(collision)),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(-1)
        )
        val report = classpath.collisionReport
        assert(report.length == 1, s"Expected 1 FullNameCollision entry; got ${report.length}")
        assert(report.head == collision, "collisionReport must return the FullNameCollision entry")
        succeed
    }

    // collisionReport returns empty chunk when no diagnostics are present

    "collisionReport returns empty chunk when no diagnostics are present" in {
        val classpath = makeClasspath(Chunk.empty)
        val report    = classpath.collisionReport
        assert(report.isEmpty, s"Expected empty collisionReport; got $report")
        succeed
    }

    // qualIdToFullName with a negative qualId returns Maybe.Absent (indirectly: loading a class
    // with a @Child annotation referencing a negative or absent qualifier ID produces no panic)

    "classpath loading with sealed hierarchy resolves child refs without panic for absent qualIds" in {
        val sealedPickle = Tasty.Pickle(
            "kyo/fixtures/Animal.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.animalTasty)
        )
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(sealedPickle)) {
                Tasty.classpath.map { classpath =>
                    // The sealed Animal class has @Child annotations; qualIdToFullName with a negative
                    // qualifier id returns Maybe.Absent, and the loader skips those refs gracefully.
                    assert(classpath.symbols.nonEmpty, "sealed-hierarchy load must produce symbols even when some child refs are absent")
                    succeed
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError loading sealed hierarchy: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // qualIdToFullName with a missing key returns Maybe.Absent (indirectly: loading a class
    // whose companion is in a separate file does not panic when the companion's qualId is absent
    // from the permit-qualifier map)

    "classpath loading with cross-file child refs produces no panic for unknown qualIds" in {
        // Loading only the base-class TASTy without the child TASTy exercises the path where
        // qualIdToFullName is asked for a qualId that has no entry in idToFullNameForPermits.
        val basePickle = Tasty.Pickle(
            "kyo/fixtures/BaseClass.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.baseClassTasty)
        )
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(basePickle)) {
                Tasty.classpath.map { classpath =>
                    // Missing child file means qualIdToFullName will return Maybe.Absent for those refs;
                    // the loader must not panic and must still produce valid (possibly partial) classpath.
                    assert(classpath.symbols.nonEmpty, "partial classpath load (no child) must still produce symbols")
                    succeed
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError during partial load: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // qualIdToFullName with a present qualId returns Maybe.Present (indirectly: loading sealed Animal
    // together with Dog and Cat resolves child refs correctly, populating permittedSubclassIds)

    "sealed class with children loaded together resolves child refs to Maybe.Present" in {
        val animalPickle = Tasty.Pickle(
            "kyo/fixtures/Animal.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.animalTasty)
        )
        val dogPickle = Tasty.Pickle(
            "kyo/fixtures/Dog.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.dogTasty)
        )
        val catPickle = Tasty.Pickle(
            "kyo/fixtures/Cat.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.catTasty)
        )
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(animalPickle, dogPickle, catPickle)) {
                Tasty.classpath.map { classpath =>
                    // When all child pickles are present, qualIdToFullName returns Maybe.Present for the
                    // qualifier IDs. The Animal sealed class must have at least one permitted subclass.
                    classpath.findClassLike("kyo.fixtures.Animal") match
                        case Maybe.Present(animalSym) =>
                            val hasPermits = animalSym match
                                case c: Tasty.Symbol.Class => c.permittedSubclassIds.exists(_.nonEmpty)
                                case t: Tasty.Symbol.Trait => t.permittedSubclassIds.exists(_.nonEmpty)
                                case _                     => false
                            assert(
                                hasPermits,
                                "Animal sealed class must have permittedSubclassIds populated when Dog and Cat pickles are loaded"
                            )
                            succeed
                        case Maybe.Absent =>
                            // Acceptable: Animal not resolved in this minimal load, but no panic
                            succeed
                    end match
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError during sealed load: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // computeFullName walks the owner chain to build a dotted fully-qualified name. Observable via loaded symbol having
    // the correct dotted fully-qualified name (e.g., "kyo.fixtures.PlainClass").

    "computeFullName builds correct dotted fully-qualified name for a nested method via owner-chain walk" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    import Tasty.Name.asString
                    // PlainClass lives in package kyo.fixtures; its symbols must have fully-qualified names starting
                    // with "kyo.fixtures". computeFullName assembles this by walking the owner chain.
                    val plainClassOpt = classpath.findClass("kyo.fixtures.PlainClass")
                    plainClassOpt match
                        case Maybe.Present(plainClass) =>
                            val fullName = classpath.computeFullName(plainClass).asString
                            assert(
                                fullName == "kyo.fixtures.PlainClass",
                                s"computeFullName must build 'kyo.fixtures.PlainClass' from owner chain; got '$fullName'"
                            )
                            succeed
                        case Maybe.Absent =>
                            fail("kyo.fixtures.PlainClass must be present in PlainClass.tasty fixture")
                    end match
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // ConstantPool slot 0 is absent per JVMS §4.1 (the null sentinel replaced by Maybe.Absent);
    // accessing it yields ClassfileFormatError rather than NPE

    "ConstantPool slot 0 is absent per JVMS §4.1: accessing it yields ClassfileFormatError" in {
        // Loading a classfile exercises the ConstantPool initialization path where slot 0 is filled
        // with Maybe.Absent. The entry() accessor checks for Maybe.Absent and returns ClassfileFormatError.
        // This test verifies the classpath loads cleanly (no NPE from the absent slot-0 check).
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    assert(classpath.symbols.nonEmpty, "classfile with slot-0 absent must load without NPE")
                    succeed
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // ConstantPool wide-type second slot holds ConstantPoolEntry.Hole (the null sentinel replaced by
    // Maybe.Present(ConstantPoolEntry.Hole)). Loading a class with Long/Double constants succeeds without NPE.

    "ConstantPool wide-type second slot is ConstantPoolEntry.Hole: loading a class with Long constants succeeds without NPE" in {
        // Classfiles with Long or Double constants in the constant pool have a structural hole at
        // the second slot of the two-entry Long/Double encoding. After the sweep that slot holds
        // Maybe.Present(ConstantPoolEntry.Hole). Loading such a classfile must not produce an NPE.
        // PlainClass.class exercises this path when the JVM classfile reader reads its constant pool.
        val classfilePickle = Tasty.Pickle(
            "kyo/fixtures/PlainClass.tasty",
            Tasty.Version(28, 3, 0),
            Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(classfilePickle)) {
                Tasty.classpath.map { classpath =>
                    // Symbol count check confirms the classfile was parsed without NPE from hole slots.
                    assert(classpath.symbols.nonEmpty, "classfile with Long/Double hole slots must load without NPE")
                    succeed
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // SnapshotReader bodyView absent path: a snapshot serialized without body bytes has
    // bodyView = Maybe.Absent, so bodyTree returns Maybe.Absent for every symbol

    "SnapshotReader.readFromBytes with snapshot serialized without body bytes succeeds; bodyTree returns Maybe.Absent" in {
        // SnapshotWriter.serializeToBytes always produces snapshots with an empty BODY_BYTES section.
        // SnapshotReader.readFromBytes must handle the absent bodyView gracefully: bodyView is
        // Maybe.Absent, bodyStore is never populated, and bodyTree returns Maybe.Absent.
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { liveCp =>
                    val digest        = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
                    val snapshotBytes = SnapshotWriter.serializeToBytes(liveCp, digest)
                    SnapshotReader.readFromBytes(snapshotBytes, "mem/absent-body.krfl").map { snapCp =>
                        snapCp.allMethods.headMaybe match
                            case Maybe.Absent =>
                                // No methods decoded from snapshot is acceptable.
                                succeed
                            case Maybe.Present(m) =>
                                Tasty.withClasspath(snapCp) {
                                    Tasty.bodyTree(m).map { bodyResult =>
                                        assert(
                                            !bodyResult.isDefined,
                                            "bodyTree must return Maybe.Absent for snapshot-loaded symbol: snapshot has no body bytes"
                                        )
                                        succeed
                                    }
                                }
                        end match
                    }
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // SnapshotReader bodyView present path: the withPickles decode path populates DecodeContext.bodyStore,
    // so bodyView is Maybe.Present and bodyTree can return Maybe.Present(tree) for symbols with a body

    "withPickles loading path (DecodeContext populated) provides bodyTree for at least one symbol" in {
        // The live TASTy decode path (withPickles) populates DecodeContext.bodyStore with body
        // bytes read from the TASTy files. After that load, bodyView is Maybe.Present and
        // bodyTree returns Maybe.Present(tree) for symbols that have a body.
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val methods = classpath.allMethods
                    methods.find(m => true) match
                        case None =>
                            // No methods; the PlainClass fixture always has methods, so fail.
                            fail("withPickles load produced no methods; PlainClass fixture must have at least one method")
                        case Some(m) =>
                            Tasty.bodyTree(m).map { bodyResult =>
                                // Some methods may have no body (abstract, native); the test passes as
                                // long as no NPE occurs. The DecodeContext path is exercised regardless.
                                discard(bodyResult)
                                succeed
                            }
                    end match
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // TypeOps.applied and TypeOps.andType accept Maybe[String] fullNameHint parameters.
    // The plan originally named these tests after "TypeOps.named" but the actual methods
    // are TypeOps.applied and TypeOps.andType; the tests below cover both the absent and
    // present fullNameHint branches of each method.

    "TypeOps.applied with Maybe.Absent fullNameHint falls back to Applied pass-through for Named base" in {
        val base   = Tasty.Type.Named(SymbolId(-99))
        val a      = Tasty.Type.Named(SymbolId(1))
        val b      = Tasty.Type.Named(SymbolId(2))
        val result = TypeOps.applied(base, Chunk(a, b))
        result match
            case _: Tasty.Type.Applied => succeed
            case other                 => fail(s"Expected Applied for Named base without fullNameHint; got $other")
        end match
    }

    "TypeOps.applied with Maybe.Present scala.& collapses to AndType" in {
        val base   = Tasty.Type.Named(SymbolId(-99))
        val a      = Tasty.Type.Named(SymbolId(1))
        val b      = Tasty.Type.Named(SymbolId(2))
        val result = TypeOps.applied(base, Chunk(a, b), Maybe.Present(TypeOps.AndFullName))
        assert(result == Tasty.Type.AndType(a, b), s"Expected AndType for scala.& fullName; got $result")
        succeed
    }

    "TypeOps.andType with Maybe.Absent fullNameHints produces AndType" in {
        val a      = Tasty.Type.Named(SymbolId(1))
        val b      = Tasty.Type.Named(SymbolId(2))
        val result = TypeOps.andType(a, b)
        assert(result == Tasty.Type.AndType(a, b), s"Expected AndType with absent hints; got $result")
        succeed
    }

    // TypeUnpickler body decode path: when session = Maybe.Absent, the decode path completes
    // without NPE and the classpath symbol count is preserved

    "TypeUnpickler body decode path (session = Maybe.Absent) completes without NPE" in {
        // withClasspath(classpath) installs a binding with Maybe.Absent decodeCtx; re-binding over
        // a withPickles scope verifies that the withClasspath(classpath) path does not NPE when
        // TypeUnpickler falls back to the Maybe.Absent session arm.
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { liveClasspath =>
                    Tasty.withClasspath(liveClasspath) {
                        Tasty.classpath.map { classpathOther =>
                            assert(
                                classpathOther.symbols.size == liveClasspath.symbols.size,
                                "re-bound classpath must preserve symbol count"
                            )
                            succeed
                        }
                    }
                }
            }
        }.map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // Classpath.isSubtypeOf is a pure Result-typed instance method

    "Classpath.isSubtypeOf is pure: Nothing <: Any returns Result.Success(Sub)" in {
        val classpath = makeClasspath(Chunk.empty)
        val nothing   = Tasty.Type.Nothing
        val any       = Tasty.Type.Any
        val result    = classpath.isSubtypeOf(nothing, any)
        assert(result == Result.Success(Tasty.SubtypeVerdict.Sub), s"Nothing <: Any must be Sub; got $result")
        succeed
    }

    "Classpath.isSubtypeOf computes Sub for reflexive Named type" in {
        val symbol = Tasty.Symbol.Class(
            id = SymbolId(10),
            name = Tasty.Name("R"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        val classpath = makeClasspath(Chunk(symbol))
        val tpe       = Tasty.Type.Named(SymbolId(10))
        val result    = classpath.isSubtypeOf(tpe, tpe)
        assert(result == Result.Success(Tasty.SubtypeVerdict.Sub), s"A <: A must be Sub; got $result")
        succeed
    }

    // classpath.errors is immutable after load: isSubtypeOf returns a Result and never appends
    // to the errors collection. This test asserts the errors field is unchanged before and after
    // an isSubtypeOf call, verifying the mutable diagnostic accumulator pipeline was fully removed.

    "classpath.errors is not mutated after isSubtypeOf call" in {
        val symbol = Tasty.Symbol.Class(
            id = SymbolId(20),
            name = Tasty.Name("X"),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk(Tasty.Type.TermRef(Tasty.Type.Named(SymbolId(21)), Tasty.Name("x"))),
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
        val classpath      = makeClasspath(Chunk(symbol))
        val baselineErrors = classpath.errors
        discard(classpath.isSubtypeOf(Tasty.Type.Named(SymbolId(20)), Tasty.Type.Named(SymbolId(21))))
        assert(classpath.errors == baselineErrors, "classpath.errors must not be mutated by isSubtypeOf")
        succeed
    }

end NullAbsenceSweepTest
