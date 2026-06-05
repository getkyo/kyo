package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Decoder-fidelity-5 Phase 5.02: cold/warm fidelity gaps -- 6 findings.
  *
  * Findings closed in this phase: F-W2-30, F-W2-31, F-W2-6, F-W2-8, F-W2-14.
  * Finding F-W2-27 (post-Scope mmap decodeBody contract) is in the JVM-only companion
  * `DecoderFidelity5Phase02JvmTest`.
  *
  * Tests P02.1 / P02.2 / P02.5 use Tasty.Classpath.make with explicit subclassIndex and
  * companionIndex to avoid cross-file parentType-resolution ambiguity in embedded fixtures.
  * Tests P02.3 / P02.4 use the embedded PlainClass / SomeCaseClass fixtures directly.
  * Leaves are numbered P02.1 through P02.5 for traceability with the exploration document.
  */
class DecoderFidelity5Phase02Test extends Test:

    import AllowUnsafe.embrace.danger

    // Minimal in-memory FileSource used across all leaves.
    final private class MemSrc(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemSrc

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
            Chunk.empty,
            Maybe.Absent
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
            Chunk.empty,
            Maybe.Absent
        )

    /** Build a synthetic classpath with non-empty subclassIndex and companionIndex.
      *
      * Symbols:
      *   - 0: Animal (trait-like Class; parent of Dog and Cat)
      *   - 1: Dog (Class, parent = Animal)
      *   - 2: Cat (Class, parent = Animal)
      *   - 3: Foo (Class with companion)
      *   - 4: Foo$ (Object companion of Foo)
      *
      * subclassIndex: Animal(0) -> [Dog(1), Cat(2)]
      * companionIndex: Foo(3) <-> Foo$(4)
      */
    private def syntheticCp(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
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
                fqnIndex = Dict(
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
    end syntheticCp

    /** Write a snapshot of `coldCp`, read it back, and return (coldCp, warmCp). */
    private def roundTrip(
        coldCp: Tasty.Classpath,
        digestByte: Byte
    )(using Frame): (Tasty.Classpath, Tasty.Classpath) < (Sync & Abort[TastyError]) =
        val cacheSrc = MemSrc()
        val digest   = Array[Byte](digestByte, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
            val hex      = DigestComputer.toHexString(digest)
            val snapPath = s"cache/$hex.krfl"
            SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                (coldCp, warmCp)
    end roundTrip

    // P02.1: F-W2-30 -- subclassIndex populated on warm load
    // Given: synthetic cp with subclassIndex: Animal -> [Dog, Cat]
    // When: write snapshot and read back (warm load)
    // Then: warm subclassIndex.size == cold subclassIndex.size (1 entry, Animal -> [Dog, Cat])
    // AND warm.directSubclassesOf(Animal) == cold.directSubclassesOf(Animal)
    // Pins: F-W2-30
    "P02.1 F-W2-30: subclassIndex populated on warm load matches cold load" in run {
        Abort.run[TastyError]:
            syntheticCp.flatMap: cold =>
                roundTrip(cold, 0x01).map: (cold, warm) =>
                    assert(
                        cold.indices.subclassIndex.size == 1,
                        s"Expected subclassIndex.size == 1; got ${cold.indices.subclassIndex.size}"
                    )
                    assert(
                        warm.indices.subclassIndex.size == 1,
                        s"warm subclassIndex is empty or wrong size; expected 1 got ${warm.indices.subclassIndex.size}; F-W2-30 not fixed"
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
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
    }

    // P02.2: F-W2-31 -- companionIndex populated on warm load
    // Given: synthetic cp with companionIndex: Foo <-> Foo$
    // When: write snapshot and read back (warm load)
    // Then: warm companionIndex.size == cold companionIndex.size (2 entries)
    // AND warm.companion(Foo) resolves to Foo$ and vice versa
    // Pins: F-W2-31
    "P02.2 F-W2-31: companionIndex populated on warm load matches cold load" in run {
        Abort.run[TastyError]:
            syntheticCp.flatMap: cold =>
                roundTrip(cold, 0x02).map: (cold, warm) =>
                    assert(
                        cold.indices.companionIndex.size == 2,
                        s"Expected companionIndex.size == 2; got ${cold.indices.companionIndex.size}"
                    )
                    assert(
                        warm.indices.companionIndex.size == 2,
                        s"warm companionIndex is empty or wrong; expected 2 got ${warm.indices.companionIndex.size}; F-W2-31 not fixed"
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
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
    }

    // P02.3: F-W2-6 / F-W2-8 -- Symbol equality is id-based, not body-byte-reference-based
    // Given: two independent cold loads of the same single-file fixture produce separate Symbol instances
    // When: we compare symbols with the same FQN from cp1 and cp2 using ==
    // Then: they are equal (because equals is overridden to use id.value, not body bytes)
    // AND they serve as equivalent HashMap keys cross-classpath
    // Pins: F-W2-6, F-W2-8
    "P02.3 F-W2-6/F-W2-8: Symbol equality is id.value-based; two cold loads of same input produce equal Symbols" in run {
        Scope.run:
            Abort.run[TastyError]:
                // Load cp1
                val src1 = MemSrc()
                src1.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
                val cp1Effect = ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src1, 1)
                // Load cp2 independently from the same bytes
                val src2 = MemSrc()
                src2.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
                val cp2Effect = ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src2, 1)
                cp1Effect.flatMap: cp1 =>
                    cp2Effect.flatMap: cp2 =>
                        // Both classpaths have the same number of symbols.
                        assert(
                            cp1.symbols.nonEmpty && cp2.symbols.nonEmpty,
                            "fixture must produce at least one symbol"
                        )
                        // For each symbol index, symbols from cp1 and cp2 with the same id.value must be ==.
                        // Symbol.equals is overridden to use id.value; body Array[Byte] reference is irrelevant.
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
                        // F-W2-8: HashMap key round-trip across classpaths
                        val sym1 = cp1.symbols(0)
                        val sym2 = cp2.symbols(0)
                        val m    = Map(sym1 -> "found")
                        assert(
                            m.get(sym2).isDefined,
                            s"sym2 from cp2 failed HashMap lookup keyed by sym1 from cp1 -- cross-classpath key contract broken"
                        )
                        succeed
            .map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    // P02.4: F-W2-14 -- cp.copy produces a structurally equal classpath (bodyMemo moved to DecodeContext)
    // Given: a Classpath cp produced by ClasspathOrchestrator.init
    // When: Tasty.Classpath.copyWithErrors(cp, cp.errors) is called
    // Then: the resulting Classpath equals the original (bodyMemo is NOT part of Classpath since Phase 05)
    // Pins: F-W2-14, INV-004 (bodyMemo is in DecodeContext, not Classpath; copy is structurally equal)
    "P02.4 F-W2-14: cp.copy produces structurally equal classpath (bodyMemo moved to DecodeContext)" in run {
        Scope.run:
            Abort.run[TastyError]:
                val src = MemSrc()
                src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    // Tasty.Classpath.copyWithErrors calls cp.copy(errors = ...) internally.
                    // bodyMemo is NOT a constructor parameter and moved to DecodeContext in Phase 05.
                    val copied = Tasty.Classpath.copyWithErrors(cp, cp.errors)
                    assert(
                        copied == cp,
                        s"Expected cp.copy with same errors to produce a structurally equal Classpath"
                    )
                    assert(
                        copied.symbols.size == cp.symbols.size,
                        s"Expected copied.symbols.size == ${cp.symbols.size} but got ${copied.symbols.size}"
                    )
                    succeed
            .map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    // P02.5: F-W2-30 cold/warm subclassesOf parity -- transitive BFS closure matches
    // Given: synthetic cp with subclassIndex: Animal -> [Dog, Cat]
    // When: write snapshot and reload; call subclassesOf(Animal) on both cold and warm
    // Then: both return 2 entries (Dog, Cat); sizes match
    // Pins: F-W2-30
    "P02.5 F-W2-30: transitive subclassesOf parity between cold and warm load" in run {
        Abort.run[TastyError]:
            syntheticCp.flatMap: cold =>
                roundTrip(cold, 0x05).map: (cold, warm) =>
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
                        s"warm subclassesOf(Animal) expected 2 (Dog, Cat); got $warmSubs; F-W2-30 not fixed"
                    )
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
    }

end DecoderFidelity5Phase02Test
