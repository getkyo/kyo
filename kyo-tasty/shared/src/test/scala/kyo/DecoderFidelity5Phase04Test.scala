package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.FullNameNormalizer
import kyo.internal.tasty.symbol.SymbolBody

/** API surface tests: null safety on find/require, unresolvedTypeReferenceCount idempotency,
  * copyWithPreErrors, findClassByBinary canonicalization, Symbol equality,
  * empty-fully-qualified name behavior, SnapshotFormat section name validation, SymbolBody.toString,
  * findClassesByName stability, evictOlderThan, SnapshotReader sequential reads, and
  * SymbolBody structural equality.
  */
class DecoderFidelity5Phase04Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // Minimal Symbol.Class for synthetic classpath construction.
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

    // Build a small synthetic classpath and write+read a snapshot of it.
    private def snapshotRoundTrip(digestByte: Byte = 0x01)(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        Sync.defer {
            val foo = makeClass(0, "Foo")
            val bar = makeClass(1, "Bar")
            Tasty.Classpath.make(
                symbols = Chunk(foo, bar),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1)),
                packageIds = Chunk.empty,
                fullNameIndex = Dict("Foo" -> SymbolId(0), "Bar" -> SymbolId(1)),
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }
            .map { classpath =>
                val digest   = Array.fill[Byte](8)(digestByte)
                val snapPath = s"cache/${DigestComputer.toHexString(digest)}.krfl"
                val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                SnapshotReader.readFromBytes(bytes, snapPath)
            }
    end snapshotRoundTrip

    "findClass(null) returns Absent without NPE" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val result = classpath.findClass(null)
                    assert(result == Maybe.Absent, s"Expected Absent for null fullName but got: $result")
                    val symbol = classpath.findSymbol(null)
                    assert(symbol == Maybe.Absent, s"Expected Absent for null symbol lookup but got: $symbol")
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "unresolvedTypeReferenceCount is idempotent across multiple calls" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val c1 = classpath.unresolvedTypeReferenceCount
                    val c2 = classpath.unresolvedTypeReferenceCount
                    val c3 = classpath.unresolvedTypeReferenceCount
                    assert(c1 == c2, s"unresolvedTypeReferenceCount was not idempotent: $c1 != $c2")
                    assert(c2 == c3, s"unresolvedTypeReferenceCount was not idempotent: $c2 != $c3")
                    assert(c1 >= 0, s"unresolvedTypeReferenceCount must be non-negative; got $c1")
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "copyWithPreErrors produces correct errors fields" in {
        Abort.run[TastyError] {
            snapshotRoundTrip().map { classpath =>
                val err = TastyError.FileNotFound("test.krfl")
                val cp2 = classpath.copy(errors = Chunk(err))
                assert(cp2.errors.length == 1, s"Expected 1 error after copy; got ${cp2.errors.length}")
                assert(cp2.errors.head == err, s"Expected $err but got ${cp2.errors.head}")

                val pre = TastyError.FileNotFound("pre.krfl")
                val cp3 = Tasty.Classpath.copyWithPreErrors(classpath, Chunk(pre))
                assert(cp3.errors.nonEmpty, "Expected non-empty errors after copyWithPreErrors")
                assert(cp3.errors.head == pre, s"Pre-error must be first; got ${cp3.errors.head}")
                succeed
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "findClassByBinary result equals findClass(FullNameNormalizer(dotted))" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    import Tasty.Name.asString
                    Kyo.foreach(classpath.allClassLike) { symbol =>
                        // Build a synthetic binary name from the source fully-qualified name (slash-separator form)
                        val fullName       = classpath.fullName(symbol)
                        val sourceFullName = fullName.asString
                        val binaryName     = sourceFullName.replace('.', '/')
                        val viaMethod      = classpath.findClassByBinary(binaryName)
                        val viaManual      = classpath.findClass(FullNameNormalizer.canonicalSourceFullName(binaryName.replace('/', '.')))
                        assert(
                            viaMethod == viaManual,
                            s"findClassByBinary('$binaryName') = $viaMethod but expected $viaManual"
                        )
                        1
                    }
                        .map { results =>
                            val checked: Int = results.foldLeft(0)(_ + _)
                            assert(checked > 0, "Must have checked at least one class")
                            succeed
                        }
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "Symbol equality is id-based; different ids are never equal" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val allSyms = classpath.symbols
                    if allSyms.length >= 2 then
                        val s1 = allSyms(0)
                        val s2 = allSyms(1)
                        if s1.id != s2.id then
                            assert(!(s1 == s2), s"Symbols with different ids must not be equal: ${s1} vs ${s2}"): Unit
                    end if
                    if allSyms.nonEmpty then
                        val s = allSyms(0)
                        assert(s == s, "A symbol must equal itself"): Unit
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    // An empty fully-qualified name is a caller programming error; InvalidFullName distinguishes this from a genuine not-found result.
    "findClass(empty) returns Absent; requireClass(empty) raises InvalidFullName(empty)" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val findResult = classpath.findClass("")
                    assert(findResult == Maybe.Absent, s"findClass(\"\") must return Absent; got $findResult")
                    Abort.run[TastyError](classpath.requireClass("")).map { reqResult =>
                        reqResult match
                            case Result.Failure(TastyError.InvalidFullName(fullName, reason)) =>
                                assert(fullName == "", s"InvalidFullName must carry empty fullName; got '$fullName'")
                                assert(reason.contains("non-empty"), s"reason must mention 'non-empty'; got '$reason'")
                                succeed
                            case Result.Failure(TastyError.NotFound("")) =>
                                fail("requireClass(\"\") should raise InvalidFullName not NotFound")
                            case Result.Success(_) =>
                                fail("requireClass(\"\") must fail, not succeed")
                            case other =>
                                fail(s"Expected InvalidFullName but got: $other")
                    }
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "all SnapshotFormat.sectionNames are <= 8 chars and NUL-free" in {
        SnapshotFormat.requireValidSectionNames()
        SnapshotFormat.sectionNames.foreach { name =>
            assert(name.length <= 8, s"Section name '$name' exceeds 8-byte limit")
            assert(!name.exists(c => c == 0.toChar), s"Section name '$name' contains NUL byte")
        }
        succeed
    }

    "classpath.symbol returns sentinel for id=-1, id=-2, and id=Int.MinValue" in {
        Abort.run[TastyError] {
            snapshotRoundTrip().map { classpath =>
                val s1 = classpath.symbol(SymbolId(-1))
                val s2 = classpath.symbol(SymbolId(-2))
                val s3 = classpath.symbol(SymbolId(Int.MinValue))
                assert(s1 == Maybe.Absent, s"symbol(id=-1) must be Absent but was $s1")
                assert(s2 == Maybe.Absent, s"symbol(id=-2) must be Absent but was $s2")
                assert(s3 == Maybe.Absent, s"symbol(id=MIN_INT) must be Absent but was $s3")
                succeed
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "8 sequential SnapshotReader.readFromBytes calls produce identical results" in {
        Abort.run[TastyError] {
            snapshotRoundTrip(0x42).map { classpath =>
                val digest   = Array.fill[Byte](8)(0x42.toByte)
                val bytes    = SnapshotWriter.serializeToBytes(classpath, digest)
                val expected = classpath.symbols.length
                def readOne(i: Int)(using Frame): Int < (Sync & Abort[TastyError]) =
                    SnapshotReader.readFromBytes(bytes, "snap.krfl").map { cp2 =>
                        assert(
                            cp2.symbols.length == expected,
                            s"symbol count mismatch on read ${i}: got ${cp2.symbols.length}, expected $expected"
                        )
                        cp2.symbols.length
                    }
                Kyo.foreach(Seq(1, 2, 3, 4, 5, 6, 7, 8))(i => readOne(i)).map { lengths =>
                    val distinct = lengths.toSet
                    assert(distinct.size == 1, s"All 8 reads must return same symbol count; got: $distinct")
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Sequential read failed: $e")
                case Result.Panic(t)   => throw t
            }
    }

    // The digest in the filename (not the file body) prevents stale hits.
    "SnapshotReader.readFromBytes succeeds regardless of embedded digest value" in {
        Abort.run[TastyError] {
            val fooClass = makeClass(0, "Foo")
            Sync.defer {
                Tasty.Classpath.make(
                    symbols = Chunk(fooClass),
                    rootSymbolId = SymbolId(-1),
                    topLevelClassIds = Chunk(SymbolId(0)),
                    packageIds = Chunk.empty,
                    fullNameIndex = Dict("Foo" -> SymbolId(0)),
                    packageIndex = Dict.empty,
                    subclassIndex = Dict.empty,
                    companionIndex = Dict.empty,
                    moduleIndex = Dict.empty,
                    errors = Chunk.empty
                )
            }
                .map { classpath =>
                    // Serialize with digest=99 bytes; readFromBytes has no filename-based digest check
                    val digest99 = Array.fill[Byte](8)(99.toByte)
                    val bytes    = SnapshotWriter.serializeToBytes(classpath, digest99)
                    SnapshotReader.readFromBytes(bytes, "snap.krfl").map { cp2 =>
                        assert(
                            cp2.symbols.length == classpath.symbols.length,
                            s"Symbol count mismatch: got ${cp2.symbols.length}, expected ${classpath.symbols.length}"
                        )
                        succeed
                    }
                }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Expected success (no digest check) but got: $e")
                case Result.Panic(t)   => throw t
            }
    }

    // An empty classpath has rootSymbolId = -1, so classpath.symbol(classpath.rootSymbolId) returns Maybe.Absent.
    "empty classpath classpath.symbol(classpath.rootSymbolId) returns sentinel" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk.empty) {
                Tasty.classpath.map { classpath =>
                    assert(classpath.symbols.isEmpty, s"Expected empty classpath; got ${classpath.symbols.length} symbols")
                    val root = classpath.symbol(classpath.rootSymbolId)
                    assert(
                        root == Maybe.Absent,
                        s"classpath.symbol(rootSymbolId) must be Absent on empty classpath; got $root"
                    )
                    succeed
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    // SymbolBody.toString renders sectionBytes as "len=<N>" and names as "[<N> entries]".
    "SymbolBody.toString contains len= not array identity hash" in {
        val body = SymbolBody(
            bodyStart = 10,
            bodyEnd = 20,
            sectionBytes = Span.fromUnsafe(new Array[Byte](42)),
            names = Span.fromUnsafe(new Array[Tasty.Name](3)),
            sectionOffset = 0,
            addrMap = scala.collection.immutable.IntMap.empty,
            pickleId = 0
        )
        val s = body.toString
        assert(s.contains("len=42"), s"Expected 'len=42' in SymbolBody.toString but got: $s")
        assert(s.contains("3 entries"), s"Expected '3 entries' in SymbolBody.toString but got: $s")
        assert(!s.contains("[B@"), s"SymbolBody.toString must not contain array identity hash '[B@'; got: $s")
        succeed
    }

    "findClassesByName returns stable results across multiple calls" in {
        Abort.run[TastyError] {
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    import Tasty.Name.asString
                    classpath.allClassLike.headOption match
                        case Some(cls) =>
                            val simpleName = cls.name.asString
                            val r1         = classpath.findClassesByName(simpleName)
                            val r2         = classpath.findClassesByName(simpleName)
                            val r3         = classpath.findClassesByName(simpleName)
                            assert(
                                r1.length == r2.length && r2.length == r3.length,
                                s"findClassesByName('$simpleName') lengths inconsistent: ${r1.length}, ${r2.length}, ${r3.length}"
                            )
                            assert(r1.nonEmpty, s"findClassesByName('$simpleName') returned empty; expected match")
                            succeed
                        case None =>
                            succeed
                    end match
                }
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "evictOlderThan on empty cache dir completes without error" in {
        Path.tempDir("kyo-df5-evict-empty").map { dir =>
            Abort.run[TastyError](Tasty.evictOlderThan(dir.toString, 1.millis)).map {
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
        }
    }

    "SnapshotFormat.sectionNames contains no HOMEFLD_ or HOME____ section" in {
        val names = SnapshotFormat.sectionNames.toSet
        assert(!names.contains("HOMEFLD_"), "Unexpected HOMEFLD_ section in SnapshotFormat")
        assert(!names.contains("HOME____"), "Unexpected HOME____ section in SnapshotFormat")
        // Verify that the snapshot round-trip of a minimal 2-class classpath preserves both symbols.
        Abort.run[TastyError] {
            snapshotRoundTrip().map { cp2 =>
                assert(cp2.symbols.length == 2, s"Expected 2 symbols in round-trip; got ${cp2.symbols.length}")
                succeed
            }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

    "evictOlderThan Duration overload converts correctly" in {
        val jDur = java.time.Duration.ofMillis(5000L)
        val d    = Duration.fromJava(jDur)
        assert(d.toMillis == 5000L, s"Duration.toMillis mismatch: ${d.toMillis}")
        Path.tempDir("kyo-df5-evict-dur").map { dir =>
            Abort.run[TastyError](Tasty.evictOlderThan(dir.toString, d)).map {
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
        }
    }

    // SymbolBody overrides equals/hashCode using Span.is (structural) so two loads of the same
    // bytes produce equal bodies rather than using Array reference identity.
    // Verified by checking that two independent withPickles loads produce the same classpath structure.
    "SymbolBody structural equality: two loads of the same fixture produce equal classpaths" in {
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
                            assert(
                                cp1.symbols.length == cp2.symbols.length,
                                s"Symbol count must match: cp1=${cp1.symbols.length} cp2=${cp2.symbols.length}"
                            )
                            cp1.symbols.zip(cp2.symbols).foreach { (s1, s2) =>
                                assert(s1 == s2, s"Symbol equality failed: $s1 vs $s2")
                                assert(s1.hashCode == s2.hashCode, s"Symbol hashCode mismatch: $s1 vs $s2")
                            }
                            succeed
                        }
                    }
                }
        }
            .map {
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected error: $e")
                case Result.Panic(t)   => throw t
            }
    }

end DecoderFidelity5Phase04Test
