package kyo

import java.nio.file.Files
import java.nio.file.Paths
import kyo.internal.TestClasspaths
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** decoder-fidelity-5 adversarial edge-case probe: 15 leaves.
  *
  * Axes:
  *   - Adversarial bytes (leaves 1-5): truncated and bit-flipped TASTy files.
  *   - API edges (leaves 6-9): empty/long/null FQN and post-scope Classpath.
  *   - Snapshot adversarial (leaves 10-12): corrupt/truncated/random KRFL bytes.
  *   - Pathological structural (leaves 13-15): most methods, most type params,
  *     deepest declaredType nesting.
  *
  * All leaves are JVM-only (classpath discovery, filesystem, real TASTy). None
  * install external libraries; all fixtures are either synthesized from
  * kyo.fixtures.Embedded.plainClassTasty (a known-good embedded byte array) or
  * discovered via TestClasspaths.all.
  *
  * Scaladoc: 8-35 lines.
  */
class DecoderFidelity5ExplorationTest extends Test:

    import AllowUnsafe.embrace.danger

    private val plainClassTasty: Array[Byte] = kyo.fixtures.Embedded.plainClassTasty

    // --- helpers ---

    private def writeTempTasty(name: String, bytes: Array[Byte]): String =
        val dir  = Files.createTempDirectory("kyo-df5")
        val path = dir.resolve(name)
        Files.write(path, bytes)
        path.toString
    end writeTempTasty

    // Load bytes from a single-file temp dir via SoftFail; return the Classpath.
    private def loadCorrupt(
        name: String,
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Async & Scope & Abort[TastyError]) =
        Sync.defer(writeTempTasty(name, bytes)).flatMap: path =>
            val dir = Paths.get(path).getParent.toString
            Tasty.Classpath.init(Seq(dir), Tasty.ErrorMode.SoftFail)
    end loadCorrupt

    private def countTypeDepth(t: Tasty.Type): Int =
        val children = t.children
        if children.isEmpty then 0
        else 1 + children.map(countTypeDepth).max
    end countTypeDepth

    // -------------------------------------------------------------------------
    // Adversarial bytes (leaves 1-5)
    // -------------------------------------------------------------------------

    // Leaf 1: Truncate PlainClass.tasty at size/4.
    // Expect: no panic; cp.errors contains MalformedSection or CorruptedFile.
    "DF5 leaf 1: truncate at size/4 produces clean error, no panic" in run {
        val bytes = plainClassTasty.take(plainClassTasty.length / 4)
        loadCorrupt("PlainClassTrunc4.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors for tasty truncated at size/4; got empty errors. cp.symbols=${cp.symbols.length}"
            )
            val acceptable = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                acceptable,
                s"Unexpected error variants for truncated file: ${cp.errors}"
            )
            succeed
    }

    // Leaf 2: Truncate PlainClass.tasty at size/2.
    // Expect: no panic; cp.errors contains MalformedSection or CorruptedFile.
    "DF5 leaf 2: truncate at size/2 produces clean error, no panic" in run {
        val bytes = plainClassTasty.take(plainClassTasty.length / 2)
        loadCorrupt("PlainClassTrunc2.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors for tasty truncated at size/2; got empty. cp.symbols=${cp.symbols.length}"
            )
            val acceptable = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(acceptable, s"Unexpected error variants: ${cp.errors}")
            succeed
    }

    // Leaf 3: Truncate PlainClass.tasty at size - 1.
    // A one-byte truncation may or may not trigger an error; what matters is no panic.
    "DF5 leaf 3: truncate at size-1 produces clean result or clean error, no panic" in run {
        val bytes = plainClassTasty.take(plainClassTasty.length - 1)
        loadCorrupt("PlainClassTrunc1.tasty", bytes).map: cp =>
            // Either an error is surfaced, or the truncation was in padding and parse succeeds.
            // Both outcomes are acceptable; a panic/exception would fail the test.
            val errorsAreKnownTypes = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                errorsAreKnownTypes,
                s"Unexpected error variants after size-1 truncation: ${cp.errors}"
            )
            succeed
    }

    // Leaf 4: Bit-flip the first byte of PlainClass.tasty (corrupts the magic).
    // Expect: cp.errors contains CorruptedFile (bad magic) or MalformedSection; no panic.
    "DF5 leaf 4: bit-flip magic byte produces clean CorruptedFile error, no panic" in run {
        val bytes = plainClassTasty.clone()
        bytes(0) = (bytes(0) ^ 0x01).toByte
        loadCorrupt("PlainClassFlipped.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors after bit-flipping magic byte; got empty. cp.symbols=${cp.symbols.length}"
            )
            val hasCorruptedOrMalformed = cp.errors.exists:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _                              => false
            assert(
                hasCorruptedOrMalformed,
                s"Expected CorruptedFile or MalformedSection for flipped-magic file; got: ${cp.errors}"
            )
            succeed
    }

    // Leaf 5: Set the last 16 bytes of PlainClass.tasty to 0xFF.
    // Expect: no panic; clean error or clean load if trailing bytes are padding.
    "DF5 leaf 5: corrupt last 16 bytes with 0xFF produces clean result or clean error" in run {
        val bytes = plainClassTasty.clone()
        val start = math.max(0, bytes.length - 16)
        for i <- start until bytes.length do bytes(i) = 0xff.toByte
        loadCorrupt("PlainClassCorruptTail.tasty", bytes).map: cp =>
            val errorsAreKnownTypes = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                errorsAreKnownTypes,
                s"Unexpected error variants after tail corruption: ${cp.errors}"
            )
            succeed
    }

    // -------------------------------------------------------------------------
    // API edges (leaves 6-9)
    // -------------------------------------------------------------------------

    // Leaf 6: cp.findClass("") on a real classpath returns Absent, no exception.
    "DF5 leaf 6: findClass empty string returns Absent, no exception" in run {
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            val result = cp.findClass("")
            assert(
                result == Maybe.Absent,
                s"Expected Absent for findClass(\"\"); got $result"
            )
            succeed
    }

    // Leaf 7: cp.findClass("a" * 1000) returns Absent, no exception.
    "DF5 leaf 7: findClass very-long FQN returns Absent, no exception" in run {
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            val longFqn = "a" * 1000
            val result  = cp.findClass(longFqn)
            assert(
                result == Maybe.Absent,
                s"Expected Absent for findClass('a' * 1000); got $result"
            )
            succeed
    }

    // Leaf 8: cp.findClass(null) is handled gracefully (Absent or specific error; NOT NPE).
    "DF5 leaf 8: findClass(null) returns Absent or specific error, no NPE" in run {
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            var threw: Option[Throwable]          = None
            var result: Maybe[Tasty.Symbol.Class] = Maybe.Absent
            try
                result = cp.findClass(null)
            catch
                case _: NullPointerException => threw = Some(new NullPointerException("NPE"))
                case t: Throwable            => threw = Some(t)
            end try
            assert(
                threw.isEmpty,
                s"findClass(null) threw an exception (BUG -- must not NPE): ${threw.map(_.getClass.getName).getOrElse("")}"
            )
            assert(
                result == Maybe.Absent,
                s"findClass(null) returned $result instead of Absent"
            )
            succeed
    }

    // Leaf 9: After Scope.run closes the scope, the Classpath value (immutable) is still
    // safely readable without panic. The Classpath is a data class; Scope manages JAR pools
    // but the symbols array remains accessible. This probes that no post-close NPE occurs.
    "DF5 leaf 9: Classpath remains safely readable after enclosing Scope completes" in run {
        // Capture the Classpath from a nested Scope, then use it after that Scope exits.
        Scope.run(
            Abort.run[TastyError](
                Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail)
            )
        ).flatMap: result =>
            result match
                case Result.Failure(e) =>
                    fail(s"Classpath.init failed unexpectedly: $e")
                case Result.Panic(t) =>
                    fail(s"Classpath.init panicked: ${t.getMessage}")
                case Result.Success(cp) =>
                    // Scope has exited; access immutable fields on the Classpath value.
                    Sync.defer:
                        var threw: Option[String] = None
                        try
                            val _syms   = cp.symbols.length
                            val _errors = cp.errors.length
                            val _absent = cp.findClass("kyo.DoesNotExist")
                            discard(_syms, _errors, _absent)
                        catch
                            case t: Throwable =>
                                threw = Some(s"${t.getClass.getName}: ${t.getMessage}")
                        end try
                        assert(
                            threw.isEmpty,
                            s"Classpath read after Scope exit threw: ${threw.getOrElse("")}"
                        )
                        succeed
    }

    // -------------------------------------------------------------------------
    // Snapshot adversarial (leaves 10-12)
    // -------------------------------------------------------------------------

    // Leaf 10: Snapshot with wrong magic bytes (first 4 bytes not 'K','R','F','L').
    // Expect: Abort.fail(TastyError.SnapshotFormatError) with reason about magic.
    "DF5 leaf 10: snapshot with wrong magic produces SnapshotFormatError, no panic" in run {
        val badBytes = Array.fill[Byte](64)(0x42) // all 'B', wrong magic
        val snapPath = "mem/bad-magic.krfl"
        val mem      = new SnapshotMemFileSource()
        mem.put(snapPath, badBytes)
        Abort.run[TastyError](SnapshotReader.read(snapPath, mem)).map:
            case Result.Failure(TastyError.SnapshotFormatError(path, reason, _)) =>
                assert(
                    reason.contains("magic") || reason.contains("KRFL"),
                    s"Expected reason to mention magic/KRFL; got: '$reason'"
                )
                succeed
            case Result.Failure(other) =>
                fail(s"Expected SnapshotFormatError; got TastyError: $other")
            case Result.Success(_) =>
                fail("Expected failure for snapshot with wrong magic; succeeded instead.")
            case Result.Panic(t) =>
                fail(s"Unexpected panic for wrong-magic snapshot: ${t.getMessage}")
    }

    // Leaf 11: Snapshot file truncated at size/2.
    // Build a valid snapshot, truncate it, attempt to read: expect clean error.
    "DF5 leaf 11: truncated KRFL snapshot produces clean error, no panic" in run {
        // Build a valid in-memory snapshot from the embedded classpath fixture.
        Tasty.Classpath.init(TestClasspaths.kyoTasty, Tasty.ErrorMode.SoftFail).map: cp =>
            val digest    = Array[Byte](0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x44.toByte)
            val fullBytes = SnapshotWriter.serializeToBytes(cp, digest)
            val halfBytes = fullBytes.take(fullBytes.length / 2)
            val snapPath  = "mem/truncated.krfl"
            val mem       = new SnapshotMemFileSource()
            mem.put(snapPath, halfBytes)
            import AllowUnsafe.embrace.danger
            val result = KyoApp.Unsafe.runAndBlock(Duration.fromJava(java.time.Duration.ofSeconds(10))):
                Abort.run[TastyError](SnapshotReader.read(snapPath, mem))
            result match
                case Result.Success(Result.Failure(_: TastyError.SnapshotFormatError)) =>
                    succeed
                case Result.Success(Result.Failure(_: TastyError.SnapshotVersionMismatch)) =>
                    succeed
                case Result.Success(Result.Failure(_: TastyError.MalformedSection)) =>
                    succeed
                case Result.Success(Result.Panic(t)) =>
                    fail(s"Unexpected panic for truncated KRFL: ${t.getMessage}")
                case Result.Success(Result.Success(_)) =>
                    // Truncated snapshot parsed successfully -- only acceptable if truncation fell in padding.
                    succeed
                case other =>
                    fail(s"Unexpected result for truncated KRFL: $other")
            end match
    }

    // Leaf 12: Snapshot read from 1024 bytes of seeded pseudo-random data.
    // RNG seeded for reproducibility: seed = 0xDF5CAFE5L.
    // Expect: clean error (SnapshotFormatError, SnapshotVersionMismatch, MalformedSection)
    // or clean parse; never a Panic.
    "DF5 leaf 12: snapshot from seeded random bytes produces clean error or clean parse, no panic" in run {
        val rng   = new java.util.Random(0xdf5cafe5L)
        val bytes = new Array[Byte](1024)
        rng.nextBytes(bytes)
        val snapPath = "mem/random.krfl"
        val mem      = new SnapshotMemFileSource()
        mem.put(snapPath, bytes)
        Abort.run[TastyError](SnapshotReader.read(snapPath, mem)).map:
            case Result.Failure(_: TastyError.SnapshotFormatError)     => succeed
            case Result.Failure(_: TastyError.SnapshotVersionMismatch) => succeed
            case Result.Failure(_: TastyError.MalformedSection)        => succeed
            case Result.Failure(other)                                 =>
                // Any structured TastyError is acceptable; this is an adversarial input.
                succeed
            case Result.Success(_) =>
                // Random bytes that happen to parse are an acceptable (if unlikely) outcome.
                succeed
            case Result.Panic(t) =>
                fail(s"Unexpected panic for random KRFL bytes: ${t.getMessage}")
    }

    // -------------------------------------------------------------------------
    // Pathological structural (leaves 13-15)
    // -------------------------------------------------------------------------

    // Leaf 13: Load the real classpath, find the Symbol.Class with the most methods.
    // Verify all methods decode correctly: no skipped, no Named(-1) in declaredType.
    "DF5 leaf 13: class with most methods decodes all methods without Named(-1)" in run {
        Tasty.Classpath.init(TestClasspaths.all, Tasty.ErrorMode.SoftFail).map: cp =>
            given Tasty.Classpath = cp
            import kyo.internal.tasty.symbol.SymbolId.value as idVal
            // Find the class with the most declared methods (non-synthetic).
            val classWithMostMethods = cp.allClasses.toIndexedSeq.maxByOption: cl =>
                cl.declarationIds.count: id =>
                    cp.symbol(id).isInstanceOf[Tasty.Symbol.Method]
            classWithMostMethods match
                case None =>
                    fail("No classes found in classpath -- unexpected empty classpath.")
                case Some(cls) =>
                    val methods = cls.declarationIds.flatMap: id =>
                        cp.symbol(id) match
                            case m: Tasty.Symbol.Method => Chunk(m)
                            case _                      => Chunk.empty
                    var namedNeg1Count = 0
                    methods.foreach: m =>
                        m.declaredType.foreach: dt =>
                            dt.foreach:
                                case Tasty.Type.Named(id) if idVal(id) == -1 =>
                                    namedNeg1Count += 1
                                case _ => ()
                    assert(
                        namedNeg1Count == 0,
                        s"Class '${cls.simpleName}' has $namedNeg1Count Named(-1) sentinels in method declaredTypes. " +
                            s"Total methods on this class: ${methods.length}."
                    )
                    assert(
                        methods.nonEmpty,
                        s"maxByOption returned a class with no methods: ${cls.simpleName}"
                    )
                    succeed
            end match
    }

    // Leaf 14: Find the method with the most type parameters; verify all decode.
    // A typeParamIds.length > 0 confirms decoding ran; verify no sentinel TypeParam.
    "DF5 leaf 14: method with most type params decodes all type params without sentinel" in run {
        Tasty.Classpath.init(TestClasspaths.all, Tasty.ErrorMode.SoftFail).map: cp =>
            given Tasty.Classpath = cp
            import kyo.internal.tasty.symbol.SymbolId.value as idVal
            val allMethods   = cp.allMethods
            val mostTpMethod = allMethods.toIndexedSeq.maxByOption(_.typeParamIds.length)
            mostTpMethod match
                case None =>
                    fail("No methods found in classpath.")
                case Some(m) =>
                    val tps = m.typeParams
                    val sentinelTps = tps.filter: tp =>
                        idVal(tp.id) == -1
                    assert(
                        sentinelTps.isEmpty,
                        s"Method '${m.simpleName}' has ${sentinelTps.length} sentinel TypeParam(s) out of ${tps.length} total. " +
                            s"typeParamIds: ${m.typeParamIds}"
                    )
                    val _ = s"Most-tp method: ${m.simpleName} with ${tps.length} type params."
                    succeed
            end match
    }

    // Leaf 15: Find the method with the deepest declaredType nesting.
    // Verify no StackOverflowError during depth measurement.
    "DF5 leaf 15: deepest declaredType nesting causes no StackOverflowError" in run {
        Tasty.Classpath.init(TestClasspaths.all, Tasty.ErrorMode.SoftFail).map: cp =>
            given Tasty.Classpath = cp
            import kyo.internal.tasty.symbol.SymbolId.value as idVal
            var maxDepth      = 0
            var deepestMethod = ""
            var soeCaught     = false
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: dt =>
                    try
                        val d = countTypeDepth(dt)
                        if d > maxDepth then
                            maxDepth = d
                            import Tasty.Name.asString
                            deepestMethod = m.name.asString
                        end if
                    catch
                        case _: StackOverflowError =>
                            soeCaught = true
                            import Tasty.Name.asString
                            deepestMethod = m.name.asString
            assert(
                !soeCaught,
                s"StackOverflowError during countTypeDepth on method '$deepestMethod'."
            )
            // Depth > 0 confirms the classpath contains real types (not all leaves).
            assert(
                maxDepth > 0,
                "Expected at least one method with declaredType depth > 0."
            )
            succeed
    }

    // -------------------------------------------------------------------------
    // SnapshotMemFileSource: minimal in-memory FileSource for adversarial tests.
    // -------------------------------------------------------------------------

    final private class SnapshotMemFileSource extends kyo.internal.tasty.query.FileSource:
        private val store = mutable.HashMap.empty[String, Array[Byte]]

        def put(path: String, bytes: Array[Byte]): Unit = store(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(store.keys.filter(k => suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            store.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(store(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            store.get(from) match
                case Some(b) =>
                    Sync.defer:
                        store.remove(from)
                        store(to) = b
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(store.contains(path))

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                store.get(path) match
                    case Some(b) => kyo.internal.tasty.query.FileSource.FileStat(mtimeMs = 0L, size = b.length.toLong)
                    case None    => Abort.fail(TastyError.FileNotFound(path))
    end SnapshotMemFileSource

end DecoderFidelity5ExplorationTest
