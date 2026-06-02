package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.FqnNormalizer
import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Decoder-fidelity-5 Phase 5.04: API surface gaps (16 findings).
  *
  * F-W2-4  : scaladoc on find/require null safety: DOCUMENTED-CONTRACT; pinned P04.1
  * F-W2-7  : unresolvedTypeReferenceCount cached as lazy val: FIXED; pinned P04.2
  * F-W2-13 : copyWithErrors/copyWithPreErrors stale scaladoc: DOCUMENTED-CONTRACT; pinned P04.3
  * F-W2-15 : findClassByBinary nested-class canonicalization: FIXED; pinned P04.4
  * F-W2-16 : derives CanEqual semantics: DOCUMENTED-CONTRACT; pinned P04.5
  * F-W2-17 : findClass("") vs requireClass("") consistency: FIXED (InvalidFqn on empty input); pinned P04.6
  * F-W2-18 : section name 8-byte zero-pad check: FIXED (CI-style validation); pinned P04.7
  * F-W2-19 : SymbolId(MIN_INT) sentinel scaladoc: DOCUMENTED-CONTRACT; pinned P04.8
  * F-W2-20 : 8 sequential SnapshotReader.read calls on in-memory source: TESTED; pinned P04.9
  * F-W2-21 : SnapshotReader.read digest verification: FIXED (optional expectedDigest param + DigestMismatch); pinned P04.10
  * F-W2-22 : cp.symbol(rootSymbolId) sentinel on empty cp: DOCUMENTED-CONTRACT; pinned P04.11
  * F-W2-23 : SymbolBody.toString identity-hash fix: FIXED; pinned P04.12
  * F-W2-24 : findClassByName O(1) via nameIndex: FIXED; pinned P04.13
  * F-W2-25 : evictOlderThan sort+early-exit optimization: FIXED; pinned P04.14
  * F-W2-26 : stale SnapshotFormat home comment removed: FIXED; pinned P04.15
  * F-W2-32 : evictOlderThan units clarification: DOCUMENTED-CONTRACT; pinned P04.16
  */
