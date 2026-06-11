package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Cold and warm decoder-fidelity tests using embedded fixtures and synthetic classpaths with explicit
  * subclassIndex and companionIndex. Covers subclassIndex/companionIndex snapshot round-trip parity,
  * Symbol equality semantics, and Classpath copy behavior.
  */
class DecoderFidelity5Phase02Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // Helper: make a minimal Symbol.Class for synthetic fixtures.
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

    /** Build a synthetic classpath with non-empty subclassIndex and companionIndex.
      *
      * Symbols:
      *   0: Animal (trait-like Class; parent of Dog and Cat)
      *   1: Dog (Class, parent = Animal)
      *   2: Cat (Class, parent = Animal)
      *   3: Foo (Class with companion)
      *   4: Foo$ (Object companion of Foo)
      *
      * subclassIndex: Animal(0) -> [Dog(1), Cat(2)]
      * companionIndex: Foo(3) <-> Foo$(4)
      */
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
                subclassIndex = Dict(
                    SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2))
                ),
                companionIndex = Dict(
                    SymbolId(3) -> SymbolId(4),
                    SymbolId(4) -> SymbolId(3)
                ),
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
    end syntheticCp

    /** Serialize a snapshot of `coldCp` and read it back, returning (coldCp, warmCp). */
    private def roundTrip(
        coldCp: Tasty.Classpath,
        digestByte: Byte
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Sync & Abort[TastyError]) =
        val digest   = Array[Byte](digestByte, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val snapPath = s"cache/${DigestComputer.toHexString(digest)}.krfl"
        val bytes    = SnapshotWriter.serializeToBytes(coldCp, digest)
        SnapshotReader.readFromBytes(bytes, snapPath).map { warmCp =>
            (coldCp, warmCp)
        }
    end roundTrip

    "subclassIndex populated on warm load matches cold load" in {
        Abort.run[TastyError] {
            syntheticCp.map { cold =>
                roundTrip(cold, 0x01).map { (cold, warm) =>
                    assert(
                        cold.indices.subclassIndex.size == 1,
                        s"Expected subclassIndex.size == 1; got ${cold.indices.subclassIndex.size}"
                    )
                    assert(
                        warm.indices.subclassIndex.size == 1,
                        s"warm subclassIndex is empty or wrong size; expected 1 got ${warm.indices.subclassIndex.size};"
                    )
                    val coldAnimal = cold.findClass("Animal").get
                    val warmAnimal = warm.findClass("Animal").get
                    val coldSubs   = cold.directSubclassesOf(coldAnimal).map(_.name.asString).toSet
                    val warmSubs   = warm.directSubclassesOf(warmAnimal).map(_.name.asString).toSet
                    assert(
                        coldSubs == warmSubs,
                        s"directSubclassesOf(Animal) mismatch: cold=$coldSubs warm=$warmSubs"
                    )
                    assert(
                        warmSubs.contains("Dog") && warmSubs.contains("Cat"),
                        s"warm directSubclassesOf(Animal) must contain Dog and Cat; got $warmSubs"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "companionIndex populated on warm load matches cold load" in {
        Abort.run[TastyError] {
            syntheticCp.map { cold =>
                roundTrip(cold, 0x02).map { (cold, warm) =>
                    assert(
                        cold.indices.companionIndex.size == 2,
                        s"Expected companionIndex.size == 2; got ${cold.indices.companionIndex.size}"
                    )
                    assert(
                        warm.indices.companionIndex.size == 2,
                        s"warm companionIndex is empty or wrong; expected 2 got ${warm.indices.companionIndex.size};"
                    )
                    val coldFoo  = cold.findClass("Foo").get
                    val warmFoo  = warm.findClass("Foo").get
                    val coldComp = cold.companion(coldFoo)
                    val warmComp = warm.companion(warmFoo)
                    assert(
                        coldComp.isDefined && warmComp.isDefined,
                        s"companion(Foo) must be Present on both cold and warm; cold=${coldComp.isDefined} warm=${warmComp.isDefined}"
                    )
                    assert(
                        coldComp.get.name.asString == warmComp.get.name.asString,
                        s"companion(Foo) name mismatch: cold=${coldComp.get.name.asString} warm=${warmComp.get.name.asString}"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

    // Symbol equality is id.value-based; two independent cold loads produce equal Symbols
    // and they serve as equivalent HashMap keys cross-classpath.
    "Symbol equality is id.value-based; two cold loads of same input produce equal Symbols" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(cp1 => cp1)
            }
                .map { cp1 =>
                    Tasty.withPickles(Chunk(Tasty.Pickle(
                        "plain-class-2",
                        Tasty.Version(28, 3, 0),
                        Span.from(kyo.fixtures.Embedded.plainClassTasty)
                    ))) {
                        Tasty.classpath.map { cp2 =>
                            // Both classpaths have the same number of symbols.
                            assert(
                                cp1.symbols.nonEmpty && cp2.symbols.nonEmpty,
                                "fixture must produce at least one symbol"
                            )
                            // For each symbol index, symbols from cp1 and cp2 with the same id.value must be ==.
                            assert(
                                cp1.symbols.length == cp2.symbols.length,
                                s"Symbol counts must match: cp1=${cp1.symbols.length} cp2=${cp2.symbols.length}"
                            )
                            var idx = 0
                            while idx < cp1.symbols.length do
                                val s1 = cp1.symbols(idx)
                                val s2 = cp2.symbols(idx)
                                assert(
                                    s1 == s2,
                                    s"Symbol[$idx] not equal across two independent cold loads: s1.id=${s1.id} s2.id=${s2.id}"
                                )
                                assert(
                                    s1.hashCode() == s2.hashCode(),
                                    s"Symbol[$idx] hashCode mismatch: s1.hc=${s1.hashCode()} s2.hc=${s2.hashCode()}"
                                )
                                idx += 1
                            end while
                            // HashMap key round-trip across classpaths
                            val sym1 = cp1.symbols(0)
                            val sym2 = cp2.symbols(0)
                            val m    = Map(sym1 -> "found")
                            assert(
                                m.get(sym2).isDefined,
                                s"sym2 from cp2 failed HashMap lookup keyed by sym1 from cp1 -- cross-classpath key contract broken"
                            )
                            succeed
                        }
                    }
                }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "classpath.copy produces structurally equal classpath (bodyMemo moved to DecodeContext)" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val copied = classpath.copy(errors = classpath.errors)
                    assert(
                        copied == classpath,
                        s"Expected classpath.copy with same errors to produce a structurally equal Classpath"
                    )
                    assert(
                        copied.symbols.size == classpath.symbols.size,
                        s"Expected copied.symbols.size == ${classpath.symbols.size} but got ${copied.symbols.size}"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "transitive subclassesOf parity between cold and warm load" in {
        Abort.run[TastyError] {
            syntheticCp.map { cold =>
                roundTrip(cold, 0x05).map { (cold, warm) =>
                    val coldAnimal = cold.findClass("Animal").get
                    val warmAnimal = warm.findClass("Animal").get
                    val coldSubs   = cold.subclassesOf(coldAnimal).length
                    val warmSubs   = warm.subclassesOf(warmAnimal).length
                    assert(
                        coldSubs == 2,
                        s"cold subclassesOf(Animal) expected 2 (Dog, Cat); got $coldSubs"
                    )
                    assert(
                        warmSubs == 2,
                        s"warm subclassesOf(Animal) expected 2 (Dog, Cat); got $warmSubs;"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
            }
    }

end DecoderFidelity5Phase02Test
