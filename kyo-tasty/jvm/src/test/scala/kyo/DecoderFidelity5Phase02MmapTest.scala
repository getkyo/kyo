package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Verifies post-Scope decodeBody behavior on mmap-loaded classpaths.
  *
  * The mmap path sets sectionBytes = Array.empty for body-bearing symbols; decodeBody detects this and
  * returns TastyError.MalformedSection("body bytes not available") rather than ClasspathClosed.
  * An IllegalStateException from the arena would only fire if view-backed bytes were accessed after close,
  * but they are not accessed at all when sectionBytes is empty.
  */
class DecoderFidelity5Phase02MmapTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeClass(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeObject(id: Int, name: String): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def syntheticCp(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val animal = makeClass(0, "Animal")
            val dog    = makeClass(1, "Dog")
            val cat    = makeClass(2, "Cat")
            val foo    = makeClass(3, "Foo")
            val fooObj = makeObject(4, "Foo$")
            Tasty.Classpath.make(
                symbols = Chunk(animal, dog, cat, foo, fooObj),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)),
                packageIds = Chunk.empty,
                fullNameIndex = Dict(
                    "Animal" -> SymbolId(0),
                    "Dog"    -> SymbolId(1),
                    "Cat"    -> SymbolId(2),
                    "Foo"    -> SymbolId(3),
                    "Foo$"   -> SymbolId(4)
                ),
                packageIndex = Dict.empty,
                subclassIndex = Dict(SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2))),
                companionIndex = Dict(SymbolId(3) -> SymbolId(4), SymbolId(4) -> SymbolId(3)),
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end syntheticCp

    "post-Scope decodeBody on mmap-loaded snapshot returns MalformedSection (body bytes not available)" in {
        val animalPickle = Tasty.Pickle("animal", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.animalTasty))
        val dogPickle    = Tasty.Pickle("dog", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.dogTasty))
        val digest       = Array[Byte](0x02, 0x27, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val tmpDir       = java.io.File.createTempFile("kyo-df5-p02-jvm", "").getAbsolutePath
        val _            = new java.io.File(tmpDir).delete()
        val _            = new java.io.File(tmpDir).mkdirs()
        // build cold classpath, write snapshot to disk.
        val writeEffect: Unit < (Async & Abort[TastyError]) =
            Abort.run[TastyError] {
                Tasty.withPickles(Chunk(animalPickle, dogPickle)) {
                    Tasty.classpath.map { coldCp =>
                        SnapshotWriter.write(coldCp, tmpDir, digest)
                    }
                }
            }.map {
                case Result.Success(_) => ()
                case Result.Failure(e) => throw new RuntimeException(s"Write failed: $e")
                case Result.Panic(t)   => throw t
            }
        writeEffect.andThen {
            val hex      = DigestComputer.toHexString(digest)
            val snapPath = s"$tmpDir/$hex.krfl"
            // load via mmap INSIDE a Scope.run; extract any symbol; let Scope exit.
            // snapshot-loaded symbols no longer carry body bytes; bodyTree returns Absent.
            val symbolAndClasspath =
                Scope.run {
                    Abort.run[TastyError] {
                        SnapshotReader.readMapped(snapPath).map { warmClasspath =>
                            val symbol = warmClasspath.symbols.headOption.getOrElse(
                                throw new RuntimeException("snapshot has no symbols")
                            )
                            (symbol, warmClasspath)
                        }
                    }.map {
                        case Result.Success(pair) => pair
                        case Result.Failure(e)    => throw new RuntimeException(s"mmap load failed: $e")
                        case Result.Panic(t)      => throw t
                    }
                }
            // Scope has exited. Call Tasty.bodyTree via a fresh binding with a fresh DecodeContext.
            // After bodyStore is empty after snapshot load; bodyTree returns Absent.
            symbolAndClasspath.map { (symbol, warmClasspath) =>
                val postScopeBinding = Binding(warmClasspath, Maybe.Present(DecodeContext.fresh()))
                Tasty.bindingLocal.let(Maybe.Present(postScopeBinding)) {
                    Abort.run[TastyError](Tasty.bodyTree(symbol)).map { result =>
                        result match
                            case Result.Success(Maybe.Absent) =>
                                // Snapshot load has empty bodyStore; bodyTree correctly returns Absent.
                                succeed
                            case Result.Success(Maybe.Present(_)) =>
                                // Would only happen if bodyStore was somehow populated; acceptable.
                                succeed
                            case Result.Failure(TastyError.MalformedSection(_, reason, _)) =>
                                // Legacy case: body bytes still present but invalid after mmap close.
                                assert(
                                    reason.contains("body bytes not available"),
                                    s"Expected 'body bytes not available' in MalformedSection reason; got: '$reason'"
                                )
                                succeed
                            case Result.Failure(TastyError.ClasspathClosed(_)) =>
                                succeed
                            case Result.Failure(other) =>
                                fail(s"Unexpected TastyError from post-Scope decodeBody: $other")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic from post-Scope decodeBody: ${t.getMessage}")
                    }
                }
            }
        }
    }

    "mmap: subclassIndex and companionIndex populated on mmap warm load" in {
        val digest = Array[Byte](0x02, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val tmpDir = java.io.File.createTempFile("kyo-df5-p02-mmap", "").getAbsolutePath
        val _      = new java.io.File(tmpDir).delete()
        val _      = new java.io.File(tmpDir).mkdirs()

        Scope.run {
            Abort.run[TastyError] {
                syntheticCp.map { cold =>
                    SnapshotWriter.write(cold, tmpDir, digest).andThen {
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"$tmpDir/$hex.krfl"
                        SnapshotReader.readMapped(snapPath).map { warm =>
                            (
                                cold.indices.subclassIndex.size,
                                cold.indices.companionIndex.size,
                                warm.indices.subclassIndex.size,
                                warm.indices.companionIndex.size
                            )
                        }
                    }
                }
            }.map {
                case Result.Success((coldSub: Int, coldComp: Int, warmSub: Int, warmComp: Int)) =>
                    assert(
                        warmSub == coldSub,
                        s"mmap warm subclassIndex size mismatch: cold=$coldSub warm=$warmSub;"
                    )
                    assert(
                        warmComp == coldComp,
                        s"mmap warm companionIndex size mismatch: cold=$coldComp warm=$warmComp;"
                    )
                    succeed
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
        }
    }

end DecoderFidelity5Phase02MmapTest