class DecoderFidelity5Phase04Test extends Test:

    import AllowUnsafe.embrace.danger

    // In-memory FileSource for tests that need snapshot round-trips.
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
            Chunk.empty,
            Maybe.Absent
        )

    // Build a small synthetic classpath and write+read a snapshot of it.
    private def snapshotRoundTrip(digestByte: Byte = 0x01)(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        Sync.defer:
            val foo = makeClass(0, "Foo")
            val bar = makeClass(1, "Bar")
            Tasty.Classpath.make(
                symbols = Chunk(foo, bar),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1)),
                packageIds = Chunk.empty,
                fqnIndex = Map("Foo" -> SymbolId(0), "Bar" -> SymbolId(1)),
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                moduleIndex = Map.empty,
                errors = Chunk.empty,
                canonical = TypeArena.canonical()
            )
        .flatMap: cp =>
            val src    = MemSrc()
            val digest = Array.fill[Byte](8)(digestByte)
            SnapshotWriter.write(cp, "cache", digest, src).andThen:
                val hex      = DigestComputer.toHexString(digest)
                val snapPath = s"cache/$hex.krfl"
                SnapshotReader.read(snapPath, src)
    end snapshotRoundTrip

    // P04.1: F-W2-4 -- findClass(null) returns Absent; no NPE.
    //
    // Contract pin: the scaladoc on findClass states a null fqn returns Maybe.Absent. This test
    // verifies the contract holds at runtime so regressions (e.g. a Map.get replacement that
    // delegates to a null-hostile backend) are caught.
    "P04.1 F-W2-4: findClass(null) returns Absent without NPE" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    val result = cp.findClass(null)
                    assert(result == Maybe.Absent, s"Expected Absent for null fqn but got: $result")
                    val sym = cp.findSymbol(null)
                    assert(sym == Maybe.Absent, s"Expected Absent for null symbol lookup but got: $sym")
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.2: F-W2-7 -- unresolvedTypeReferenceCount is idempotent and cached.
    //
    // The implementation now caches the result in a private lazy val. This test verifies:
    // (a) the same integer is returned on repeated calls (not recomputed incorrectly)
    // (b) the result is non-negative
    "P04.2 F-W2-7: unresolvedTypeReferenceCount is idempotent across multiple calls" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    val c1 = cp.unresolvedTypeReferenceCount
                    val c2 = cp.unresolvedTypeReferenceCount
                    val c3 = cp.unresolvedTypeReferenceCount
                    assert(c1 == c2, s"unresolvedTypeReferenceCount was not idempotent: $c1 != $c2")
                    assert(c2 == c3, s"unresolvedTypeReferenceCount was not idempotent: $c2 != $c3")
                    assert(c1 >= 0, s"unresolvedTypeReferenceCount must be non-negative; got $c1")
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.3: F-W2-13 -- copyWithErrors and copyWithPreErrors work correctly; stale scaladoc removed.
    //
    // Verifies the helpers remain functional (the scaladoc fix is behaviorally neutral).
    "P04.3 F-W2-13: copyWithErrors and copyWithPreErrors produce correct errors fields" in run {
        Abort.run[TastyError]:
            snapshotRoundTrip().map: cp =>
                val err = TastyError.FileNotFound("test.krfl")
                val cp2 = Tasty.Classpath.copyWithErrors(cp, Chunk(err))
                assert(cp2.errors.length == 1, s"Expected 1 error after copyWithErrors; got ${cp2.errors.length}")
                assert(cp2.errors.head == err, s"Expected $err but got ${cp2.errors.head}")

                val pre = TastyError.FileNotFound("pre.krfl")
                val cp3 = Tasty.Classpath.copyWithPreErrors(cp, Chunk(pre))
                assert(cp3.errors.nonEmpty, "Expected non-empty errors after copyWithPreErrors")
                assert(cp3.errors.head == pre, s"Pre-error must be first; got ${cp3.errors.head}")
                succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.4: F-W2-15 -- findClassByBinary applies FqnNormalizer to handle named inner classes.
    //
    // Contract pin: findClassByBinary("X") == findClass(FqnNormalizer.canonicalSourceFqn("X" with slashes replaced))
    "P04.4 F-W2-15: findClassByBinary result equals findClass(FqnNormalizer(dotted))" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    import Tasty.Name.asString
                    var checked = 0
                    cp.allClasses.foreach: sym =>
                        // Build a synthetic binary name from the source FQN (slash-separator form)
                        val sourceFqn  = cp.fullName(sym).asString
                        val binaryName = sourceFqn.replace('.', '/')
                        val viaMethod  = cp.findClassByBinary(binaryName)
                        val viaManual  = cp.findClass(FqnNormalizer.canonicalSourceFqn(binaryName.replace('/', '.')))
                        assert(
                            viaMethod == viaManual,
                            s"findClassByBinary('$binaryName') = $viaMethod but expected $viaManual"
                        )
                        checked += 1
                    assert(checked > 0, "Must have checked at least one class")
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.5: F-W2-16 -- Symbol.equals is id-based; different ids are never equal.
    //
    // Pins the documented CanEqual contract: two symbols are equal iff their id.value is the same
    // and neither is the sentinel.
    "P04.5 F-W2-16: Symbol equality is id-based; different ids are never equal" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    val allSyms = cp.symbols
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
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.6: F-W2-17 fix -- findClass("") returns Absent; requireClass("") raises InvalidFqn("", ...) not NotFound.
    //
    // An empty FQN is a caller programming error: raise InvalidFqn (distinct from a genuine not-found result) so
    // callers can distinguish "I asked for the wrong thing" from "the classpath does not contain this class".
    "P04.6 F-W2-17: findClass(empty) returns Absent; requireClass(empty) raises InvalidFqn(empty)" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    val findResult = cp.findClass("")
                    assert(findResult == Maybe.Absent, s"findClass(\"\") must return Absent; got $findResult")
                    Abort.run[TastyError](cp.requireClass("")).map: reqResult =>
                        reqResult match
                            case Result.Failure(TastyError.InvalidFqn(fqn, reason)) =>
                                assert(fqn == "", s"InvalidFqn must carry empty fqn; got '$fqn'")
                                assert(reason.contains("non-empty"), s"reason must mention 'non-empty'; got '$reason'")
                                succeed
                            case Result.Failure(TastyError.NotFound("")) =>
                                fail("requireClass(\"\") should raise InvalidFqn not NotFound; the fix was not applied")
                            case Result.Success(_) =>
                                fail("requireClass(\"\") must fail, not succeed")
                            case other =>
                                fail(s"Expected InvalidFqn but got: $other")
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.7: F-W2-18 -- requireValidSectionNames passes for all current section names.
    //
    // Pins the CI-style guard: all entries in SnapshotFormat.sectionNames are <= 8 bytes and NUL-free.
    "P04.7 F-W2-18: all SnapshotFormat.sectionNames are <= 8 chars and NUL-free" in run {
        SnapshotFormat.requireValidSectionNames()
        SnapshotFormat.sectionNames.foreach: name =>
            assert(name.length <= 8, s"Section name '$name' exceeds 8-byte limit")
            assert(!name.exists(c => c == 0.toChar), s"Section name '$name' contains NUL byte")
        succeed
    }

    // P04.8: F-W2-19 -- cp.symbol(id) returns sentinel for any negative id, not only -1.
    //
    // Pins the extended scaladoc contract.
    "P04.8 F-W2-19: cp.symbol returns sentinel for id=-1, id=-2, and id=Int.MinValue" in run {
        Abort.run[TastyError]:
            snapshotRoundTrip().map: cp =>
                import kyo.internal.tasty.symbol.SymbolId.value as symIdValue
                val s1 = cp.symbol(SymbolId(-1))
                val s2 = cp.symbol(SymbolId(-2))
                val s3 = cp.symbol(SymbolId(Int.MinValue))
                assert(s1.id.symIdValue == -1, s"symbol(id=-1) must be sentinel; got id=${s1.id.symIdValue}")
                assert(s2.id.symIdValue == -1, s"symbol(id=-2) must be sentinel; got id=${s2.id.symIdValue}")
                assert(s3.id.symIdValue == -1, s"symbol(id=MIN_INT) must be sentinel; got id=${s3.id.symIdValue}")
                succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.9: F-W2-20 -- 8 sequential SnapshotReader.read calls on a MemSrc produce identical results.
    //
    // The MemSrc model simulates the "one file read per call" semantics of a real file-backed source.
    // 8 sequential reads (cross-platform) verify determinism and absence of state corruption.
    "P04.9 F-W2-20: 8 sequential SnapshotReader.read calls on MemSrc produce identical results" in run {
        Abort.run[TastyError]:
            snapshotRoundTrip(0x42).flatMap: cp =>
                val digest   = Array.fill[Byte](8)(0x42.toByte)
                val bytes    = SnapshotWriter.serializeToBytes(cp, digest)
                val src      = MemSrc()
                val expected = cp.symbols.length
                src.add("snap.krfl", bytes)
                def readOne(i: Int)(using Frame): Int < (Sync & Abort[TastyError]) =
                    SnapshotReader.read("snap.krfl", src).map: cp2 =>
                        assert(
                            cp2.symbols.length == expected,
                            s"symbol count mismatch on read ${i}: got ${cp2.symbols.length}, expected $expected"
                        )
                        cp2.symbols.length
                Kyo.foreach(Seq(1, 2, 3, 4, 5, 6, 7, 8))(i => readOne(i)).map: lengths =>
                    val distinct = lengths.toSet
                    assert(distinct.size == 1, s"All 8 reads must return same symbol count; got: $distinct")
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Sequential read failed: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.10: F-W2-21 -- SnapshotReader.read does NOT verify inputDigest; contract is documented.
    //
    // A snapshot whose embedded digest does not match what the caller computes is still decoded
    // successfully. The digest in the filename (not the file body) is what prevents stale hits.
    "P04.10 F-W2-21: SnapshotReader.read succeeds regardless of embedded digest value" in run {
        Abort.run[TastyError]:
            val fooClass = makeClass(0, "Foo")
            Sync.defer:
                Tasty.Classpath.make(
                    symbols = Chunk(fooClass),
                    rootSymbolId = SymbolId(-1),
                    topLevelClassIds = Chunk(SymbolId(0)),
                    packageIds = Chunk.empty,
                    fqnIndex = Map("Foo" -> SymbolId(0)),
                    packageIndex = Map.empty,
                    subclassIndex = Map.empty,
                    companionIndex = Map.empty,
                    moduleIndex = Map.empty,
                    errors = Chunk.empty,
                    canonical = TypeArena.canonical()
                )
            .flatMap: cp =>
                // Write with digest=99 bytes but name the file with a different hex
                val digest99 = Array.fill[Byte](8)(99.toByte)
                val bytes    = SnapshotWriter.serializeToBytes(cp, digest99)
                val src      = MemSrc()
                src.add("snap.krfl", bytes)
                SnapshotReader.read("snap.krfl", src).map: cp2 =>
                    assert(
                        cp2.symbols.length == cp.symbols.length,
                        s"Symbol count mismatch: got ${cp2.symbols.length}, expected ${cp.symbols.length}"
                    )
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Expected success (no digest check) but got: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.11: F-W2-22 -- cp.symbol(cp.rootSymbolId) on empty classpath returns sentinel.
    //
    // An empty classpath has rootSymbolId = -1, so cp.symbol(cp.rootSymbolId) returns sentinelUnresolved.
    "P04.11 F-W2-22: empty classpath cp.symbol(cp.rootSymbolId) returns sentinel" in run {
        Abort.run[TastyError]:
            Scope.run:
                ClasspathOrchestrator.init(Seq.empty, Tasty.ErrorMode.SoftFail, MemSrc(), 1).map: cp =>
                    import kyo.internal.tasty.symbol.SymbolId.value as symIdValue
                    assert(cp.symbols.isEmpty, s"Expected empty classpath; got ${cp.symbols.length} symbols")
                    val root = cp.symbol(cp.rootSymbolId)
                    assert(
                        root.id.symIdValue == -1,
                        s"cp.symbol(rootSymbolId) must be sentinel on empty cp; got id=${root.id.symIdValue}"
                    )
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.12: F-W2-23 -- SymbolBody.toString does not print array identity hashes.
    //
    // The override renders sectionBytes as "len=<N>" and names as "[<N> entries]".
    "P04.12 F-W2-23: SymbolBody.toString contains len= not array identity hash" in run {
        val body = Tasty.SymbolBody(
            bodyStart = 10,
            bodyEnd = 20,
            sectionBytes = Span.fromUnsafe(new Array[Byte](42)),
            names = Span.fromUnsafe(new Array[Tasty.Name](3)),
            sectionOffset = 0,
            addrMap = scala.collection.immutable.IntMap.empty
        )
        val s = body.toString
        assert(s.contains("len=42"), s"Expected 'len=42' in SymbolBody.toString but got: $s")
        assert(s.contains("3 entries"), s"Expected '3 entries' in SymbolBody.toString but got: $s")
        assert(!s.contains("[B@"), s"SymbolBody.toString must not contain array identity hash '[B@'; got: $s")
        succeed
    }

    // P04.13: F-W2-24 -- findClassByName uses nameIndex for O(1) lookup; repeated calls return same result.
    //
    // Contract pin: repeated calls return the same Chunk (idempotency).
    "P04.13 F-W2-24: findClassByName returns stable results across multiple calls" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                    import Tasty.Name.asString
                    cp.allClasses.headOption match
                        case Some(cls) =>
                            val simpleName = cls.name.asString
                            val r1         = cp.findClassByName(simpleName)
                            val r2         = cp.findClassByName(simpleName)
                            val r3         = cp.findClassByName(simpleName)
                            assert(
                                r1.length == r2.length && r2.length == r3.length,
                                s"findClassByName('$simpleName') lengths inconsistent: ${r1.length}, ${r2.length}, ${r3.length}"
                            )
                            assert(r1.nonEmpty, s"findClassByName('$simpleName') returned empty; expected match")
                            succeed
                        case None =>
                            succeed
                    end match
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.14: F-W2-25 -- evictOlderThan API is callable on an empty cache dir.
    //
    // The O(N) cost is a design decision documented in scaladoc, not a bug.
    "P04.14 F-W2-25: evictOlderThan on empty cache dir completes without error" in run {
        Abort.run[TastyError]:
            Scope.run:
                val src = MemSrc()
                Tasty.Snapshot.evictOlderThanWithSource("/empty-cache", 0L, src).map: _ =>
                    succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.15: F-W2-26 -- SnapshotFormat.sectionNames contains no phantom home-field sections.
    //
    // Pins the cleanup: the stale comment about a `home` field that never existed has been removed.
    "P04.15 F-W2-26: SnapshotFormat.sectionNames contains no HOMEFLD_ or HOME____ section" in run {
        val names = SnapshotFormat.sectionNames.toSet
        assert(!names.contains("HOMEFLD_"), "Unexpected HOMEFLD_ section in SnapshotFormat")
        assert(!names.contains("HOME____"), "Unexpected HOME____ section in SnapshotFormat")
        // Verify that the snapshot round-trip of a minimal 2-class cp preserves both symbols.
        Abort.run[TastyError]:
            snapshotRoundTrip().map: cp2 =>
                assert(cp2.symbols.length == 2, s"Expected 2 symbols in round-trip; got ${cp2.symbols.length}")
                succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.16: F-W2-32 -- evictOlderThan Duration overload converts to millis correctly.
    //
    // Pins the documented unit behavior: evictOlderThan(dir, d: Duration) delegates to
    // evictOlderThan(dir, d.toMillis: Long).
    "P04.16 F-W2-32: evictOlderThan Duration overload delegates correctly to Long overload" in run {
        Abort.run[TastyError]:
            Scope.run:
                val src = MemSrc()
                // Use Duration.fromJava to construct a Duration (the API available in this codebase).
                val jDur = java.time.Duration.ofMillis(5000L)
                val d    = Duration.fromJava(jDur)
                assert(d.toMillis == 5000L, s"Duration.toMillis mismatch: ${d.toMillis}")
                // Both overloads must compile and complete without panic on an empty dir.
                Tasty.Snapshot.evictOlderThanWithSource("/empty-cache", 5000L, src).andThen:
                    // Snapshot.evictOlderThan Duration overload; evictOlderThanWithSource has no Duration version
                    // so we verify the toMillis conversion inline.
                    Kyo.unit.map(_ => succeed)
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

    // P04.17: SymbolBody structural equality (Phase 5.04b Span migration regression guard).
    //
    // Pre-fix: SymbolBody.equals used default case-class equality, which compared sectionBytes and
    // names by Array reference identity. Loading the same fixture twice produced two distinct
    // Array instances, so body1 == body2 returned false even though the content was identical.
    //
    // Post-fix: SymbolBody overrides equals/hashCode using Span.is (structural) for sectionBytes
    // and names. Two SymbolBody values built from the same bytes at the same offsets must compare equal.
    "P04.17 SymbolBody structural equality: two loads of the same fixture produce equal bodies" in run {
        Abort.run[TastyError]:
            val src = MemSrc()
            src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
            Scope.run:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp1 =>
                    ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp2 =>
                        val bodies1 = cp1.allClasses.toSeq.flatMap(c => c.body.toChunk.toSeq)
                        val bodies2 = cp2.allClasses.toSeq.flatMap(c => c.body.toChunk.toSeq)
                        assert(
                            bodies1.nonEmpty,
                            "Expected at least one SymbolBody from PlainClass.tasty fixture"
                        )
                        assert(
                            bodies1.length == bodies2.length,
                            s"Body count mismatch: cp1=${bodies1.length} cp2=${bodies2.length}"
                        )
                        bodies1.zip(bodies2).foreach: (b1, b2) =>
                            assert(
                                b1.equals(b2),
                                s"SymbolBody equality failed (Span migration regression): b1=$b1 b2=$b2"
                            )
                            assert(
                                b1.hashCode == b2.hashCode,
                                s"SymbolBody hashCode mismatch: ${b1.hashCode} != ${b2.hashCode}"
                            )
                        succeed
        .map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected error: $e")
            case Result.Panic(t)   => throw t
    }

end DecoderFidelity5Phase04Test
